"""Background execution of simulation runs.

HTTP requests only enqueue work and return immediately; clients poll ``GET /runs/{id}`` for
status and progress. Cancellation is cooperative (checked between simulation chunks) and every
run is bounded by request-count and wall-clock limits.

Two runners: ``ProcessJobRunner`` (the default) simulates each run in a worker process, so
CPU-bound runs use separate cores and never hold the API's GIL; ``ThreadJobRunner`` runs them in
threads of the API process (``ARCHSIM_RUNNER=thread``; lighter to start, used by most tests).
The ``JobRunner`` protocol is the seam for Redis/RQ/Celery workers later.
"""

from __future__ import annotations

import contextlib
import logging
import multiprocessing
import threading
import time
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeout
from typing import Any, Protocol

from sqlalchemy import select, update

from archsim_api.db import Database, SimulationRun, utcnow
from archsim_engine import (
    DocumentInvalidError,
    SimulationCancelledError,
    SimulationLimitError,
    SimulationLimits,
    run_simulation,
)

log = logging.getLogger("archsim.jobs")

ACTIVE = ("queued", "running")


class JobRunner(Protocol):
    def submit(self, run_id: str) -> None: ...
    def cancel(self, run_id: str) -> bool: ...
    def live_progress(self, run_id: str) -> float | None: ...
    def shutdown(self) -> None: ...


def run_summary(report: dict[str, Any]) -> dict[str, Any]:
    """Small denormalised summary stored on the run row for fast listing."""
    s = report["summary"]
    lat = s["latencyMs"]
    bottleneck = next((f for f in report["findings"] if f["kind"] == "bottleneck"), None)
    return {
        "offeredRps": s["offeredRps"],
        "throughputRps": s["throughputRps"],
        "successRate": s["successRate"],
        "timeoutRate": s["timeoutRate"],
        "errorRate": s["errorRate"],
        "p50": lat["p50"],
        "p95": lat["p95"],
        "p99": lat["p99"],
        "bottleneck": bottleneck["title"] if bottleneck else None,
        "bottleneckNodeId": bottleneck["nodeId"] if bottleneck else None,
    }


