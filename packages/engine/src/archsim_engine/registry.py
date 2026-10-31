"""Component registry: the extension point for new architectural component types.

A component model bundles, for one ``type``:

* typed ports (which request types each port accepts or emits, and how many links it allows)
* a Pydantic configuration model whose fields carry UI metadata (unit, group, assumption flag)
* type-specific validation
* a ``build`` method producing the executable runtime behaviour

The web UI renders palettes and inspector forms from ``ComponentRegistry.catalog()``, so adding a
component requires no frontend changes. See ``docs/extending-components.md``.
"""

from __future__ import annotations

import builtins
import os
import types
import typing
from abc import ABC, abstractmethod
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, ClassVar, Literal

import annotated_types
from pydantic import BaseModel, ValidationError

from archsim_engine.issues import Issue, error
from archsim_engine.schema import REQUEST_TYPES, EdgeConfig, RequestType, Workload
from archsim_engine.technology import (
    TIER_NOTE,
    Profile,
    Tier,
    defaults_catalog,
    sources_catalog,
    tiers_catalog,
    traits_catalog,
)

if TYPE_CHECKING:
    from archsim_engine.runtime import RuntimeComponent, SimContext
    from archsim_engine.schema import EdgeSpec, NodeSpec, Workload


# --------------------------------------------------------------------------------------
# Ports
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class PortSpec:
    name: str
    direction: Literal["in", "out"]
    label: str
    description: str = ""
    request_types: tuple[RequestType, ...] = REQUEST_TYPES
    min_edges: int = 0
    max_edges: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "direction": self.direction,
            "label": self.label,
            "description": self.description,
            "requestTypes": list(self.request_types),
            "minEdges": self.min_edges,
            "maxEdges": self.max_edges,
        }


# --------------------------------------------------------------------------------------
# UI field metadata
# --------------------------------------------------------------------------------------


def ui(
    *,
    label: str | None = None,
    unit: str | None = None,
    group: str | None = None,
    assumption: bool = False,
    headline: bool = False,
    visible_when: dict[str, Any] | None = None,
    step: float | None = None,
    kind: str | None = None,
    ref_type: str | None = None,
) -> dict[str, Any]:
    """Metadata attached to a config field via ``json_schema_extra``.

    ``assumption=True`` marks values the user asserts (e.g. service time, hit ratio) as opposed to
    structural settings; the UI labels them so results are not mistaken for measurements.
    """
    return {
        "x-ui": {
            k: v
            for k, v in {
                "label": label,
                "unit": unit,
                "group": group,
                "assumption": assumption,
                "headline": headline,
                "visibleWhen": visible_when,
                "step": step,
                "kind": kind,
                "refType": ref_type,
            }.items()
            if v is not None
        }
    }


def _humanize(name: str) -> str:
    out = []
    for i, ch in enumerate(name):
        if ch.isupper() and i:
            out.append(" ")
        out.append(ch)
    s = "".join(out).replace("_", " ")
    return s[:1].upper() + s[1:].lower()


def _field_kind(annotation: Any) -> tuple[str, bool, list[Any] | None]:
    """Return (kind, nullable, enum options) for a field annotation."""
    nullable = False
    ann = annotation
    origin = typing.get_origin(ann)
    if origin in (typing.Union, types.UnionType):
        args = [a for a in typing.get_args(ann) if a is not type(None)]
        nullable = len(args) != len(typing.get_args(ann))
        if len(args) == 1:
            ann = args[0]
            origin = typing.get_origin(ann)
        else:
            # union of models -> distribution
            return "distribution", nullable, None
    if origin is typing.Annotated:
        inner = typing.get_args(ann)[0]
        if typing.get_origin(inner) in (typing.Union, types.UnionType):
            return "distribution", nullable, None
        return _field_kind(inner)[0], nullable, None
    if origin is Literal:
        return "enum", nullable, list(typing.get_args(ann))
    if origin is list:
        return "requestTypes", nullable, list(REQUEST_TYPES)
    if ann is bool:
        return "boolean", nullable, None
    if ann is int:
        return "integer", nullable, None
    if ann is float:
        return "number", nullable, None
    if ann is str:
        return "string", nullable, None
    return "object", nullable, None


