"""Write a small OTLP JSON trace export for tests and docs (generated, seeded).

Traces of a "checkout-api" service: each request is a SERVER span whose own work is lognormal
(median 6 ms, p95 18 ms) around a CLIENT span to the "orders" PostgreSQL database (reads
median 4 / p95 14 ms, writes median 9 / p95 30 ms); one in ten also calls an "inventory"
service the starter design does not have. Each SERVER span of checkout-api records the CPU time
it used (``app.cpu_time_ms``: 70% of its own time), which calibration reads as the CPU share
when the mapping names it (``cpuTime``).

Usage: uv run python scripts/make_otlp_fixture.py
"""

from __future__ import annotations

import json
import math
import random
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "packages" / "engine" / "tests" / "fixtures" / "calibration"
START_NS = 1790812800 * 10**9  # 2026-10-01T00:00:00Z
TRACES = 400
Z95 = 1.6448536269514722
CPU_SHARE = 0.7  # of a SERVER span's own time


def lognormal(rng: random.Random, median: float, p95: float) -> float:
    return rng.lognormvariate(math.log(median), math.log(p95 / median) / Z95)


def attr(key: str, value: Any) -> dict[str, Any]:
    v: dict[str, Any]
    if isinstance(value, int):
        v = {"intValue": str(value)}
    elif isinstance(value, float):
        v = {"doubleValue": value}
    else:
        v = {"stringValue": str(value)}
    return {"key": key, "value": v}


def main() -> None:
    rng = random.Random(7)
    api_spans: list[dict[str, Any]] = []
    inv_spans: list[dict[str, Any]] = []
    for i in range(TRACES):
        trace = f"{i + 1:032x}"
        t0 = START_NS + int(i * 150e6)  # one request every 150 ms
        own = lognormal(rng, 6, 18)
        write = rng.random() < 0.1
        db_ms = lognormal(rng, 9, 30) if write else lognormal(rng, 4, 14)
        inv_ms = lognormal(rng, 3, 8) if i % 10 == 0 else 0.0
        before = own * 0.4
        db_start = t0 + int(before * 1e6)
        db_end = db_start + int(db_ms * 1e6)
        inv_start = db_end
        inv_end = inv_start + int(inv_ms * 1e6)
        end = inv_end + int(own * 0.6 * 1e6)
        root = f"{i + 1:08x}a0000000"
        db = f"{i + 1:08x}b0000000"
        api_spans.append(
            {
                "traceId": trace,
                "spanId": root,
                "name": "POST /orders" if write else "GET /orders/{id}",
                "kind": 2,
                "startTimeUnixNano": str(t0),
                "endTimeUnixNano": str(end),
                "attributes": [
                    attr("http.request.method", "POST" if write else "GET"),
                    attr("app.cpu_time_ms", round(own * CPU_SHARE, 4)),
                ],
            }
        )
        api_spans.append(
            {
                "traceId": trace,
                "spanId": db,
                "parentSpanId": root,
                "name": "UPDATE orders" if write else "SELECT orders",
                "kind": 3,
                "startTimeUnixNano": str(db_start),
                "endTimeUnixNano": str(db_end),
                "attributes": [
                    attr("db.system", "postgresql"),
                    attr("db.name", "orders"),
                    attr("db.operation", "UPDATE" if write else "SELECT"),
                ],
            }
        )
        if inv_ms:
            call = f"{i + 1:08x}c0000000"
            api_spans.append(
                {
                    "traceId": trace,
                    "spanId": call,
                    "parentSpanId": root,
                    "name": "GET inventory",
                    "kind": 3,
                    "startTimeUnixNano": str(inv_start),
                    "endTimeUnixNano": str(inv_end),
                    "attributes": [attr("server.address", "inventory")],
                }
            )
            inv_spans.append(
                {
                    "traceId": trace,
                    "spanId": f"{i + 1:08x}d0000000",
                    "parentSpanId": call,
                    "name": "GET /stock",
                    "kind": 2,
                    "startTimeUnixNano": str(inv_start + 100_000),
                    "endTimeUnixNano": str(inv_end - 100_000),
                }
            )

    def resource(service: str, spans: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "resource": {"attributes": [attr("service.name", service)]},
            "scopeSpans": [{"scope": {"name": "fixture"}, "spans": spans}],
        }

    data = {
        "resourceSpans": [resource("checkout-api", api_spans), resource("inventory", inv_spans)]
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "checkout-traces.otlp.json").write_text(
        json.dumps(data) + "\n", encoding="utf-8", newline="\n"
    )
    (OUT / "otlp-mapping.yaml").write_text(
        "# OpenTelemetry names -> parts of examples/cache-aside-starter.json\n"
        "services:\n  checkout-api: api\n"
        "databases:\n  orders: db\n"
        "# the span attribute in which checkout-api records each request's CPU time\n"
        "cpuTime: {attribute: app.cpu_time_ms, unit: ms}\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"wrote {OUT.relative_to(ROOT)}/checkout-traces.otlp.json ({len(api_spans) + len(inv_spans)} spans)"
    )


if __name__ == "__main__":
    main()
