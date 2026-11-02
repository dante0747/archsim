"""Catalog files: the component catalog, the document JSON Schema, pricing, the assumptions and
constants, and the formatting helpers on grids of values."""

from __future__ import annotations

from typing import Any

from golden.adapters import call
from golden.adapters.catalogs import CONSTANTS
from golden.codec import Json
from golden.file import GoldenFile
from golden.paths import PRICES, read_json


def components_file() -> GoldenFile:
    g = GoldenFile("catalog", name="components")
    g.entry(
        (), "catalog_document", "archsim_engine.registry.ComponentRegistry.catalog_document", {}
    )
    g.entry((), "types", "archsim_engine.registry.ComponentRegistry.types", {})
    g.cases = 1
    return g


def schema_file() -> GoldenFile:
    g = GoldenFile("catalog", name="schema")
    g.entry((), "document_json_schema", "archsim_engine.document_json_schema", {})
    g.cases = 1
    return g


def pricing_file() -> GoldenFile:
    g = GoldenFile("catalog", name="pricing")
    snapshots: dict[str, Json] = {}
    for path in sorted(PRICES.glob("*.json")):
        data = read_json(path)
        g.place(("files", path.name), data)
        snapshots[path.name] = g.entry(
            (),
            f"parse_snapshot[{path.name}]",
            "archsim_engine.pricing.parse_snapshot",
            {"data": data},
        )
        g.entry(
            (),
            f"content_sha256[{path.name}]",
            "archsim_engine.pricing.content_sha256",
            {"data": data},
        )
    for every in (False, True):
        g.entry(
            (), f"available[everyDate={str(every).lower()}]", "archsim_engine.pricing.available",
            {"everyDate": every},
        )  # fmt: skip
    g.entry((), "default_pricing", "archsim_engine.pricing.default_pricing", {})
    newest_name = sorted(snapshots)[-1]
    newest = snapshots[newest_name]
    oldest = snapshots[sorted(snapshots)[0]]
    lookups: list[Json] = [
        {"provider": "generic", "region": None, "date": None, "sha256": None},
        {"provider": "aws", "region": None, "date": None, "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": None, "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": oldest["snapshotDate"], "sha256": None},
        {
            "provider": "aws",
            "region": "us-east-1",
            "date": oldest["snapshotDate"],
            "sha256": oldest["sha256"],
        },
        {"provider": "aws", "region": "us-east-1", "date": "2020-01-01", "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": None, "sha256": "0" * 64},
        {"provider": "aws", "region": "eu-west-9", "date": None, "sha256": None},
        {"provider": "gcp", "region": "us-central1", "date": None, "sha256": None},
    ]
    for n, args in enumerate(lookups):
        g.entry(
            (), f"load_snapshot[{n}]", "archsim_engine.pricing.load_snapshot", args, errors=True
        )
    pricings: list[Json | None] = [
        None,
        {"provider": "generic"},
        {"provider": "aws", "region": "us-east-1"},
        {"provider": "aws", "region": "us-east-1", "snapshotDate": oldest["snapshotDate"]},
        {"provider": "aws", "region": "us-east-1", "snapshotDate": "2020-01-01"},
        {"provider": "aws", "region": "us-east-1", "families": ["m7g", "t4g"]},
    ]
    for n, pricing in enumerate(pricings):
        g.entry((), f"pinned[{n}]", "archsim_engine.pricing.pinned", {"pricing": pricing})
        g.entry(
            (), f"snapshot_for[{n}]", "archsim_engine.pricing.snapshot_for", {"pricing": pricing},
            errors=True,
        )  # fmt: skip
    families = sorted({s["family"] for items in newest["skus"].values() for s in items})
    g.entry(
        (),
        "unknown_families",
        "archsim_engine.pricing.unknown_families",
        {"snapshot": newest, "families": [*families[:3], "zz9", "t4g", "nope"]},
    )
    sizes = [
        (None, None), (0.25, 0.5), (1, 1), (1, 4), (2, 3), (2, 16), (3, 6), (8, 64), (48, 96),
        (1000, 1), (1, 5000),
    ]  # fmt: skip
    n = 0
    for category, items in sorted(newest["skus"].items()):
        engines = sorted({e for s in items for e in s["engines"]})[:2]
        for engine in [None, *engines]:
            for cpu, mem in sizes:
                g.entry(
                    (), f"cover[{n}]", "archsim_engine.pricing.Snapshot.cover",
                    {
                        "snapshot": newest, "category": category, "cpu": cpu, "memoryGib": mem,
                        "option": "on_demand", "engine": engine, "families": [], "busy": None,
                        "burstable": "auto",
                    },
                )  # fmt: skip
                n += 1
    compute_families = sorted({s["family"] for s in newest["skus"].get("compute", [])})
    for cpu, mem in ((0.5, 1), (1, 2), (2, 4)):
        for busy in (None, {"peak": 0.1, "base": 0.05}, {"peak": 1.5, "base": 0.9}):
            for burstable in ("auto", "never"):
                for fams in ([], ["t4g"], compute_families[:1]):
                    g.entry(
                        (), f"cover[{n}]", "archsim_engine.pricing.Snapshot.cover",
                        {
                            "snapshot": newest, "category": "compute", "cpu": cpu,
                            "memoryGib": mem, "option": "on_demand", "engine": None,
                            "families": fams, "busy": busy, "burstable": burstable,
                        },
                    )  # fmt: skip
                    n += 1
    kinds: list[tuple[str, Json]] = [
        ("service", {}), ("service", {"platform": "kubernetes"}),
        ("service", {"platform": "serverless"}), ("database", {}),
        ("database", {"technology": "mysql"}), ("database", {"technology": "dynamodb"}),
        ("cache", {}), ("cache", {"technology": "memcached"}),
        ("message_queue", {"technology": "kafka"}), ("message_queue", {"technology": "sqs"}),
        ("message_queue", {"technology": "rabbitmq"}), ("load_balancer", {}),
        ("load_balancer", {"technology": "aws_alb"}), ("kubernetes_cluster", {}),
        ("traffic_source", {}), ("external_api", {}),
    ]  # fmt: skip
    for n, (node_type, cfg) in enumerate(kinds):
        g.entry(
            (), f"machine_kind[{n}]", "archsim_engine.pricing.machine_kind",
            {"nodeType": node_type, "cfg": cfg},
        )  # fmt: skip
    g.cases = len(snapshots)
    return g