def describe_fields(model: type[BaseModel]) -> list[dict[str, Any]]:
    """Field descriptors used by the UI to render a configuration form."""
    defaults = model().model_dump(mode="json", by_alias=True)
    fields: list[dict[str, Any]] = []
    for name, info in model.model_fields.items():
        key = info.alias or name
        kind, nullable, options = _field_kind(info.annotation)
        extra = info.json_schema_extra if isinstance(info.json_schema_extra, dict) else {}
        raw_ui = extra.get("x-ui", {})
        meta: dict[str, Any] = dict(raw_ui) if isinstance(raw_ui, dict) else {}
        if "kind" in meta:
            if meta["kind"] != kind:  # e.g. a list that is not request types: no such options
                options = None
            kind = meta.pop("kind")
        default = defaults.get(key)
        if default is None and info.default_factory is list:  # left out of the JSON when empty
            default = []
        desc: dict[str, Any] = {
            "key": key,
            "label": meta.pop("label", None) or _humanize(key),
            "kind": kind,
            "nullable": nullable,
            "description": info.description or "",
            "default": default,
            "assumption": meta.pop("assumption", False),
            "headline": meta.pop("headline", False),
        }
        if options is not None:
            desc["options"] = options
        for c in info.metadata:
            if isinstance(c, annotated_types.Ge):
                desc["min"] = c.ge
            elif isinstance(c, annotated_types.Gt):
                desc["min"] = c.gt
                desc["exclusiveMin"] = True
            elif isinstance(c, annotated_types.Le):
                desc["max"] = c.le
            elif isinstance(c, annotated_types.Lt):
                desc["max"] = c.lt
        desc.update(meta)
        fields.append(desc)
    return fields


# --------------------------------------------------------------------------------------
# Component model
# --------------------------------------------------------------------------------------


@dataclass
class GraphView:
    """Read-only view of a document given to component validators."""

    nodes: dict[str, NodeSpec]
    configs: dict[str, BaseModel]
    out_edges: dict[str, list[EdgeSpec]]
    in_edges: dict[str, list[EdgeSpec]]
    workload: Workload
    incoming_types: dict[str, set[str]] = field(default_factory=dict)


@dataclass(frozen=True)
class Preset:
    """A named technology profile of a component type, e.g. PostgreSQL for ``database``.

    ``config`` holds camelCase config values that differ from the defaults; it is validated
    against the component's config model by the test suite. Behaviour comes from the config
    values: the technology field resolves the "auto" behaviour settings through the component's
    ``technology_profiles``, and presets add typical numbers (replicas, shards, sizes...).
    Those numbers are illustrative unless ``sources`` links the documentation that states them
    (camelCase config key -> URL).
    """

    id: str
    name: str
    technology: str
    family: str  # e.g. "Relational", "Document", "Kubernetes", "Log / stream"
    description: str
    config: dict[str, Any]
    tags: tuple[str, ...] = ()
    sources: dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "technology": self.technology,
            "family": self.family,
            "description": self.description,
            "config": dict(self.config),
            "tags": list(self.tags),
            "sources": dict(self.sources),
        }


class ComponentModel(ABC):
    """Subclass, set the class attributes, implement ``build``; register with a registry."""

    type: ClassVar[str]
    display_name: ClassVar[str]
    category: ClassVar[str]  # traffic | network | compute | storage
    description: ClassVar[str]
    icon: ClassVar[str] = "box"
    config_model: ClassVar[builtins.type[BaseModel]]
    ports: ClassVar[tuple[PortSpec, ...]]
    is_traffic_source: ClassVar[bool] = False
    modeled: ClassVar[tuple[str, ...]] = ()
    not_modeled: ClassVar[tuple[str, ...]] = ()
    presets: ClassVar[tuple[Preset, ...]] = ()
    # Link settings (camelCase, a partial EdgeConfig) suggested for links drawn from this
    # component, e.g. the network latency of a mobile client. Applied by the editor; editable.
    link_defaults: ClassVar[dict[str, Any]] = {}
    # Behaviour per technology (see archsim_engine.technology), for the catalog and inspector.
    technology_profiles: ClassVar[dict[str, Profile]] = {}

    # -- configuration -------------------------------------------------------------------
    def parse_config(self, raw: dict[str, Any]) -> BaseModel:
        return self.config_model.model_validate(raw)

    def default_config(self) -> dict[str, Any]:
        return self.config_model().model_dump(mode="json", by_alias=True, exclude_none=True)

    def port(self, name: str) -> PortSpec | None:
        return next((p for p in self.ports if p.name == name), None)

    def in_ports(self) -> list[PortSpec]:
        return [p for p in self.ports if p.direction == "in"]

    def out_ports(self) -> list[PortSpec]:
        return [p for p in self.ports if p.direction == "out"]

    # -- validation ------------------------------------------------------------------------
    def emitted_types(
        self, port: str, incoming: set[str], config: Any, view: GraphView
    ) -> set[str]:
        """Request types that may leave through ``port`` given the types arriving at the node."""
        return set(incoming)

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        """Type-specific checks beyond port arity (override as needed)."""
        return []

    # -- simulation ------------------------------------------------------------------------
    def capacity_slots(self, config: Any) -> int | None:
        """Total concurrent slots (workers/connections) used for utilization; None if n/a."""
        return None

    def technology_tier(self, config: Any) -> tuple[str, Tier] | None:
        """The node's technology and its tier, or None for parts without technology profiles."""
        tech = getattr(config, "technology", None)
        profile = self.technology_profiles.get(tech) if isinstance(tech, str) else None
        return (tech, profile.tier) if isinstance(tech, str) and profile else None

    def uses_keys(self, config: Any) -> bool:
        """True if runtime behaviour depends on request keys (enables key sampling)."""
        return False

    @abstractmethod
    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent: ...

    # -- catalog -------------------------------------------------------------------------
    def catalog_entry(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "displayName": self.display_name,
            "category": self.category,
            "description": self.description,
            "icon": self.icon,
            "isTrafficSource": self.is_traffic_source,
            "ports": [p.to_dict() for p in self.ports],
            "fields": describe_fields(self.config_model),
            "defaultConfig": self.default_config(),
            "modeled": list(self.modeled),
            "notModeled": list(self.not_modeled),
            "presets": [self._preset_entry(p) for p in self.presets],
            "linkDefaults": dict(self.link_defaults),
            "technologyTraits": traits_catalog(self.technology_profiles),
            "technologyDefaults": defaults_catalog(self.technology_profiles),
            "technologySources": sources_catalog(self.technology_profiles),
            "technologyTiers": tiers_catalog(self.technology_profiles),
        }

    def _preset_entry(self, preset: Preset) -> dict[str, Any]:
        out = preset.to_dict()
        profile = self.technology_profiles.get(preset.technology)
        if profile is not None:
            out["tier"] = profile.tier
        return out


