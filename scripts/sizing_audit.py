"""Sizing audit: does auto-sizing give each part what it needs, on every template?

    uv run python scripts/sizing_audit.py [--seconds 30 | --full] [--json out.json] [template ...]

Each template's design as drawn (no scenario, so no faults) is simulated, compressed to
``--seconds`` (a burst at 40% of the run stays at 40%) unless ``--full``, and checked against
auto-sizing's acceptance criteria (docs/simulation-model.md, "Auto-sizing"):

1. **cpu**: each auto-sized part's p95 CPU (busy cores per instance, from the per-bucket
   ``cpuBusyCores`` series) is 25% to 75% of its vCPU, unless it is already on the smallest type
   its CPU need allows (sizing says ``smallest``), its CPU is capped by its workers or
   connections, or its memory picked a bigger type (the explanation says each).
2. **bottleneck**: no auto-sized part is the run's bottleneck because of its CPU or memory (a
   template may still saturate its workers or connections on purpose).
3. **memory**: simulated memory peak ≤ the memory it needs ≤ its instance's memory.
4. **rerun**: running the run's input again gives the same instance types and cost.
5. **monotonic**: at 1.5 and 2 times the request rate, no part's instance gets cheaper, and its
   memory still covers what it needs. (vCPU alone is not the measure: a light part moves from a
   2-vCPU burstable t4g type to a 1-vCPU full-core type as its load grows, and back to a bigger
   burstable one when that is the cheapest that passes the credit check.)
6. **per-request**: each per-request cost line is within 10% of a hand calculation from the
   run: the part's rate at the base load, times the month, times each unit's list price (a
   function also its duration at the base load and its memory).
7. **cost**: the auto-sized monthly cost is at most 1.15 times the template's hand-picked
   reference (``REFERENCE``, recorded with the test).

Prints one line per part and a summary; ``--json`` writes everything. Exit status 1 when a check
fails. Run by hand and by ``packages/engine/tests/test_sizing_audit.py``.
"""

from __future__ import annotations

import argparse
import copy
import itertools
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples"
sys.path.insert(0, str(ROOT / "packages" / "engine" / "src"))

from archsim_engine import analyze_document, create_default_registry, run_simulation  # noqa: E402
from archsim_engine.cost import SECONDS_PER_MONTH, sku_named  # noqa: E402
from archsim_engine.load import base_rps  # noqa: E402
from archsim_engine.pricing import snapshot_for  # noqa: E402

Json = dict[str, Any]
REGISTRY = create_default_registry(include_plugins=False)
CPU_LOW, CPU_HIGH = 0.25, 0.75
COST_SLACK = 1.15
RERUN_SECONDS = 4.0
"""The reproducibility check runs each template this long (twice)."""
HELD_BACK = 0.75
"""A part that receives less than this share of the peak the design sends it (a bottleneck or a
queue in front holds it back) is sized for more than it gets: its low CPU is not a sizing error."""
PER_REQUEST_SLACK = 0.10
REFERENCE: dict[str, float] = {}
"""Hand-picked monthly cost per template ($), filled in by the test (see test_sizing_audit.py)."""


@dataclass
class Check:
    template: str
    part: str
    check: str
    ok: bool
    detail: str


@dataclass
class Audit:
    template: str
    monthly: float = 0.0
    checks: list[Check] = field(default_factory=list)

    def add(self, part: str, check: str, ok: bool, detail: str) -> None:
        self.checks.append(Check(self.template, part, check, ok, detail))

    @property
    def failed(self) -> list[Check]:
        return [c for c in self.checks if not c.ok]


def compressed(doc: Json, seconds: float) -> Json:
    """The template's whole timeline squeezed into ``seconds`` (a burst or fault at 40% of the
    run stays at 40%), its scenarios dropped."""
    d = copy.deepcopy(doc)
    d["scenarios"] = []
    w = d["workload"]
    k = seconds / w["durationS"]
    if k >= 1:
        return d
    w["durationS"], w["warmupS"] = seconds, max(1.0, round(w["warmupS"] * k, 3))
    p = w.get("pattern") or {}
    for key in ("startS", "durationS"):
        if key in p:
            p[key] = round(p[key] * k, 3)
    if p.get("kind") == "trace":
        p["points"] = [[round(t * k, 3), r] for t, r in p["points"]]
    for f in w.get("faults") or []:
        for key in ("startS", "durationS"):
            if key in f:
                f[key] = round(f[key] * k, 3)
    return d


