"""Calibrating a design from telemetry, and checking the result by replaying what was observed.

``calibrate_prometheus`` sets each mapped input from Prometheus (histograms fitted to
distributions, gauges, hit ratios, link latency, the arrival rate as a ``trace`` pattern, CPU per
request as the CPU share, the working set as memory at idle, per req/s and per request held),
records each one's provenance (source, query, time range, fit error, when, and the value set),
then simulates the calibrated design and compares it with the observed latency and throughput
(``replay``) and with the CPU and memory each fitted part used. The result is a new document;
the input is not changed. The run's duration and warm-up are never changed: an observed traffic
curve is fitted into the run's duration.
"""

from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from archsim_engine.calibration.fit import Fit, fit_histogram
from archsim_engine.calibration.mapping import (
    BYTES,
    DERIVED,
    CpuPerRequestSource,
    GaugeSource,
    HistogramSource,
    Mapping,
    Range,
    RatioSource,
    Replay,
    ReplayTarget,
    WorkingSetSource,
)
from archsim_engine.calibration.prometheus import (
    PrometheusClient,
    histogram,
    reduce,
    summed,
)
from archsim_engine.calibration.resources import fit_cpu_share, fit_memory
from archsim_engine.engine import SimulationLimits
from archsim_engine.load import estimate_load, mean_ms
from archsim_engine.provenance import edge_path, node_path, stamp_values, workload_path
from archsim_engine.registry import ComponentRegistry, describe_fields
from archsim_engine.scenarios import load_document

Json = dict[str, Any]
MAX_TRACE_POINTS = 300


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
class Observed:
    """What a calibrated part used over the range, for the replay: CPU cores (all replicas)
    and the working set per replica (MiB), each as (timestamp, value)."""

    cpu: list[tuple[float, float]] = field(default_factory=list)
    memory: list[tuple[float, float]] = field(default_factory=list)


MEMORY_FIELDS = ("memoryBaselineMb", "memoryPerRpsMb", "memoryPerRequestMb")
"""The settings a working-set fit sets."""


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
    """ISO 8601; a time without an offset is UTC (never the local time of the machine)."""
    t = datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
    return (t if t.tzinfo is not None else t.replace(tzinfo=UTC)).timestamp()


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
    limits: SimulationLimits | None = None,
) -> Outcome:
    """Calibrate ``document`` from Prometheus through ``mapping``. ``limits`` bound the replay
    run (``SimulationLimitError`` when it would exceed them)."""
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
            if key in DERIVED:
                continue  # fitted after every setting and the workload (below)
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
                out.notes.extend(f"{node_id}.{key}: {n}" for n in fit.notes)
            elif isinstance(src, GaugeSource):
                query = src.gauge
                value = reduce(q(query), src.reduce)
                if src.unit is not None:
                    unit = known[key].get("unit")
                    if unit not in BYTES:
                        out.notes.append(
                            f"{node_id}: a unit ({src.unit}) is only for memory and size "
                            f"settings; '{key}' is not one: skipped"
                        )
                        continue
                    value = value * BYTES[src.unit] / BYTES[unit]
                value = (
                    round(value)
                    if src.round or known[key]["kind"] == "integer"
                    else round(value, 6)
                )
            elif isinstance(src, RatioSource):
                query = f"{src.ratio['hits']} / ({src.ratio['hits']} + {src.ratio['misses']})"
                hits = reduce(q(src.ratio["hits"]), "mean")
                misses = reduce(q(src.ratio["misses"]), "mean")
                if not hits + misses > 0:
                    out.notes.append(
                        f"{node_id}: no hits or misses in the range, so no hit ratio was "
                        f"measured; {key} unchanged"
                    )
                    continue
                value = round(hits / (hits + misses), 6)
            else:  # pragma: no cover - the mapping model admits nothing else
                continue
            node["config"][key] = value
            path = node_path(node_id, key)
            note = (
                " ".join([f"{fit.count:,.0f} observations per range step on average.", *fit.notes])[
                    :500
                ]
                if fit
                else None
            )
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
                previous = (wl.get("pattern") or {}).get("kind", "constant")
                pattern, note = _trace(pts, start, end, wl)
                wl["pattern"] = pattern
                prov[workload_path("pattern")] = _provenance(
                    client.source, rate_src.rate, rng, note=note
                )
                out.changes.append(
                    Change(
                        workload_path("pattern"),
                        "Workload: traffic pattern",
                        previous,
                        "trace",
                        None,
                        rate_src.rate,
                    )
                )
                out.notes.append(note)

    # CPU and memory last: a CPU share depends on the (calibrated) service time and read mix
    observed: dict[str, Observed] = {}

    def set_input(
        node: Json, key: str, label: str, value: Any, query: str, error: float | None, note: str
    ) -> None:
        model = registry.get(node["type"])
        before = node["config"].get(key, model.default_config().get(key))
        node["config"][key] = value
        path = node_path(node["id"], key)
        prov[path] = _provenance(client.source, query, rng, error, note[:500])
        name = f"{node.get('label') or node['id']}: {label}"
        out.changes.append(Change(path, name, before, value, error, query))

    for node_id, fields in mapping.nodes.items():
        node = nodes.get(node_id)
        if node is None:
            continue  # reported above
        model = registry.get(node["type"])
        known = {f["key"]: f for f in describe_fields(model.config_model)}
        for src in fields.values():
            if isinstance(src, CpuPerRequestSource):
                seen = _fit_cpu(src, q, doc, node, model, known, registry, out.notes, set_input)
                if seen:
                    observed.setdefault(node_id, Observed()).cpu = seen
            elif isinstance(src, WorkingSetSource):
                seen = _fit_working_set(src, q, node, model, known, out.notes, set_input)
                if seen:
                    observed.setdefault(node_id, Observed()).memory = seen

    stamp_values(load_document(doc).to_json_dict(), prov)  # also: the document is still valid
    doc["provenance"] = {**(doc.get("provenance") or {}), **prov}
    load_document(doc)
    record: Json = {
        "at": now_iso(),
        "source": client.source,
        "timeRange": f"{rng.start}/{rng.end}",
        "inputs": len(prov),
        "replay": [],
        "notes": out.notes[:50],
    }
    keep = _replayed(mapping, doc, start, end)
    if observed and keep is None:
        out.notes.append(
            "CPU and memory are replayed only when the workload is calibrated from the same "
            "range (workload.rps): the run's own traffic would not match what they used"
        )
        record["notes"] = out.notes[:50]
    if replay and (mapping.replay is not None or (observed and keep is not None)):
        checks, digest = replay_check(
            doc,
            mapping.replay,
            lambda query: q(query),
            registry,
            limits,
            observed if keep is not None else None,
            keep,
        )
        record["replay"] = checks
        record["replayDigest"] = digest
    doc["calibration"] = record
    load_document(doc)
    out.document = doc
    return out