class UnknownComponentError(KeyError):
    pass


class ComponentRegistry:
    def __init__(self, models: Iterable[ComponentModel] = ()) -> None:
        self._models: dict[str, ComponentModel] = {}
        for m in models:
            self.register(m)

    def register(self, model: ComponentModel) -> None:
        if not getattr(model, "type", None):
            raise ValueError("component model must define a non-empty 'type'")
        if model.type in self._models:
            raise ValueError(f"component type '{model.type}' is already registered")
        names = [p.name for p in model.ports]
        if len(set(names)) != len(names):
            raise ValueError(f"component '{model.type}' has duplicate port names")
        self._models[model.type] = model

    def get(self, type_: str) -> ComponentModel:
        try:
            return self._models[type_]
        except KeyError:
            raise UnknownComponentError(type_) from None

    def __contains__(self, type_: object) -> bool:
        return type_ in self._models

    def models(self) -> list[ComponentModel]:
        return list(self._models.values())

    def types(self) -> list[str]:
        return list(self._models)

    def catalog(self) -> list[dict[str, Any]]:
        return [m.catalog_entry() for m in self._models.values()]

    def catalog_document(self) -> dict[str, Any]:
        """Everything an editor renders from (``GET /api/v1/components``): the component
        catalog, link and workload settings, and the text of the "experimental" badge."""
        return {
            "components": self.catalog(),
            "edgeFields": describe_fields(EdgeConfig),
            "workloadFields": describe_fields(Workload),
            "tierNote": TIER_NOTE,
        }

    def technology_tiers(self, nodes: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
        """Each part that has technology profiles, with its technology and tier (``nodes`` as in
        a document: id, type, label, camelCase config). Unknown types and invalid configs are
        skipped; validation reports them."""
        out: list[dict[str, Any]] = []
        for n in nodes:
            model = self._models.get(str(n.get("type")))
            if model is None or not model.technology_profiles:
                continue
            try:
                config = model.parse_config(n.get("config") or {})
            except ValidationError:
                continue
            found = model.technology_tier(config)
            if found:
                out.append(
                    {
                        "id": n["id"],
                        "label": n.get("label") or n["id"],
                        "type": model.type,
                        "technology": found[0],
                        "tier": found[1],
                    }
                )
        return out

    def parse_node_config(self, node: NodeSpec) -> tuple[BaseModel | None, list[Issue]]:
        model = self.get(node.type)
        try:
            return model.parse_config(node.config), []
        except ValidationError as exc:
            issues = []
            for err in exc.errors():
                loc = ".".join(str(p) for p in err["loc"])
                issues.append(
                    error(
                        "node.config_invalid",
                        f"{node.label or node.id}: '{loc}' {err['msg'].lower()}",
                        node_id=node.id,
                        path=loc,
                        hint="Fix the highlighted field in the inspector.",
                    )
                )
            return None, issues


def load_entry_point_plugins(registry: ComponentRegistry) -> list[str]:
    """Register component models exposed by installed packages (entry point group
    ``archsim.components``). Opt-in via ``ARCHSIM_ENABLE_PLUGINS=1``.

    Only locally installed (trusted) packages are considered; plugins can never be uploaded
    through the API.
    """
    if os.environ.get("ARCHSIM_ENABLE_PLUGINS") != "1":
        return []
    from importlib.metadata import entry_points

    loaded = []
    for ep in entry_points(group="archsim.components"):
        obj = ep.load()
        model = obj() if isinstance(obj, type) else obj
        registry.register(model)
        loaded.append(model.type)
    return loaded
