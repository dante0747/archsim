"""Shared builders for engine tests (imported by test modules)."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
EXAMPLES = REPO_ROOT / "examples"

ZERO = {"kind": "constant", "value": 0}


def node(id_: str, type_: str, label: str | None = None, **config: Any) -> dict[str, Any]:
    return {"id": id_, "type": type_, "label": label or id_, "config": config}


def edge(
    id_: str,
    source: str,
    target: str,
    source_port: str = "out",
    request_types: list[str] | None = None,
    latency: dict[str, Any] | None = None,
    **config: Any,
) -> dict[str, Any]:
    cfg = {"requestTypes": request_types or ["read", "write"], "latencyMs": latency or ZERO}
    cfg.update(config)
    return {
        "id": id_,
        "source": source,
        "sourcePort": source_port,
        "target": target,
        "targetPort": "in",
        "config": cfg,
    }


def doc(
    nodes: list[dict[str, Any]],
    edges: list[dict[str, Any]],
    scenarios: list[dict[str, Any]] | None = None,
    **workload: Any,
) -> dict[str, Any]:
    wl = {"rps": 100, "durationS": 20, "warmupS": 2, "seed": 7, "readRatio": 1.0}
    wl.update(workload)
    return {
        "schemaVersion": "1.0",
        "kind": "archsim.architecture",
        "metadata": {"name": "test"},
        "architecture": {"nodes": nodes, "edges": edges},
        "workload": wl,
        "scenarios": scenarios or [],
    }


def single_service(
    *,
    rps: float,
    service: dict[str, Any] | None = None,
    workers: int = 1,
    replicas: int = 1,
    queue_capacity: int = 100_000,
    queue_timeout_ms: float | None = None,
    client_timeout_ms: float = 600_000,
    max_attempts: int = 1,
    **workload: Any,
) -> dict[str, Any]:
    """Clients -> one service. With exponential service and Poisson arrivals this is M/M/c."""
    return doc(
        [
            node(
                "clients",
                "traffic_source",
                timeoutMs=client_timeout_ms,
                maxAttempts=max_attempts,
                retryBackoffMs=10,
            ),
            node(
                "svc",
                "service",
                replicas=replicas,
                workersPerReplica=workers,
                queueCapacity=queue_capacity,
                queueTimeoutMs=queue_timeout_ms,
                serviceTimeMs=service or {"kind": "exponential", "mean": 10},
            ),
        ],
        [edge("c-s", "clients", "svc")],
        rps=rps,
        **workload,
    )


def load_example(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
    return data


def short(d: dict[str, Any], duration: float = 20, warmup: float = 4) -> dict[str, Any]:
    d = copy.deepcopy(d)
    d["workload"]["durationS"] = duration
    d["workload"]["warmupS"] = warmup
    return d


def node_by_id(report: dict[str, Any], node_id: str) -> dict[str, Any]:
    return next(n for n in report["nodes"] if n["id"] == node_id)