def _span_text(seconds: float) -> str:
    if seconds >= 7200:
        return f"{seconds / 3600:.1f} h"
    if seconds >= 120:
        return f"{seconds / 60:.0f} min"
    return f"{seconds:.0f} s"


def _trace(pts: list[tuple[float, float]], start: float, end: float, wl: Json) -> tuple[Json, str]:
    """The observed rate as a ``trace`` pattern fitted into the run's existing duration: the
    curve keeps its shape and is compressed (or stretched) in time. The run's duration and
    warm-up are not changed, so calibrating never makes later runs longer."""
    span = end - start
    duration = float(wl.get("durationS") or 60)
    scale = duration / span
    seen = f"observed rate over {_span_text(span)}"
    if math.isclose(scale, 1.0, rel_tol=1e-6):
        note = f"{seen} replayed in real time in the {duration:g} s run"
    elif scale < 1:
        note = f"{seen} compressed into the {duration:g} s run ({1 / scale:,.1f}x faster)"
    else:
        note = f"{seen} stretched over the {duration:g} s run ({scale:,.1f}x slower)"
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


SetInput = Callable[[Json, str, str, Any, str, float | None, str], None]
"""Sets one input, with its provenance and change: (node, key, label, value, query, fit
error, note)."""


def _mean_service_ms(doc: Json, node: Json, registry: ComponentRegistry) -> float:
    """The mean time of a request's work that the CPU share applies to (ms): a service's service
    time; a database's read and write query times weighted by the reads and writes it gets (its
    log flush needs no CPU)."""
    model = registry.get(node["type"])
    cfg = model.parse_config(node["config"]).model_dump(mode="json", by_alias=True)
    if node["type"] != "database":
        return mean_ms(cfg.get("serviceTimeMs"))
    read, write = mean_ms(cfg.get("readServiceTimeMs")), mean_ms(cfg.get("writeServiceTimeMs"))
    load = estimate_load(doc, registry).get(node["id"])
    if load is not None and load.read + load.write > 0:
        share = load.read / (load.read + load.write)
    else:
        share = float(doc["workload"].get("readRatio", 0.9))
    return share * read + (1 - share) * write


