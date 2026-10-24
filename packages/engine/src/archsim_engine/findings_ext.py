"""Findings contributed by newer component behaviours, kept apart from the core analysis.

* ``findings_data``     - data stores and dependencies (shards, replicas, throttling, external APIs)
* ``findings_dynamics`` - time-varying behaviour (faults, autoscaling, serverless, async queues)

Each module exposes ``findings(report, resolved) -> list[dict]`` returning finding dicts with the
same shape as ``analysis.analyze`` (severity, kind, nodeId, title, detail, suggestion?, evidence).
"""

from __future__ import annotations

from typing import Any

from archsim_engine import findings_data, findings_dynamics
from archsim_engine.schema import ArchitectureDocument


def extension_findings(
    report: dict[str, Any], resolved: ArchitectureDocument
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    out.extend(findings_data.findings(report, resolved))
    out.extend(findings_dynamics.findings(report, resolved))
    return out
