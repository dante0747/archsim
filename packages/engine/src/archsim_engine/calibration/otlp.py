"""Calibrating from OpenTelemetry traces (an OTLP JSON export, as the collector's file exporter
or ``otel-cli`` write it).

From the spans this derives, per service, the handler time without downstream calls (a SERVER
span's duration minus the time covered by its outgoing CLIENT and PRODUCER spans), per database
the read and write times (CLIENT spans with ``db.system``, split by ``db.operation`` or the
statement's first word), and the observed call graph (which service calls which). Services and
databases map to parts by an explicit mapping or by matching a part's id or label.
"""

from __future__ import annotations

import json
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from archsim_engine.calibration.apply import Change, Outcome, now_iso
from archsim_engine.calibration.fit import fit_samples, sample_quantile
from archsim_engine.engine import SimulationLimits
from archsim_engine.provenance import node_path, stamp_values
from archsim_engine.registry import ComponentRegistry
from archsim_engine.scenarios import load_document

Json = dict[str, Any]
KINDS = {1: "INTERNAL", 2: "SERVER", 3: "CLIENT", 4: "PRODUCER", 5: "CONSUMER"}
WRITES = {"INSERT", "UPDATE", "DELETE", "UPSERT", "MERGE", "SET", "PUT", "HSET", "DEL"}
MAX_PARENT_DEPTH = 1000
"""Most parent links followed from one span (real traces are far shallower)."""
REPLAY_MAX_DURATION_S = 300.0
"""Longest replay run: the replay compares latency at the traced rate, which needs minutes at
most, and the API runs it inside a request."""


@dataclass
class Span:
    trace_id: str
    span_id: str
    parent_id: str | None
    service: str
    name: str
    kind: str
    start_ns: int
    end_ns: int
    attrs: dict[str, Any]

    @property
    def ms(self) -> float:
        return max(0, self.end_ns - self.start_ns) / 1e6


@dataclass
class TraceSummary:
    services: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    db_reads: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    db_writes: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    calls: dict[tuple[str, str], int] = field(default_factory=lambda: defaultdict(int))
    roots: list[float] = field(default_factory=list)
    server_ms: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    start_ns: int = 0
    end_ns: int = 0
    spans: int = 0
    parent_loops: int = 0  # spans whose chain of parent links loops back on itself


def _attr_value(v: dict[str, Any]) -> Any:
    for k in ("stringValue", "intValue", "doubleValue", "boolValue"):
        if k in v:
            return v[k]
    return None


def _attrs(items: list[dict[str, Any]] | None) -> dict[str, Any]:
    return {a["key"]: _attr_value(a.get("value") or {}) for a in items or []}


def parse_otlp(data: Json) -> list[Span]:
    spans: list[Span] = []
    for rs in data.get("resourceSpans") or []:
        service = str(
            _attrs((rs.get("resource") or {}).get("attributes")).get("service.name", "unknown")
        )
        for ss in rs.get("scopeSpans") or rs.get("instrumentationLibrarySpans") or []:
            for s in ss.get("spans") or []:
                kind = s.get("kind", 1)
                kind_name = (
                    KINDS.get(kind, str(kind).removeprefix("SPAN_KIND_"))
                    if isinstance(kind, int)
                    else str(kind).removeprefix("SPAN_KIND_")
                )
                spans.append(
                    Span(
                        trace_id=s["traceId"],
                        span_id=s["spanId"],
                        parent_id=s.get("parentSpanId") or None,
                        service=service,
                        name=s.get("name", ""),
                        kind=kind_name,
                        start_ns=int(s["startTimeUnixNano"]),
                        end_ns=int(s["endTimeUnixNano"]),
                        attrs=_attrs(s.get("attributes")),
                    )
                )
    return spans


