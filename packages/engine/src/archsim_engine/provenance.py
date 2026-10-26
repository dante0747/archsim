"""Where every numeric input of a design came from.

An input is **measured** when the document's ``provenance`` says so (calibration writes it, with
the source, the query, the time range, how well the fit matched and the value it set) **and** the
input still has that value; **illustrative** when it is ArchSim's default or the number the
technology's preset fills in; and **assumed** otherwise (a person typed it). A measured input whose
value has changed since (a scenario override, a sweep, an edit through the CLI, MCP or API) is
classified as if it had no record, with a note saying it was measured before. Reports list every
performance input with its provenance; the inspector badges measured ones.

Paths: ``nodes/<id>/<config key>``, ``edges/<id>/<config key>``, ``workload/<field>``.
"""

from __future__ import annotations

import math
from typing import Any

from pydantic import BaseModel

from archsim_engine.distributions import Constant, Empirical, Exponential, LogNormal, Uniform
from archsim_engine.registry import ComponentRegistry, describe_fields
from archsim_engine.schema import (
    ArchitectureDocument,
    EdgeConfig,
    InputProvenance,
    NodeSpec,
    Workload,
)

Json = dict[str, Any]
WORKLOAD_INPUTS = ("rps", "readRatio", "keyCount", "keySkew", "pattern")


def node_path(node_id: str, key: str) -> str:
    return f"nodes/{node_id}/{key}"


def edge_path(edge_id: str, key: str) -> str:
    return f"edges/{edge_id}/{key}"


def workload_path(key: str) -> str:
    return f"workload/{key}"


def _same(a: Any, b: Any) -> bool:
    if isinstance(a, int | float) and isinstance(b, int | float):
        return float(a) == float(b)
    return bool(a == b)


def same_value(a: Any, b: Any) -> bool:
    """JSON values equal up to number representation (``6`` and ``6.0``) and float noise."""
    if isinstance(a, bool) or isinstance(b, bool):
        return a is b
    if isinstance(a, int | float) and isinstance(b, int | float):
        return math.isclose(float(a), float(b), rel_tol=1e-9, abs_tol=1e-12)
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(same_value(a[k], b[k]) for k in a)
    if isinstance(a, list | tuple) and isinstance(b, list | tuple):
        return len(a) == len(b) and all(same_value(x, y) for x, y in zip(a, b, strict=True))
    return bool(a == b)


def value_at(doc: Json, path: str) -> Any:
    """The value at a provenance path in a document's JSON (``None`` when it is not set)."""
    scope, _, rest = path.partition("/")
    if scope == "workload":
        return (doc.get("workload") or {}).get(rest)
    if scope not in ("nodes", "edges"):
        return None
    ident, _, key = rest.rpartition("/")
    for item in (doc.get("architecture") or {}).get(scope) or []:
        if item.get("id") == ident:
            return (item.get("config") or {}).get(key)
    return None


def stamp_values(doc: Json, records: dict[str, Json]) -> None:
    """Record in each provenance record the value its path holds in ``doc`` (a document's
    canonical JSON, ``load_document(...).to_json_dict()``), so the input counts as measured only
    while it keeps that value."""
    for path, record in records.items():
        record["value"] = value_at(doc, path)


def _recorded(entry: InputProvenance | None, current: Any) -> tuple[Json | None, str | None]:
    """The recorded provenance to report, or (None, a note) when a measured input has changed
    since it was measured (it is then classified as if it had no record)."""
    if entry is None:
        return None, None
    if (
        entry.kind == "measured"
        and entry.value is not None
        and not same_value(entry.value, current)
    ):
        where = ", ".join(x for x in (entry.source, entry.time_range) if x) or "telemetry"
        return None, f"changed since it was measured ({where})"
    return entry.model_dump(mode="json", by_alias=True, exclude_none=True, exclude={"value"}), None


def _with_note(result: Json, note: str | None) -> Json:
    return {**result, "note": note} if note else result


def describe_value(v: Any, unit: str | None = None) -> str:
    if isinstance(v, Constant | Exponential | Uniform | LogNormal | Empirical):
        return v.describe()
    if isinstance(v, BaseModel):
        kind = getattr(v, "kind", None)
        return f"pattern {kind}" if kind else str(v)
    if isinstance(v, dict) and "kind" in v:
        return f"pattern {v['kind']}" if "points" in v or v["kind"] in ("ramp", "burst") else str(v)
    if isinstance(v, bool):
        return "on" if v else "off"
    text = f"{v:g}" if isinstance(v, float) else str(v)
    return f"{text} {unit}" if unit and isinstance(v, int | float) else text


