"""Cache (Redis, Valkey, Memcached, CDN edge, Varnish). Hits return immediately. A miss is
handled one of two ways:

- **cache-aside** (no ``miss`` link): the cache answers "miss" and the calling service reads the
  data store itself over a link set to ``readsOnMiss``, then writes the value back here;
- **read-through** (a ``miss`` link): the cache asks the data store on that link and repopulates
  itself.

The technology sets behaviour (see ``archsim_engine.technology``): Redis and Valkey execute
commands on one thread per shard however many cores they have, evict by policy (noeviction makes
writes fail when memory is full) and stall while forking for snapshots; Memcached runs worker
threads in parallel. Settings in the "Technology behaviour" group default to *auto*."""

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
    busy_cores_series,
    core_utilization,
    cpu_field,
    instance_type_field,
    memory_field,
)
from archsim_engine.distributions import Distribution, Exponential
from archsim_engine.explain import formula, num, pct
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    MISS,
    OK,
    Handler,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    WorkerPool,
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import CACHE_PROFILES, Profile, resolve

BEHAVIOUR = "Technology behaviour"
KEY_MEMORY_SHARE = 0.8
"""Share of a cache node's memory that holds keys; the rest is overhead (illustrative)."""

# Documentation behind preset numbers (see Preset.sources).
_REDIS_LATENCY = (
    "https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/latency/"
)
_VALKEY_THREADS = "https://valkey.io/blog/unlock-one-million-rps/"
_MEMCACHED_CONFIG = "https://docs.memcached.org/serverguide/configuring/"


