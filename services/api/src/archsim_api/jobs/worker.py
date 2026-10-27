"""Workers: take runs from the queue and simulate each one in a process of its own.

A ``Worker`` is the supervisor. It has ``slots`` runs at most and three kinds of threads:
- the *claim* thread takes queued runs whenever a slot is free (woken at once by a local
  enqueue or a finished run, otherwise every ``poll_seconds``);
- the *heartbeat* thread renews the leases of its runs every ``heartbeat_seconds``, passes on
  cancel requests from any API instance, records the worker in ``job_workers``, and every
  ``reap_seconds`` settles runs whose worker died (``queue.reap``);
- one *run* thread per run, which starts a ``spawn`` process for it (``child.main``), relays its
  progress to the database at most once a second and records the outcome.

A process that crashes or is killed fails only its own run (``WORKER_STOPPED``). Cancelling sends
a message down the run's pipe; a run still going ``cancel_grace_seconds`` later is terminated,
then killed. Every run has a hard deadline of ``max_wall_seconds`` plus a margin, past which it is
killed and failed. A worker that stops (``stop``: ``serve`` shutting down, or ``archsim-api
worker`` getting SIGTERM) requeues its runs; one that dies leaves them to be requeued when their
leases run out.

``executor="thread"`` runs simulations in the run threads instead (no process start-up cost,
used by most tests): the queue, leases and cancellation behave the same, but a run can only stop
cooperatively and a crash takes the whole worker with it.
"""

from __future__ import annotations

import contextlib
import logging
import multiprocessing
import os
import socket
import threading
import time
import uuid
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass
from multiprocessing.connection import wait as wait_ready
from multiprocessing.process import BaseProcess
from queue import SimpleQueue
from typing import Any, Literal, TypeVar

from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import Database, SimulationRun
from archsim_api.jobs import child, orphans, queue
from archsim_api.jobs.child import Outcome, Simulate
from archsim_engine import SimulationLimits

log = logging.getLogger("archsim.jobs")

PROGRESS_WRITE_EVERY_S = 1.0
TICK_S = 0.2
"""How often a run thread checks on its process (cancel, deadline, exit)."""

T = TypeVar("T")


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


@dataclass(frozen=True)
class WorkerConfig:
    slots: int
    limits: SimulationLimits
    executor: Literal["process", "thread"] = "process"
    lease_seconds: float = 30.0
    heartbeat_seconds: float = 2.0
    reap_seconds: float = 10.0
    poll_seconds: float = 1.0
    cancel_grace_seconds: float = 10.0
    """How long a cancelled run may take to stop on its own before it is terminated."""
    kill_grace_seconds: float = 5.0
    """How long a terminated process may take to exit before it is killed."""
    deadline_margin_seconds: float = 30.0
    """Added to ``limits.max_wall_seconds`` for the hard deadline of a run's process (start-up,
    building the report)."""

    @classmethod
    def from_settings(cls, settings: Settings, slots: int | None = None) -> WorkerConfig:
        return cls(
            slots=slots or settings.max_concurrent_runs,
            limits=SimulationLimits(
                max_requests=settings.max_requests, max_wall_seconds=settings.max_wall_seconds
            ),
            executor=settings.runner,
            lease_seconds=settings.lease_seconds,
            heartbeat_seconds=settings.heartbeat_seconds,
            reap_seconds=settings.reap_seconds,
            cancel_grace_seconds=settings.cancel_grace_seconds,
        )


def new_worker_id() -> str:
    return f"{socket.gethostname()[:32]}-{os.getpid()}-{uuid.uuid4().hex[:8]}"


def _retry(what: str, fn: Callable[[], T], attempts: int = 3) -> T | None:
    """Database writes that must not be lost to a brief outage."""
    for attempt in range(attempts):
        try:
            return fn()
        except Exception:
            log.warning("%s failed (attempt %d of %d)", what, attempt + 1, attempts, exc_info=True)
            if attempt + 1 < attempts:
                time.sleep(1.0 + attempt)
    return None