class ThreadJobRunner:
    def __init__(self, db: Database, limits: SimulationLimits, max_workers: int) -> None:
        self.db = db
        self.limits = limits
        self.executor = ThreadPoolExecutor(
            max_workers=max_workers, thread_name_prefix="archsim-run"
        )
        self._cancel: dict[str, threading.Event] = {}
        self._progress: dict[str, float] = {}
        self._lock = threading.Lock()

    # -- public API --------------------------------------------------------------------------
    def submit(self, run_id: str) -> None:
        with self._lock:
            self._cancel[run_id] = threading.Event()
            self._progress[run_id] = 0.0
        self.executor.submit(self._execute, run_id)

    def cancel(self, run_id: str) -> bool:
        with self._lock:
            flag = self._cancel.get(run_id)
        if flag is not None:
            flag.set()
        # A queued run that has not started yet is cancelled immediately.
        with self.db.session() as s:
            res = s.execute(
                update(SimulationRun)
                .where(SimulationRun.id == run_id, SimulationRun.status == "queued")
                .values(status="cancelled", finished_at=utcnow())
            )
            s.commit()
            return bool(res.rowcount) or flag is not None  # type: ignore[attr-defined]

    def live_progress(self, run_id: str) -> float | None:
        with self._lock:
            return self._progress.get(run_id)

    def shutdown(self) -> None:
        with self._lock:
            for flag in self._cancel.values():
                flag.set()
        self.executor.shutdown(wait=True, cancel_futures=True)

    def recover_interrupted(self) -> int:
        """Mark runs left active by a previous process as failed."""
        with self.db.session() as s:
            res = s.execute(
                update(SimulationRun)
                .where(SimulationRun.status.in_(ACTIVE))
                .values(
                    status="failed",
                    finished_at=utcnow(),
                    error={"message": "Interrupted: the API restarted before this run finished."},
                )
            )
            s.commit()
            return int(res.rowcount or 0)  # type: ignore[attr-defined]

    # -- worker ------------------------------------------------------------------------------
    def _finish(self, run_id: str, **values: Any) -> None:
        with self.db.session() as s:
            s.execute(
                update(SimulationRun)
                .where(SimulationRun.id == run_id)
                .values(finished_at=utcnow(), **values)
            )
            s.commit()

    def _execute(self, run_id: str) -> None:
        flag = self._cancel.get(run_id) or threading.Event()
        try:
            with self.db.session() as s:
                run = s.execute(
                    select(SimulationRun).where(SimulationRun.id == run_id)
                ).scalar_one_or_none()
                if run is None or run.status != "queued":
                    return
                document = run.input_document
                scenario_id = run.scenario_id
                run.status = "running"
                run.started_at = utcnow()
                s.commit()

            last_write = 0.0

            def on_progress(p: float) -> None:
                nonlocal last_write
                self._progress[run_id] = p
                now = time.monotonic()
                if now - last_write >= 1.0:  # throttle DB writes (once per second at most)
                    last_write = now
                    with self.db.session() as ps:
                        ps.execute(
                            update(SimulationRun)
                            .where(SimulationRun.id == run_id)
                            .values(progress=p)
                        )
                        ps.commit()

            try:
                report = run_simulation(
                    document,
                    scenario_id=scenario_id,
                    limits=self.limits,
                    progress=on_progress,
                    should_cancel=flag.is_set,
                )
            except SimulationCancelledError:
                self._finish(run_id, status="cancelled")
                return
            except DocumentInvalidError as exc:
                self._finish(
                    run_id,
                    status="failed",
                    error={"message": str(exc), "issues": [i.to_dict() for i in exc.issues]},
                )
                return
            except SimulationLimitError as exc:
                self._finish(run_id, status="failed", error={"message": str(exc)})
                return
            p = report["provenance"]
            self._finish(
                run_id,
                status="succeeded",
                progress=1.0,
                report=report,
                summary=run_summary(report),
                input_digest=p["inputDigest"],
                result_digest=p["resultDigest"],
                wall_seconds=p["wallSeconds"],
            )
        except Exception as exc:  # pragma: no cover - safety net
            log.exception("simulation run %s crashed", run_id)
            self._finish(
                run_id,
                status="failed",
                error={"message": f"Internal error while simulating: {type(exc).__name__}: {exc}"},
            )
        finally:
            with self._lock:
                self._cancel.pop(run_id, None)
                self._progress.pop(run_id, None)


# ---------------------------------------------------------------------------------------------
# process-based runner
# ---------------------------------------------------------------------------------------------


def _simulate_in_worker(
    document: dict[str, Any],
    scenario_id: str | None,
    limits: SimulationLimits,
    cancel: Any,
    progress: Any,
    run_id: str,
) -> dict[str, Any]:
    """Runs in a worker process: the simulation with progress and cancellation through manager
    proxies (a shared dict and an event). Returns a tagged result, since engine exceptions carry
    objects that do not always pickle."""
    last = [0.0]

    def on_progress(p: float) -> None:
        if p - last[0] >= 0.01 or p >= 1.0:  # about 100 updates a run
            last[0] = p
            progress[run_id] = p

    try:
        report = run_simulation(
            document,
            scenario_id=scenario_id,
            limits=limits,
            progress=on_progress,
            should_cancel=cancel.is_set,
        )
    except SimulationCancelledError:
        return {"status": "cancelled"}
    except DocumentInvalidError as exc:
        return {
            "status": "failed",
            "error": {"message": str(exc), "issues": [i.to_dict() for i in exc.issues]},
        }
    except SimulationLimitError as exc:
        return {"status": "failed", "error": {"message": str(exc)}}
    return {"status": "succeeded", "report": report}