def assumptions_file() -> GoldenFile:
    g = GoldenFile("catalog", name="assumptions")
    g.entry((), "constants", "archsim_engine.assumptions.constants", {})
    g.entry((), "render", "archsim_engine.assumptions.render", {})
    for name in CONSTANTS:
        g.entry((), name.rsplit(".", 2)[-2] + "." + name.rsplit(".", 1)[-1], name, {})
    g.cases = 1
    return g


NUMBERS: list[Any] = [
    0, 0.0001234, 0.004, 0.05, 0.125, 0.5, 0.999, 1, 1.25, 2.5, 2.675, 9.95, 9.999, 10, 12.345,
    99.4, 99.95, 100, 100.5, 999.5, 1234.5, 12345.678, 1e6, 2.5e9, 1e16, -0.5, -12.345, -1500,
]  # fmt: skip
DISTRIBUTIONS: list[Json] = [
    {"kind": "constant", "value": 0},
    {"kind": "constant", "value": 0.25},
    {"kind": "constant", "value": 5},
    {"kind": "exponential", "mean": 12.5},
    {"kind": "exponential", "mean": 0.05},
    {"kind": "uniform", "min": 1, "max": 3},
    {"kind": "lognormal", "median": 5, "p95": 20},
    {"kind": "lognormal", "median": 10, "p95": 10},
    {"kind": "lognormal", "median": 0.8, "p95": 2.25},
    {"kind": "empirical", "quantiles": [[0, 1], [0.5, 5], [0.95, 20], [1, 50]]},
    {"kind": "empirical", "quantiles": [[0, 0.123], [0.25, 1.5], [0.75, 7.25], [1, 123.456]]},
]


