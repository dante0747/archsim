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
from archsim_engine.schema import REQUEST_TYPES, RequestType

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
        meta: dict[str, Any] = dict(extra.get("x-ui", {}))
        if "kind" in meta:
            kind = meta.pop("kind")
        desc: dict[str, Any] = {
            "key": key,
            "label": meta.pop("label", None) or _humanize(key),
            "kind": kind,
            "nullable": nullable,
            "description": info.description or "",
            "default": defaults.get(key),
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
        }


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

    def types(self) -> list[str]:
        return list(self._models)

    def catalog(self) -> list[dict[str, Any]]:
        return [m.catalog_entry() for m in self._models.values()]

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