def _p95(values: list[float]) -> float:
    xs = sorted(values)
    if not xs:
        return 0.0
    return xs[min(len(xs) - 1, max(0, math.ceil(0.95 * len(xs)) - 1))]


def _window(report: Json) -> list[int]:
    t = report["timeseries"]["t"]
    lo, hi = report["window"]["warmupS"], report["window"]["durationS"]
    return [i for i, x in enumerate(t) if lo <= x < hi]


def _instances(node: Json, cfg: Json, i: int) -> float:
    """A part's instances in bucket ``i``: its replicas (as it scaled), shards, or database nodes."""
    ts = node.get("timeseries") or {}
    if node["type"] == "service":
        reps = ts.get("replicas")
        if reps and i < len(reps) and reps[i]:
            return float(reps[i])
        return float(cfg.get("replicas") or 1)
    if node["type"] == "cache":
        return float(cfg.get("shards") or 1)
    if node["type"] == "database":
        shards = float(cfg.get("shards") or 1)
        per = max(float(cfg.get("replicationFactor") or 1), 1 + float(cfg.get("readReplicas") or 0))
        return shards * per
    return 1.0


def check_cpu(audit: Audit, report: Json, cfgs: dict[str, Json]) -> None:
    sizing = report["sizing"]["nodes"]
    window = _window(report)
    slot_bound = {
        f.get("nodeId")
        for f in report.get("findings") or []
        if f.get("kind") == "bottleneck"
        and (
            next((n for n in report["nodes"] if n["id"] == f.get("nodeId")), {}).get("summary")
            or {}
        ).get("capacityLimit")
        in (None, "slots")
    }
    for node in report["nodes"]:
        entry = sizing.get(node["id"])
        if not entry or entry.get("skipped"):
            continue
        spec = (entry.get("specs") or {}).get("cpuCores") or {}
        if spec.get("source") != "auto":
            continue
        busy = (node.get("timeseries") or {}).get("cpuBusyCores")
        vcpu = float(spec["value"])
        if not busy:
            continue  # its CPU is not simulated (a load balancer or broker: illustrative only)
        cfg = cfgs[node["id"]]
        per = [
            busy[i] / _instances(node, cfg, i)
            for i in window
            if i < len(busy) and busy[i] is not None
        ]
        util = _p95(per) / vcpu if vcpu else 0.0
        why = entry.get("why", {}).get("cpuCores", "")
        need = (entry.get("need") or {}).get("cpuCores") or 0.0
        arrivals = (node.get("timeseries") or {}).get("arrivalRps") or []
        seen = _p95([arrivals[i] for i in window if i < len(arrivals) and arrivals[i] is not None])
        designed = float(entry.get("peakRps") or 0.0)
        if CPU_LOW <= util <= CPU_HIGH:
            ok, reason = True, "in range"
        elif util < CPU_LOW and entry.get("smallest"):
            ok, reason = True, "already on the smallest type its CPU need allows"
        elif "capped at" in why:
            ok, reason = True, "capped by its workers or connections"
        elif util > CPU_HIGH and node["id"] in slot_bound:
            ok, reason = True, "the run's bottleneck by its workers or connections, not its CPU"
        elif util < CPU_LOW and vcpu > max(1.0, 2 ** math.ceil(math.log2(max(need, 1e-9)))) + 1e-9:
            ok, reason = True, "a bigger type for its memory"
        elif util < CPU_LOW and designed > 0 and seen < HELD_BACK * designed:
            ok, reason = (
                True,
                (
                    f"its load is held back upstream: p95 {seen:,.0f} req/s reached it of the "
                    f"{designed:,.0f} the design's peak sends"
                ),
            )
        else:
            ok, reason = False, "out of range"
        audit.add(
            node["id"],
            "cpu",
            ok,
            f"p95 {_p95(per):.2f} busy cores per instance of {vcpu:g} vCPU = {util:.0%} ({reason}; {entry.get('sku') or 'no type'})",
        )