class ProcessJobRunner(ThreadJobRunner):
    """Runs each simulation in a worker process, so a CPU-bound run neither holds the GIL the
    API needs nor slows other runs: up to ``max_workers`` runs use as many cores. The API process
    keeps the database: a coordinating thread per run submits it to the process pool, relays
    progress and writes the outcome. Cancellation and progress cross the process boundary
    through a ``multiprocessing.Manager`` (an event per run, a shared progress dict)."""

    def __init__(self, db: Database, limits: SimulationLimits, max_workers: int) -> None:
        super().__init__(db, limits, max_workers)
        ctx = multiprocessing.get_context("spawn")
        self.manager = ctx.Manager()
        self.shared_progress = self.manager.dict()
        self.pool = ProcessPoolExecutor(max_workers=max_workers, mp_context=ctx)
        self._events: dict[str, Any] = {}

    def submit(self, run_id: str) -> None:
        with self._lock:
            self._cancel[run_id] = threading.Event()
            self._events[run_id] = self.manager.Event()
            self._progress[run_id] = 0.0
        self.executor.submit(self._execute, run_id)

    def cancel(self, run_id: str) -> bool:
        with self._lock:
            ev = self._events.get(run_id)
        if ev is not None:
            ev.set()
        return super().cancel(run_id)

    def live_progress(self, run_id: str) -> float | None:
        try:
            p = self.shared_progress.get(run_id)
        except Exception:  # the manager is shutting down
            p = None
        return p if p is not None else super().live_progress(run_id)

    def shutdown(self) -> None:
        with self._lock:
            for ev in self._events.values():
                ev.set()
        super().shutdown()
        self.pool.shutdown(wait=True, cancel_futures=True)
        self.manager.shutdown()

    def _execute(self, run_id: str) -> None:
        try:
            with self.db.session() as s:
                run = s.execute(
                    select(SimulationRun).where(SimulationRun.id == run_id)
                ).scalar_one_or_none()
                if run is None or run.status != "queued":
                    return
                document, scenario_id = run.input_document, run.scenario_id
                run.status = "running"
                run.started_at = utcnow()
                s.commit()
            future = self.pool.submit(
                _simulate_in_worker,
                document,
                scenario_id,
                self.limits,
                self._events[run_id],
                self.shared_progress,
                run_id,
            )
            last_write = 0.0
            while True:
                try:
                    outcome = future.result(timeout=0.5)
                    break
                except FuturesTimeout:
                    p = self.shared_progress.get(run_id)
                    now = time.monotonic()
                    if p is not None and now - last_write >= 1.0:
                        last_write = now
                        with self.db.session() as ps:
                            ps.execute(
                                update(SimulationRun)
                                .where(SimulationRun.id == run_id)
                                .values(progress=p)
                            )
                            ps.commit()
            if outcome["status"] == "succeeded":
                report = outcome["report"]
                p = report["provenance"]
                self._finish(
                    run_id,
                    status="succeeded",
                    progress=1.0,
                    report=report,
                    summary=run_summary(report),
                    input_digest=p["inputDigest"],
                    result_digest=p["resultDigest"],
                    wall_seconds=p["wallSeconds"],
                )
            elif outcome["status"] == "cancelled":
                self._finish(run_id, status="cancelled")
            else:
                self._finish(run_id, status="failed", error=outcome["error"])
        except Exception as exc:  # pragma: no cover - safety net (a crashed worker, a broken pool)
            log.exception("simulation run %s crashed", run_id)
            self._finish(
                run_id,
                status="failed",
                error={"message": f"Internal error while simulating: {type(exc).__name__}: {exc}"},
            )
        finally:
            with self._lock:
                self._cancel.pop(run_id, None)
                self._progress.pop(run_id, None)
                self._events.pop(run_id, None)
            with contextlib.suppress(Exception):  # the manager may already be shut down
                self.shared_progress.pop(run_id, None)