def _readable(conn: Any) -> bool:
    """Data (or EOF) is waiting; a broken pipe on Windows raises instead of answering."""
    try:
        return bool(conn.poll(0))
    except (OSError, EOFError):
        return False


@dataclass
class _Child:
    """A simulation process and the parent's ends of its two pipes."""

    proc: BaseProcess
    up: Any  # Connection (POSIX) or PipeConnection (Windows)
    down: Any

    @property
    def pid(self) -> int:
        assert self.proc.pid is not None
        return self.proc.pid

    def dispose(self, grace: float = 2.0) -> None:
        """Close the pipes (an idle process exits on its own), then make sure it is gone."""
        for conn in (self.down, self.up):
            with contextlib.suppress(OSError):
                conn.close()
        self.proc.join(timeout=grace)
        if self.proc.is_alive():
            self.proc.kill()
            self.proc.join(timeout=grace)


class _Launcher:
    """Starts every simulation process, from one thread that lives as long as the worker, and
    keeps one started ahead so a run does not wait for the engine to import.

    One thread because on Linux ``PR_SET_PDEATHSIG`` fires when the *thread* that started the
    child exits, not the process: a process started by a run thread and handed to another run
    would be killed when the first run ended."""

    def __init__(self, keep_spare: bool) -> None:
        self._keep_spare = keep_spare
        self._requests: SimpleQueue[Future[_Child] | None] = SimpleQueue()
        self._spare: _Child | None = None
        self._thread = threading.Thread(target=self._loop, name="archsim-launcher", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def take(self) -> _Child:
        """A started process for one run: the spare if it is still alive, else a new one."""
        request: Future[_Child] = Future()
        self._requests.put(request)
        return request.result(timeout=120)

    def stop(self) -> None:
        """Close the spare. Call it once no run uses a process from here (see the class note)."""
        self._requests.put(None)
        self._thread.join(timeout=30)

    def _loop(self) -> None:
        self._refill()
        while (request := self._requests.get()) is not None:
            child_proc, self._spare = self._spare, None
            if child_proc is not None and not child_proc.proc.is_alive():
                child_proc.dispose()  # killed while idle
                child_proc = None
            try:
                request.set_result(child_proc or _spawn())
            except Exception as exc:
                request.set_exception(exc)
            self._refill()
        if self._spare is not None:
            self._spare.dispose()

    def _refill(self) -> None:
        if not self._keep_spare or self._spare is not None:
            return
        try:
            self._spare = _spawn()
        except Exception:
            log.warning("could not start a spare simulation process", exc_info=True)


def _spawn() -> _Child:
    ctx = multiprocessing.get_context("spawn")
    up_recv, up_send = ctx.Pipe(duplex=False)  # progress and the result
    down_recv, down_send = ctx.Pipe(duplex=False)  # the run, "cancel"; closing it ends the child
    proc = ctx.Process(
        target=child.main, args=(up_send, down_recv, os.getpid()), name="archsim-sim", daemon=True
    )
    proc.start()
    up_send.close()  # the child holds the only copies now, so a dead child reads as EOF
    down_recv.close()
    started = _Child(proc, up_recv, down_send)
    orphans.adopt(started.pid)
    return started


class Worker:
    def __init__(
        self,
        db: Database,
        config: WorkerConfig,
        *,
        worker_id: str | None = None,
        target: Simulate | None = None,
    ) -> None:
        self.db = db
        self.config = config
        self.id = worker_id or new_worker_id()
        self.target = target
        """Replaces ``child.simulate`` (tests); with processes it must be a module-level function."""
        self._executions: dict[str, Execution] = {}
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._stopping = threading.Event()
        self._halt_heartbeat = threading.Event()
        self._threads: list[threading.Thread] = []
        self._launcher = _Launcher(keep_spare=True) if config.executor == "process" else None
        self._started = False
        self._stopped = False

    # -- control -----------------------------------------------------------------------------
    def start(self) -> None:
        """Register, settle runs left by workers that died (this process before a restart, for
        example), then start taking runs in background threads."""
        if self._started:
            return
        self._started = True
        self._beat()
        self._reap()
        if self._launcher is not None:
            self._launcher.start()
        for name, fn in (("archsim-claim", self._claim_loop), ("archsim-heartbeat", self._heart)):
            t = threading.Thread(target=fn, name=name, daemon=True)
            t.start()
            self._threads.append(t)
        log.info(
            "worker %s takes up to %d run(s) at once (%s executor)",
            self.id,
            self.config.slots,
            self.config.executor,
        )

    def stop(self) -> None:
        """Stop taking runs, give back those in flight (requeued, or cancelled if a cancel was
        requested), and deregister. Returns once the runs are given back."""
        if not self._started or self._stopped:
            return
        self._stopped = True
        self._stopping.set()
        self._wake.set()
        self._threads[0].join()  # the claim thread: nothing new starts after this
        with self._lock:
            executions = list(self._executions.values())
        if executions:
            log.info("worker %s stopping: giving back %d run(s)", self.id, len(executions))
        for ex in executions:
            ex.abandon("requeue")
        deadline = time.monotonic() + self.config.kill_grace_seconds + 10.0
        for ex in executions:  # the heartbeat keeps their leases alive meanwhile
            ex.thread.join(max(0.0, deadline - time.monotonic()))
        if self._launcher is not None:
            self._launcher.stop()
        self._halt_heartbeat.set()
        self._threads[1].join(timeout=10.0)
        with contextlib.suppress(Exception):
            queue.forget_worker(self.db, self.id)
        log.info("worker %s stopped", self.id)

    def wake(self) -> None:
        """A run was queued here: claim now rather than at the next poll."""
        self._wake.set()

    def cancel_now(self, run_id: str) -> bool:
        """Pass on a cancel at once when this worker holds the run (otherwise its own worker
        sees the request at its next heartbeat)."""
        with self._lock:
            ex = self._executions.get(run_id)
        if ex is None:
            return False
        ex.request_cancel()
        return True

    @property
    def running(self) -> dict[str, int | None]:
        """Runs in flight here, with the id of the process simulating each (None for threads)."""
        with self._lock:
            return {run_id: ex.pid for run_id, ex in self._executions.items()}

    # -- threads -----------------------------------------------------------------------------
    def _claim_loop(self) -> None:
        while not self._stopping.is_set():
            self._wake.clear()
            with self._lock:
                free = self.config.slots - len(self._executions)
                busy = list(self._executions)
            if free > 0:
                try:
                    claimed = queue.claim(
                        self.db, self.id, free, self.config.lease_seconds, exclude=busy
                    )
                except Exception:
                    log.warning("could not claim runs", exc_info=True)
                    claimed = []
                for run in claimed:
                    ex = Execution(self, run)
                    with self._lock:
                        self._executions[run.id] = ex
                    ex.thread.start()
            self._wake.wait(self.config.poll_seconds)

    def _heart(self) -> None:
        next_reap = time.monotonic() + self.config.reap_seconds
        while not self._halt_heartbeat.wait(self.config.heartbeat_seconds):
            self._renew()  # before reaping, so a worker back from a stall keeps its own runs
            self._beat()
            if not self._stopping.is_set() and time.monotonic() >= next_reap:
                self._reap()
                next_reap = time.monotonic() + self.config.reap_seconds

    def _renew(self) -> None:
        with self._lock:
            snapshot = dict(self._executions)  # taken first: a run claimed later is not "lost"
        if not snapshot:
            return
        try:
            renewal = queue.renew(self.db, self.id, list(snapshot), self.config.lease_seconds)
        except Exception:
            log.warning("could not renew leases", exc_info=True)
            return
        for run_id, ex in snapshot.items():
            if run_id not in renewal.owned:
                ex.abandon("lost")
            elif run_id in renewal.cancel_requested:
                ex.request_cancel()

    def _beat(self) -> None:
        with self._lock:
            running = len(self._executions)
        try:
            queue.heartbeat(
                self.db,
                self.id,
                hostname=socket.gethostname(),
                pid=os.getpid(),
                slots=self.config.slots,
                running=running,
                status="draining" if self._stopping.is_set() else "running",
            )
        except Exception:
            log.warning("could not record a heartbeat", exc_info=True)

    def _reap(self) -> None:
        try:
            reaped = queue.reap(self.db)
        except Exception:
            log.warning("could not reap expired runs", exc_info=True)
            return
        if reaped.total:
            log.warning(
                "runs whose worker stopped: %d requeued, %d failed, %d cancelled",
                reaped.requeued,
                reaped.failed,
                reaped.cancelled,
            )
        if reaped.requeued:
            self._wake.set()

    def _done(self, ex: Execution) -> None:
        with self._lock:
            self._executions.pop(ex.id, None)
        self._wake.set()  # a slot is free


class Execution:
    """One claimed run, from start to recorded outcome, in its own thread."""

    def __init__(self, worker: Worker, run: queue.ClaimedRun) -> None:
        self.worker = worker
        self.run = run
        self.id = run.id
        self.pid: int | None = None
        self._cancel = threading.Event()
        self._abandon: str | None = None
        """"requeue" (the worker is stopping) or "lost" (another worker may hold the run now)."""
        self._last_progress = 0.0
        self.thread = threading.Thread(
            target=self._main, name=f"archsim-run-{run.id[:8]}", daemon=True
        )

    def request_cancel(self) -> None:
        self._cancel.set()

    def abandon(self, reason: Literal["requeue", "lost"]) -> None:
        if self._abandon is None:
            self._abandon = reason

    def _stop_wanted(self) -> bool:
        return self._cancel.is_set() or self._abandon is not None

    # -- lifecycle ---------------------------------------------------------------------------
    def _main(self) -> None:
        log.info("run %s: attempt %d on worker %s", self.id, self.run.attempts, self.worker.id)
        try:
            if self.worker.config.executor == "process":
                outcome = self._in_process()
            else:
                outcome = self._in_thread()
            self._record(outcome)
        except Exception as exc:
            log.exception("run %s: the worker failed while running it", self.id)
            message = f"Internal error while simulating: {type(exc).__name__}: {exc}"
            self._finish("failed", error={"message": message})
        finally:
            self.worker._done(self)

    def _progress(self, p: float) -> None:
        now = time.monotonic()
        if now - self._last_progress < PROGRESS_WRITE_EVERY_S:
            return
        self._last_progress = now
        try:
            held = queue.set_progress(self.worker.db, self.id, self.worker.id, p)
        except Exception:
            log.debug("could not record progress of run %s", self.id, exc_info=True)
            return
        if not held:
            self.abandon("lost")

    def _in_thread(self) -> Outcome | None:
        simulate = self.worker.target or child.simulate
        outcome = simulate(
            self.run.document,
            self.run.scenario_id,
            self.worker.config.limits,
            self._progress,
            self._stop_wanted,
        )
        if outcome["status"] == "cancelled" and self._abandon is not None:
            return None
        return outcome

    def _in_process(self) -> Outcome | None:
        cfg = self.worker.config
        launcher = self.worker._launcher
        assert launcher is not None
        job = (self.run.document, self.run.scenario_id, cfg.limits, self.worker.target)
        for attempt in (1, 2):
            started = launcher.take()
            try:
                started.down.send(job)
                break
            except OSError:  # it died before taking the run: try a fresh one once
                started.dispose()
                if attempt == 2:
                    raise
        proc, up_recv, down_send = started.proc, started.up, started.down
        self.pid = started.pid
        log.info("run %s: simulating in process %d", self.id, started.pid)

        deadline = time.monotonic() + cfg.limits.max_wall_seconds + cfg.deadline_margin_seconds
        outcome: Outcome | None = None
        cancel_sent_at: float | None = None
        terminate_at = float("inf")
        kill_at: float | None = None
        timed_out = False
        try:
            while True:
                ready = wait_ready([up_recv, proc.sentinel], timeout=TICK_S)
                if up_recv in ready or (ready and _readable(up_recv)):
                    try:
                        kind, value = up_recv.recv()
                    except Exception:  # EOF or a message cut short: the process is gone
                        break
                    if kind == "result":
                        outcome = value
                        break
                    self._progress(value)
                    continue
                if ready:  # exited without a result
                    break
                now = time.monotonic()
                if self._stop_wanted():
                    if cancel_sent_at is None:
                        cancel_sent_at = now
                        with contextlib.suppress(OSError):
                            down_send.send("cancel")
                    grace = 0.0 if self._abandon is not None else cfg.cancel_grace_seconds
                    terminate_at = min(terminate_at, cancel_sent_at + grace)
                if now >= deadline and terminate_at == float("inf"):
                    timed_out = True
                    terminate_at = now
                if kill_at is None and now >= terminate_at:
                    if self._abandon is None:
                        log.warning("run %s: stopping process %d", self.id, proc.pid)
                    proc.terminate()
                    kill_at = now + cfg.kill_grace_seconds
                elif kill_at is not None and now >= kill_at:
                    log.warning("run %s: killing process %d", self.id, proc.pid)
                    proc.kill()
                    kill_at = float("inf")
        finally:
            started.dispose(grace=cfg.kill_grace_seconds)  # it exits on its own if still running

        if outcome is not None:
            return outcome
        if self._abandon is None and self.worker._stopping.is_set():
            self.abandon("requeue")  # it died as the worker was being stopped: give it back
        if self._abandon is not None:
            return None
        if self._cancel.is_set():
            return {"status": "cancelled"}
        if timed_out:
            message = (
                f"Simulation exceeded the wall-clock limit of {cfg.limits.max_wall_seconds:.0f} s"
                " and was stopped. Reduce RPS, duration, fan-out or model size."
            )
            return {"status": "failed", "error": {"message": message}}
        log.error(
            "run %s: simulation process %d stopped unexpectedly (exit code %s)",
            self.id,
            proc.pid,
            proc.exitcode,
        )
        return {"status": "failed", "error": {"message": queue.WORKER_STOPPED}}

    # -- outcome -----------------------------------------------------------------------------
    def _record(self, outcome: Outcome | None) -> None:
        db, me = self.worker.db, self.worker.id
        if outcome is None:
            if self._abandon == "requeue":
                status = _retry(
                    f"giving back run {self.id}", lambda: queue.release(db, self.id, me)
                )
                log.info("run %s: given back (%s)", self.id, status or "no longer held")
            else:
                log.warning("run %s: this worker lost its lease; its run was stopped", self.id)
            return
        values: dict[str, Any] = {}
        status = outcome["status"]
        if status == "succeeded":
            report = outcome["report"]
            p = report["provenance"]
            values = {
                "progress": 1.0,
                "report": report,
                "summary": run_summary(report),
                "input_digest": p["inputDigest"],
                "result_digest": p["resultDigest"],
                "wall_seconds": p["wallSeconds"],
            }
        elif status == "failed":
            values = {"error": outcome["error"]}
        self._finish(status, **values)

    def _finish(self, status: str, **values: Any) -> None:
        db, me = self.worker.db, self.worker.id
        recorded = _retry(
            f"recording run {self.id}", lambda: queue.finish(db, self.id, me, status, **values)
        )
        if recorded:
            log.info("run %s: %s", self.id, status)
        else:
            log.warning(
                "run %s: lease lost before it finished; its %s was dropped", self.id, status
            )


class JobService:
    """What the API uses: enqueue and cancel through the database (so they work from any
    instance), and this process's embedded worker if it has one."""

    def __init__(self, db: Database, worker: Worker | None) -> None:
        self.db = db
        self.worker = worker

    def start(self) -> None:
        if self.worker is not None:
            self.worker.start()

    def shutdown(self) -> None:
        if self.worker is not None:
            self.worker.stop()

    def enqueue(self, session: Session, run: SimulationRun) -> SimulationRun:
        queue.enqueue(session, run)
        if self.worker is not None:
            self.worker.wake()
        return run

    def cancel(self, run_id: str) -> str | None:
        status = queue.request_cancel(self.db, run_id)
        if status == "running" and self.worker is not None:
            self.worker.cancel_now(run_id)
        return status
