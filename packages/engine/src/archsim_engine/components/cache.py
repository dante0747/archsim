"""In-memory cache (e.g. Redis) used cache-aside: hits return immediately, misses continue to
the data store linked from the ``miss`` port."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.distributions import Distribution, Exponential
from archsim_engine.issues import Issue, error, warning
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, ui
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


class CacheConfig(CamelModel):
    hit_model: Literal["fixed_ratio", "ttl_keyspace"] = Field(
        default="fixed_ratio",
        description=(
            "fixed_ratio: each read hits with the given probability (simplest assumption). "
            "ttl_keyspace: reads pick keys from the workload's Zipf keyspace; a key hits if it was "
            "filled less than TTL seconds ago (derives hit rate, including cold start)."
        ),
        json_schema_extra=ui(label="Hit model", group="Cache behaviour", headline=True),
    )
    hit_ratio: float = Field(
        default=0.8,
        ge=0,
        le=1,
        description="Probability a read is served from the cache (fixed_ratio model).",
        json_schema_extra=ui(
            label="Hit ratio",
            group="Cache behaviour",
            assumption=True,
            headline=True,
            visible_when={"hitModel": "fixed_ratio"},
            step=0.05,
        ),
    )
    ttl_seconds: float = Field(
        default=60.0,
        gt=0,
        le=86_400,
        description="Time a filled key stays valid (ttl_keyspace model). Capacity is unbounded (no eviction).",
        json_schema_extra=ui(
            label="TTL",
            unit="s",
            group="Cache behaviour",
            assumption=True,
            visible_when={"hitModel": "ttl_keyspace"},
        ),
    )
    populate_on_miss: bool = Field(
        default=True,
        description="After a miss is served by the data store, write the value back (one extra cache operation).",
        json_schema_extra=ui(label="Populate on miss", group="Cache behaviour"),
    )
    service_time_ms: Distribution = Field(
        default_factory=lambda: Exponential(mean=0.05),
        description="Time to execute one cache command, excluding network (ms).",
        json_schema_extra=ui(label="Command time", unit="ms", group="Performance", assumption=True),
    )
    concurrency: int = Field(
        default=1,
        ge=1,
        le=1024,
        description="Commands executed in parallel (Redis executes commands on a single thread).",
        json_schema_extra=ui(label="Command executors", group="Capacity"),
    )
    queue_capacity: int = Field(
        default=10_000,
        ge=0,
        le=100_000,
        description="Commands that may wait for an executor; more are rejected.",
        json_schema_extra=ui(label="Queue limit", group="Capacity"),
    )
    queue_timeout_ms: float | None = Field(
        default=None,
        gt=0,
        le=600_000,
        description="Commands waiting longer than this are rejected (ms). Empty = no limit.",
        json_schema_extra=ui(label="Queue timeout", unit="ms", group="Capacity"),
    )

    def can_miss(self) -> bool:
        return self.hit_model == "ttl_keyspace" or self.hit_ratio < 1.0


class CacheRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: CacheConfig) -> None:
        super().__init__(ctx, node, config, config.concurrency)
        self.pool = WorkerPool(
            ctx.env, config.concurrency, config.queue_capacity, config.queue_timeout_ms, self.m
        )
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.hit_rng = ctx.rng(f"node:{node.id}:hits")
        self.expiry: dict[int, float] = {}
        self.ttl_ms = config.ttl_seconds * 1000.0
        self.ttl_mode = config.hit_model == "ttl_keyspace"

    def _command(self) -> Generator[simpy.Event, Any, str | None]:
        """Execute one cache command on an executor. Returns a rejection reason or None."""
        env = self.env
        tok, reason = yield from self.pool.acquire()
        if tok is None:
            return reason or "rejected"
        start = env.now
        st = self.config.service_time_ms.sample(self.rng)
        if st > 0:
            yield env.timeout(st)
        self.pool.release(tok)
        self.m.service.record(env.now, st)
        self.m.hold.record(env.now, env.now - start)
        return None

    def _is_hit(self, req: Request) -> bool:
        if self.ttl_mode:
            exp = self.expiry.get(req.key)
            return exp is not None and exp > self.env.now
        return self.hit_rng.random() < self.config.hit_ratio

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        m.arrive(env.now)
        reason = yield from self._command()
        if reason is not None:
            m.reject(reason, env.now)
            return rejected(reason, self.id)
        if req.op == "write":
            # cache-aside write path: invalidate the key
            if self.ttl_mode:
                self.expiry.pop(req.key, None)
            m.count("invalidations", env.now)
            m.depart(env.now, OK, req)
            return OK
        if self._is_hit(req):
            m.count("hits", env.now)
            m.depart(env.now, OK, req)
            return OK
        m.count("misses", env.now)
        miss_edges = self.out_edges("miss")
        if not miss_edges:  # prevented by validation; kept for safety
            outcome = rejected("miss_unrouted", self.id)
            m.depart(env.now, outcome, req)
            return outcome
        outcome = yield from self.ctx.call(miss_edges[0], req, self.id)
        if outcome.ok and self.config.populate_on_miss:
            r = yield from self._command()
            if r is None:
                m.count("populates", env.now)
                if self.ttl_mode:
                    self.expiry[req.key] = env.now + self.ttl_ms
        m.depart(env.now, outcome, req)
        return outcome


class CacheModel(ComponentModel):
    type = "cache"
    display_name = "Cache (Redis)"
    category = "storage"
    description = (
        "Cache-aside: reads that hit return from memory; misses continue through the 'miss' port "
        "to the data store, then optionally repopulate the cache. Writes invalidate the key."
    )
    icon = "zap"
    config_model = CacheConfig
    ports = (
        PortSpec("in", "in", "Commands", "Reads (lookups) and writes (invalidations)."),
        PortSpec(
            "miss",
            "out",
            "Miss",
            "Where reads go when the key is not cached (usually the database).",
            request_types=("read",),
            max_edges=1,
        ),
    )
    modeled = (
        "Hit/miss decision per read (fixed probability or TTL over a Zipf keyspace)",
        "Single-threaded command execution with a queue",
        "Miss path latency added to the caller; repopulation cost",
    )
    not_modeled = (
        "Memory limits and eviction (LRU/LFU)",
        "Thundering herd / request coalescing on misses",
        "Replication and persistence",
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.concurrency)

    def uses_keys(self, config: Any) -> bool:
        return bool(config.hit_model == "ttl_keyspace")

    def emitted_types(
        self, port: str, incoming: set[str], config: Any, view: GraphView
    ) -> set[str]:
        if port == "miss":
            return {"read"} & incoming if config.can_miss() else set()
        return set()

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        miss_edges = [e for e in view.out_edges.get(node.id, []) if e.source_port == "miss"]
        receives_reads = "read" in view.incoming_types.get(node.id, {"read"})
        name = node.label or node.id
        if config.can_miss() and not miss_edges and receives_reads:
            what = (
                f"hit ratio {config.hit_ratio:.0%}"
                if config.hit_model == "fixed_ratio"
                else "TTL-based keys expire"
            )
            issues.append(
                error(
                    "cache.miss_unrouted",
                    f"{name} can miss ({what}) but its 'miss' port is not connected, so missed "
                    "reads would have nowhere to go.",
                    node_id=node.id,
                    hint="Connect the cache's 'miss' port to the database that holds the data.",
                )
            )
        if not config.can_miss() and miss_edges:
            issues.append(
                warning(
                    "cache.miss_unused",
                    f"{name} has a 100% hit ratio, so its miss link is never used.",
                    node_id=node.id,
                    edge_id=miss_edges[0].id,
                )
            )
        if config.hit_model == "ttl_keyspace" and not config.populate_on_miss:
            issues.append(
                warning(
                    "cache.never_fills",
                    f"{name} uses the TTL model but does not populate on miss, so it never fills "
                    "and every read misses.",
                    node_id=node.id,
                    path="populateOnMiss",
                )
            )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return CacheRuntime(ctx, node, config)