def _fit_cpu(
    src: CpuPerRequestSource,
    q: Callable[[str], Any],
    doc: Json,
    node: Json,
    model: Any,
    known: dict[str, Json],
    registry: ComponentRegistry,
    notes: list[str],
    set_input: SetInput,
) -> list[tuple[float, float]]:
    """``cpuShare`` from CPU per request; returns the CPU cores observed (for the replay)."""
    node_id = node["id"]
    if "cpuShare" not in known:
        notes.append(
            f"{node_id}: CPU per request sets the CPU share of service time, which "
            f"{model.display_name} does not have: skipped"
        )
        return []
    cpu = dict(summed(q(src.cpu)))
    reqs = dict(summed(q(src.requests)))
    ts = sorted(cpu.keys() & reqs.keys())
    fit = fit_cpu_share([(reqs[t], cpu[t]) for t in ts], _mean_service_ms(doc, node, registry))
    if fit is None:
        notes.append(
            f"{node_id}: no requests (or no service time) in the range, so no CPU per request "
            "was measured; the CPU share is unchanged"
        )
        return []
    note = (
        f"{fit.cpu_ms:.4g} ms of CPU per request (CPU seconds ÷ requests over {fit.points} "
        f"points) ÷ {fit.service_ms:.4g} ms mean service time; fit error at and above the "
        "median rate."
    )
    query = f"({src.cpu}) / ({src.requests})"
    set_input(
        node,
        "cpuShare",
        known["cpuShare"]["label"],
        fit.share,
        query,
        fit.error,
        " ".join([note, *fit.notes]),
    )
    notes.extend(f"{node_id}.cpuShare: {n}" for n in fit.notes)
    return [(t, cpu[t]) for t in ts]


def _fit_working_set(
    src: WorkingSetSource,
    q: Callable[[str], Any],
    node: Json,
    model: Any,
    known: dict[str, Json],
    notes: list[str],
    set_input: SetInput,
) -> list[tuple[float, float]]:
    """Memory at idle, per req/s and per request held from the working set; returns the
    working set per replica observed (MiB, for the replay)."""
    node_id = node["id"]
    if not all(k in known for k in MEMORY_FIELDS):
        notes.append(
            f"{node_id}: {model.display_name} has no memory at idle, per req/s and per request "
            "held to fit: skipped"
        )
        return []
    cfg = model.parse_config(node["config"])
    if getattr(cfg, "platform", None) == "serverless":
        notes.append(
            f"{node_id}: a function's memory is per instance (one request each), not a working "
            "set that follows load: memory unchanged"
        )
        return []
    ws = dict(summed(q(src.working_set)))
    rate = dict(summed(q(src.requests))) if src.requests else None
    held = dict(summed(q(src.inflight))) if src.inflight else None
    reps = dict(summed(q(src.replicas))) if src.replicas else None
    fixed = float(getattr(cfg, "replicas", 1) or 1)
    if reps is None and getattr(cfg, "autoscaling", False):
        notes.append(
            f"{node_id}: autoscaling, and the mapping gives no replicas query: the working set "
            f"is taken as spread over {fixed:g} replicas"
        )
    keys = set(ws)
    for series in (rate, held, reps):
        if series is not None:
            keys &= series.keys()
    to_mib = BYTES[src.unit] / BYTES["MiB"]
    ts: list[float] = []
    per_ws: list[float] = []
    per_rate: list[float] = []
    per_held: list[float] = []
    for t in sorted(keys):
        n = reps[t] if reps is not None else fixed
        if n <= 0:
            continue
        ts.append(t)
        per_ws.append(ws[t] * to_mib / n)
        if rate is not None:
            per_rate.append(rate[t] / n)
        if held is not None:
            per_held.append(held[t] / n)
    fit = (
        fit_memory(
            per_ws,
            per_rate if rate is not None else None,
            per_held if held is not None else None,
        )
        if ts
        else None
    )
    if fit is None:
        notes.append(f"{node_id}: no working set in the range: memory unchanged")
        return []
    against = " and ".join(
        {"rate": "the request rate", "held": "requests held"}[a] for a in fit.against
    )
    note = (
        f"working set per replica = {fit.baseline_mb:.4g} MiB + {fit.per_rps_mb:.4g} MiB per "
        f"req/s + {fit.per_request_mb:.4g} MiB per request held, fitted against {against} over "
        f"{fit.points} points (non-negative least squares; a term not fitted against is zero, "
        "memory per request held its 0.01 MiB floor); fit error at and above the median."
    )
    values = {
        "memoryBaselineMb": min(1_048_576.0, fit.baseline_mb),
        "memoryPerRpsMb": min(65_536.0, fit.per_rps_mb),
        "memoryPerRequestMb": min(65_536.0, fit.per_request_mb),
    }
    for key, value in values.items():
        set_input(
            node,
            key,
            known[key]["label"],
            value,
            src.working_set,
            fit.error,
            " ".join([note, *fit.notes]),
        )
    notes.extend(f"{node_id} memory: {n}" for n in fit.notes)
    return list(zip(ts, per_ws, strict=True))


