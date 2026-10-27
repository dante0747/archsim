"""Stand-ins for the simulation that the worker tests run in simulation processes. A spawned
process imports this module, so it imports nothing heavy."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

FINISHED = "the stand-in run finished"


def stubborn(
    document: dict[str, Any],
    scenario_id: str | None,
    limits: Any,
    progress: Callable[[float], None],
    should_cancel: Callable[[], bool],
) -> dict[str, Any]:
    """Reports progress for ever and ignores cancellation, like a run stuck in native code."""
    while True:
        progress(0.5)
        time.sleep(0.05)


def cooperative(
    document: dict[str, Any],
    scenario_id: str | None,
    limits: Any,
    progress: Callable[[float], None],
    should_cancel: Callable[[], bool],
) -> dict[str, Any]:
    """Runs for ``document["seconds"]`` (default: for ever) unless cancelled, then ends as
    ``failed`` with the message ``FINISHED`` (a stand-in has no report to succeed with)."""
    end = time.monotonic() + float(document.get("seconds", 1e9))
    while time.monotonic() < end:
        if should_cancel():
            return {"status": "cancelled"}
        progress(0.5)
        time.sleep(0.02)
    return {"status": "failed", "error": {"message": FINISHED}}


def until_cancelled(
    document: dict[str, Any],
    scenario_id: str | None,
    limits: Any,
    progress: Callable[[float], None],
    should_cancel: Callable[[], bool],
) -> dict[str, Any]:
    """Like ``cooperative``, but whatever the document says."""
    return cooperative({}, scenario_id, limits, progress, should_cancel)