def classify_node_input(
    doc: ArchitectureDocument, registry: ComponentRegistry, node: NodeSpec, key: str
) -> Json:
    """Provenance of one config value of a node (see the module docstring)."""
    explicit, changed = _recorded(doc.provenance.get(node_path(node.id, key)), node.config.get(key))
    if explicit is not None:
        return explicit
    model = registry.get(node.type)
    default = model.default_config()
    if key not in node.config or _same(node.config[key], default.get(key)):
        return _with_note({"kind": "illustrative", "source": "ArchSim default"}, changed)
    tech = node.config.get("technology", default.get("technology"))
    for preset in model.presets:
        if (
            preset.technology == tech
            and key in preset.config
            and _same(preset.config[key], node.config[key])
        ):
            return _with_note({"kind": "illustrative", "source": f"preset {preset.name}"}, changed)
    return _with_note({"kind": "assumed"}, changed)


def classify_edge_input(
    doc: ArchitectureDocument, registry: ComponentRegistry, edge_id: str, key: str, raw: Json
) -> Json:
    value = raw.get(key)
    explicit, changed = _recorded(doc.provenance.get(edge_path(edge_id, key)), value)
    if explicit is not None:
        return explicit
    default = EdgeConfig().model_dump(mode="json", by_alias=True)
    if value is None or _same(value, default.get(key)):
        return _with_note({"kind": "illustrative", "source": "ArchSim default"}, changed)
    edge = next((e for e in doc.architecture.edges if e.id == edge_id), None)
    src = doc.node(edge.source) if edge else None
    if src is not None and src.type in registry:
        suggested = registry.get(src.type).link_defaults.get(key)
        if suggested is not None and _same(suggested, value):
            return _with_note(
                {
                    "kind": "illustrative",
                    "source": f"{registry.get(src.type).display_name} link default",
                },
                changed,
            )
    return _with_note({"kind": "assumed"}, changed)


def classify_workload_input(doc: ArchitectureDocument, key: str, raw: Json) -> Json:
    explicit, changed = _recorded(doc.provenance.get(workload_path(key)), raw.get(key))
    if explicit is not None:
        return explicit
    default = Workload().model_dump(mode="json", by_alias=True)
    if key not in raw or _same(raw[key], default.get(key)):
        return _with_note({"kind": "illustrative", "source": "ArchSim default"}, changed)
    return _with_note({"kind": "assumed"}, changed)


def input_provenance(doc: ArchitectureDocument, registry: ComponentRegistry) -> list[Json]:
    """Every performance input (fields marked as assumptions, link latencies, the workload) and
    every input with recorded provenance, each with its value and provenance."""
    out: list[Json] = []
    raw = doc.model_dump(mode="json", by_alias=True)
    for n in doc.architecture.nodes:
        if n.type not in registry:
            continue
        model = registry.get(n.type)
        try:
            cfg = model.parse_config(n.config)
        except Exception:  # invalid configs are reported by validation
            continue
        values = cfg.model_dump(by_alias=True)
        for fd in describe_fields(model.config_model):
            path = node_path(n.id, fd["key"])
            if not fd["assumption"] and path not in doc.provenance:
                continue
            vis = fd.get("visibleWhen")
            if vis and any(
                n.config.get(k, model.default_config().get(k)) != v for k, v in vis.items()
            ):
                continue
            value = getattr(cfg, _attr(cfg, fd["key"]), values.get(fd["key"]))
            out.append(
                {
                    "scope": "node",
                    "id": n.id,
                    "label": n.label or n.id,
                    "field": fd["key"],
                    "fieldLabel": fd["label"],
                    "value": describe_value(value, fd.get("unit")),
                    "provenance": classify_node_input(doc, registry, n, fd["key"]),
                }
            )
    for e, e_raw in zip(doc.architecture.edges, raw["architecture"]["edges"], strict=True):
        out.append(
            {
                "scope": "edge",
                "id": e.id,
                "label": f"{e.source} → {e.target}",
                "field": "latencyMs",
                "fieldLabel": "Network latency (one way)",
                "value": e.config.latency_ms.describe(),
                "provenance": classify_edge_input(
                    doc, registry, e.id, "latencyMs", e_raw["config"]
                ),
            }
        )
    wl = doc.workload
    wl_raw = raw["workload"]
    labels = {
        "rps": ("Baseline load", f"{wl.rps:g} req/s"),
        "readRatio": ("Read share", f"{wl.read_ratio:.0%}"),
        "keyCount": ("Distinct keys", f"{wl.key_count:,}"),
        "keySkew": ("Key skew (Zipf s)", f"{wl.key_skew:g}"),
        "pattern": ("Traffic pattern", f"{wl.pattern.kind}, {wl.arrival_process} arrivals"),
    }
    for key in WORKLOAD_INPUTS:
        label, text = labels[key]
        out.append(
            {
                "scope": "workload",
                "id": None,
                "label": "Workload",
                "field": key,
                "fieldLabel": label,
                "value": text,
                "provenance": classify_workload_input(doc, key, wl_raw),
            }
        )
    return out


def provenance_counts(inputs: list[Json]) -> dict[str, int]:
    counts = {"measured": 0, "assumed": 0, "illustrative": 0}
    for i in inputs:
        counts[i["provenance"]["kind"]] += 1
    return counts


def _attr(cfg: BaseModel, key: str) -> str:
    return next(
        (name for name, info in type(cfg).model_fields.items() if (info.alias or name) == key), key
    )
