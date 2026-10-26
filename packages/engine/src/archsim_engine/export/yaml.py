"""Minimal YAML emitter for generated manifests: block style, JSON-quoted strings when needed."""

from __future__ import annotations

import json
import math
import re
from typing import Any

PLAIN = re.compile(r"^[A-Za-z_/.][A-Za-z0-9_./@:-]*$")
RESERVED = re.compile(r"^(true|false|yes|no|on|off|null|~|y|n)$", re.I)


class Omit:
    """A value to leave out (like ``undefined`` in a JavaScript object)."""


OMIT = Omit()


def js_number(v: float) -> str:
    """A number as JavaScript prints it (2 not 2.0)."""
    if isinstance(v, bool):
        return "true" if v else "false"
    if not math.isfinite(v):
        return "null"
    if float(v).is_integer() and abs(v) < 1e21:
        return str(int(v))
    return repr(float(v))


def scalar(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int | float):
        return js_number(v)
    s = str(v)
    if PLAIN.match(s) and not RESERVED.match(s) and not s.endswith(":") and ": " not in s:
        return s
    return json.dumps(s, ensure_ascii=False)


def _is_scalar(v: Any) -> bool:
    return not isinstance(v, dict | list)


def _present(items: Any) -> list[Any]:
    return [x for x in items if x is not OMIT]


def _emit(v: Any, indent: int) -> list[str]:
    pad = " " * indent
    if isinstance(v, list):
        lines: list[str] = []
        for item in _present(v):
            if _is_scalar(item):
                lines.append(f"{pad}- {scalar(item)}")
            elif isinstance(item, list):
                if not _present(item):
                    lines.append(f"{pad}- []")
                else:
                    lines += [f"{pad}-", *_emit(item, indent + 2)]
            else:
                inner = _emit(item, indent + 2)
                if not inner:
                    lines.append(f"{pad}- {{}}")
                else:
                    lines += [f"{pad}- {inner[0][indent + 2 :]}", *inner[1:]]
        return lines
    if isinstance(v, dict):
        lines = []
        for k, val in v.items():
            if val is OMIT:
                continue
            key = scalar(k)
            if _is_scalar(val):
                lines.append(f"{pad}{key}: {scalar(val)}")
            elif isinstance(val, list) and not _present(val):
                lines.append(f"{pad}{key}: []")
            elif isinstance(val, dict) and not _present(val.values()):
                lines.append(f"{pad}{key}: {{}}")
            else:
                lines += [f"{pad}{key}:", *_emit(val, indent + 2)]
        return lines
    return [f"{pad}{scalar(v)}"]


def to_yaml(value: Any) -> str:
    """YAML text of ``value`` (no trailing newline). ``OMIT`` values are left out."""
    return "\n".join(_emit(value, 0))


def comments(lines: list[str]) -> str:
    return "\n".join(f"# {ln}" if ln else "#" for ln in lines)
