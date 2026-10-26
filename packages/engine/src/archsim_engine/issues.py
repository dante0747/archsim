"""Validation issues: structured, actionable feedback about a document."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Literal

Severity = Literal["error", "warning"]


@dataclass(frozen=True)
class Issue:
    severity: Severity
    code: str
    message: str
    node_id: str | None = None
    edge_id: str | None = None
    path: str | None = None
    hint: str | None = None

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        return {
            "severity": d["severity"],
            "code": d["code"],
            "message": d["message"],
            "nodeId": d["node_id"],
            "edgeId": d["edge_id"],
            "path": d["path"],
            "hint": d["hint"],
        }


def error(code: str, message: str, **kw: Any) -> Issue:
    return Issue("error", code, message, **kw)


def warning(code: str, message: str, **kw: Any) -> Issue:
    return Issue("warning", code, message, **kw)


class DocumentInvalidError(ValueError):
    """Raised when a document cannot be simulated. ``issues`` explains why."""

    def __init__(self, issues: list[Issue]) -> None:
        self.issues = issues
        errors = [i for i in issues if i.severity == "error"]
        first = errors[0].message if errors else "invalid document"
        more = f" (+{len(errors) - 1} more)" if len(errors) > 1 else ""
        super().__init__(f"{first}{more}")

    def __reduce__(self) -> tuple[Any, ...]:
        # Pickled from the issues, not the message: a worker process (parallel sweeps, the API's
        # process runner) sends it back to the parent, and an exception that cannot be rebuilt
        # there breaks the whole process pool instead of reporting what is wrong.
        return (type(self), (self.issues,), self.__dict__)