def _covered(intervals: list[tuple[int, int]], lo: int, hi: int) -> int:
    """Length of the union of ``intervals`` clipped to [lo, hi]."""
    clipped = sorted((max(a, lo), min(b, hi)) for a, b in intervals if b > lo and a < hi)
    total, cur_a, cur_b = 0, None, None
    for a, b in clipped:
        if cur_b is None or a > cur_b:
            if cur_b is not None and cur_a is not None:
                total += cur_b - cur_a
            cur_a, cur_b = a, b
        else:
            cur_b = max(cur_b, b)
    if cur_b is not None and cur_a is not None:
        total += cur_b - cur_a
    return total


def summarize(spans: list[Span]) -> TraceSummary:
    out = TraceSummary()
    if not spans:
        return out
    out.spans = len(spans)
    out.start_ns = min(s.start_ns for s in spans)
    out.end_ns = max(s.end_ns for s in spans)
    by_id = {(s.trace_id, s.span_id): s for s in spans}
    children: dict[tuple[str, str], list[Span]] = defaultdict(list)
    for s in spans:
        if s.parent_id:
            children[(s.trace_id, s.parent_id)].append(s)

    def server_of(s: Span) -> Span | None:
        """The nearest SERVER ancestor. Parent links come from the file, so they may loop (a
        span that is its own parent, or a cycle): each span is visited once, and the walk stops
        after ``MAX_PARENT_DEPTH`` links."""
        seen = {s.span_id}
        cur: Span | None = s
        for _ in range(MAX_PARENT_DEPTH):
            if cur is None or not cur.parent_id:
                return None
            if cur.parent_id in seen:
                out.parent_loops += 1
                return None
            seen.add(cur.parent_id)
            cur = by_id.get((cur.trace_id, cur.parent_id))
            if cur is not None and cur.kind == "SERVER":
                return cur
        return None

    outgoing: dict[tuple[str, str], list[tuple[int, int]]] = defaultdict(list)
    for s in spans:
        if s.kind in ("CLIENT", "PRODUCER"):
            srv = server_of(s)
            if srv is not None and srv.service == s.service:
                outgoing[(srv.trace_id, srv.span_id)].append((s.start_ns, s.end_ns))
        if s.kind == "CLIENT" and s.attrs.get("db.system"):
            db = str(s.attrs.get("db.name") or s.attrs.get("db.system"))
            op = str(
                s.attrs.get("db.operation")
                or str(s.attrs.get("db.statement") or "").split(" ", 1)[0]
            ).upper()
            (out.db_writes if op in WRITES else out.db_reads)[db].append(s.ms)
            out.calls[(s.service, db)] += 1
        if s.kind == "CLIENT":
            for child in children.get((s.trace_id, s.span_id), []):
                if child.kind == "SERVER" and child.service != s.service:
                    out.calls[(s.service, child.service)] += 1
        if not s.parent_id:
            out.roots.append(s.ms)
    for s in spans:
        if s.kind == "SERVER":
            out.server_ms[s.service].append(s.ms)
            busy = _covered(outgoing.get((s.trace_id, s.span_id), []), s.start_ns, s.end_ns)
            out.services[s.service].append(max(0, (s.end_ns - s.start_ns) - busy) / 1e6)
    return out


def _match(name: str, doc: Json, explicit: dict[str, str], types: tuple[str, ...]) -> str | None:
    if name in explicit:
        return explicit[name]
    low = name.lower()
    for n in doc["architecture"]["nodes"]:
        if n["type"] in types and (n["id"].lower() == low or (n.get("label") or "").lower() == low):
            return str(n["id"])
    return None


