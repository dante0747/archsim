"""Relational database (e.g. PostgreSQL) modeled as a connection-limited server."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any

import simpy
from pydantic import Field

from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.registry import ComponentModel, PortSpec, ui
from archsim_engine.runtime import (
    OK,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    WorkerPool,
    rejected,
)
from archsim_engine.schema import CamelModel, NodeSpec


class DatabaseConfig(CamelModel):
    max_connections: int = Field(
        default=10,
        ge=1,
        le=10_000,
        description="Queries executing concurrently (connection pool size / backend workers).",
        json_schema_extra=ui(label="Max connections", group="Capacity", headline=True),
    )
    queue_capacity: int = Field(
        default=200,
        ge=0,
        le=100_000,
        description="Queries that may wait for a connection; more are rejected.",
        json_schema_extra=ui(label="Wait queue limit", group="Capacity"),
    )
    queue_timeout_ms: float | None = Field(
        default=1000.0,
        gt=0,
        le=600_000,
        description="Connection acquire timeout (ms). Queries waiting longer are rejected. Empty = no limit.",
        json_schema_extra=ui(label="Acquire timeout", unit="ms", group="Capacity"),
    )
    read_service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=5.0, p95=20.0),
        description="Execution time of a read query (ms).",
        json_schema_extra=ui(
            label="Read query time", unit="ms", group="Performance", assumption=True, headline=True
        ),
    )
    write_service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=10.0, p95=40.0),
        description="Execution time of a write query including commit (ms).",
        json_schema_extra=ui(
            label="Write query time", unit="ms", group="Performance", assumption=True
        ),
    )


class DatabaseRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: DatabaseConfig) -> None:
        super().__init__(ctx, node, config, config.max_connections)
        self.pool = WorkerPool(
            ctx.env, config.max_connections, config.queue_capacity, config.queue_timeout_ms, self.m
        )
        self.rng = ctx.rng(f"node:{node.id}:service")

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: DatabaseConfig = self.config
        m.arrive(env.now)
        tok, reason = yield from self.pool.acquire()
        if tok is None:
            m.reject(reason or "rejected", env.now)
            return rejected(reason or "rejected", self.id)
        start = env.now
        dist = cfg.read_service_time_ms if req.op == "read" else cfg.write_service_time_ms
        st = dist.sample(self.rng)
        if st > 0:
            yield env.timeout(st)
        self.pool.release(tok)
        m.service.record(env.now, st)
        m.hold.record(env.now, env.now - start)
        m.count("reads" if req.op == "read" else "writes", env.now)
        m.depart(env.now, OK, req)
        return OK


class DatabaseModel(ComponentModel):
    type = "database"
    display_name = "Database (PostgreSQL)"
    category = "storage"
    description = (
        "Executes read and write queries on a limited number of connections. Queries wait for a "
        "free connection up to the acquire timeout."
    )
    icon = "database"
    config_model = DatabaseConfig
    ports = (PortSpec("in", "in", "Queries", "Read and write queries."),)
    modeled = (
        "Connection limit with a bounded wait queue and acquire timeout",
        "Separate read/write query time distributions",
    )
    not_modeled = (
        "Locks, MVCC contention, disk I/O and buffer cache behaviour",
        "Replication lag, read replicas, query planning",
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.max_connections)

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return DatabaseRuntime(ctx, node, config)
