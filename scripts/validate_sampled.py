"""Validate sampled (hybrid) mode against full simulation: a steady 10-minute variant of every
template (its base rate, constant, no faults) simulated both ways. Writes
benchmarks/perf/sampled-validation.json; docs/performance.md quotes it.

    uv run python scripts/validate_sampled.py

Run on a quiet machine (the full runs take several minutes).
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packages" / "engine" / "src"))

from archsim_engine import default_registry, run_simulation  # noqa: E402
from archsim_engine.engine import SimulationLimits  # noqa: E402

OUT = ROOT / "benchmarks" / "perf"
LIMITS = SimulationLimits(max_requests=50_000_000, max_wall_seconds=3600)
DURATION_S = 600.0
TOLERANCE = 0.05


def steady(doc: dict[str, Any]) -> dict[str, Any]:
    d = json.loads(json.dumps(doc))
    d["scenarios"] = []
    w = d["workload"]
    w.update(
        durationS=DURATION_S,
        warmupS=max(10.0, float(w.get("warmupS") or 0)),
        pattern={"kind": "constant"},
        faults=[],
    )
    return d


def summary(report: dict[str, Any], wall: float) -> dict[str, Any]:
    s = report["summary"]
    return {
        "p50": s["latencyMs"]["p50"],
        "p95": s["latencyMs"]["p95"],
        "throughputRps": s["throughputRps"],
        "successRate": s["successRate"],
        "requests": report["provenance"]["requestsGenerated"],
        "wallSeconds": round(wall, 2),
        "mode": (report.get("simulationMode") or {}).get("kind", "full"),
    }


def err(a: float | None, b: float | None) -> float | None:
    return None if a is None or not b else round((a - b) / b, 4)


def main() -> None:
    rows = []
    for path in sorted((ROOT / "examples").glob("*.json")):
        doc = steady(json.loads(path.read_text(encoding="utf-8")))
        out: dict[str, Any] = {"template": path.stem, "rps": doc["workload"]["rps"]}
        for mode in ("full", "sampled"):
            t0 = time.perf_counter()
            report = run_simulation(
                json.loads(json.dumps(doc)), registry=default_registry(), limits=LIMITS, mode=mode
            )
            out[mode] = summary(report, time.perf_counter() - t0)
            if mode == "sampled":
                out["checks"] = (report.get("simulationMode") or {}).get("checks")
        f, s = out["full"], out["sampled"]
        out["p95Error"] = err(s["p95"], f["p95"])
        out["throughputError"] = err(s["throughputRps"], f["throughputRps"])
        out["within"] = s["mode"] == "full" or (
            abs(out["p95Error"] or 0) <= TOLERANCE and abs(out["throughputError"] or 0) <= TOLERANCE
        )
        out["speedup"] = round(f["wallSeconds"] / max(s["wallSeconds"], 1e-6), 1)
        rows.append(out)
        print(
            f"{path.stem:32} {s['mode']:8} p95 {f['p95']} vs {s['p95']} ({out['p95Error']}), "
            f"throughput {out['throughputError']}, {f['wallSeconds']} s vs {s['wallSeconds']} s",
            file=sys.stderr,
        )
    OUT.mkdir(parents=True, exist_ok=True)
    result = {
        "durationS": DURATION_S,
        "tolerance": TOLERANCE,
        "rows": rows,
        "allWithin": all(r["within"] for r in rows),
    }
    (OUT / "sampled-validation.json").write_text(
        json.dumps(result, indent=1) + "\n", encoding="utf-8"
    )
    print(f"all within ±{TOLERANCE:.0%}: {result['allWithin']}", file=sys.stderr)


if __name__ == "__main__":
    main()
