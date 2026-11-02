"""The golden files' JSON conventions: how engine values, references and errors are recorded."""

from __future__ import annotations

import dataclasses
import json
import math
from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel, ValidationError

from archsim_engine.calibration import mapping as calibration_mapping
from archsim_engine.issues import DocumentInvalidError

Json = dict[str, Any]


def camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(p[:1].upper() + p[1:] for p in rest)


def to_json(value: Any) -> Any:
    """Plain JSON of an engine value: pydantic models dumped by alias (as the engine dumps them),
    dataclasses as objects with camelCase field names, tuples as lists, sets as sorted lists, a
    dict with non-string keys as a list of ``[key, value]`` pairs, non-finite floats as the
    strings ``"Infinity"``, ``"-Infinity"`` and ``"NaN"``."""
    if value is None or isinstance(value, bool | int | str):
        return value
    if isinstance(value, float):
        if math.isfinite(value):
            return value
        return "NaN" if math.isnan(value) else ("Infinity" if value > 0 else "-Infinity")
    if isinstance(value, BaseModel):
        return to_json(value.model_dump(mode="json", by_alias=True))
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {camel(f.name): to_json(getattr(value, f.name)) for f in dataclasses.fields(value)}
    if isinstance(value, Mapping):
        if all(isinstance(k, str) for k in value):
            return {k: to_json(v) for k, v in value.items()}
        return [[to_json(k), to_json(v)] for k, v in value.items()]
    if isinstance(value, list | tuple):
        return [to_json(v) for v in value]
    if isinstance(value, set | frozenset):
        return sorted(to_json(v) for v in value)
    raise TypeError(f"cannot record a {type(value).__name__} as JSON")


def number(value: Any) -> Any:
    """A recorded number back as a float (non-finite floats are recorded as strings)."""
    if value in ("Infinity", "-Infinity", "NaN"):
        return float(value.replace("Infinity", "inf").replace("NaN", "nan"))
    return value


def from_json(cls: type, data: Json) -> Any:
    """A dataclass from its recorded JSON (camelCase field names): lists become tuples where the
    field is a tuple, and recorded non-finite numbers floats where it is a float."""
    names = {camel(f.name): f for f in dataclasses.fields(cls)}
    kwargs: Json = {}
    for key, value in data.items():
        field = names[key]
        kind = str(field.type)
        if isinstance(value, list) and kind.startswith("tuple"):
            value = tuple(tuple(v) if isinstance(v, list) else v for v in value)
        kwargs[field.name] = number(value) if kind.startswith("float") else value
    return cls(**kwargs)


def canonical(value: Any) -> bytes:
    """Canonical JSON: sorted keys, no whitespace, UTF-8 (not ASCII-escaped), no NaN."""
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")


def pointer(path: tuple[str | int, ...]) -> str:
    """The JSON Pointer (RFC 6901) of ``path``."""
    return "".join("/" + str(p).replace("~", "~0").replace("/", "~1") for p in path)


def lookup(root: Any, ref: str) -> Any:
    """What the JSON Pointer ``ref`` points to in ``root`` (KeyError, IndexError: nothing)."""
    target = root
    for token in ref[1:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        target = target[int(token)] if isinstance(target, list) else target[token]
    return target


def resolve_refs(value: Any, root: Any) -> Any:
    """``value`` with every ``{"$ref": "/..."}`` replaced by what it points to in ``root``."""
    if isinstance(value, dict):
        ref = value.get("$ref")
        if len(value) == 1 and isinstance(ref, str) and ref.startswith("/"):
            return resolve_refs(lookup(root, ref), root)
        return {k: resolve_refs(v, root) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve_refs(v, root) for v in value]
    return value


def error_json(exc: BaseException) -> Json:
    """How a raised error is recorded: its type and message (and a document's issues)."""
    message = (
        calibration_mapping.validation_message(exc)
        if isinstance(exc, ValidationError)
        else str(exc)
    )
    out: Json = {"type": type(exc).__name__, "message": message}
    if isinstance(exc, DocumentInvalidError):
        out["issues"] = [i.to_dict() for i in exc.issues]
    return out