def calibrate_otlp(
    document: Json,
    traces: Json | str | Path,
    registry: ComponentRegistry,
    mapping: dict[str, dict[str, str]] | None = None,
    min_samples: int = 20,
    limits: SimulationLimits | None = None,
) -> Outcome:
    """Set service and database times of ``document`` from an OTLP JSON trace export
    (``traces``: the parsed export, or a path to read it from). ``limits`` bound the replay run
    (``SimulationLimitError`` when it would exceed them)."""
    data = (
        json.loads(Path(traces).read_text(encoding="utf-8"))
        if isinstance(traces, str | Path)
        else traces
    )
    label = (
        f"OpenTelemetry traces {Path(traces).name}"
        if isinstance(traces, str | Path)
        else "OpenTelemetry traces"
    )
    try:
        spans = parse_otlp(data)
    except (AttributeError, TypeError) as exc:
        raise ValueError(f"not an OTLP JSON export ({type(exc).__name__})") from None
    summary = summarize(spans)
    doc = load_document(document).to_json_dict()
    out = Outcome(doc)
    if not summary.spans:
        out.notes.append("the file has no spans")
        return out
    services_map, db_map = _check_mapping(mapping)
    if summary.parent_loops:
        out.notes.append(
            f"{summary.parent_loops} spans have parent links that loop back on themselves: "
            "their time is not subtracted from any service"
        )
    rng = f"{_iso(summary.start_ns)}/{_iso(summary.end_ns)}"
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    prov: Json = {}

    def apply(node_id: str, key: str, samples: list[float], what: str) -> None:
        node = nodes[node_id]
        if len(samples) < min_samples:
            out.notes.append(
                f"{what}: only {len(samples)} spans (need {min_samples}); {node_id}.{key} unchanged"
            )
            return
        fit = fit_samples(samples)
        before = node["config"].get(key)
        node["config"][key] = fit.distribution
        path = node_path(node_id, key)
        prov[path] = {
            "kind": "measured",
            "source": label,
            "query": what,
            "timeRange": rng,
            "fitError": fit.error,
            "fittedAt": now_iso(),
            "note": f"{len(samples):,} spans",
        }
        if fit.error is None:
            del prov[path]["fitError"]
        out.changes.append(
            Change(
                path,
                f"{node.get('label') or node_id}: {key}",
                before,
                fit.distribution,
                fit.error,
                what,
            )
        )

    for service, samples in sorted(summary.services.items()):
        node_id = _match(service, doc, services_map, ("service",))
        if node_id is None:
            out.notes.append(
                f"service '{service}' ({len(samples)} spans) matches no service part: map it in the mapping's 'services'"
            )
            continue
        apply(
            node_id,
            "serviceTimeMs",
            samples,
            f"SERVER spans of {service}, minus their outgoing calls",
        )
    for db in sorted(set(summary.db_reads) | set(summary.db_writes)):
        node_id = _match(db, doc, db_map, ("database",))
        if node_id is None:
            out.notes.append(
                f"database '{db}' matches no database part: map it in the mapping's 'databases'"
            )
            continue
        if summary.db_reads.get(db):
            apply(
                node_id, "readServiceTimeMs", summary.db_reads[db], f"CLIENT spans to {db} (reads)"
            )
        if summary.db_writes.get(db):
            apply(
                node_id,
                "writeServiceTimeMs",
                summary.db_writes[db],
                f"CLIENT spans to {db} (writes)",
            )

    links = {(e["source"], e["target"]) for e in doc["architecture"]["edges"]}
    for (src, dst), n in sorted(summary.calls.items()):
        a = _match(src, doc, services_map, ("service",))
        b = _match(dst, doc, {**services_map, **db_map}, ("service", "database"))
        if a and b and (a, b) not in links:
            out.notes.append(
                f"the traces show {src} calling {dst} ({n} calls) but the design has no link {a} → {b}"
            )
    stamp_values(load_document(doc).to_json_dict(), prov)
    doc["provenance"] = {**(doc.get("provenance") or {}), **prov}
    replay, digest = _replay(doc, summary, services_map, registry, limits)
    doc["calibration"] = {
        "at": now_iso(),
        "source": label,
        "timeRange": rng,
        "inputs": len(prov),
        "replay": replay,
        "replayDigest": digest,
        "notes": out.notes[:50],
    }
    load_document(doc)
    out.document = doc
    return out