def check_bottleneck(audit: Audit, report: Json) -> None:
    sizing = report["sizing"]["nodes"]
    for f in report.get("findings") or []:
        if f.get("kind") != "bottleneck":
            continue
        nid = f.get("nodeId")
        entry = sizing.get(nid) or {}
        specs = entry.get("specs") or {}
        auto = [k for k, v in specs.items() if v.get("source") == "auto"]
        node = next((n for n in report["nodes"] if n["id"] == nid), None)
        by = ((node or {}).get("summary") or {}).get("capacityLimit")
        resource = {"cpu": "cpuCores", "memory": "memoryGib"}.get(str(by))
        bad = resource is not None and resource in auto
        audit.add(
            str(nid),
            "bottleneck",
            not bad,
            f"bottleneck limited by {by or 'its slots'}"
            + (" (an auto-sized resource)" if bad else " (not an auto-sized resource)"),
        )


def check_memory(audit: Audit, report: Json, cfgs: dict[str, Json]) -> None:
    sizing = report["sizing"]["nodes"]
    for node in report["nodes"]:
        entry = sizing.get(node["id"])
        if not entry or entry.get("skipped"):
            continue
        spec = (entry.get("specs") or {}).get("memoryGib") or {}
        if spec.get("source") != "auto":
            continue
        need = entry.get("need") or {}
        most = need.get("memoryGibMax", need.get("memoryGib"))
        instance = float(spec["value"])
        s = node.get("summary") or {}
        peak = s.get("memoryPeakGiB")
        if peak is None and node["type"] == "cache" and s.get("keysCached") is not None:
            item = float(cfgs[node["id"]].get("itemSizeKb") or 1.0)
            shards = float(cfgs[node["id"]].get("shards") or 1)
            peak = s["keysCached"] * item / 1024 / 1024 / shards
        if most is None:
            continue
        # the report rounds the peak to 3 decimals
        ok = most <= instance + 1e-6 and (peak is None or peak <= most + 1e-3)
        shown = "not simulated" if peak is None else f"{peak:.3f} GiB"
        audit.add(
            node["id"],
            "memory",
            ok,
            f"peak {shown} ≤ need {most:.3f} GiB ≤ instance {instance:g} GiB",
        )


def check_rerun(audit: Audit, doc: Json) -> None:
    """A short run of the template, then its input run again: the same types and cost."""
    report = run_simulation(compressed(doc, RERUN_SECONDS))
    again = run_simulation(copy.deepcopy(report["input"]))
    first = {k: v.get("sku") for k, v in report["sizing"]["nodes"].items()}
    second = {k: v.get("sku") for k, v in again["sizing"]["nodes"].items()}
    same = first == second and abs(again["cost"]["monthly"] - report["cost"]["monthly"]) < 0.01
    audit.add(
        "*",
        "rerun",
        same,
        f"types {'same' if first == second else f'{first} -> {second}'}, cost "
        f"${report['cost']['monthly']:,.2f} -> ${again['cost']['monthly']:,.2f}",
    )


def _scaled(doc: Json, factor: float) -> Json:
    d = copy.deepcopy(doc)
    w = d["workload"]
    w["rps"] = w["rps"] * factor
    p = w.get("pattern") or {}
    for key in ("burstRps", "startRps", "endRps"):
        if key in p:
            p[key] = p[key] * factor
    if p.get("kind") == "trace":
        p["points"] = [[t, r * factor] for t, r in p["points"]]
    return d