def _replayed(
    mapping: Mapping, doc: Json, start: float, end: float
) -> Callable[[float], bool] | None:
    """Which observed timestamps the replay's measurement window covers, or None when the
    workload was not calibrated from the range (the replay would not run what was observed). A
    traced rate is fitted into the run's duration, whose warm-up is not measured."""
    if "rps" not in mapping.workload:
        return None
    if not mapping.workload["rps"].trace or end <= start:
        return lambda t: True
    wl = doc["workload"]
    scale = float(wl.get("durationS") or 60) / (end - start)
    warmup = float(wl.get("warmupS") or 0)
    return lambda t: (t - start) * scale >= warmup


def replay_check(
    doc: Json,
    replay: Replay | None,
    query: Any,
    registry: ComponentRegistry,
    limits: SimulationLimits | None = None,
    observed: dict[str, Observed] | None = None,
    keep: Callable[[float], bool] | None = None,
) -> tuple[list[Json], str]:
    """Simulate the calibrated design and compare it with what was observed: latency and
    throughput (``replay``), and the CPU and memory of each part whose CPU or working set was
    fitted (``observed``: the mean CPU cores over all replicas, and the peak working set per
    replica against the simulated memory peak; ``keep`` picks the timestamps the run's
    measurement window covers)."""
    from archsim_engine import run_simulation

    report = run_simulation(doc, registry=registry, limits=limits)
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

    if replay is not None and replay.end_to_end is not None:
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
    for node_id, target in (replay.components if replay is not None else {}).items():
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
    window = report["window"]
    bucket = report["timeseries"]["bucketS"]
    measured = [
        window["warmupS"] <= t + bucket / 2 < window["durationS"] for t in report["timeseries"]["t"]
    ]
    for node_id, seen in (observed or {}).items():
        n = nodes.get(node_id)
        if n is None:
            continue
        cpu = [v for t, v in seen.cpu if keep is None or keep(t)]
        if cpu:
            series = (n.get("timeseries") or {}).get("cpuBusyCores") or []
            busy = [v for v, m in zip(series, measured, strict=False) if m and v is not None]
            simulated = sum(busy) / len(busy) if busy else None
            checks.append(
                _check("component", node_id, n["label"], "cpuCores", sum(cpu) / len(cpu), simulated)
            )
        mem = [v for t, v in seen.memory if keep is None or keep(t)]
        if mem:
            peak = n["summary"].get("memoryPeakGiB")
            simulated = peak * 1024.0 if isinstance(peak, int | float) else None
            checks.append(
                _check("component", node_id, n["label"], "memoryMiB", max(mem), simulated)
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


METRIC_WORDS = {"throughputRps": "throughput", "cpuCores": "CPU", "memoryMiB": "memory"}
"""How the replay summary names a check's metric (latency quantiles keep their names)."""


def replay_summary(record: Json | None) -> str | None:
    """One line for people: how well the model reproduces what was observed, with the end-to-end
    p95 and always the worst of all replay checks (a good p95 can hide a bad p99 or part)."""
    if not record or not record.get("replay"):
        return None
    checks = [c for c in record["replay"] if c.get("error") is not None]
    if not checks:
        return None
    rng = record.get("timeRange") or "the observed range"
    worst = max(checks, key=lambda c: abs(c["error"]))
    metric = METRIC_WORDS.get(worst["metric"], worst["metric"])
    worst_text = (
        f"the largest of {len(checks)} replay errors is {worst['error']:+.0%} "
        f"({worst['label']} {metric})"
    )
    e2e = next((c for c in checks if c["scope"] == "end_to_end" and c["metric"] == "p95"), None)
    if e2e is None:
        return f"Replay of {rng}: {worst_text}."
    err = e2e["error"]
    return (
        f"This model reproduces the observed p95 ({rng}) within {abs(err) * 100:.0f}% "
        f"({'over' if err > 0 else 'under'}); {worst_text}."
    )