def load_otlp_mapping(source: str | dict[str, Any] | None) -> dict[str, dict[str, str]] | None:
    """An OTLP mapping (``services``, ``databases``: name -> part id) from YAML/JSON text or a
    dict. A string is always the mapping's text, never a path."""
    if source is None:
        return None
    data: Any = source
    if isinstance(source, str):
        import yaml

        from archsim_engine.safe_yaml import describe_yaml_error, load_yaml

        try:
            data = load_yaml(source)
        except yaml.YAMLError as exc:
            raise ValueError(
                f"the OTLP mapping is not valid YAML: {describe_yaml_error(exc)}"
            ) from None
    if data is None:
        return None
    services, databases = _check_mapping(data)
    return {"services": services, "databases": databases}


def _check_mapping(mapping: Any) -> tuple[dict[str, str], dict[str, str]]:
    """``services`` and ``databases`` of an OTLP mapping, each name -> part id."""
    if mapping is None:
        return {}, {}
    if not isinstance(mapping, dict):
        raise ValueError("the OTLP mapping must be an object: {services: {...}, databases: {...}}")
    out: list[dict[str, str]] = []
    for key in ("services", "databases"):
        section = mapping.get(key) or {}
        if not isinstance(section, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in section.items()
        ):
            raise ValueError(f"the OTLP mapping's '{key}' must map names to part ids")
        out.append(section)
    return out[0], out[1]


def _replay(
    doc: Json,
    summary: TraceSummary,
    services_map: dict[str, str],
    registry: ComponentRegistry,
    limits: SimulationLimits | None = None,
) -> tuple[list[Json], str | None]:
    """Simulate the calibrated design at the traced request rate and compare each mapped
    service's latency (its SERVER spans, downstream calls included) with the simulation. The
    run lasts the document's duration, at least 60 s and at most ``REPLAY_MAX_DURATION_S``."""
    import copy

    from archsim_engine import run_simulation

    span_s = (summary.end_ns - summary.start_ns) / 1e9
    if not summary.server_ms or span_s <= 0:
        return [], None
    rate = len(summary.roots) / span_s
    replayed = copy.deepcopy(doc)
    wl = replayed["workload"]
    wl["rps"] = round(min(max(rate, 0.1), 100_000), 4)
    wl["pattern"] = {"kind": "constant"}
    duration = max(60.0, float(wl.get("durationS", 60)))
    if duration > REPLAY_MAX_DURATION_S:
        duration = REPLAY_MAX_DURATION_S
        wl["warmupS"] = min(float(wl.get("warmupS", 0)), duration / 10)
    wl["durationS"] = duration
    report = run_simulation(replayed, registry=registry, limits=limits)
    nodes = {n["id"]: n for n in report["nodes"]}
    checks: list[Json] = []
    for service, samples in sorted(summary.server_ms.items()):
        node_id = _match(service, doc, services_map, ("service",))
        if node_id is None or node_id not in nodes or len(samples) < 20:
            continue
        lat = nodes[node_id]["summary"].get("latencyMs") or {}
        ordered = sorted(samples)
        for metric, q in (("p50", 0.5), ("p95", 0.95), ("p99", 0.99)):
            observed = sample_quantile(ordered, q)
            simulated = lat.get(metric)
            err = (simulated - observed) / observed if simulated is not None and observed else None
            checks.append(
                {
                    "scope": "component",
                    "id": node_id,
                    "label": f"{nodes[node_id]['label']} (traced as {service}, at {rate:.1f} req/s)",
                    "metric": metric,
                    "observed": round(observed, 4),
                    "simulated": round(simulated, 4) if simulated is not None else None,
                    "error": round(err, 4) if err is not None else None,
                }
            )
    return checks, report["provenance"]["resultDigest"]


def _iso(ns: int) -> str:
    from datetime import UTC, datetime

    return datetime.fromtimestamp(ns / 1e9, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
