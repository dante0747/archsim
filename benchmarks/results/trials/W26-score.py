"""W26: score protocol v6 trial calibrations against the kept runs' measured ladders (in-sample).

    uv run python benchmarks/results/trials/W26-score.py [--only cache-aside ...] [--form log]

For each in-sample experiment with a ``<date>-<experiment>-w26/`` trial, the trial's calibration
gives the model inputs (``run.rederive_inputs``, today's code: the curve above the staircase
follows ``--form``, run.CURVE_FORM by default), and the model simulates each counted kept run's measured steps and searches
its knee; the knee is compared with that run's measured knee the way ``run.compare`` does. The
model's CPU per request at each step (the service's busy cores over the rate) is set against
the step's measured one (its container's busy cores net of the run's idle, over the rate).

The stacks match the kept runs' except where W22 and W23 changed compose.yaml after them
(PostgreSQL loads pg_stat_statements; Kafka's broker healthcheck runs once): the Kafka trial is
therefore also set against W23's fixed-stack passes (``2026-10-31-kafka-w23/knee.json``).
Writes ``W26-score-<suffix>-<form>.json`` next to this file. Nothing here is a prediction: the kept runs were
measured before this code was written, and the rule was chosen with them in view.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
BENCH = HERE.parents[1]
sys.path.insert(0, str(BENCH))

import run  # noqa: E402

COUNTED = {
    "pg-mixed": ["run-8", "run-9", ""],
    "pg-hot-row": ["run-6", "run-7", ""],
    "cache-aside": ["run-3", "run-4", ""],
    "kafka": ["run-3", "run-4", ""],
}
"""The three counted protocol v5 runs of each experiment (docs/validation.md, repeatability)."""
SERVICE = {"kafka": "producer"}


def trial_dir(exp_id: str, date: str | None, suffix: str = "w26") -> Path | None:
    found = sorted(HERE.glob(f"{date or '*'}-{exp_id}-{suffix}"))
    return found[-1] if found else None


def measured_cpu(exp_id: str, d: Path) -> dict[float, float]:
    """Each measured step's CPU per request of the service (ms): busy cores net of the run's
    idle, over the measured throughput."""
    cal = run.read_json(d / "calibration.json") or {}
    cmp_ = run.read_json(d / "comparison.json") or {}
    svc = SERVICE.get(exp_id, "api")
    rest = (cal.get("idle") or {}).get("containerCpuMsPerSecond", {}).get(svc) or 0.0
    out = {}
    for row in cmp_.get("rows", []):
        res = (row.get("resources") or {}).get(svc)
        thr = row["measured"].get("throughputRps") or row["offeredRps"]
        if res and res["measured"].get("cores") is not None and thr:
            out[row["offeredRps"]] = (res["measured"]["cores"] * 1000 - rest) / thr
    return out


def score_run(exp_id: str, d: Path, inputs: dict[str, Any]) -> dict[str, Any]:
    exp = run.EXPERIMENTS[exp_id]
    meas = run.read_json(d / "measured.json")
    assert meas
    sims = [run.simulate(run.design(exp, inputs, s["offeredRps"])) for s in meas["steps"]]
    knee = run.predicted_knee(exp, inputs)
    c = run.compare(exp, {"knee": knee, "steps": sims}, meas)
    cpu = measured_cpu(exp_id, d)
    steps = []
    for row in c["rows"]:
        rate = row["offeredRps"]
        pred = (row.get("resources") or {}).get(SERVICE.get(exp_id, "api"), {}).get("predicted")
        pc = pred.get("cores") if pred else None
        steps.append(
            {
                "offeredRps": rate,
                "p95": {
                    "measured": row["measured"].get("p95"),
                    "model": row["predicted"].get("p95"),
                },
                "cpuMsPerRequest": {
                    "measured": round(cpu[rate], 4) if rate in cpu else None,
                    "model": round(pc * 1000 / rate, 4) if pc is not None else None,
                },
            }
        )
    return {
        "results": d.relative_to(run.RESULTS).as_posix(),
        "measuredKnee": c["measuredKnee"],
        "modelKnee": c["predictedKnee"],
        "kneeError": c["kneeError"],
        "searchedKnee": knee["kneeRps"],
        "steps": steps,
    }


def score(exp_id: str, date: str | None, suffix: str = "w26") -> dict[str, Any] | None:
    folder = trial_dir(exp_id, date, suffix)
    cal = run.read_json(folder / "calibration.json") if folder else None
    if not cal:
        return None
    exp = run.EXPERIMENTS[exp_id]
    inputs = run.rederive_inputs(exp, cal)
    runs = [run.RESULTS / exp_id / r if r else run.RESULTS / exp_id for r in COUNTED[exp_id]]
    with ProcessPoolExecutor(max_workers=len(runs)) as pool:
        scored = list(pool.map(score_run, [exp_id] * len(runs), runs, [inputs] * len(runs)))
    errors = [s["kneeError"] for s in scored if s["kneeError"] is not None]
    return {
        "experiment": exp_id,
        "trial": folder.name if folder else None,
        "staircaseRps": cal.get("staircaseRps"),
        "cpuByLoad": [
            {"rateRps": b["rateRps"], "serviceCpuMsPerRequest": b["serviceCpuMsPerRequest"]}
            for b in cal.get("cpuByLoad") or []
        ],
        "serviceTimeMs": inputs["serviceTimeMs"],
        "serviceTimeByLoad": inputs.get("serviceTimeByLoad"),
        "medianKneeError": statistics.median(errors) if errors else None,
        "runs": scored,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", choices=list(COUNTED))
    ap.add_argument("--date", help="the trials' date (default: the latest of each)")
    ap.add_argument("--form", choices=["flat", "log", "busy"], help="the curve above the top")
    ap.add_argument(
        "--suffix", default="w26", help="the trials' folder suffix (w24: round 1's v5 trials)"
    )
    a = ap.parse_args()
    run.CURVE_FORM = a.form or run.CURVE_FORM  # the inputs are derived here, in this process
    out = {
        "at": run.now(),
        "form": run.CURVE_FORM,
        "trials": a.suffix,
        "provenance": run.provenance(),
        "experiments": {},
    }
    for exp_id in a.only or list(COUNTED):
        s = score(exp_id, a.date, a.suffix)
        if s is None:
            run.log(f"{exp_id}: no W26 trial")
            continue
        out["experiments"][exp_id] = s
        errs = ", ".join(f"{r['kneeError']:+.0%}" for r in s["runs"] if r["kneeError"] is not None)
        run.log(f"{exp_id}: knee errors {errs}; median {s['medianKneeError']}")
    name = f"W26-score-{a.suffix}-{run.CURVE_FORM}.json"
    (HERE / name).write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