def formatting_file() -> GoldenFile:
    """Helpers that turn numbers into text or small results, on grids of values: where a port
    is most likely to differ (rounding, format specifiers, thousands separators)."""
    g = GoldenFile("catalog", name="formatting")
    special: list[Any] = [None, "Infinity", "-Infinity", "NaN"]
    n = 0

    def rec(fn: str, inputs: Json, errors: bool = False) -> None:
        nonlocal n
        g.entry((), f"{fn.rsplit('.', 1)[-1]}[{n}]", fn, inputs, errors=errors)
        n += 1

    for v in NUMBERS + special:
        for digits in (1, 2):
            rec("archsim_engine.explain.num", {"v": v, "digits": digits})
    for v in [None, 0, 0.001, 0.0125, 0.333, 0.5, 0.9995, 1, 1.5]:
        rec("archsim_engine.explain.pct", {"v": v})
    for c in (1, 2, 4, 16, 100):
        for offered in (0, 0.5, 0.9 * c, c - 0.01, c, c + 1):
            rec("archsim_engine.explain.erlang_c", {"c": c, "offered": offered})
    for keys in (1, 10, 1000, 10_000, 100_000, 1_000_000):
        for s in (0, 0.5, 0.99, 1.0, 1.2):
            for top in (1, 10):
                rec("archsim_engine.explain.zipf_share", {"n": keys, "s": s, "top": top})
    for x in NUMBERS:
        rec("archsim_engine.load.js_num", {"x": x})
        rec("archsim_engine.load.fmt_int", {"x": x})
        rec("archsim_engine.sizing.fmt", {"v": x})
        for digits in (0, 1, 2, 3):
            rec("archsim_engine.load.fixed", {"x": x, "digits": digits})
            rec("archsim_engine.load.round_half_up", {"x": x, "digits": digits})
        if x > 0:
            rec("archsim_engine.seeds.fmt_ms", {"v": x})
    for x in (0.005, 0.015, 0.025, 0.135, 1.005, 1.115, 2.5, 3.5, -2.5):
        for digits in (0, 1, 2):
            rec("archsim_engine.load.round_half_up", {"x": x, "digits": digits})
    for d in DISTRIBUTIONS:
        rec("archsim_engine.distributions.Distribution.expected", {"d": d})
        rec("archsim_engine.distributions.Distribution.describe", {"d": d})
        rec("archsim_engine.load.mean_ms", {"d": d})
        if d["kind"] == "empirical":
            for q in (0, 0.25, 0.5, 0.95, 0.99, 1):
                rec("archsim_engine.distributions.Empirical.quantile", {"d": d, "q": q})
    for v, unit in [
        (5, None), (5, "ms"), (0.25, "ms"), (12345.678, "req/s"), (True, None), ("redis", None),
        (None, None), ([1, 2], None), (DISTRIBUTIONS[6], "ms"), (DISTRIBUTIONS[9], "ms"),
        (DISTRIBUTIONS[3], None), ({"kind": "trace", "points": [[0, 10], [5, 20]]}, None),
    ]:  # fmt: skip
        rec("archsim_engine.provenance.describe_value", {"v": v, "unit": unit}, errors=True)
    for values in ([], [None], [12.5], [3, None, 1.25, 7], [100.5, 99.5]):
        r = call("archsim_engine.seeds.value_range", {"values": values})
        rec("archsim_engine.seeds.value_range", {"values": values})
        for label in ("p95", "throughput"):
            rec("archsim_engine.seeds.range_text", {"label": label, "r": r})
    for seeds in ([], [42], [42, 43, 44], [1, 5, 9], [42, 43, 45], [7, 8]):
        rec("archsim_engine.seeds.seeds_text", {"seeds": seeds})
    for level in ("low", "medium", "high", "unknown"):
        for cap in (None, "low", "medium", "high"):
            rec("archsim_engine.seeds.cap_confidence", {"level": level, "cap": cap})
    for args in [
        (100, 0, 1000, 0.99, 60, None), (1000, 10, 100_000, 0.99, 300, None),
        (1000, 10, 100_000, 0.99, 300, 5000), (50, 50, 10, 0, 1, None),
        (0, 10, 1000, 0.99, 60, None), (500, 0, 1_000_000, 1.0, 30, 20_000),
        (2000, 200, 50_000, 1.2, 5, None), (10, 1, 3000, 0.5, 600, 2500),
    ]:  # fmt: skip
        names = ("readRps", "writeRps", "keys", "skew", "ttlS", "capacityKeys")
        rec("archsim_engine.load.ttl_hit_ratio", dict(zip(names, args, strict=True)))
    for slots, lam, hold in [
        (1, 50, 10), (1, 99, 10), (4, 320, 10), (8, 100, 50), (1, 100, 10), (16, 0, 5),
        (2, 150, 12.5), (100, 9000, 10),
    ]:  # fmt: skip
        rec("archsim_engine.estimate.mmc_wait", {"slots": slots, "lam": lam, "holdMs": hold})
    for lo, hi, steps in [(50, 300, 6), (0, 10, 6), (1, 1, 3), (300, 50, 4), (12, 18, 10), (5, 200_000, 2)]:  # fmt: skip
        rec("archsim_engine.sweep.levels", {"fromRps": lo, "toRps": hi, "steps": steps})
    for current in (0, 1, 7, 40, 120, 400, 750, 1500, 2800, 60_000):
        rec("archsim_engine.sweep.suggest_range", {"currentRps": current, "cap": 100_000.0})
    rec("archsim_engine.sweep.suggest_range", {"currentRps": 40_000, "cap": 50_000.0})
    for duration in (2.0, 20.0, 60.0):
        rec("archsim_engine.sweep.rps_cap", {"durationS": duration})
    objects: list[Any] = [
        {}, [], {"b": 1, "a": [1, 2.5, "x"]}, {"u": "caf\u00e9 \u2713 \u6771\u4eac \u2013 \U0001f600"},
        {"f": [0.1, 0.2, 0.30000000000000004, 1e-05, 1e-7, 1e16, 1e22, 1.5e300, 123456789.125]},
        {"i": [0, -1, 2**53, 2**63, -(2**31)]}, {"n": None, "t": True, "f": False},
        {"neg": -0.0, "half": 0.5, "third": 1 / 3}, {"esc": "quote \" slash \\ tab \t nl \n"},
        {"keys": {"Z": 1, "a": 2, "_": 3, "\u00e9": 4, "10": 5, "9": 6}},
    ]  # fmt: skip
    for obj in objects:
        rec("archsim_engine.report.canonical_digest", {"obj": obj})
        rec("archsim_engine.pricing.content_sha256", {"data": obj})
    g.cases = 1
    return g
