"""Where every numeric input of a design came from.

An input is **measured** when the document's ``provenance`` says so (calibration writes it, with
the source, the query, the time range and how well the fit matched), **illustrative** when it is
ArchSim's default or the number the technology's preset fills in, and **assumed** otherwise (a
person typed it). Reports list every performance input with its provenance; the inspector badges
measured ones.

Paths: ``nodes/<id>/<config key>``, ``edges/<id>/<config key>``, ``workload/<field>``.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from archsim_engine.distributions import Constant, Empirical, Exponential, LogNormal, Uniform
from archsim_engine.registry import ComponentRegistry, describe_fields
from archsim_engine.schema import ArchitectureDocument, EdgeConfig, NodeSpec, Workload

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
    explicit = doc.provenance.get(node_path(node.id, key))
    if explicit is not None:
        return explicit.model_dump(mode="json", by_alias=True, exclude_none=True)
    model = registry.get(node.type)
    default = model.default_config()
    if key not in node.config or _same(node.config[key], default.get(key)):
        return {"kind": "illustrative", "source": "ArchSim default"}
    tech = node.config.get("technology", default.get("technology"))
    for preset in model.presets:
        if (
            preset.technology == tech
            and key in preset.config
            and _same(preset.config[key], node.config[key])
        ):
            return {"kind": "illustrative", "source": f"preset {preset.name}"}
    return {"kind": "assumed"}


def classify_edge_input(
    doc: ArchitectureDocument, registry: ComponentRegistry, edge_id: str, key: str, raw: Json
) -> Json:
    explicit = doc.provenance.get(edge_path(edge_id, key))
    if explicit is not None:
        return explicit.model_dump(mode="json", by_alias=True, exclude_none=True)
    default = EdgeConfig().model_dump(mode="json", by_alias=True)
    value = raw.get(key)
    if value is None or _same(value, default.get(key)):
        return {"kind": "illustrative", "source": "ArchSim default"}
    edge = next((e for e in doc.architecture.edges if e.id == edge_id), None)
    src = doc.node(edge.source) if edge else None
    if src is not None and src.type in registry:
        suggested = registry.get(src.type).link_defaults.get(key)
        if suggested is not None and _same(suggested, value):
            return {
                "kind": "illustrative",
                "source": f"{registry.get(src.type).display_name} link default",
            }
    return {"kind": "assumed"}


def classify_workload_input(doc: ArchitectureDocument, key: str, raw: Json) -> Json:
    explicit = doc.provenance.get(workload_path(key))
    if explicit is not None:
        return explicit.model_dump(mode="json", by_alias=True, exclude_none=True)
    default = Workload().model_dump(mode="json", by_alias=True)
    if key not in raw or _same(raw[key], default.get(key)):
        return {"kind": "illustrative", "source": "ArchSim default"}
    return {"kind": "assumed"}


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
