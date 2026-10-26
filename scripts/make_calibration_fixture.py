"""Write the Prometheus calibration fixture for the cache-aside starter (tests and docs).

The responses are **generated**, not recorded from a live server: component latency histograms
come from known lognormal distributions (the "truth" below), cache hits and misses from a known
hit ratio, the arrival rate from a known wave, and the end-to-end latency and throughput that the
replay check compares against come from simulating the truth. Calibrating the starter from this
fixture should therefore recover the truth and replay it closely; the test checks both.

Usage: uv run python scripts/make_calibration_fixture.py
"""

from __future__ import annotations

import copy
import itertools
import json
import math
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from archsim_engine import run_simulation

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "packages" / "engine" / "tests" / "fixtures" / "calibration"
START = 1790812800  # 2026-10-01T00:00:00Z
SPAN_S = 120
STEP_S = 5


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, UTC).isoformat().replace("+00:00", "Z")


LE_S = [
    0.0005,
    0.001,
    0.002,
    0.003,
    0.004,
    0.005,
    0.006,
    0.008,
    0.01,
    0.0125,
    0.015,
    0.02,
    0.025,
    0.03,
    0.04,
    0.05,
    0.06,
    0.075,
    0.09,
    0.1,
    0.125,
    0.15,
    0.2,
    0.25,
    0.3,
    0.4,
    0.5,
    1.0,
    2.5,
]

TRUTH = {
    "api": {"median": 6.0, "p95": 18.0},
    "dbRead": {"median": 4.0, "p95": 14.0},
    "dbWrite": {"median": 9.0, "p95": 30.0},
    "hitRatio": 0.85,
    "apiDbOneWayMs": 0.4,
}

Q = {
    "api": 'sum by (le) (rate(http_server_handler_duration_seconds_bucket{job="api"}[1m]))',
    "dbRead": 'sum by (le) (rate(pg_query_duration_seconds_bucket{job="postgres",op="read"}[1m]))',
    "dbWrite": 'sum by (le) (rate(pg_query_duration_seconds_bucket{job="postgres",op="write"}[1m]))',
    "dbClient": 'sum by (le) (rate(db_client_duration_seconds_bucket{job="api"}[1m]))',
    "hits": "sum(rate(redis_keyspace_hits_total[1m]))",
    "misses": "sum(rate(redis_keyspace_misses_total[1m]))",
    "rate": 'sum(rate(http_requests_total{job="lb"}[1m]))',
    "e2e": "sum by (le) (rate(client_request_duration_seconds_bucket[1m]))",
    "ok": 'sum(rate(http_requests_total{job="lb",code=~"2.."}[1m]))',
    "dbObserved": 'sum by (le) (rate(pg_query_duration_seconds_bucket{job="postgres"}[1m]))',
}


def times() -> list[int]:
    return list(range(START, START + SPAN_S + 1, STEP_S))


def rate_at(t: int) -> float:
    return 250 + 50 * math.sin(2 * math.pi * (t - START) / SPAN_S)


def lognormal_cdf(x_ms: float, median: float, p95: float, shift: float = 0.0) -> float:
    x = x_ms - shift
    if x <= 0:
        return 0.0
    sigma = math.log(p95 / median) / 1.6448536269514722
    return 0.5 * (1 + math.erf((math.log(x) - math.log(median)) / (sigma * math.sqrt(2))))


def piecewise_cdf(x_ms: float, pct: dict[str, float]) -> float:
    """A CDF through (0, 0), (p50, .5), (p95, .95), (p99, .99), (max, 1)."""
    pts = [(0.0, 0.0), (pct["p50"], 0.5), (pct["p95"], 0.95), (pct["p99"], 0.99), (pct["max"], 1.0)]
    for (x0, c0), (x1, c1) in itertools.pairwise(pts):
        if x_ms <= x1:
            return c0 + (c1 - c0) * (x_ms - x0) / (x1 - x0) if x1 > x0 else c1
    return 1.0


def histogram(cdf: Any, share: float) -> dict[str, Any]:
    """Bucket rates (per second) over time: cumulative rate per le = rate x share x CDF(le)."""
    result = []
    for le in [*LE_S, math.inf]:
        frac = 1.0 if math.isinf(le) else cdf(le * 1000)
        result.append(
            {
                "metric": {"le": "+Inf" if math.isinf(le) else f"{le:g}"},
                "values": [[t, f"{rate_at(t) * share * frac:.6f}"] for t in times()],
            }
        )
    return {"status": "success", "data": {"resultType": "matrix", "result": result}}


def gauge(f: Any) -> dict[str, Any]:
    return {
        "status": "success",
        "data": {
            "resultType": "matrix",
            "result": [{"metric": {}, "values": [[t, f"{f(t):.6f}"] for t in times()]}],
        },
    }