def check_monotonic(audit: Audit, doc: Json) -> None:
    steps = [
        analyze_document(_scaled(doc, f), REGISTRY)["sizing"]["nodes"] for f in (1.0, 1.5, 2.0)
    ]
    for nid, entry in steps[0].items():
        if entry.get("skipped"):
            continue
        shrank = []
        for lo, hi in itertools.pairwise(steps):
            a, b = lo.get(nid) or {}, hi.get(nid) or {}
            sa, sb = a.get("specs") or {}, b.get("specs") or {}
            if "cpuCores" not in sa or "cpuCores" not in sb:
                continue
            pa, pb = a.get("pricePerHour"), b.get("pricePerHour")
            mb = (sb.get("memoryGib") or {}).get("value") or 0
            need = (b.get("need") or {}).get("memoryGibMax", (b.get("need") or {}).get("memoryGib"))
            if (sb.get("memoryGib") or {}).get("source") != "auto":
                need = None  # memory set on the part: the design's choice
            short = need is not None and mb and mb < need - 1e-9
            cheaper = pa is not None and pb is not None and pb < pa - 1e-9
            if cheaper or short:
                shrank.append(
                    f"{a.get('sku') or sa['cpuCores']['value']} -> {b.get('sku') or sb['cpuCores']['value']}"
                    + (" (cheaper)" if cheaper else " (memory below its need)")
                )
        audit.add(nid, "monotonic", not shrank, "; ".join(shrank) or "never cheaper at 1.5x and 2x")


def check_per_request(audit: Audit, report: Json, doc: Json) -> None:
    """Each per-request line against a hand calculation: the part's rate while the offered load
    was at the base (from the run's own series), times the month, times each list price."""
    base = base_rps(report["input"]["workload"])
    ts = report["timeseries"]
    window = _window(report)
    calm = [i for i in window if ts["offeredRps"][i] <= base * 1.25] or window
    offered = sum(ts["offeredRps"][i] for i in calm) / len(calm)
    snap = snapshot_for(report["input"].get("pricing"))
    rates = snap.rates
    nodes = {n["id"]: n for n in report["nodes"]}
    cfgs = {n["id"]: n.get("config") or {} for n in report["input"]["architecture"]["nodes"]}
    for line in report["cost"]["byNode"]:
        if line.get("unit") != "million" or not line.get("monthly"):
            continue
        node = nodes.get(line["id"])
        if node is None:
            continue
        arr = node["timeseries"]["arrivalRps"]
        rps = sum(arr[i] for i in calm) / len(calm) * base / offered
        cfg = cfgs[line["id"]]
        month = rps * SECONDS_PER_MONTH
        tech = cfg.get("technology")
        if node["type"] == "external_api":
            hand = month * float(cfg.get("costPerMillionRequests") or 0) / 1e6
            how = f"{rps:.1f} calls/s"
        elif tech == "api_gateway":
            hand = month * (rates.get("apiGatewayPerMillion") or 0) / 1e6
            how = f"{rps:.1f} req/s"
        elif tech == "sqs":
            hand = month * 3 * (rates.get("queuePerMillionRequests") or 0) / 1e6
            how = f"{rps:.1f} messages/s x 3 requests"
        elif cfg.get("platform") == "serverless":
            inflight = node["timeseries"]["inflightMean"]
            held, came = sum(inflight[i] for i in calm), sum(arr[i] for i in calm)
            duration = held / came if came else 0.0
            mem = float(cfg.get("memoryGib") or 0)
            hand = month * (
                (rates.get("serverlessPerMillionRequests") or 0) / 1e6
                + duration * mem * (rates.get("serverlessGbSecond") or 0)
            )
            how = f"{rps:.1f} invocations/s x {duration * 1000:.1f} ms x {mem * 1024:,.0f} MB"
        elif tech == "dynamodb":
            continue  # on-demand units depend on the read/write split: checked in test_cost_managed
        else:
            hand = month * (line.get("unitPrice") or 0) / 1e6
            how = f"{rps:.1f} req/s"
        ok = hand == 0 or abs(line["monthly"] - hand) <= PER_REQUEST_SLACK * hand
        audit.add(
            line["id"],
            "per-request",
            ok,
            f"${line['monthly']:,.2f} vs ${hand:,.2f} by hand ({how})",
        )


def check_cost(audit: Audit, report: Json) -> None:
    ref = REFERENCE.get(audit.template)
    if ref is None:
        return
    monthly = report["cost"]["monthly"]
    audit.add("*", "cost", monthly <= COST_SLACK * ref, f"${monthly:,.0f} vs reference ${ref:,.0f}")


