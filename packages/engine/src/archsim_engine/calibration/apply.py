"""Calibrating a design from telemetry, and checking the result by replaying what was observed.

``calibrate_prometheus`` sets each mapped input from Prometheus (histograms fitted to
distributions, gauges, hit ratios, link latency, the arrival rate as a ``trace`` pattern),
records each one's provenance (source, query, time range, fit error, when), then simulates the
calibrated design and compares it with the observed latency and throughput (``replay``). The
result is a new document; the input is not changed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from archsim_engine.calibration.fit import Fit, fit_histogram
from archsim_engine.calibration.mapping import (
    GaugeSource,
    HistogramSource,
    Mapping,
    Range,
    RatioSource,
    ReplayTarget,
)
from archsim_engine.calibration.prometheus import (
    PrometheusClient,
    histogram,
    reduce,
    summed,
)
from archsim_engine.provenance import edge_path, node_path, workload_path
from archsim_engine.registry import ComponentRegistry, describe_fields
from archsim_engine.scenarios import load_document

Json = dict[str, Any]
MAX_TRACE_POINTS = 300
REALTIME_LIMIT_S = 3600.0


@dataclass
class Change:
    path: str
    label: str
    before: Any
    after: Any
    fit_error: float | None = None
    query: str | None = None

    def to_dict(self) -> Json:
        return {
            "path": self.path,
            "label": self.label,
            "before": self.before,
            "after": self.after,
            "fitError": self.fit_error,
            "query": self.query,
        }


@dataclass
class Outcome:
    document: Json
    changes: list[Change] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> Json:
        return {
            "document": self.document,
            "changes": [c.to_dict() for c in self.changes],
            "notes": self.notes,
            "calibration": self.document.get("calibration"),
        }


def parse_time(text: str) -> float:
    return datetime.fromisoformat(text.strip().replace("Z", "+00:00")).timestamp()


def parse_range(text: str, step: float = 60) -> Range:
    """``START/END`` (ISO 8601)."""
    start, _, end = text.partition("/")
    if not end:
        raise ValueError("a range is START/END, e.g. 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z")
    return Range(start=start, end=end, step=step)


def now_iso() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _provenance(
    source: str,
    query: str | None,
    rng: Range,
    fit_error: float | None = None,
    note: str | None = None,
) -> Json:
    out: Json = {
        "kind": "measured",
        "source": source,
        "query": query,
        "timeRange": f"{rng.start}/{rng.end}",
        "fittedAt": now_iso(),
    }
    if fit_error is not None:
        out["fitError"] = fit_error
    if note:
        out["note"] = note
    return out


def calibrate_prometheus(
    document: Json,
    mapping: Mapping,
    client: PrometheusClient,
    registry: ComponentRegistry,
    time_range: Range | None = None,
    replay: bool = True,
) -> Outcome:
    given = time_range or mapping.range
    recorded: Range | None = getattr(client, "recorded_range", None)
    notes: list[str] = []
    if recorded is not None:  # a recording answers for the window it was taken over
        if given is not None and (given.start, given.end) != (recorded.start, recorded.end):
            notes.append(
                f"the recording covers {recorded.start}/{recorded.end}; "
                f"the range {given.start}/{given.end} was not used"
            )
        rng = recorded
    elif given is not None:
        rng = given
    else:
        raise ValueError("no time range: pass one or set 'range' in the mapping")
    start, end = parse_time(rng.start), parse_time(rng.end)
    if end <= start:
        raise ValueError("the range ends before it starts")
    doc = load_document(document).to_json_dict()  # validated, migrated copy
    out = Outcome(doc)
    out.notes.extend(notes)
    prov: Json = {}
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}

    def q(query: str) -> Any:
        return client.query_range(query, start, end, rng.step)

    for node_id, fields in mapping.nodes.items():
        node = nodes.get(node_id)
        if node is None:
            out.notes.append(f"mapping names part '{node_id}', which is not in the design: skipped")
            continue
        model = registry.get(node["type"])
        known = {f["key"]: f for f in describe_fields(model.config_model)}
        for key, src in fields.items():
            if key not in known:
                out.notes.append(
                    f"{node_id}: '{key}' is not a setting of {model.display_name}: skipped"
                )
                continue
            before = node["config"].get(key, model.default_config().get(key))
            fit: Fit | None = None
            value: Any
            query: str
            if isinstance(src, HistogramSource):
                query = src.histogram
                fit = fit_histogram(histogram(q(query), src.unit), src.fit)
                value = fit.distribution
            elif isinstance(src, GaugeSource):
                query = src.gauge
                value = reduce(q(query), src.reduce)
                value = (
                    round(value)
                    if src.round or known[key]["kind"] == "integer"
                    else round(value, 6)
                )
            elif isinstance(src, RatioSource):
                query = f"{src.ratio['hits']} / ({src.ratio['hits']} + {src.ratio['misses']})"
                hits = reduce(q(src.ratio["hits"]), "mean")
                misses = reduce(q(src.ratio["misses"]), "mean")
                value = round(hits / (hits + misses), 6) if hits + misses > 0 else before
            else:  # pragma: no cover - the mapping model admits nothing else
                continue
            node["config"][key] = value
            path = node_path(node_id, key)
            note = f"{fit.count:,.0f} observations per range step on average" if fit else None
            prov[path] = _provenance(client.source, query, rng, fit.error if fit else None, note)
            out.changes.append(
                Change(
                    path,
                    f"{node.get('label') or node_id}: {known[key]['label']}",
                    before,
                    value,
                    fit.error if fit else None,
                    query,
                )
            )

    edges = {e["id"]: e for e in doc["architecture"]["edges"]}
    for edge_id, link_fields in mapping.edges.items():
        edge = edges.get(edge_id)
        if edge is None:
            out.notes.append(f"mapping names link '{edge_id}', which is not in the design: skipped")
            continue
        for key, link_src in link_fields.items():
            client_mean = histogram(q(link_src.client), link_src.unit).mean_upper()
            server_mean = histogram(q(link_src.server), link_src.unit).mean_upper()
            one_way = max(0.0, client_mean - server_mean) / 2
            before = edge["config"].get(key)
            value = {"kind": "constant", "value": round(one_way, 4)}
            edge["config"][key] = value
            path = edge_path(edge_id, key)
            prov[path] = _provenance(
                client.source,
                f"client: {link_src.client}; server: {link_src.server}",
                rng,
                note=f"(client mean {client_mean:.3f} ms - server mean {server_mean:.3f} ms) / 2, one way",
            )
            out.changes.append(
                Change(
                    path,
                    f"{edge['source']} → {edge['target']}: network latency",
                    before,
                    value,
                    None,
                    link_src.client,
                )
            )

    if "rps" in mapping.workload:
        rate_src = mapping.workload["rps"]
        pts = summed(q(rate_src.rate))
        if not pts:
            out.notes.append("the arrival-rate query returned no data: workload unchanged")
        else:
            wl = doc["workload"]
            before = wl.get("rps")
            mean = sum(v for _, v in pts) / len(pts)
            wl["rps"] = round(min(mean, 100_000.0), 4)
            prov[workload_path("rps")] = _provenance(
                client.source, rate_src.rate, rng, note="mean of the observed rate"
            )
            out.changes.append(
                Change(
                    workload_path("rps"),
                    "Workload: baseline load",
                    before,
                    wl["rps"],
                    None,
                    rate_src.rate,
                )
            )
            if rate_src.trace:
                pattern, note = _trace(pts, start, end, wl)
                wl["pattern"] = pattern
                prov[workload_path("pattern")] = _provenance(
                    client.source, rate_src.rate, rng, note=note
                )
                out.changes.append(
                    Change(
                        workload_path("pattern"),
                        "Workload: traffic pattern",
                        "previous pattern",
                        "trace",
                        None,
                        rate_src.rate,
                    )
                )
                out.notes.append(note)

    doc["provenance"] = {**(doc.get("provenance") or {}), **prov}
    load_document(doc)  # the calibrated document must still be valid
    record: Json = {
        "at": now_iso(),
        "source": client.source,
        "timeRange": f"{rng.start}/{rng.end}",
        "inputs": len(prov),
        "replay": [],
        "notes": out.notes[:50],
    }
    if replay and mapping.replay is not None:
        checks, digest = replay_check(doc, mapping.replay, lambda query: q(query), registry)
        record["replay"] = checks
        record["replayDigest"] = digest
    doc["calibration"] = record
    load_document(doc)
    out.document = doc
    return out


def _trace(pts: list[tuple[float, float]], start: float, end: float, wl: Json) -> tuple[Json, str]:
    """The observed rate as a ``trace`` pattern: in real time when the range fits in an hour
    (the run then lasts as long as the range), else compressed into the run's duration."""
    span = end - start
    if span <= REALTIME_LIMIT_S:
        duration = max(span, 2.0)
        wl["durationS"] = round(duration, 3)
        wl["warmupS"] = min(float(wl.get("warmupS", 0)), round(duration / 10, 3))
        scale = 1.0
        note = f"observed rate replayed in real time ({span:.0f} s)"
    else:
        duration = float(wl.get("durationS") or 60)
        scale = duration / span
        note = f"observed rate over {span / 3600:.1f} h compressed into the {duration:.0f} s run ({1 / scale:,.0f}x faster)"
    raw = [((t - start) * scale, min(max(v, 0.0), 100_000.0)) for t, v in pts if start <= t <= end]
    if len(raw) > MAX_TRACE_POINTS:
        size = math.ceil(len(raw) / MAX_TRACE_POINTS)
        raw = [
            (raw[i][0], sum(v for _, v in raw[i : i + size]) / len(raw[i : i + size]))
            for i in range(0, len(raw), size)
        ]
    points: list[list[float]] = []
    for t, v in raw:
        if points and t <= points[-1][0]:
            continue
        points.append([round(t, 4), round(v, 4)])
    if not points or points[0][0] != 0.0:
        points.insert(0, [0.0, points[0][1] if points else 0.0])
    if len(points) < 2:
        points.append([round(duration, 4), points[0][1]])
    return {"kind": "trace", "points": points, "source": note}, note