class CacheConfig(CamelModel):
    technology: Literal["redis", "valkey", "memcached", "cdn", "varnish", "generic"] = Field(
        default="redis",
        description=(
            "The cache product. It sets how commands execute (one thread for Redis and Valkey, "
            "worker threads for Memcached) and the eviction behaviour, shown under Technology "
            "behaviour. Presets also fill in typical numbers."
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
        description=(
            "Commands executed in parallel per shard (Memcached threads, CDN or Varnish "
            "parallelism). Ignored when commands run single-threaded (Redis, Valkey)."
        ),
        json_schema_extra=ui(label="Command executors", group="Capacity"),
    )
    shards: int = Field(
        default=1,
        ge=1,
        le=1000,
        description=(
            "Cache nodes the keys are split across (Redis Cluster shards, Memcached servers). "
            "Each has its own executors and memory; keys hash to a shard, so a hot key loads one."
        ),
        json_schema_extra=ui(label="Shards (cluster nodes)", group="Capacity"),
    )
    single_threaded: Literal["auto", "on", "off"] = Field(
        default="auto",
        description="Execute commands one at a time per shard, whatever the cores. Auto: on for Redis and Valkey.",
        json_schema_extra=ui(label="Single-threaded commands", group=BEHAVIOUR),
    )
    eviction_policy: Literal["auto", "allkeys_lru", "noeviction"] = Field(
        default="auto",
        description=(
            "When memory is full (TTL model with memory set): allkeys_lru evicts the least "
            "recently used key; noeviction (Redis's own default) refuses new keys with an "
            "out-of-memory error, so missed reads cannot be cached. Auto: allkeys_lru."
        ),
        json_schema_extra=ui(label="Eviction policy", group=BEHAVIOUR),
    )
    persistence: Literal["auto", "none", "rdb"] = Field(
        default="auto",
        description=(
            "rdb: snapshot to disk every interval; forking the process blocks commands for "
            "about the fork time per GiB of data. Auto: none (cache use)."
        ),
        json_schema_extra=ui(label="Persistence", group=BEHAVIOUR),
    )
    snapshot_interval_s: float = Field(
        default=60.0,
        gt=0,
        le=86_400,
        description="Time between RDB snapshots (s).",
        json_schema_extra=ui(
            label="Snapshot interval",
            unit="s",
            group=BEHAVIOUR,
            visible_when={"persistence": "rdb"},
        ),
    )
    fork_ms_per_gib: float = Field(
        default=10.0,
        ge=0,
        le=10_000,
        description="Command stall per GiB of data while forking for a snapshot (ms). Typical Linux hosts: around 10; some virtual machines much more.",
        json_schema_extra=ui(
            label="Fork stall per GiB",
            unit="ms",
            group=BEHAVIOUR,
            assumption=True,
            visible_when={"persistence": "rdb"},
        ),
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
    instance_type: str | None = instance_type_field("cache node")
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

    def profile(self) -> Profile:
        return CACHE_PROFILES.get(self.technology, CACHE_PROFILES["generic"])

    def setting(self, key: str, fallback: Any = None) -> Any:
        return resolve(getattr(self, key, None), self.profile(), key, fallback)

    def executors(self) -> int:
        """Commands one shard executes at once."""
        return 1 if self.setting("single_threaded", "off") == "on" else self.concurrency

    def capacity_keys(self) -> int | None:
        """Keys that fit in KEY_MEMORY_SHARE of memory, or None when memory is not set."""
        if self.memory_gib is None:
            return None
        return max(
            1, math.floor(KEY_MEMORY_SHARE * self.memory_gib * 1024 * 1024 / self.item_size_kb)
        )


class CacheShard:
    __slots__ = ("capacity", "cores", "expiry", "paused_until", "pool")

    def __init__(self, pool: WorkerPool, cores: Cores, capacity: int | None, lru: bool) -> None:
        self.pool = pool
        self.cores = cores
        self.capacity = capacity
        self.expiry: dict[int, float] = OrderedDict() if lru else {}
        self.paused_until = 0.0


class CacheRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: CacheConfig) -> None:
        self.execs = config.executors()
        super().__init__(ctx, node, config, self.execs * config.shards)
        env = ctx.env
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.hit_rng = ctx.rng(f"node:{node.id}:hits")
        self.ttl_ms = config.ttl_seconds * 1000.0
        self.ttl_mode = config.hit_model == "ttl_keyspace"
        self.policy = config.setting("eviction_policy", "allkeys_lru")
        self.persistence = config.setting("persistence", "none")
        # With memory set the TTL keyspace is bounded per shard.
        capacity = config.capacity_keys() if self.ttl_mode else None
        self.core_busy = Gauge(ctx.tl) if config.cpu_cores is not None else None
        self.shards = [
            CacheShard(
                WorkerPool(env, self.execs, config.queue_capacity, config.queue_timeout_ms, self.m),
                Cores(env, config.cpu_cores, self.execs, self.core_busy),
                capacity,
                capacity is not None,
            )
            for _ in range(config.shards)
        ]
        self.capacity = capacity * config.shards if capacity is not None else None
        self.mem_used: list[float | None] = [None] * ctx.tl.n
        self.fork_stall = [0.0] * ctx.tl.n
        if self.capacity is not None:
            env.process(self._memory_sampler())
        if self.persistence == "rdb":
            for sh in self.shards:
                env.process(self._snapshots(sh))

    def _shard(self, key: int) -> CacheShard:
        n = len(self.shards)
        if n == 1:
            return self.shards[0]
        return self.shards[(((key + 1) * 2654435761) & 0xFFFFFFFF) % n]

    def _command(
        self, sh: CacheShard, req: Request | None = None
    ) -> Generator[simpy.Event, Any, str | None]:
        """Execute one cache command on an executor. Returns a rejection reason or None."""
        env = self.env
        tok, reason = yield from sh.pool.acquire(req, self.id)
        if tok is None:
            return reason or "rejected"
        start = env.now
        if sh.paused_until > env.now:
            t0 = env.now
            yield env.timeout(sh.paused_until - env.now)
            if req is not None:
                span(req, self.id, "fork", t0, env.now, "snapshot fork blocked the command thread")
        st = self.sample_time(self.config.service_time_ms, self.rng)
        yield from sh.cores.run(st)
        sh.pool.release(tok)
        self.m.service.record(env.now, st)
        self.m.hold.record(env.now, env.now - start)
        return None

    def _is_hit(self, sh: CacheShard, req: Request) -> bool:
        if self.ttl_mode:
            exp = sh.expiry.get(req.key)
            hit = exp is not None and exp > self.env.now
            if hit and sh.capacity is not None:
                sh.expiry.move_to_end(req.key)  # type: ignore[attr-defined]
            return hit
        return self.hit_rng.random() < self.config.hit_ratio

    def _fill(self, sh: CacheShard, key: int) -> bool:
        """Cache ``key``. False when memory is full and the policy refuses new keys."""
        now = self.env.now
        full = sh.capacity is not None and key not in sh.expiry and len(sh.expiry) >= sh.capacity
        if full and self.policy == "noeviction":
            # drop expired keys first (Redis reclaims them); refuse if still full
            expired = [k for k, e in list(sh.expiry.items())[:64] if e <= now]
            for k in expired:
                sh.expiry.pop(k, None)
            if len(sh.expiry) >= (sh.capacity or 0):
                self.m.count("oomErrors", now)
                return False
        sh.expiry[key] = now + self.ttl_ms
        if sh.capacity is not None:
            sh.expiry.move_to_end(key)  # type: ignore[attr-defined]
            while len(sh.expiry) > sh.capacity:
                sh.expiry.popitem(last=False)  # type: ignore[call-arg]
                self.m.count("evictions", now)
        return True

    def _memory_sampler(self) -> Generator[simpy.Event, Any, None]:
        tl = self.ctx.tl
        step = max(50.0, tl.bucket_ms / 4.0)
        while True:
            i = tl.index(self.env.now)
            used = sum(len(sh.expiry) for sh in self.shards) / (self.capacity or 1)
            prev = self.mem_used[i]
            self.mem_used[i] = used if prev is None else max(prev, used)
            yield self.env.timeout(step)

    def _snapshots(self, sh: CacheShard) -> Generator[simpy.Event, Any, None]:
        cfg: CacheConfig = self.config
        env = self.env
        interval = cfg.snapshot_interval_s * 1000.0
        while True:
            yield env.timeout(interval)
            if self.ttl_mode:
                gib = len(sh.expiry) * cfg.item_size_kb / (1024 * 1024)
            else:
                gib = KEY_MEMORY_SHARE * (cfg.memory_gib or 0.0)
            stall = cfg.fork_ms_per_gib * gib
            if stall > 0:
                sh.paused_until = max(sh.paused_until, env.now + stall)
                self.m.count("forks", env.now)
                self.fork_stall[self.ctx.tl.index(env.now)] += stall

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
        usable = min(cores, float(self.execs)) * len(self.shards)
        return {"cpu": usable * 1000.0 / service_ms}

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {}
        if self.core_busy is not None and self.shards:
            out["cpuBusyCores"] = busy_cores_series(self.core_busy, self.shards[0].cores)
        if "evictions" in self.m.extra:
            out["evictionRps"] = list(self.m.extra["evictions"].rate_series())
        if self.capacity is not None:
            out["memoryUsedRatio"] = list(self.mem_used)
        if self.persistence == "rdb":
            out["forkPauseMs"] = list(self.fork_stall)
        return out

    def extra_summary(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            out["cpuCoreUtilization"] = core_utilization(
                self.core_busy, self.shards[0].cores.n * len(self.shards)
            )
        if self.capacity is not None:
            out["capacityKeys"] = self.capacity
            out["keysCached"] = sum(len(sh.expiry) for sh in self.shards)
        out["executorsPerShard"] = self.execs
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: CacheConfig = self.config
        active = {
            "single_thread": self.execs == 1 and cfg.setting("single_threaded") == "on",
            "eviction": self.ttl_mode and cfg.memory_gib is not None,
            "fork_stall": self.persistence == "rdb",
        }
        return [t.to_dict() for t in cfg.profile().traits if active.get(t.id, True)]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: CacheConfig = self.config
        out: list[dict[str, Any]] = []
        svc = cfg.service_time_ms.expected()
        cap = self.execs * len(self.shards) * 1000.0 / svc if svc else None
        out.append(
            formula(
                "command_capacity",
                "Command capacity",
                "capacity = shards × executors per shard × 1000 ÷ command time",
                f"{len(self.shards)} × {self.execs} × 1000 ÷ {num(svc, 3)} ms",
                cap,
                "commands/s",
                note=(
                    "Redis and Valkey run commands on one thread per shard: adding cores does "
                    "not raise this, adding shards does."
                    if cfg.setting("single_threaded") == "on"
                    else None
                ),
                metric="utilization",
            )
        )
        hits = summary.get("counters", {}).get("hits", 0)
        misses = summary.get("counters", {}).get("misses", 0)
        if hits + misses:
            out.append(
                formula(
                    "hit_ratio",
                    "Hit ratio",
                    "hit ratio = hits ÷ (hits + misses)",
                    f"{hits:,} ÷ ({hits:,} + {misses:,})",
                    hits / (hits + misses),
                    "",
                    note=(
                        f"Each miss also costs a read on the data store behind the cache "
                        f"({pct(misses / (hits + misses))} of reads)."
                    ),
                    metric="hitRatio",
                )
            )
        if self.capacity is not None:
            out.append(
                formula(
                    "key_capacity",
                    "Keys that fit",
                    f"keys = shards × {KEY_MEMORY_SHARE:.0%} of memory ÷ item size",
                    f"{len(self.shards)} × {KEY_MEMORY_SHARE:g} × {num(cfg.memory_gib)} GiB ÷ {num(cfg.item_size_kb)} KiB",
                    float(self.capacity),
                    "keys",
                    metric="memoryUsedRatio",
                )
            )
        return out

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        m.arrive(env.now)
        sh = self._shard(req.key)
        reason = yield from self._command(sh, req)
        if reason is not None:
            m.reject(reason, env.now)
            return rejected(reason, self.id)
        if req.op == "write":
            # cache-aside write path: invalidate the key
            if self.ttl_mode:
                sh.expiry.pop(req.key, None)
            m.count("invalidations", env.now)
            m.depart(env.now, OK, req)
            return OK
        if self._is_hit(sh, req):
            m.count("hits", env.now)
            span(req, self.id, "hit", env.now, env.now, "served from the cache")
            m.depart(env.now, OK, req)
            return OK
        m.count("misses", env.now)
        miss_edges = self.out_edges("miss")
        if not miss_edges:
            # cache-aside: the caller reads the data store itself and writes the value back
            span(
                req,
                self.id,
                "miss",
                env.now,
                env.now,
                "not cached: the caller reads the data store",
            )
            m.depart(env.now, MISS, req)
            return MISS
        span(req, self.id, "miss", env.now, env.now, "not cached: asking the data store")
        outcome = yield from self.ctx.call(miss_edges[0], req, self.id)
        if outcome.ok and self.config.populate_on_miss:
            r = yield from self._command(sh, req)
            if r is None and (not self.ttl_mode or self._fill(sh, req.key)):
                m.count("populates", env.now)
        m.depart(env.now, outcome, req)
        return outcome

    def populate_handler(self) -> Handler | None:
        """The write-back a cache-aside caller sends after reading the data store on a miss (one
        more command on this cache), or None when this cache does not populate on miss."""
        return self._populate if self.config.populate_on_miss else None

    def _populate(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        m.arrive(env.now)
        sh = self._shard(req.key)
        t0 = env.now
        reason = yield from self._command(sh, req)
        if reason is not None:
            m.reject(reason, env.now)
            return rejected(reason, self.id)
        if not self.ttl_mode or self._fill(sh, req.key):
            m.count("populates", env.now)
            span(req, self.id, "populate", t0, env.now, "value written back to the cache")
        m.depart(env.now, OK, req)
        return OK


class CacheModel(ComponentModel):
    type = "cache"
    display_name = "Cache"
    category = "storage"
    description = (
        "Reads that hit return from memory. On a miss, either the calling service reads the data "
        "store itself and writes the value back (cache-aside: leave the 'miss' port empty and set "
        "the service's link to the data store to 'Reads only on a cache miss'), or the cache asks "
        "the data store linked to its 'miss' port (read-through). Writes invalidate the key."
    )
    icon = "zap"
    config_model = CacheConfig
    technology_profiles = CACHE_PROFILES
    ports = (
        PortSpec("in", "in", "Commands", "Reads (lookups) and writes (invalidations)."),
        PortSpec(
            "miss",
            "out",
            "Miss",
            "Read-through: where the cache sends reads it does not hold (usually the database). "
            "Leave it empty for cache-aside, where the calling service reads the data store.",
            request_types=("read",),
            max_edges=1,
        ),
    )
    modeled = (
        "Hit/miss decision per read (fixed probability or TTL over a Zipf keyspace)",
        "Technology behaviour: single-threaded commands (Redis, Valkey) or worker threads (Memcached)",
        "Cluster shards: keys hash to a shard with its own executors and memory",
        "Cache-aside (the caller reads the data store and writes the value back) or read-through (the cache reads it through its miss link)",
        "Miss path latency added to the caller; repopulation cost",
        "Memory (when set, TTL model): key capacity per shard, LRU eviction or noeviction out-of-memory errors",
        "RDB snapshots (when on): fork stalls block commands for a time proportional to the data",
        "CPU cores (when set): commands queue for a core when executors outnumber cores",
    )
    not_modeled = (
        "LFU and other eviction policies; memory for the fixed-ratio model (checked, not simulated)",
        "Thundering herd / request coalescing on misses",
        "Replicas and failover; copy-on-write memory growth during snapshots",
        "CDN geography (edge-to-user latency belongs on the incoming link)",
    )
    presets = (
        Preset(
            "redis",
            "Redis",
            "redis",
            "In-memory",
            "Single Redis node: commands run on one thread, sub-millisecond; extra cores do "
            "not add command throughput. Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "redis",
                "concurrency": 1,
                "costPerHour": 0.1,
            },
            ("in-memory", "key-value"),
            sources={"concurrency": _REDIS_LATENCY},
        ),
        Preset(
            "valkey",
            "Valkey",
            "valkey",
            "In-memory",
            "Redis-compatible; I/O threads take socket work off the command thread, so each "
            "command is shorter, but commands still run one at a time. Illustrative defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "valkey",
                "concurrency": 1,
                "serviceTimeMs": {"kind": "exponential", "mean": 0.035},
                "costPerHour": 0.1,
            },
            ("in-memory", "key-value"),
            sources={"concurrency": _VALKEY_THREADS},
        ),
        Preset(
            "memcached",
            "Memcached",
            "memcached",
            "In-memory",
            "Multi-threaded get/set cache with its default 4 worker threads. Illustrative "
            "defaults.",
            {
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "memcached",
                "concurrency": 4,
                "serviceTimeMs": {"kind": "exponential", "mean": 0.04},
                "costPerHour": 0.09,
            },
            ("in-memory", "key-value"),
            sources={"concurrency": _MEMCACHED_CONFIG},
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
        return int(config.executors() * config.shards)

    def uses_keys(self, config: Any) -> bool:
        return bool(config.hit_model == "ttl_keyspace" or config.shards > 1)

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
        # cache-aside: callers that read the data store themselves on a miss
        readers = [
            e.source for e in view.in_edges.get(node.id, []) if "read" in e.config.request_types
        ]
        unhandled = [
            src
            for src in dict.fromkeys(readers)
            if not any(
                x.config.reads_on_miss and "read" in x.config.request_types
                for x in view.out_edges.get(src, [])
            )
            or view.nodes[src].type != "service"
        ]
        if config.can_miss() and not miss_edges and receives_reads and unhandled:
            what = (
                f"hit ratio {config.hit_ratio:.0%}"
                if config.hit_model == "fixed_ratio"
                else "TTL-based keys expire"
            )
            caller = view.nodes[unhandled[0]].label or unhandled[0]
            issues.append(
                error(
                    "cache.miss_unrouted",
                    f"{name} can miss ({what}) but nothing reads the data on a miss: its 'miss' "
                    f"port is not connected and {caller} has no link set to 'Reads only on a "
                    "cache miss'.",
                    node_id=node.id,
                    hint="Cache-aside: give the service a link to the database with 'Reads only "
                    "on a cache miss' on. Read-through: connect the cache's 'miss' port to the "
                    "database.",
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
                need = keys * config.item_size_kb / (1024 * 1024) / KEY_MEMORY_SHARE
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
        if config.concurrency > 1 and config.setting("single_threaded") == "on":
            issues.append(
                warning(
                    "cache.single_threaded",
                    f"{name} is {config.technology}, which executes commands on one thread, so "
                    f"its {config.concurrency} command executors count as 1 per shard.",
                    node_id=node.id,
                    path="concurrency",
                    hint="Add shards (cluster nodes) to run more commands in parallel.",
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