def truth_document() -> dict[str, Any]:
    doc = json.loads((ROOT / "examples" / "cache-aside-starter.json").read_text(encoding="utf-8"))
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    nodes["api"]["config"]["serviceTimeMs"] = {"kind": "lognormal", **TRUTH["api"]}
    nodes["db"]["config"]["readServiceTimeMs"] = {"kind": "lognormal", **TRUTH["dbRead"]}
    nodes["db"]["config"]["writeServiceTimeMs"] = {"kind": "lognormal", **TRUTH["dbWrite"]}
    nodes["cache"]["config"]["hitRatio"] = TRUTH["hitRatio"]
    for e in doc["architecture"]["edges"]:
        if e["id"] == "api-db":
            e["config"]["latencyMs"] = {"kind": "constant", "value": TRUTH["apiDbOneWayMs"]}
    doc["workload"].update(
        {
            "durationS": SPAN_S,
            "warmupS": 10,
            "rps": 250,
            "pattern": {"kind": "trace", "points": [[t - START, rate_at(t)] for t in times()]},
        }
    )
    doc["scenarios"] = []
    return doc


def main() -> None:
    truth = truth_document()
    report = run_simulation(copy.deepcopy(truth))
    e2e = report["summary"]["latencyMs"]
    db = next(n for n in report["nodes"] if n["id"] == "db")["summary"]["latencyMs"]
    read_share = truth["workload"]["readRatio"]
    responses = {
        Q["api"]: histogram(lambda x: lognormal_cdf(x, **TRUTH["api"]), 1.0),
        Q["dbRead"]: histogram(
            lambda x: lognormal_cdf(x, **TRUTH["dbRead"]), read_share * (1 - TRUTH["hitRatio"])
        ),
        Q["dbWrite"]: histogram(lambda x: lognormal_cdf(x, **TRUTH["dbWrite"]), 1 - read_share),
        Q["dbClient"]: histogram(
            lambda x: lognormal_cdf(x, **TRUTH["dbRead"], shift=2 * TRUTH["apiDbOneWayMs"]),
            read_share * (1 - TRUTH["hitRatio"]),
        ),
        Q["hits"]: gauge(lambda t: rate_at(t) * read_share * TRUTH["hitRatio"]),
        Q["misses"]: gauge(lambda t: rate_at(t) * read_share * (1 - TRUTH["hitRatio"])),
        Q["rate"]: gauge(rate_at),
        Q["e2e"]: histogram(lambda x: piecewise_cdf(x, e2e), 1.0),
        Q["ok"]: gauge(lambda t: report["summary"]["throughputRps"]),
        Q["dbObserved"]: histogram(lambda x: piecewise_cdf(x, db), 1.0),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "starter-prometheus.json").write_text(
        json.dumps(
            {
                "source": "generated fixture (scripts/make_calibration_fixture.py)",
                "truth": TRUTH,
                "range": {"start": iso(START), "end": iso(START + SPAN_S), "step": STEP_S},
                "responses": responses,
            },
            indent=1,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    mapping = f"""# Calibration mapping for examples/cache-aside-starter.json against the generated fixture
# starter-prometheus.json (scripts/make_calibration_fixture.py). Real deployments use their own
# metric names; see docs/calibration.md.
range: {{start: "2026-10-01T00:00:00Z", end: "2026-10-01T00:02:00Z", step: {STEP_S}}}
nodes:
  api:
    serviceTimeMs: {{histogram: '{Q["api"]}', unit: s}}
  cache:
    hitRatio:
      ratio: {{hits: '{Q["hits"]}', misses: '{Q["misses"]}'}}
  db:
    readServiceTimeMs: {{histogram: '{Q["dbRead"]}', unit: s}}
    writeServiceTimeMs: {{histogram: '{Q["dbWrite"]}', unit: s}}
edges:
  api-db:
    latencyMs: {{client: '{Q["dbClient"]}', server: '{Q["dbRead"]}', unit: s}}
workload:
  rps: {{rate: '{Q["rate"]}', trace: true}}
replay:
  endToEnd: {{histogram: '{Q["e2e"]}', unit: s, throughput: '{Q["ok"]}'}}
  components:
    db: {{histogram: '{Q["dbObserved"]}', unit: s}}
"""
    (OUT / "starter-mapping.yaml").write_text(mapping, encoding="utf-8", newline="\n")
    print(
        f"wrote {OUT.relative_to(ROOT)}: truth e2e p95 {e2e['p95']} ms, throughput {report['summary']['throughputRps']}"
    )


if __name__ == "__main__":
    main()