def replay_check(
    doc: Json, replay: Any, query: Any, registry: ComponentRegistry
) -> tuple[list[Json], str]:
    """Simulate the calibrated design and compare it with what was observed."""
    from archsim_engine import run_simulation

    report = run_simulation(doc, registry=registry)
    checks: list[Json] = []

    def compare(
        target: ReplayTarget,
        scope: str,
        ident: str | None,
        label: str,
        sim: Json,
        sim_rps: float | None,
    ) -> None:
        h = histogram(query(target.histogram), target.unit)
        for metric, qv in (("p50", 0.5), ("p95", 0.95), ("p99", 0.99)):
            observed = h.quantile(qv)
            simulated = sim.get(metric)
            if observed is None:
                continue
            checks.append(_check(scope, ident, label, metric, observed, simulated))
        if target.throughput:
            observed_rps = reduce(query(target.throughput), "mean")
            checks.append(_check(scope, ident, label, "throughputRps", observed_rps, sim_rps))

    if replay.end_to_end is not None:
        s = report["summary"]
        compare(
            replay.end_to_end,
            "end_to_end",
            None,
            "End to end",
            s["latencyMs"],
            s.get("throughputRps"),
        )
    nodes = {n["id"]: n for n in report["nodes"]}
    for node_id, target in replay.components.items():
        n = nodes.get(node_id)
        if n is None:
            continue
        compare(
            target,
            "component",
            node_id,
            n["label"],
            n["summary"].get("latencyMs") or {},
            n["summary"].get("throughputRps"),
        )
    return checks, report["provenance"]["resultDigest"]


def _check(
    scope: str, ident: str | None, label: str, metric: str, observed: float, simulated: float | None
) -> Json:
    err = (simulated - observed) / observed if simulated is not None and observed else None
    return {
        "scope": scope,
        "id": ident,
        "label": label,
        "metric": metric,
        "observed": round(observed, 4),
        "simulated": round(simulated, 4) if simulated is not None else None,
        "error": round(err, 4) if err is not None else None,
    }


def replay_summary(record: Json | None) -> str | None:
    """One line for people: how well the model reproduces what was observed."""
    if not record or not record.get("replay"):
        return None
    e2e = [
        c
        for c in record["replay"]
        if c["scope"] == "end_to_end" and c["metric"] == "p95" and c["error"] is not None
    ]
    if not e2e:
        return None
    err = e2e[0]["error"]
    rng = record.get("timeRange") or "the observed range"
    return f"This model reproduces the observed p95 ({rng}) within {abs(err) * 100:.0f}% ({'over' if err > 0 else 'under'})."