def reference_cost(report: Json) -> tuple[float, list[str]]:
    """The monthly cost with each auto-sized part on the cheapest instance type its measured load
    allows: vCPU for its p95 busy cores at 75%, the memory it needs, a burstable t4g type only
    when its measured CPU passes the credit check. Everything else as auto-sized."""
    from archsim_engine.pricing import Busy

    snap = snapshot_for(report["input"].get("pricing"))
    window = _window(report)
    cfgs = {n["id"]: n.get("config") or {} for n in report["input"]["architecture"]["nodes"]}
    lines = {x["id"]: x for x in report["cost"]["byNode"]}
    total = report["cost"]["monthly"]
    notes = []
    for node in report["nodes"]:
        entry = report["sizing"]["nodes"].get(node["id"])
        line = lines.get(node["id"])
        if not entry or not line or not line.get("sku") or "on implied" in line.get("basis", ""):
            continue
        if (entry.get("specs", {}).get("cpuCores") or {}).get("source") != "auto":
            continue
        busy = (node.get("timeseries") or {}).get("cpuBusyCores")
        cur = sku_named(snap, line["sku"])
        if not busy or cur is None:
            continue
        cfg = cfgs[node["id"]]
        per = [
            busy[i] / _instances(node, cfg, i)
            for i in window
            if i < len(busy) and busy[i] is not None
        ]
        p95, mean = _p95(per), (sum(per) / len(per) if per else 0.0)
        need = entry.get("need") or {}
        mem = need.get("memoryGibMax", need.get("memoryGib")) or 0.0
        category = next(c for c, items in snap.skus.items() if cur in items)
        engine = None if category == "compute" else (cfg.get("technology") or None)
        best = snap.cover(
            category, max(p95 / CPU_HIGH, 1e-9), mem, engine=engine, busy=Busy(p95, mean)
        )  # type: ignore[arg-type]
        if best is None or (best.price("on_demand") or 0) >= (cur.price("on_demand") or 0):
            continue
        saving = (
            ((cur.price("on_demand") or 0) - (best.price("on_demand") or 0))
            * line["instances"]
            * 730
        )
        total -= saving
        notes.append(f"{node['id']}: {cur.sku} -> {best.sku} (-${saving:,.0f})")
    return total, notes


def audit_template(name: str, seconds: float | None) -> tuple[Audit, Json]:
    doc = json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))
    run_doc = compressed(doc, seconds) if seconds else {**copy.deepcopy(doc), "scenarios": []}
    report = run_simulation(run_doc)
    audit = Audit(name, report["cost"]["monthly"])
    cfgs = {n["id"]: n.get("config") or {} for n in report["input"]["architecture"]["nodes"]}
    check_cpu(audit, report, cfgs)
    check_bottleneck(audit, report)
    check_memory(audit, report, cfgs)
    check_rerun(audit, doc)
    check_monotonic(audit, doc)
    check_per_request(audit, report, doc)
    check_cost(audit, report)
    return audit, report


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("templates", nargs="*", help="template names (default: every one in examples/)")
    ap.add_argument("--seconds", type=float, default=30.0, help="compress each run to this long")
    ap.add_argument("--full", action="store_true", help="run each template at its own length")
    ap.add_argument("--json", help="write the checks here")
    ap.add_argument("--reference", action="store_true", help="print each template's reference cost")
    args = ap.parse_args()
    names = args.templates or sorted(p.stem for p in EXAMPLES.glob("*.json"))
    failed = 0
    out = []
    for name in names:
        audit, report = audit_template(name, None if args.full else args.seconds)
        print(f"== {name}: ${audit.monthly:,.0f} a month, {len(audit.failed)} failed")
        for c in audit.checks:
            print(f"   {'ok  ' if c.ok else 'FAIL'} {c.check:12s} {c.part:16s} {c.detail}")
        failed += len(audit.failed)
        if args.reference:
            ref, why = reference_cost(report)
            print(f"   reference ${ref:,.2f} ({'; '.join(why) or 'every type as auto-sized'})")
        out.append(
            {
                "template": name,
                "monthly": audit.monthly,
                "checks": [c.__dict__ for c in audit.checks],
            }
        )
    if args.json:
        Path(args.json).write_text(json.dumps(out, indent=1), encoding="utf-8")
    print(f"{len(names)} templates, {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
