"""When scripts/perf.py dates a measurement, and the dates of the measurements kept."""

from __future__ import annotations

import importlib.util
import json
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("perf", Path(__file__).parents[1] / "perf.py")
assert _spec and _spec.loader
perf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(perf)

UTC_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def test_a_measurement_is_dated_in_utc_with_its_offset() -> None:
    taken = datetime(2026, 11, 9, 23, 33, 40, 512_000, tzinfo=timezone(timedelta(hours=1)))

    assert perf.measured_at(taken) == "2026-11-09T22:33:40Z"


def test_a_measurement_is_dated_when_it_is_written() -> None:
    before = datetime.now(UTC).replace(microsecond=0)

    at = datetime.strptime(perf.measured_at(), UTC_FORMAT).replace(tzinfo=UTC)

    assert before <= at <= datetime.now(UTC)


@pytest.mark.parametrize(
    "path",
    sorted(
        p
        for p in (ROOT / "benchmarks" / "perf").glob("*.json")
        if "at" in json.loads(p.read_text(encoding="utf-8"))
    ),
    ids=lambda p: p.name,
)
def test_every_kept_measurement_is_dated_in_utc(path: Path) -> None:
    at = json.loads(path.read_text(encoding="utf-8"))["at"]

    assert datetime.strptime(at, UTC_FORMAT).strftime(UTC_FORMAT) == at
