"""What runs inside a simulation process (and, with the thread executor, in a worker thread).

The worker starts one ``spawn`` process per run with two one-way pipes:
- *down*, parent to child: first the run (document, scenario, limits), then possibly
  ``"cancel"``. The pipe also tells the child its parent is alive: when it closes (the worker
  exited or was killed), the process ends at once;
- *up*, child to parent: ``("progress", p)`` messages, then one ``("result", outcome)``.

A process imports the engine (about a second on Windows) before it reads its run, so the worker
starts one ahead of time and the next run need not wait. Each process still simulates one run
and then exits.

Cancellation is a ``threading.Event`` the watchdog sets, which the engine checks every few
thousand events. A ``multiprocessing.Event`` would share a lock with the parent, which a child
killed while holding it would leave locked for ever.

Ctrl+C in a terminal reaches every process attached to it, so a simulation process ignores it:
the worker decides when it stops (through the pipe, or terminate/kill), and a run in flight when
the worker is interrupted is given back to the queue rather than lost. The process imports the
engine only after that, and nothing from the API, the database or SQLAlchemy at all.
"""

from __future__ import annotations

import logging
import os
import signal
import threading
import time
from collections.abc import Callable
from multiprocessing.connection import Connection
from typing import TYPE_CHECKING, Any

from archsim_api.jobs.orphans import EXIT_ORPHANED, die_with_parent

if TYPE_CHECKING:
    from archsim_engine import SimulationLimits

log = logging.getLogger("archsim.jobs")

Outcome = dict[str, Any]
"""``{"status": "succeeded", "report": ...}``, ``{"status": "cancelled"}`` or
``{"status": "failed", "error": {"message": ..., "issues"?: [...]}}``."""

Simulate = Callable[
    [dict[str, Any], str | None, "SimulationLimits", Callable[[float], None], Callable[[], bool]],
    Outcome,
]

PROGRESS_EVERY_S = 0.25
"""How often the child reports progress; the worker writes it to the database once a second."""


def simulate(
    document: dict[str, Any],
    scenario_id: str | None,
    limits: SimulationLimits,
    progress: Callable[[float], None],
    should_cancel: Callable[[], bool],
) -> Outcome:
    """Simulate one run and describe how it ended, without raising."""
    from archsim_engine import (
        DocumentInvalidError,
        SimulationCancelledError,
        SimulationLimitError,
        run_simulation,
    )

    try:
        report = run_simulation(
            document,
            scenario_id=scenario_id,
            limits=limits,
            progress=progress,
            should_cancel=should_cancel,
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
    except Exception as exc:  # a bug in the engine: report it on the run
        log.exception("simulation crashed")
        return {
            "status": "failed",
            "error": {"message": f"Internal error while simulating: {type(exc).__name__}: {exc}"},
        }
    return {"status": "succeeded", "report": report}


def _watch_parent(down: Connection, cancel: threading.Event) -> None:
    while True:
        try:
            message = down.recv()
        except (EOFError, OSError):  # the worker is gone: nobody will read the result
            os._exit(EXIT_ORPHANED)
        if message == "cancel":
            cancel.set()


def main(up: Connection, down: Connection, parent_pid: int) -> None:
    """Entry point of a simulation process: wait for one run, simulate it, report, exit."""
    for name in ("SIGINT", "SIGBREAK"):  # Ctrl+C / Ctrl+Break are for the worker (see above)
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), signal.SIG_IGN)
    die_with_parent(parent_pid)
    import archsim_engine  # noqa: F401 - now, while the process waits for its run

    try:
        document, scenario_id, limits, target = down.recv()
    except (EOFError, OSError):  # closed before it got a run: no longer needed, or orphaned
        os._exit(EXIT_ORPHANED)
    cancel = threading.Event()
    threading.Thread(
        target=_watch_parent, args=(down, cancel), name="archsim-lifeline", daemon=True
    ).start()
    last = 0.0

    def on_progress(p: float) -> None:
        nonlocal last
        now = time.monotonic()
        if now - last >= PROGRESS_EVERY_S or p >= 1.0:
            last = now
            try:
                up.send(("progress", p))
            except OSError:
                os._exit(EXIT_ORPHANED)

    simulate_run: Simulate = target or simulate  # ``target`` replaces ``simulate`` in tests
    outcome = simulate_run(document, scenario_id, limits, on_progress, cancel.is_set)
    up.send(("result", outcome))
    up.close()
