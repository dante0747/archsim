"""Cache used cache-aside (Redis, Valkey, Memcached, CDN edge, Varnish): hits return
immediately, misses continue to the data store linked from the ``miss`` port.

The ``technology`` field is a label; presets fill in illustrative defaults (executors, command
time, hit ratio) for each technology."""

from __future__ import annotations

import math
from collections import OrderedDict
from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.components.resources import (
    CPU_CONTENTION,
    RESOURCES,
    Cores,
    core_utilization,
    cpu_field,
    memory_field,
)
from archsim_engine.distributions import Distribution, Exponential
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
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
    technology: Literal["redis", "valkey", "memcached", "cdn", "varnish", "generic"] = Field(
        default="redis",
        description=(
            "Technology label. It does not change behaviour by itself; presets set executors, "
            "command time and hit ratio to illustrative defaults for that technology."
        ),
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
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
    # -- resources -------------------------------------------------------------------------
    cpu_cores: float | None = cpu_field("cache node", CPU_CONTENTION)
    memory_gib: float | None = memory_field(
        "cache node",
        "80% of it holds keys. TTL model: when it is full the least recently used key is evicted. "
        "Fixed hit ratio: checked against the workload's keyspace.",
    )
    item_size_kb: float = Field(
        default=1.0,
        gt=0,
        le=1_048_576,
        description="Average size of one cached key and value, with overhead (KiB). Only used when memory is set.",
        json_schema_extra=ui(label="Item size", unit="KiB", group=RESOURCES, assumption=True),
    )
    cost_per_hour: float = Field(
        default=0.1,
        ge=0,
        le=100_000,
        description="Price of the cache per hour, used for cost estimates only. Illustrative default.",
        json_schema_extra=ui(label="Cost per hour", unit="$/h", group="Cost", assumption=True),
    )

    def can_miss(self) -> bool:
        return self.hit_model == "ttl_keyspace" or self.hit_ratio < 1.0

    def capacity_keys(self) -> int | None:
        """Keys that fit in 80% of memory, or None when memory is not set."""
        if self.memory_gib is None:
            return None
        return max(1, math.floor(0.8 * self.memory_gib * 1024 * 1024 / self.item_size_kb))


class CacheRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: CacheConfig) -> None:
        super().__init__(ctx, node, config, config.concurrency)
        self.pool = WorkerPool(
            ctx.env, config.concurrency, config.queue_capacity, config.queue_timeout_ms, self.m
        )
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.hit_rng = ctx.rng(f"node:{node.id}:hits")
        self.ttl_ms = config.ttl_seconds * 1000.0
        self.ttl_mode = config.hit_model == "ttl_keyspace"
        # With memory set the TTL keyspace is bounded: least recently used keys are evicted.
        self.capacity = config.capacity_keys() if self.ttl_mode else None
        self.expiry: dict[int, float] = OrderedDict() if self.capacity is not None else {}
        self.core_busy = Gauge(ctx.tl) if config.cpu_cores is not None else None
        self.cores = Cores(ctx.env, config.cpu_cores, config.concurrency, self.core_busy)

    def _command(self) -> Generator[simpy.Event, Any, str | None]:
        """Execute one cache command on an executor. Returns a rejection reason or None."""
        env = self.env
        tok, reason = yield from self.pool.acquire()
        if tok is None:
            return reason or "rejected"
        start = env.now
        st = self.sample_time(self.config.service_time_ms, self.rng)
        yield from self.cores.run(st)
        self.pool.release(tok)
        self.m.service.record(env.now, st)
        self.m.hold.record(env.now, env.now - start)
        return None

    def _is_hit(self, req: Request) -> bool:
        if self.ttl_mode:
            exp = self.expiry.get(req.key)
            hit = exp is not None and exp > self.env.now
            if hit and self.capacity is not None:
                self.expiry.move_to_end(req.key)  # type: ignore[attr-defined]
            return hit
        return self.hit_rng.random() < self.config.hit_ratio

    def _fill(self, key: int) -> None:
        self.expiry[key] = self.env.now + self.ttl_ms
        if self.capacity is not None:
            self.expiry.move_to_end(key)  # type: ignore[attr-defined]
            while len(self.expiry) > self.capacity:
                self.expiry.popitem(last=False)  # type: ignore[call-arg]
                self.m.count("evictions", self.env.now)

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        if self.core_busy is not None:
            self.core_busy.finalize(t_end)

    def resource_capacity(
        self, hold_ms: float | None, service_ms: float | None
    ) -> dict[str, float]:
        cores = self.config.cpu_cores
        if cores is None or not service_ms:
            return {}
        return {"cpu": cores * 1000.0 / service_ms}

    def extra_summary(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            out["cpuCoreUtilization"] = core_utilization(self.core_busy, self.cores.n)
        if self.capacity is not None:
            out["capacityKeys"] = self.capacity
            out["keysCached"] = len(self.expiry)
        return out

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
                    self._fill(req.key)
        m.depart(env.now, outcome, req)
        return outcome


class CacheModel(ComponentModel):
    type = "cache"
    display_name = "Cache"
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
        "Memory (when set, TTL model): key capacity from memory and item size, LRU eviction",
        "CPU cores (when set): commands queue for a core when executors outnumber cores",
    )
    not_modeled = (
        "LFU and other eviction policies; memory for the fixed-ratio model (checked, not simulated)",
        "Thundering herd / request coalescing on misses",
        "Replication, persistence and cluster sharding",
        "CDN geography (edge-to-user latency belongs on the incoming link)",
    )
    presets = (
        Preset(
            "redis",
            "Redis",
            "redis",
            "In-memory",
            "Single Redis node: commands run on one thread, sub-millisecond. Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "redis",
                "concurrency": 1,
                "costPerHour": 0.1,
            },
            ("in-memory", "key-value"),
        ),
        Preset(
            "valkey",
            "Valkey",
            "valkey",
            "In-memory",
            "Redis-compatible; multi-threaded I/O lets a few commands proceed in parallel. "
            "Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "valkey",
                "concurrency": 4,
                "costPerHour": 0.1,
            },
            ("in-memory", "key-value"),
        ),
        Preset(
            "memcached",
            "Memcached",
            "memcached",
            "In-memory",
            "Multi-threaded, simple get/set cache. Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "memcached",
                "concurrency": 8,
                "serviceTimeMs": {"kind": "exponential", "mean": 0.04},
                "costPerHour": 0.09,
            },
            ("in-memory", "key-value"),
        ),
        Preset(
            "cdn",
            "CDN edge cache",
            "cdn",
            "Edge cache",
            "Edge cache in front of the origin: huge parallelism, a few ms per request, most reads "
            "served at the edge. Put user-to-edge latency on the incoming link. Illustrative "
            "defaults.",
            {
                "technology": "cdn",
                "concurrency": 1024,
                "queueCapacity": 100000,
                "hitRatio": 0.9,
                "serviceTimeMs": {"kind": "lognormal", "median": 1.5, "p95": 4},
                "costPerHour": 0.2,
            },
            ("edge", "http", "static"),
        ),
        Preset(
            "varnish",
            "Varnish",
            "varnish",
            "HTTP accelerator",
            "Reverse-proxy HTTP cache in front of the application. Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 8,
                "technology": "varnish",
                "concurrency": 256,
                "hitRatio": 0.85,
                "serviceTimeMs": {"kind": "lognormal", "median": 0.3, "p95": 1.2},
                "costPerHour": 0.08,
            },
            ("http", "reverse-proxy"),
        ),
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
        cap = config.capacity_keys()
        if cap is not None and config.hit_model == "fixed_ratio":
            keys = view.workload.key_count
            if keys > cap:
                need = keys * config.item_size_kb / (1024 * 1024) / 0.8
                issues.append(
                    warning(
                        "cache.keyspace_exceeds_memory",
                        f"{name} holds about {cap:,} keys in {config.memory_gib:g} GiB but the "
                        f"workload uses {keys:,}; the {config.hit_ratio:.0%} hit ratio may be "
                        f"optimistic (all keys need about {need:,.1f} GiB).",
                        node_id=node.id,
                        path="memoryGib",
                        hint="Add memory, or use the TTL model to simulate eviction.",
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
