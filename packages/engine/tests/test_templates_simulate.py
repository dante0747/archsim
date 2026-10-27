"""Every template in ``examples/`` simulates: a shortened run finishes, every request is
accounted for, and the report has something in it. The other template tests only parse and
validate them; until this file, only the starter was ever simulated."""

from __future__ import annotations

import copy
from pathlib import Path
from typing import Any

import pytest

from archsim_engine import run_simulation
from engine_builders import EXAMPLES, load_example

TEMPLATES = sorted(EXAMPLES.glob("*.json"))
DURATION_S = 8.0
WARMUP_S = 2.0


def compressed(d: dict[str, Any], duration: float, warmup: float) -> dict[str, Any]:
    """The template's whole timeline squeezed into ``duration`` seconds: a burst or fault at 40 s
    of a 90 s run happens at the same fraction of the short one, so it still runs."""
    d = copy.deepcopy(d)
    w = d["workload"]
    k = duration / w["durationS"]
    w["durationS"], w["warmupS"] = duration, warmup
    timed = [w.get("pattern") or {}, *(w.get("faults") or [])]
    for item in timed:
        for key in ("startS", "durationS"):
            if key in item:
                item[key] = round(item[key] * k, 3)
    return d


def test_there_are_templates() -> None:
    assert len(TEMPLATES) >= 10


@pytest.mark.parametrize("path", TEMPLATES, ids=lambda p: p.stem)
def test_every_template_simulates(path: Path) -> None:
    r: dict[str, Any] = run_simulation(compressed(load_example(path.name), DURATION_S, WARMUP_S))

    s = r["summary"]
    assert s["accounting"]["invariantHolds"], s["accounting"]
    assert s["arrived"] > 0
    assert s["succeeded"] > 0, s["failureBreakdown"]

    assert r["nodes"], "the report lists no parts"
    for n in r["nodes"]:
        assert n["accounting"]["invariantHolds"], (n["id"], n["accounting"])
    assert r["timeseries"]["t"], "the report has no time series"
