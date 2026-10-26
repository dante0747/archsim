"""Measure the engine's speed on every template (and the 6M-request target design), and profile
the slowest. Writes benchmarks/perf/<label>.json; docs/performance.md quotes these files.

    uv run python scripts/perf.py --label baseline [--profile] [--target]

Run on a quiet machine: other work skews wall-clock numbers.
"""

from __future__ import annotations

import argparse
import cProfile
import io
import json
import platform
import pstats
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packages" / "engine" / "src"))

from archsim_engine import default_registry, run_simulation  # noqa: E402
from archsim_engine.engine import SimulationLimits  # noqa: E402

OUT = ROOT / "benchmarks" / "perf"
UNLIMITED = SimulationLimits(max_requests=50_000_000, max_wall_seconds=3600)


def target_design(rps: float = 10_000, duration_s: float = 600) -> dict[str, Any]:
    """Phase 8's target: a six-part design (clients, load balancer, API service, cache, sharded
    database, a third-party email API on writes) sized to carry 10k req/s for 10 minutes, about
    6M requests: 99.98% success, no part saturated (checked with a 12 s run)."""
    d = json.loads((ROOT / "examples" / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d["scenarios"] = []
    d["workload"].update(
        rps=rps,
        durationS=duration_s,
        warmupS=10,
        pattern={"kind": "constant"},
        keyCount=1_000_000,
        keySkew=0.8,
    )
    nodes = {n["id"]: n for n in d["architecture"]["nodes"]}
    nodes["api"]["config"]["replicas"] = 320
    nodes["db"]["config"].update(readReplicas=2, maxConnections=100, shards=4, queueCapacity=1000)
    d["architecture"]["nodes"].append(
        {"id": "email", "type": "external_api", "label": "Email API", "config": {"technology": "email"}}
    )
    d["architecture"]["edges"].append(
        {
            "id": "api-email",
            "source": "api",
            "sourcePort": "out",
            "target": "email",
            "targetPort": "in",
            "config": {"requestTypes": ["write"]},
        }
    )
    d["metadata"]["name"] = "Phase 8 target: 10k req/s for 10 minutes"
    return d


def measure(doc: dict[str, Any], repeats: int, mode: str = "full") -> dict[str, Any]:
    walls, report = [], None
    for _ in range(repeats):
        t0 = time.perf_counter()
        report = run_simulation(
            json.loads(json.dumps(doc)),
            registry=default_registry(),
            limits=UNLIMITED,
            mode=mode,  # type: ignore[arg-type]
        )
        walls.append(time.perf_counter() - t0)
    assert report is not None
    p = report["provenance"]
    best = min(walls)
    return {
        "name": doc["metadata"]["name"],
        "requests": p["requestsGenerated"],
        "events": p["eventsProcessed"],
        "wallSeconds": [round(w, 3) for w in walls],
        "bestWallSeconds": round(best, 3),
        "requestsPerSecond": round(p["requestsGenerated"] / best),
        "eventsPerSecond": round(p["eventsProcessed"] / best),
        "resultDigest": p["resultDigest"],
        "mode": (report.get("simulationMode") or {}).get("kind", "full"),
        "p95": report["summary"]["latencyMs"]["p95"],
        "throughputRps": report["summary"]["throughputRps"],
    }


def profile(doc: dict[str, Any], top: int = 25) -> str:
    pr = cProfile.Profile()
    pr.enable()
    run_simulation(json.loads(json.dumps(doc)), registry=default_registry(), limits=UNLIMITED)
    pr.disable()
    buf = io.StringIO()
    pstats.Stats(pr, stream=buf).sort_stats("tottime").print_stats(top)
    return buf.getvalue()


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--label", required=True, help="e.g. baseline, after")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--profile", action="store_true", help="also profile the slowest template")
    ap.add_argument(
        "--target", action="store_true", help="also run the 6M-request target design once"
    )
    ap.add_argument("--target-mode", choices=["full", "sampled"], default="full")
    ap.add_argument("--only-target", action="store_true", help="skip the templates")
    args = ap.parse_args()
    rows = []
    for path in [] if args.only_target else sorted((ROOT / "examples").glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["scenarios"] = []
        row = {"template": path.stem, **measure(doc, args.repeats)}
        rows.append(row)
        print(
            f"{path.stem:32} {row['bestWallSeconds']:8.2f} s {row['requests']:>10,} req {row['requestsPerSecond']:>8,} req/s",
            file=sys.stderr,
        )
    out: dict[str, Any] = {
        "label": args.label,
        "at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "python": platform.python_version(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "templates": rows,
    }
    if args.target:
        out["target"] = measure(target_design(), 1, args.target_mode)
        t = out["target"]
        print(
            f"target: {t['bestWallSeconds']:.1f} s for {t['requests']:,} requests", file=sys.stderr
        )
    if args.profile and rows:
        slowest = max(rows, key=lambda r: r["bestWallSeconds"])
        doc = json.loads(
            (ROOT / "examples" / f"{slowest['template']}.json").read_text(encoding="utf-8")
        )
        doc["scenarios"] = []
        out["profile"] = {"template": slowest["template"], "top": profile(doc)}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{args.label}.json").write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {OUT / (args.label + '.json')}", file=sys.stderr)


if __name__ == "__main__":
    main()
