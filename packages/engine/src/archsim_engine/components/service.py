"""Stateless application service (e.g. API servers) with replicas, worker pools and queues."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any

import simpy
from pydantic import Field

from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.issues import Issue
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, ui
from archsim_engine.runtime import (
    OK,
    EdgeRuntime,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    WorkerPool,
    rejected,
)
from archsim_engine.schema import CamelModel, NodeSpec


class ServiceConfig(CamelModel):
    replicas: int = Field(
        default=2,
        ge=1,
        le=500,
        description="Number of identical instances. Each has its own workers and queue.",
        json_schema_extra=ui(label="Replicas", group="Capacity", headline=True),
    )
    workers_per_replica: int = Field(
        default=4,
        ge=1,
        le=4096,
        description="Requests one replica processes concurrently (threads/worker processes).",
        json_schema_extra=ui(label="Workers per replica", group="Capacity", headline=True),
    )
    queue_capacity: int = Field(
        default=50,
        ge=0,
        le=100_000,
        description="Requests that may wait for a free worker per replica; more are rejected (HTTP 503).",
        json_schema_extra=ui(label="Queue limit per replica", group="Capacity"),
    )
    queue_timeout_ms: float | None = Field(
        default=1000.0,
        gt=0,
        le=600_000,
        description="Requests waiting longer than this for a worker are rejected (ms). Empty = no limit.",
        json_schema_extra=ui(label="Queue timeout", unit="ms", group="Capacity"),
    )
    service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=8.0, p95=25.0),
        description=(
            "Handler time per request on a worker, excluding downstream calls (ms). "
            "This is an assumption unless calibrated from telemetry."
        ),
        json_schema_extra=ui(
            label="Service time", unit="ms", group="Performance", assumption=True, headline=True
        ),
    )
    blocking_downstream: bool = Field(
        default=True,
        description=(
            "On: a worker stays occupied while waiting for downstream calls (thread-per-request). "
            "Off: the worker is released during downstream I/O (async/event-loop servers)."
        ),
        json_schema_extra=ui(label="Workers block on downstream calls", group="Performance"),
    )


class ServiceRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: ServiceConfig) -> None:
        super().__init__(ctx, node, config, config.replicas * config.workers_per_replica)
        self.m.has_cpu_split = True
        self.pools = [
            WorkerPool(
                ctx.env,
                config.workers_per_replica,
                config.queue_capacity,
                config.queue_timeout_ms,
                self.m,
            )
            for _ in range(config.replicas)
        ]
        self.load = [0] * config.replicas
        self.rr = 0
        self.rng = ctx.rng(f"node:{node.id}:service")
        self._deps: dict[str, list[EdgeRuntime]] = {}

    def deps_for(self, op: str) -> list[EdgeRuntime]:
        d = self._deps.get(op)
        if d is None:
            d = sorted(
                (e for e in self.out_edges("out") if e.carries(op)),
                key=lambda e: (e.cfg.order, e.id),
            )
            self._deps[op] = d
        return d

    def instance_count(self) -> int:
        return len(self.pools)

    def instance_load(self, index: int) -> int:
        return self.load[index]

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        # Called without a load balancer: spread across replicas round-robin.
        i = self.rr
        self.rr = (i + 1) % len(self.pools)
        return self.handle_on(req, i)

    def handle_on(self, req: Request, index: int) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: ServiceConfig = self.config
        m.arrive(env.now)
        self.load[index] += 1
        pool = self.pools[index]
        tok, reason = yield from pool.acquire()
        if tok is None:
            self.load[index] -= 1
            m.reject(reason or "rejected", env.now)
            return rejected(reason or "rejected", self.id)
        start = env.now
        st = cfg.service_time_ms.sample(self.rng)
        m.cpu.add(1, start)
        if st > 0:
            yield env.timeout(st)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        outcome: Outcome = OK
        deps = self.deps_for(req.op)
        if cfg.blocking_downstream:
            for e in deps:
                outcome = yield from self.ctx.call(e, req, self.id)
                if not outcome.ok:
                    break
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
        else:
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
            for e in deps:
                outcome = yield from self.ctx.call(e, req, self.id)
                if not outcome.ok:
                    break
        self.load[index] -= 1
        m.depart(env.now, outcome, req)
        return outcome


class ServiceModel(ComponentModel):
    type = "service"
    display_name = "Service / API Servers"
    category = "compute"
    description = (
        "Stateless application servers. Each replica has a worker pool and a bounded queue. "
        "Outgoing links are called in order for matching request types (e.g. reads to the cache, "
        "writes to the database)."
    )
    icon = "server"
    config_model = ServiceConfig
    ports = (
        PortSpec("in", "in", "Requests", "Incoming requests."),
        PortSpec(
            "out",
            "out",
            "Dependencies",
            "Downstream calls made while handling a request. Each link's request types decide which requests use it.",
        ),
    )
    modeled = (
        "FIFO queue per replica with a size limit and a wait timeout",
        "Worker pool per replica; service time sampled from the configured distribution",
        "Sequential downstream calls; blocking (thread-per-request) or non-blocking I/O",
    )
    not_modeled = (
        "CPU contention between workers, garbage collection pauses, autoscaling",
        "Parallel fan-out calls (dependencies are called one after another)",
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.replicas * config.workers_per_replica)

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        return []

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return ServiceRuntime(ctx, node, config)
