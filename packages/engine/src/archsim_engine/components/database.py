"""Database: relational primaries, replica sets, sharded clusters, leaderless and managed stores.

One component type covers many technologies, and the technology decides how it behaves (see
``archsim_engine.technology``): PostgreSQL locks rows, waits for the write-ahead log and replays
its log on replicas with a single worker; MongoDB waits for a majority of the replica set and
serves reads from the primary; DynamoDB meters capacity units per partition; Elasticsearch sends
every search to every shard. Each of these is a setting in the "Technology behaviour" group whose
default is *auto* (the technology's own default); set it to override.

Topology at run time::

    shard 0: primary [+ replica 1 .. n]     each instance: connection pool + wait queue
    shard 1: primary [+ replica 1 .. n]       (+ tickets, + CPU cores when set)
    ...

Request path (write)::

    shard by key -> throttles -> connection -> [write ticket] -> [row lock] -> execute
      -> [log flush, group commit] -> release lock -> [wait for replica acks] -> release

Request path (read)::

    shard by key, or every shard (scatter) -> pick primary/replica -> throttles -> connection
      -> [read ticket] -> [wait for a writer's lock] -> execute -> [disk reads on a buffer miss]
"""

from __future__ import annotations

import heapq
import math
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
    instance_type_field,
    memory_field,
    storage_field,
)
from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.explain import formula, num, pct, zipf_share
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge
from archsim_engine.ratelimit import TokenBucket
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    OK,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    WorkerPool,
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import DATABASE_PROFILES, Profile, resolve

DatabaseTechnology = Literal[
    "postgresql",
    "mysql",
    "mariadb",
    "sqlserver",
    "oracle",
    "cockroachdb",
    "yugabytedb",
    "mongodb",
    "cassandra",
    "scylladb",
    "dynamodb",
    "couchbase",
    "elasticsearch",
    "opensearch",
    "generic",
]

BEHAVIOUR = "Technology behaviour"
OnOff = Literal["auto", "on", "off"]
DYNAMO = {"technology": "dynamodb"}

# DynamoDB documented limits (see the partition_limits / capacity_units traits).
PARTITION_READ_UNITS = 3000.0
PARTITION_WRITE_UNITS = 1000.0
PARTITION_GIB = 10.0
ON_DEMAND_READ_UNITS = 12_000.0
ON_DEMAND_WRITE_UNITS = 4000.0
BURST_SECONDS = 300.0
BUFFER_CACHE_SHARE = 0.75
"""Share of a database's memory that caches hot data (illustrative)."""

# Documentation behind preset numbers (see Preset.sources).
_RAFT = "https://docs.cockroachlabs.com/docs/stable/architecture/replication-layer"
_ES_POOLS = (
    "https://www.elastic.co/docs/reference/elasticsearch/configuration-refe"
    "rence/thread-pool-settings"
)


class DatabaseConfig(CamelModel):
    technology: DatabaseTechnology = Field(
        default="postgresql",
        description=(
            "The database product. It sets how the database behaves (locks, commit, "
            "replication, read routing, partition limits...), shown under Technology behaviour, "
            "where every setting can be overridden. Presets also fill in typical numbers."
        ),
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    max_connections: int = Field(
        default=10,
        ge=1,
        le=100_000,
        description=(
            "Queries executing concurrently on one instance (connection pool size / backend "
            "workers). Every primary and replica in every shard has its own limit."
        ),
        json_schema_extra=ui(label="Max connections per instance", group="Capacity", headline=True),
    )
    read_service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=5.0, p95=20.0),
        description="Execution time of a read query on one instance (ms).",
        json_schema_extra=ui(
            label="Read query time", unit="ms", group="Performance", assumption=True, headline=True
        ),
    )
    write_service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=10.0, p95=40.0),
        description=(
            "Execution time of a write on one instance, before the log flush and replica "
            "acknowledgements (ms)."
        ),
        json_schema_extra=ui(
            label="Write query time", unit="ms", group="Performance", assumption=True
        ),
    )
    read_replicas: int = Field(
        default=0,
        ge=0,
        le=15,
        description=(
            "Copies per shard that replay the primary's writes (replicas, secondaries). "
            "Writes always go to the primary; which instances serve reads depends on 'Reads "
            "served by'."
        ),
        json_schema_extra=ui(label="Read replicas", group="Topology", headline=True),
    )
    shards: int = Field(
        default=1,
        ge=1,
        le=256,
        description=(
            "Partitions of the data, each with its own primary (and replicas). Requests are routed "
            "by hashing the key, so popular keys concentrate load on one shard. DynamoDB-style "
            "capacity units derive the partition count instead."
        ),
        json_schema_extra=ui(label="Shards (partitions)", group="Topology"),
    )
    replication_factor: int = Field(
        default=1,
        ge=1,
        le=7,
        description=(
            "Copies of each item kept by a leaderless or consensus store (e.g. Cassandra, "
            "distributed SQL). Combined with the consistency level it decides how many replica "
            "responses an operation waits for."
        ),
        json_schema_extra=ui(label="Replication factor", group="Topology"),
    )
    consistency: Literal["one", "quorum", "all"] = Field(
        default="one",
        description=(
            "Replica responses an operation waits for when the replication factor is above 1: "
            "one, a majority (quorum) or all. Latency becomes the k-th fastest of the replicas."
        ),
        json_schema_extra=ui(label="Consistency level", group="Topology"),
    )
    throughput_limit_rps: float | None = Field(
        default=None,
        gt=0,
        le=1_000_000,
        description=(
            "Provisioned throughput per shard (operations/s, bursts up to one second's worth). "
            "Requests above it are throttled. Empty = no limit."
        ),
        json_schema_extra=ui(
            label="Throughput limit per shard", unit="ops/s", group="Capacity", assumption=True
        ),
    )
    queue_capacity: int = Field(
        default=200,
        ge=0,
        le=100_000,
        description="Queries that may wait for a connection on one instance; more are rejected.",
        json_schema_extra=ui(label="Wait queue limit", group="Capacity"),
    )
    queue_timeout_ms: float | None = Field(
        default=1000.0,
        gt=0,
        le=600_000,
        description="Connection acquire timeout (ms). Queries waiting longer are rejected. Empty = no limit.",
        json_schema_extra=ui(label="Acquire timeout", unit="ms", group="Capacity"),
    )
    # -- technology behaviour (auto = the technology's default) ---------------------------
    row_locks: OnOff = Field(
        default="auto",
        description=(
            "Writes to the same key (row or document) take a lock: a second write waits until "
            "the first commits, holding its connection meanwhile. Auto: on for relational "
            "databases and MongoDB, off for Cassandra, DynamoDB and search engines."
        ),
        json_schema_extra=ui(label="Row locks on writes", group=BEHAVIOUR),
    )
    reads_wait_for_writes: OnOff = Field(
        default="auto",
        description=(
            "A read of a key that is being written waits for the writer's lock (SQL Server's "
            "default READ COMMITTED). Auto: on for SQL Server; MVCC databases read the last "
            "committed version instead."
        ),
        json_schema_extra=ui(label="Reads wait for writers", group=BEHAVIOUR),
    )
    commit_flush: OnOff = Field(
        default="auto",
        description=(
            "Each write waits for the write-ahead log (or journal) to reach disk. Commits that "
            "arrive during a flush share the next one (group commit). Auto: on for relational "
            "databases and MongoDB."
        ),
        json_schema_extra=ui(label="Commits wait for the log flush", group=BEHAVIOUR),
    )
    commit_flush_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=1.0, p95=3.0),
        description="Time of one log flush (fsync) on the storage (ms). Network block storage is slower than local NVMe.",
        json_schema_extra=ui(label="Log flush time", unit="ms", group=BEHAVIOUR, assumption=True),
    )
    replication: Literal["auto", "async", "majority", "all", "none"] = Field(
        default="auto",
        description=(
            "How writes reach the replicas. async: the primary commits, replicas replay later "
            "(replication lag, stale reads). majority: the write also waits until a majority of "
            "the copies have it (MongoDB's default write concern). all: it waits for every "
            "replica (Elasticsearch). none: replicas only serve reads."
        ),
        json_schema_extra=ui(label="Replica writes", group=BEHAVIOUR),
    )
    replica_apply_workers: int | None = Field(
        default=None,
        ge=1,
        le=256,
        description=(
            "Workers a replica uses to replay writes. Empty = the technology's default "
            "(PostgreSQL 1, MySQL 4, MongoDB 16)."
        ),
        json_schema_extra=ui(label="Replica apply workers", group=BEHAVIOUR),
    )
    replica_apply_factor: float = Field(
        default=0.5,
        gt=0,
        le=5,
        description="Replaying a write on a replica costs this share of the write's execution time.",
        json_schema_extra=ui(
            label="Replay cost vs. write time", group=BEHAVIOUR, assumption=True, step=0.05
        ),
    )
    replication_delay_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=0.5, p95=2.0),
        description="Network delay shipping a write (and an acknowledgement) between primary and replicas (ms).",
        json_schema_extra=ui(
            label="Replication network delay", unit="ms", group=BEHAVIOUR, assumption=True
        ),
    )
    read_from: Literal["auto", "all", "primary", "replicas"] = Field(
        default="auto",
        description=(
            "Which instances serve reads: all (round-robin over primary and replicas), the "
            "primary only (MongoDB's default read preference) or the replicas only "
            "(secondaryPreferred). Auto follows the technology."
        ),
        json_schema_extra=ui(label="Reads served by", group=BEHAVIOUR),
    )
    read_tickets: int | None = Field(
        default=None,
        ge=1,
        le=100_000,
        description=(
            "Read operations one instance runs at once, beyond the connection limit (MongoDB "
            "WiredTiger: 128). Empty = the technology's default (none for most)."
        ),
        json_schema_extra=ui(label="Concurrent reads (tickets)", group=BEHAVIOUR),
    )
    write_tickets: int | None = Field(
        default=None,
        ge=1,
        le=100_000,
        description="Write operations one instance runs at once (MongoDB WiredTiger: 128). Empty = technology default.",
        json_schema_extra=ui(label="Concurrent writes (tickets)", group=BEHAVIOUR),
    )
    scatter_reads: float | None = Field(
        default=None,
        ge=0,
        le=1,
        description=(
            "Share of reads that do not target one shard and go to every shard, waiting for the "
            "slowest (searches; MongoDB queries without the shard key). Empty = technology "
            "default (Elasticsearch: every search)."
        ),
        json_schema_extra=ui(label="Reads sent to every shard", group=BEHAVIOUR, step=0.05),
    )
    router_overhead_ms: float | None = Field(
        default=None,
        ge=0,
        le=1000,
        description=(
            "Routing or merging time added per operation when sharded or scattering (mongos, "
            "Elasticsearch coordinating node) (ms). Empty = technology default."
        ),
        json_schema_extra=ui(label="Router / coordinator time", unit="ms", group=BEHAVIOUR),
    )
    sstables_per_read: float | None = Field(
        default=None,
        ge=1,
        le=50,
        description=(
            "Log-structured stores (Cassandra, Scylla): disk reads a buffer-cache miss costs, "
            "because a row can be spread over several SSTables. Empty = technology default."
        ),
        json_schema_extra=ui(label="SSTables per cold read", group=BEHAVIOUR, assumption=True),
    )
    capacity_units: OnOff = Field(
        default="auto",
        description=(
            "Meter reads and writes in capacity units with per-partition limits (3,000 read and "
            "1,000 write units per second each), DynamoDB-style. Auto: on for DynamoDB."
        ),
        json_schema_extra=ui(label="Capacity units and partition limits", group=BEHAVIOUR),
    )
    capacity_mode: Literal["on_demand", "provisioned"] = Field(
        default="on_demand",
        description=(
            "On-demand: the table serves up to 12,000 read and 4,000 write units per second "
            "from the start. Provisioned: the units below, plus up to 300 s of banked burst."
        ),
        json_schema_extra=ui(label="Capacity mode", group=BEHAVIOUR, visible_when=DYNAMO),
    )
    read_capacity_units: float = Field(
        default=1000,
        gt=0,
        le=10_000_000,
        description="Provisioned read capacity units per second for the table.",
        json_schema_extra=ui(
            label="Read capacity units", unit="RCU", group=BEHAVIOUR, visible_when=DYNAMO
        ),
    )
    write_capacity_units: float = Field(
        default=500,
        gt=0,
        le=10_000_000,
        description="Provisioned write capacity units per second for the table.",
        json_schema_extra=ui(
            label="Write capacity units", unit="WCU", group=BEHAVIOUR, visible_when=DYNAMO
        ),
    )
    item_size_kb: float = Field(
        default=1.0,
        gt=0,
        le=400,
        description="Average item size (KB): a read costs one unit per 4 KB, a write one per 1 KB.",
        json_schema_extra=ui(
            label="Item size", unit="KB", group=BEHAVIOUR, visible_when=DYNAMO, assumption=True
        ),
    )
    consistent_reads: Literal["eventual", "strong"] = Field(
        default="eventual",
        description="Eventually consistent reads cost half a unit (the default); strongly consistent reads a full unit.",
        json_schema_extra=ui(label="Read consistency", group=BEHAVIOUR, visible_when=DYNAMO),
    )
    # -- resources (per instance: each shard's primary and each replica) ------------------
    cpu_cores: float | None = cpu_field(
        "instance", CPU_CONTENTION + " Only the CPU share of each query needs a core."
    )
    cpu_share: float = Field(
        default=0.5,
        ge=0,
        le=1,
        description=(
            "Share of query time that needs a CPU core; the rest is I/O, locks and network. Only "
            "used when CPU is set."
        ),
        json_schema_extra=ui(
            label="CPU share of query time", group=RESOURCES, assumption=True, step=0.05
        ),
    )
    memory_gib: float | None = memory_field(
        "instance",
        "With a working set, 75% of it is buffer cache: reads of data that does not fit go to disk.",
    )
    working_set_gib: float | None = Field(
        default=None,
        gt=0,
        le=10_000_000,
        description=(
            "Size of the data that is read often (GiB), across all shards. With memory set, the "
            "share of reads served from memory is min(1, 75% of memory / working set per shard)."
        ),
        json_schema_extra=ui(label="Hot working set", unit="GiB", group=RESOURCES, assumption=True),
    )
    disk_read_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=1.0, p95=4.0),
        description="Extra time of a read that misses the buffer cache and goes to disk (ms).",
        json_schema_extra=ui(label="Disk read time", unit="ms", group=RESOURCES, assumption=True),
    )
    storage_gib: float | None = storage_field(
        "instance", "Checked against the data size: each instance stores its shard's data."
    )
    instance_type: str | None = instance_type_field("instance")
    data_size_gib: float | None = Field(
        default=None,
        gt=0,
        le=100_000_000,
        description="Total data stored (GiB), across all shards. Used to check storage (and DynamoDB partitions).",
        json_schema_extra=ui(label="Data size", unit="GiB", group=RESOURCES, assumption=True),
    )
    cost_per_hour: float = Field(
        default=0.35,
        ge=0,
        le=100_000,
        description=(
            "Price of one instance (primary or replica, per shard) per hour, used for cost "
            "estimates only. Illustrative default; use your own price."
        ),
        json_schema_extra=ui(
            label="Cost per instance-hour", unit="$/h", group="Cost", assumption=True
        ),
    )

    # -- derived -----------------------------------------------------------------------------
    def profile(self) -> Profile:
        return DATABASE_PROFILES.get(self.technology, DATABASE_PROFILES["generic"])

    def setting(self, key: str, fallback: Any = None) -> Any:
        return resolve(getattr(self, key, None), self.profile(), key, fallback)

    def is_on(self, key: str) -> bool:
        return self.setting(key, "off") == "on"

    def metered(self) -> bool:
        return self.is_on("capacity_units")

    def partitions(self) -> int:
        """Shards, or with capacity units the partitions the table's capacity and size need."""
        if not self.metered():
            return self.shards
        if self.capacity_mode == "provisioned":
            rcu, wcu = self.read_capacity_units, self.write_capacity_units
        else:
            rcu, wcu = ON_DEMAND_READ_UNITS, ON_DEMAND_WRITE_UNITS
        by_capacity = math.ceil(rcu / PARTITION_READ_UNITS + wcu / PARTITION_WRITE_UNITS - 1e-9)
        by_size = math.ceil(self.data_size_gib / PARTITION_GIB) if self.data_size_gib else 1
        return max(1, by_capacity, by_size)

    def read_units(self) -> float:
        blocks = math.ceil(self.item_size_kb / 4.0 - 1e-9)
        return max(1, blocks) * (1.0 if self.consistent_reads == "strong" else 0.5)

    def write_units(self) -> float:
        return float(max(1, math.ceil(self.item_size_kb - 1e-9)))

    def instances(self) -> int:
        """Instances that are paid for: per shard, the larger of replicas+1 and the RF."""
        return self.partitions() * max(1 + self.read_replicas, self.replication_factor)

    def buffer_hit_ratio(self) -> float | None:
        """Share of reads served from memory, or None when memory or working set is not set."""
        if self.memory_gib is None or self.working_set_gib is None:
            return None
        per_shard = self.working_set_gib / self.partitions()
        return min(1.0, BUFFER_CACHE_SHARE * self.memory_gib / per_shard)

    def wait_for(self) -> int:
        """Replica responses an operation waits for (k of RF)."""
        rf = self.replication_factor
        if self.consistency == "all":
            return rf
        if self.consistency == "quorum":
            return rf // 2 + 1
        return 1

    def replica_acks(self) -> int:
        """Replicas a write waits for: majority of the copies (minus the primary), or all."""
        mode = self.setting("replication", "none")
        r = self.read_replicas
        if r == 0 or mode not in ("majority", "all"):
            return 0
        if mode == "all":
            return r
        return (1 + r) // 2  # majority of (1 + r) members, the primary being one of them

    def uses_key_state(self) -> bool:
        """True when behaviour depends on request keys (sharding, locks, staleness, partitions)."""
        return (
            self.partitions() > 1
            or self.is_on("row_locks")
            or self.is_on("reads_wait_for_writes")
            or (self.read_replicas > 0 and self.setting("replication", "none") != "none")
        )


def shard_of(key: int, shards: int) -> int:
    """Deterministic key -> shard mapping (multiplicative hash, so key ranks do not cluster)."""
    return (((key + 1) * 2654435761) & 0xFFFFFFFF) % shards


class LogFlusher:
    """Group commit: one flush at a time; commits that arrive while a flush runs share the next
    flush. Each commit waits for the end of the flush that covers it."""

    __slots__ = ("batch_sizes", "env", "flushes", "flushing", "pending", "sample")

    def __init__(self, env: simpy.Environment, sample: Any) -> None:
        self.env = env
        self.sample = sample
        self.flushing = False
        self.pending: list[simpy.Event] = []
        self.flushes = 0
        self.batch_sizes = 0

    def commit(self) -> simpy.Event:
        ev = self.env.event()
        self.pending.append(ev)
        if not self.flushing:
            self.flushing = True
            self.env.process(self._run())
        return ev

    def _run(self) -> Generator[simpy.Event, Any, None]:
        while self.pending:
            batch, self.pending = self.pending, []
            self.flushes += 1
            self.batch_sizes += len(batch)
            d = self.sample()
            if d > 0:
                yield self.env.timeout(d)
            for ev in batch:
                ev.succeed()
        self.flushing = False


class LockTable:
    """Exclusive locks per key, created on demand and dropped when free (bounded memory)."""

    __slots__ = ("env", "locks")

    def __init__(self, env: simpy.Environment) -> None:
        self.env = env
        self.locks: dict[int, simpy.Resource] = {}

    def acquire(self, key: int) -> tuple[simpy.Resource, Any]:
        r = self.locks.get(key)
        if r is None:
            r = self.locks[key] = simpy.Resource(self.env, capacity=1)
        return r, r.request()

    def held(self, key: int) -> simpy.Resource | None:
        r = self.locks.get(key)
        return r if r is not None and (r.count or r.queue) else None

    def release(self, key: int, r: simpy.Resource, tok: Any) -> None:
        r.release(tok)
        if not r.count and not r.queue:
            self.locks.pop(key, None)


class ApplyJob:
    __slots__ = ("acks", "acks_needed", "committed_at", "cost_ms", "done", "key", "seq")

    def __init__(
        self, seq: int, key: int, committed_at: float, cost_ms: float, acks_needed: int, done: Any
    ) -> None:
        self.seq = seq
        self.key = key
        self.committed_at = committed_at
        self.cost_ms = cost_ms
        self.acks_needed = acks_needed
        self.acks = 0
        self.done = done  # event triggered once ``acks_needed`` replicas applied it, or None


class Replica:
    """A replica's replay pipeline: writes arrive in commit order and are replayed by a few
    workers. ``applied`` is the highest sequence number with every earlier write replayed."""

    __slots__ = ("applied", "completed", "pending", "queue", "workers")

    def __init__(self, env: simpy.Environment) -> None:
        self.queue: simpy.Store = simpy.Store(env)
        self.applied = 0
        self.completed: list[int] = []  # min-heap of replayed sequence numbers beyond ``applied``
        self.pending: dict[int, float] = {}  # seq -> commit time, for writes not yet replayed
        self.workers = 0

    def lag(self, now: float) -> float:
        if not self.pending:
            return 0.0
        oldest = self.pending.get(self.applied + 1)
        if oldest is None:
            oldest = min(self.pending.values())
        return max(0.0, now - oldest)

    def mark(self, seq: int) -> None:
        self.pending.pop(seq, None)
        heapq.heappush(self.completed, seq)
        while self.completed and self.completed[0] == self.applied + 1:
            heapq.heappop(self.completed)
            self.applied += 1


class Instance:
    __slots__ = ("cores", "index", "pool", "read_tickets", "replica", "shard", "write_tickets")

    def __init__(
        self,
        shard: int,
        index: int,
        pool: WorkerPool,
        cores: Cores,
        read_tickets: simpy.Resource | None,
        write_tickets: simpy.Resource | None,
        replica: Replica | None,
    ) -> None:
        self.shard = shard
        self.index = index
        self.pool = pool
        self.cores = cores
        self.read_tickets = read_tickets
        self.write_tickets = write_tickets
        self.replica = replica


class DatabaseRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: DatabaseConfig) -> None:
        env = ctx.env
        self.parts = config.partitions()
        per_shard = 1 + config.read_replicas
        super().__init__(ctx, node, config, self.parts * per_shard * config.max_connections)
        c = config
        self.row_locks = c.is_on("row_locks")
        self.reads_wait = c.is_on("reads_wait_for_writes")
        self.flush_on = c.is_on("commit_flush")
        self.replication = c.setting("replication", "none") if c.read_replicas else "none"
        self.apply_workers = int(c.setting("replica_apply_workers", 4) or 4)
        self.read_from = c.setting("read_from", "all")
        self.scatter = float(c.setting("scatter_reads", 0.0) or 0.0) if self.parts > 1 else 0.0
        self.router_ms = float(c.setting("router_overhead_ms", 0.0) or 0.0)
        self.sstables = float(c.setting("sstables_per_read", 1.0) or 1.0)
        self.metered = c.metered()
        self.acks_needed = c.replica_acks()
        rt_ = c.setting("read_tickets")
        wt_ = c.setting("write_tickets")
        self.core_busy = Gauge(ctx.tl) if c.cpu_cores is not None else None
        self.groups: list[list[Instance]] = []
        for s in range(self.parts):
            group = []
            for i in range(per_shard):
                replica = Replica(env) if i > 0 and self.replication != "none" else None
                group.append(
                    Instance(
                        s,
                        i,
                        WorkerPool(
                            env, c.max_connections, c.queue_capacity, c.queue_timeout_ms, self.m
                        ),
                        Cores(env, c.cpu_cores, c.max_connections, self.core_busy),
                        simpy.Resource(env, capacity=int(rt_)) if rt_ else None,
                        simpy.Resource(env, capacity=int(wt_)) if wt_ else None,
                        replica,
                    )
                )
            self.groups.append(group)
        self.locks = (
            [LockTable(env) for _ in range(self.parts)]
            if (self.row_locks or self.reads_wait)
            else None
        )
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.flush_rng = ctx.rng(f"node:{node.id}:flush")
        self.repl_rng = ctx.rng(f"node:{node.id}:replication")
        self.flushers = (
            [
                LogFlusher(env, lambda: self.sample_time(c.commit_flush_ms, self.flush_rng))
                for _ in range(self.parts)
            ]
            if self.flush_on
            else None
        )
        self.hit = c.buffer_hit_ratio()
        self.buffer_rng = ctx.rng(f"node:{node.id}:buffer")
        self.scatter_rng = ctx.rng(f"node:{node.id}:scatter")
        self.rr = [0] * self.parts
        self.buckets = (
            [TokenBucket(c.throughput_limit_rps) for _ in range(self.parts)]
            if c.throughput_limit_rps
            else None
        )
        self.k = c.wait_for()
        # replication bookkeeping
        self.seq = [0] * self.parts
        self.last_write: list[dict[int, int]] = [{} for _ in range(self.parts)]
        self.lag_max = [0.0] * ctx.tl.n
        self.lag_seen = [False] * ctx.tl.n
        if self.replication != "none":
            for group in self.groups:
                for inst in group[1:]:
                    for _ in range(self.apply_workers):
                        env.process(self._apply_worker(inst))
            env.process(self._lag_sampler())
        # capacity units
        if self.metered:
            self.part_read = [
                TokenBucket(PARTITION_READ_UNITS, PARTITION_READ_UNITS) for _ in range(self.parts)
            ]
            self.part_write = [
                TokenBucket(PARTITION_WRITE_UNITS, PARTITION_WRITE_UNITS) for _ in range(self.parts)
            ]
            if c.capacity_mode == "provisioned":
                rcu, wcu = c.read_capacity_units, c.write_capacity_units
                self.table_read = TokenBucket(rcu, rcu * BURST_SECONDS)
                self.table_write = TokenBucket(wcu, wcu * BURST_SECONDS)
            else:
                self.table_read = TokenBucket(ON_DEMAND_READ_UNITS, ON_DEMAND_READ_UNITS)
                self.table_write = TokenBucket(ON_DEMAND_WRITE_UNITS, ON_DEMAND_WRITE_UNITS)

    # -- helpers ------------------------------------------------------------------------------
    def _op_time(self, dist: Distribution) -> float:
        rf = self.config.replication_factor
        if rf <= 1:
            return self.sample_time(dist, self.rng)
        samples = sorted(self.sample_time(dist, self.rng) for _ in range(rf))
        return samples[self.k - 1]

    def _pick(self, s: int, op: str) -> Instance:
        group = self.groups[s]
        if op == "write" or len(group) == 1 or self.read_from == "primary":
            return group[0]
        if self.read_from == "replicas":
            n = len(group) - 1
            i = self.rr[s] % n
            self.rr[s] = i + 1
            return group[1 + i]
        i = self.rr[s]
        self.rr[s] = (i + 1) % len(group)
        return group[i]

    def _throttle(self, req: Request, s: int, op: str) -> bool:
        """True when a throughput limit refuses this operation on shard ``s``."""
        now = self.env.now
        if self.buckets is not None and not self.buckets[s].try_take(now):
            return True
        if not self.metered:
            return False
        cfg: DatabaseConfig = self.config
        if op == "read":
            units = cfg.read_units()
            if not self.part_read[s].try_take(now, units) or not self.table_read.try_take(
                now, units
            ):
                return True
            self.m.count("readUnitsX10", now, round(units * 10))
        else:
            units = cfg.write_units()
            if not self.part_write[s].try_take(now, units) or not self.table_write.try_take(
                now, units
            ):
                return True
            self.m.count("writeUnitsX10", now, round(units * 10))
        return False

    def _run(self, inst: Instance, st: float) -> Generator[simpy.Event, Any, None]:
        cfg: DatabaseConfig = self.config
        if cfg.cpu_cores is None:
            if st > 0:
                yield self.env.timeout(st)
            return
        cpu = st * cfg.cpu_share
        yield from inst.cores.run(cpu)
        if st - cpu > 0:
            yield self.env.timeout(st - cpu)

    # -- request handling ----------------------------------------------------------------------
    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        m.arrive(env.now)
        if self.router_ms > 0 and (self.parts > 1 or self.scatter > 0):
            yield env.timeout(self.router_ms)
        scatter = (
            req.op == "read"
            and self.scatter > 0
            and (self.scatter >= 1.0 or self.scatter_rng.random() < self.scatter)
        )
        if scatter:
            m.count("scatterReads", env.now)
            t0 = env.now
            procs = [env.process(self._operation(req, s, record=s == 0)) for s in range(self.parts)]
            yield simpy.AllOf(env, procs)
            results = [p.value for p in procs]
            reason = next((r for r in results if r is not None), None)
            if req.trace is not None:
                span(req, self.id, "scatter", t0, env.now, f"{self.parts} shards, slowest decides")
        else:
            s = shard_of(req.key, self.parts) if self.parts > 1 else 0
            reason = yield from self._operation(req, s, record=True)
        if reason is not None:
            m.reject(reason, env.now)
            if req.trace is not None:
                span(req, self.id, "rejected", env.now, env.now, reason)
            return rejected(reason, self.id)
        m.count("reads" if req.op == "read" else "writes", env.now)
        m.depart(env.now, OK, req)
        return OK

    def _operation(
        self, req: Request, s: int, record: bool
    ) -> Generator[simpy.Event, Any, str | None]:
        """One operation on shard ``s``. Returns a rejection reason or None."""
        env = self.env
        m = self.m
        cfg: DatabaseConfig = self.config
        op = req.op
        if self.parts > 1 and record:
            m.count(f"shard{s}", env.now)
        if self._throttle(req, s, op):
            return "throttled"
        inst = self._pick(s, op)
        tok, reason = yield from inst.pool.acquire(req, self.id)
        if tok is None:
            return reason or "rejected"
        start = env.now
        ticket_pool = inst.write_tickets if op == "write" else inst.read_tickets
        ticket = None
        if ticket_pool is not None:
            t0 = env.now
            ticket = ticket_pool.request()
            yield ticket
            if env.now > t0:
                m.mean("ticketWaitMs", env.now, env.now - t0)
                span(req, self.id, "tickets", t0, env.now, "waiting for a WiredTiger ticket")
        lock = None
        if op == "write" and self.row_locks and self.locks is not None:
            t0 = env.now
            res, ltok = self.locks[s].acquire(req.key)
            yield ltok
            wait = env.now - t0
            m.mean("lockWaitMs", env.now, wait)
            if wait > 0:
                m.count("lockWaits", env.now)
                span(req, self.id, "lock", t0, env.now, f"row lock on key {req.key}")
            lock = (res, ltok)
        elif op == "read" and self.reads_wait and self.locks is not None:
            held = self.locks[s].held(req.key)
            if held is not None:
                t0 = env.now
                rtok = held.request()
                yield rtok
                held.release(rtok)
                m.count("readsBlocked", env.now)
                span(req, self.id, "lock", t0, env.now, "waiting for a writer's lock")
                if not held.count and not held.queue:
                    self.locks[s].locks.pop(req.key, None)
        t_exec = env.now
        dist = cfg.read_service_time_ms if op == "read" else cfg.write_service_time_ms
        st = self._op_time(dist)
        yield from self._run(inst, st)
        if (
            op == "read"
            and self.hit is not None
            and self.hit < 1.0
            and self.buffer_rng.random() >= self.hit
        ):
            reads = self._disk_reads()
            m.count("diskReads", env.now, reads)
            for _ in range(reads):
                d = cfg.disk_read_ms.sample(self.buffer_rng)
                if d > 0:
                    yield env.timeout(d)
        span(req, self.id, "execute", t_exec, env.now, "read" if op == "read" else "write")
        job: ApplyJob | None = None
        if op == "write":
            if self.flushers is not None:
                t0 = env.now
                yield self.flushers[s].commit()
                m.mean("commitWaitMs", env.now, env.now - t0)
                span(req, self.id, "commit", t0, env.now, "write-ahead log flush")
            if lock is not None:
                self.locks[s].release(req.key, lock[0], lock[1])  # type: ignore[index]
            if self.replication != "none":
                job = self._replicate(s, req.key, st)
        elif inst.replica is not None:
            last = self.last_write[s].get(req.key)
            if last is not None and last > inst.replica.applied:
                m.count("staleReads", env.now)
                span(
                    req,
                    self.id,
                    "stale",
                    env.now,
                    env.now,
                    "replica had not replayed the latest write",
                )
        if ticket is not None and ticket_pool is not None:
            ticket_pool.release(ticket)
        if job is not None and job.done is not None:
            t0 = env.now
            yield job.done
            d = cfg.replication_delay_ms.sample(self.repl_rng)
            if d > 0:
                yield env.timeout(d)
            m.mean("replicaAckMs", env.now, env.now - t0)
            span(
                req,
                self.id,
                "replication",
                t0,
                env.now,
                f"waiting for {job.acks_needed} replica(s)",
            )
        inst.pool.release(tok)
        if record:
            m.service.record(env.now, st)
            m.hold.record(env.now, env.now - start)
            if inst.index > 0:
                m.count("replicaReads", env.now)
        return None

    def _disk_reads(self) -> int:
        n = self.sstables
        whole = int(n)
        if n > whole and self.buffer_rng.random() < n - whole:
            whole += 1
        return max(1, whole)

    # -- replication -------------------------------------------------------------------------
    def _replicate(self, s: int, key: int, write_ms: float) -> ApplyJob:
        env = self.env
        cfg: DatabaseConfig = self.config
        self.seq[s] += 1
        seq = self.seq[s]
        self.last_write[s][key] = seq
        done = env.event() if self.acks_needed else None
        job = ApplyJob(
            seq, key, env.now, write_ms * cfg.replica_apply_factor, self.acks_needed, done
        )
        for inst in self.groups[s][1:]:
            assert inst.replica is not None
            inst.replica.pending[seq] = env.now
        env.process(self._ship(s, job))
        return job

    def _ship(self, s: int, job: ApplyJob) -> Generator[simpy.Event, Any, None]:
        d = self.config.replication_delay_ms.sample(self.repl_rng)
        if d > 0:
            yield self.env.timeout(d)
        for inst in self.groups[s][1:]:
            assert inst.replica is not None
            inst.replica.queue.put(job)

    def _apply_worker(self, inst: Instance) -> Generator[simpy.Event, Any, None]:
        env = self.env
        rep = inst.replica
        assert rep is not None
        while True:
            job: ApplyJob = yield rep.queue.get()
            yield from self._run(inst, job.cost_ms)
            rep.mark(job.seq)
            self.m.count("replayed", env.now)
            if job.done is not None:
                job.acks += 1
                if job.acks == job.acks_needed:
                    job.done.succeed()

    def _lag_sampler(self) -> Generator[simpy.Event, Any, None]:
        env = self.env
        tl = self.ctx.tl
        step = max(50.0, tl.bucket_ms / 4.0)
        while True:
            now = env.now
            lag = 0.0
            for group in self.groups:
                for inst in group[1:]:
                    if inst.replica is not None:
                        lag = max(lag, inst.replica.lag(now))
            i = tl.index(now)
            self.lag_max[i] = max(self.lag_max[i], lag)
            self.lag_seen[i] = True
            yield env.timeout(step)

    # -- report hooks ------------------------------------------------------------------------
    def background_busy(self) -> bool:
        return any(
            inst.replica is not None and inst.replica.pending
            for group in self.groups
            for inst in group[1:]
        )

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        if self.core_busy is not None:
            self.core_busy.finalize(t_end)

    def resource_capacity(
        self, hold_ms: float | None, service_ms: float | None
    ) -> dict[str, float]:
        cfg: DatabaseConfig = self.config
        if cfg.cpu_cores is None or not service_ms or cfg.cpu_share <= 0:
            return {}
        # replicas add CPU only when they serve reads (not with reads on the primary, MongoDB's default)
        serving = 1 if self.read_from == "primary" else 1 + cfg.read_replicas
        instances = self.parts * serving
        return {"cpu": cfg.cpu_cores * instances * 1000.0 / (service_ms * cfg.cpu_share)}

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        m = self.m
        out: dict[str, list[float | None]] = {}
        for name, key in (
            ("lockWaitMs", "lockWaitMs"),
            ("commitWaitMs", "commitWaitMs"),
            ("ticketWaitMs", "ticketWaitMs"),
            ("replicaAckMs", "replicaAckMs"),
        ):
            if key in m.means:
                out[name] = m.means[key].series()
        if self.replication != "none":
            out["replicationLagMs"] = [
                v if seen else None for v, seen in zip(self.lag_max, self.lag_seen, strict=True)
            ]
        for name, counter in (("staleReadRps", "staleReads"), ("diskReadRps", "diskReads")):
            if counter in m.extra:
                out[name] = list(m.extra[counter].rate_series())
        if self.metered:
            for name, counter in (
                ("consumedReadUnits", "readUnitsX10"),
                ("consumedWriteUnits", "writeUnitsX10"),
            ):
                c = m.extra.get(counter)
                out[name] = [v / 10.0 for v in c.rate_series()] if c else [0.0] * self.ctx.tl.n
        if "throttled" in m.rejected:
            out["throttledRps"] = list(m.rejected["throttled"].rate_series())
        return out

    def extra_summary(self) -> dict[str, Any]:
        cfg: DatabaseConfig = self.config
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            instances = self.parts * (1 + cfg.read_replicas)
            out["cpuCoreUtilization"] = core_utilization(
                self.core_busy, self.groups[0][0].cores.n * instances
            )
        if self.hit is not None:
            out["bufferHitRatio"] = round(self.hit, 4)
        if self.parts != cfg.shards:
            out["partitions"] = self.parts
        for key in ("lockWaitMs", "commitWaitMs", "ticketWaitMs", "replicaAckMs"):
            acc = self.m.means.get(key)
            if acc is not None and acc.window_mean() is not None:
                out[f"mean{key[0].upper()}{key[1:]}"] = round(acc.window_mean() or 0.0, 4)
        if self.flushers is not None:
            flushes = sum(f.flushes for f in self.flushers)
            batch = sum(f.batch_sizes for f in self.flushers)
            out["commitsPerFlush"] = round(batch / flushes, 3) if flushes else None
        if self.replication != "none":
            i0 = math.ceil(self.ctx.tl.window_start / self.ctx.tl.bucket_ms)
            i1 = max(i0 + 1, math.floor(self.ctx.tl.window_end / self.ctx.tl.bucket_ms))
            lags = [v for v, s in zip(self.lag_max[i0:i1], self.lag_seen[i0:i1], strict=True) if s]
            out["maxReplicationLagMs"] = round(max(lags), 3) if lags else None
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: DatabaseConfig = self.config
        active = {
            "row_locks": self.row_locks,
            "document_locks": self.row_locks,
            "mvcc_reads": not self.reads_wait,
            "locking_reads": self.reads_wait,
            "group_commit": self.flush_on,
            "async_replicas": self.replication == "async",
            "parallel_replica": self.replication == "async",
            "majority_writes": self.replication == "majority" and cfg.read_replicas > 0,
            "sync_replicas": self.replication == "all" and cfg.read_replicas > 0,
            "primary_reads": self.read_from == "primary",
            "tickets": cfg.setting("read_tickets") is not None,
            "mongos": self.parts > 1,
            "fan_out": self.scatter > 0,
            "partition_limits": self.metered,
            "capacity_units": self.metered,
            "burst": self.metered and cfg.capacity_mode == "provisioned",
            "read_amplification": self.sstables > 1,
        }
        return [t.to_dict() for t in cfg.profile().traits if active.get(t.id, True)]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: DatabaseConfig = self.config
        out: list[dict[str, Any]] = []
        wl = self.ctx.shared.get("workload")
        if self.hit is not None and cfg.working_set_gib and cfg.memory_gib:
            out.append(
                formula(
                    "buffer_hit",
                    "Buffer cache hit ratio",
                    f"hit = min(1, {BUFFER_CACHE_SHARE:g} × memory ÷ (working set ÷ shards))",
                    f"min(1, {BUFFER_CACHE_SHARE:g} × {num(cfg.memory_gib)} GiB ÷ ({num(cfg.working_set_gib)} GiB ÷ {self.parts}))",
                    self.hit,
                    "",
                    note="A read that misses pays a disk read"
                    + (
                        f" for each of about {num(self.sstables)} SSTables."
                        if self.sstables > 1
                        else "."
                    ),
                    metric="diskReadRps",
                )
            )
        writes = summary.get("counters", {}).get("writes", 0)
        window_s = self.ctx.tl.window_ms / 1000.0
        wps = writes / window_s if window_s else 0.0
        if self.row_locks and wl is not None and wps > 0:
            share = zipf_share(wl.key_count, wl.key_skew)
            w_mean = cfg.write_service_time_ms.expected()
            flush = cfg.commit_flush_ms.expected() * 1.5 if self.flush_on else 0.0
            busy = wps * share * (w_mean + flush) / 1000.0
            out.append(
                formula(
                    "hot_row",
                    "Hottest row's lock",
                    "lock busy = writes/s × hottest key's share × (write time + commit wait) ÷ 1000",
                    f"{num(wps, 2)} × {pct(share)} × ({num(w_mean, 2)} + {num(flush, 2)}) ms ÷ 1000",
                    busy,
                    "",
                    note=(
                        f"The hottest of {wl.key_count:,} keys gets {pct(share)} of writes "
                        f"(Zipf s = {wl.key_skew:g}). As its lock nears 100% busy, writes to "
                        "it queue and hold connections while they wait."
                    ),
                    metric="lockWaitMs",
                )
            )
        if self.flushers is not None and summary.get("commitsPerFlush"):
            out.append(
                formula(
                    "group_commit",
                    "Group commit",
                    "commits per flush = commits ÷ log flushes",
                    f"over the run, with a mean flush of {num(cfg.commit_flush_ms.expected(), 2)} ms",
                    summary["commitsPerFlush"],
                    "commits",
                    note=(
                        "A commit waits for the flush in progress plus its own, about 1.5 "
                        "flushes on average; batching keeps write throughput from being capped "
                        "at 1000 ÷ flush time per second."
                    ),
                    metric="commitWaitMs",
                )
            )
        if self.replication == "async" and cfg.read_replicas:
            apply_ms = cfg.write_service_time_ms.expected() * cfg.replica_apply_factor
            per_shard = wps / self.parts if self.parts else wps
            cap = self.apply_workers * 1000.0 / apply_ms if apply_ms else None
            out.append(
                formula(
                    "replay_capacity",
                    "Replica replay capacity",
                    "replay capacity = apply workers × 1000 ÷ replay time per write",
                    f"{self.apply_workers} × 1000 ÷ ({num(cfg.write_service_time_ms.expected(), 2)} ms × {num(cfg.replica_apply_factor, 2)})",
                    cap,
                    "writes/s",
                    note=(
                        f"Writes per shard: {num(per_shard, 2)}/s. When writes outpace replay "
                        "the lag grows without bound and replica reads turn stale."
                    ),
                    metric="replicationLagMs",
                )
            )
        if self.acks_needed:
            out.append(
                formula(
                    "replica_acks",
                    "Write acknowledgement",
                    "acks needed = majority of (1 + replicas) − 1"
                    if self.replication == "majority"
                    else "acks needed = replicas",
                    f"1 + {cfg.read_replicas} members",
                    float(self.acks_needed),
                    "replicas",
                    note="Each write also waits for that many replicas to replay it, plus the network round trip.",
                    metric="replicaAckMs",
                )
            )
        if self.metered:
            if cfg.capacity_mode == "provisioned":
                rcu, wcu = cfg.read_capacity_units, cfg.write_capacity_units
            else:
                rcu, wcu = ON_DEMAND_READ_UNITS, ON_DEMAND_WRITE_UNITS
            out.append(
                formula(
                    "partitions",
                    "Partitions",
                    "partitions = max(⌈RCU ÷ 3000 + WCU ÷ 1000⌉, ⌈size ÷ 10 GB⌉)",
                    f"max(⌈{num(rcu)} ÷ 3000 + {num(wcu)} ÷ 1000⌉, ⌈{num(cfg.data_size_gib or 0)} ÷ 10⌉)",
                    float(self.parts),
                    "partitions",
                    note="Each partition serves at most 3,000 read and 1,000 write units per second, whatever the table's total.",
                    metric="throttledRps",
                )
            )
            out.append(
                formula(
                    "unit_cost",
                    "Capacity units per request",
                    "read = ⌈item ÷ 4 KB⌉ × (½ if eventually consistent),  write = ⌈item ÷ 1 KB⌉",
                    f"item {num(cfg.item_size_kb)} KB, {cfg.consistent_reads} reads",
                    cfg.read_units(),
                    "read units",
                    note=f"Each write costs {num(cfg.write_units())} write unit(s).",
                    metric="consumedReadUnits",
                )
            )
        if self.scatter > 0:
            out.append(
                formula(
                    "fan_out",
                    "Search fan-out",
                    "shard queries per second = reads/s × share scattered × shards",
                    f"{num(summary.get('arrivalRps'), 2)} × {pct(self.scatter)} × {self.parts}",
                    (summary.get("arrivalRps") or 0) * self.scatter * self.parts,
                    "queries/s",
                    note="A scattered read finishes when the slowest shard answers, so its latency is the maximum of the shards' times.",
                )
            )
        return out


_ILLUSTRATIVE = "Numbers are illustrative assumptions, not vendor benchmarks."


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


class DatabaseModel(ComponentModel):
    type = "database"
    display_name = "Database"
    category = "storage"
    description = (
        "Executes read and write queries on a limited number of connections per instance, and "
        "behaves like the technology you pick: row locks and group commit for relational "
        "databases, majority writes for MongoDB, capacity units for DynamoDB, fan-out searches "
        "for Elasticsearch, replicas that lag."
    )
    icon = "database"
    config_model = DatabaseConfig
    technology_profiles = DATABASE_PROFILES
    ports = (PortSpec("in", "in", "Queries", "Read and write queries."),)
    modeled = (
        "Connection limit with a bounded wait queue and acquire timeout, per instance",
        "Technology behaviour: row/document locks, group commit, replica replay with lag and stale reads, majority or all-replica write acknowledgements, read routing, concurrency tickets",
        "Key-hash sharding; a skewed (Zipf) keyspace creates hot shards and hot rows",
        "Scatter-gather reads across every shard (searches, queries without the shard key)",
        "Capacity units with per-partition limits and burst capacity (DynamoDB-style)",
        "Replication factor + consistency level: latency is the k-th fastest of RF replicas",
        "CPU cores (when set): the CPU share of each query (and of replica replay) needs a core",
        "Buffer cache (memory and working set set): cold reads pay disk reads (several SSTables for LSM stores)",
        "Storage (with a data size): checked per instance",
    )
    not_modeled = (
        "Deadlocks, lock escalation and range (gap) locks; vacuum, checkpoints and compaction",
        "Failover and leader election",
        "Query planning, cross-shard transactions and rebalancing",
        "Adaptive capacity moving throughput between DynamoDB partitions",
    )
    presets = (
        Preset(
            "postgresql",
            "PostgreSQL",
            "postgresql",
            "Relational",
            "Single PostgreSQL primary behind an application connection pool: row locks, group "
            "commit on the write-ahead log. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "postgresql",
                "maxConnections": 20,
                "readServiceTimeMs": _lognormal(4, 15),
                "writeServiceTimeMs": _lognormal(7, 28),
                "costPerHour": 0.35,
            },
            ("relational", "sql", "acid"),
        ),
        Preset(
            "postgresql-replicas",
            "PostgreSQL + 2 read replicas",
            "postgresql",
            "Relational",
            "Primary for writes, two streaming replicas that share the reads and replay the "
            "write-ahead log on one worker each (lag under heavy writes). " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "postgresql",
                "maxConnections": 20,
                "readReplicas": 2,
                "readServiceTimeMs": _lognormal(4, 15),
                "writeServiceTimeMs": _lognormal(7, 28),
                "costPerHour": 0.35,
            },
            ("relational", "sql", "read-scaling"),
        ),
        Preset(
            "mysql",
            "MySQL",
            "mysql",
            "Relational",
            "Single MySQL (InnoDB) primary: row locks, binary log group commit. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "mysql",
                "maxConnections": 20,
                "readServiceTimeMs": _lognormal(4, 16),
                "writeServiceTimeMs": _lognormal(8, 32),
                "costPerHour": 0.33,
            },
            ("relational", "sql", "acid"),
        ),
        Preset(
            "sqlserver",
            "SQL Server",
            "sqlserver",
            "Relational",
            "Single SQL Server instance with the default READ COMMITTED locking: reads of a row "
            "being written wait. The hourly price includes a licence. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 32,
                "storageGib": 250,
                "technology": "sqlserver",
                "maxConnections": 30,
                "readServiceTimeMs": _lognormal(5, 20),
                "writeServiceTimeMs": _lognormal(9, 36),
                "costPerHour": 0.9,
            },
            ("relational", "sql", "acid"),
        ),
        Preset(
            "cockroachdb",
            "CockroachDB-style distributed SQL",
            "cockroachdb",
            "Distributed SQL",
            "Three ranges/shards, each replicated three times with quorum (Raft) writes: survives a "
            "node loss, pays a consensus round trip. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 300,
                "technology": "cockroachdb",
                "maxConnections": 20,
                "shards": 3,
                "replicationFactor": 3,
                "consistency": "quorum",
                "readServiceTimeMs": _lognormal(6, 25),
                "writeServiceTimeMs": _lognormal(13, 55),
                "costPerHour": 0.5,
            },
            ("relational", "sql", "distributed", "consensus"),
            sources={"replicationFactor": _RAFT},
        ),
        Preset(
            "mongodb-replica-set",
            "MongoDB replica set",
            "mongodb",
            "Document",
            "Primary plus two secondaries. Writes wait for a majority (the default write "
            "concern); reads use the secondaries (secondaryPreferred). " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 250,
                "technology": "mongodb",
                "maxConnections": 500,
                "queueCapacity": 1000,
                "readReplicas": 2,
                "readFrom": "replicas",
                "readServiceTimeMs": _lognormal(3, 12),
                "writeServiceTimeMs": _lognormal(5, 20),
                "costPerHour": 0.3,
            },
            ("nosql", "document", "read-scaling"),
        ),
        Preset(
            "mongodb-sharded",
            "MongoDB sharded cluster",
            "mongodb",
            "Document",
            "Four shards, each a replica set, behind mongos. Operations with the shard key go to "
            "one shard; 10% of reads lack it and are broadcast to all four. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 250,
                "technology": "mongodb",
                "maxConnections": 500,
                "queueCapacity": 1000,
                "shards": 4,
                "readReplicas": 2,
                "scatterReads": 0.1,
                "readServiceTimeMs": _lognormal(3, 12),
                "writeServiceTimeMs": _lognormal(5, 20),
                "costPerHour": 0.3,
            },
            ("nosql", "document", "sharded"),
        ),
        Preset(
            "cassandra",
            "Cassandra",
            "cassandra",
            "Wide-column",
            "Six token ranges, replication factor 3, QUORUM reads and writes. LSM storage makes "
            "writes cheaper than reads. " + _ILLUSTRATIVE,
            {
                "cpuCores": 8,
                "memoryGib": 32,
                "storageGib": 1000,
                "technology": "cassandra",
                "maxConnections": 32,
                "shards": 6,
                "replicationFactor": 3,
                "consistency": "quorum",
                "readServiceTimeMs": _lognormal(4, 18),
                "writeServiceTimeMs": _lognormal(1.5, 6),
                "costPerHour": 0.4,
            },
            ("nosql", "wide-column", "leaderless"),
        ),
        Preset(
            "dynamodb-style",
            "DynamoDB-style key-value store",
            "dynamodb",
            "Key-value",
            "Managed key-value table in on-demand mode: capacity units with per-partition limits, "
            "so a hot key is throttled. The hourly price stands in for the request charges. "
            + _ILLUSTRATIVE,
            {
                "technology": "dynamodb",
                "maxConnections": 5000,
                "queueCapacity": 1000,
                "capacityMode": "on_demand",
                "readServiceTimeMs": _lognormal(3, 8),
                "writeServiceTimeMs": _lognormal(5, 12),
                "costPerHour": 0.5,
            },
            ("nosql", "key-value", "managed"),
        ),
        Preset(
            "elasticsearch",
            "Elasticsearch",
            "elasticsearch",
            "Search",
            "Three primary shards with one replica each. Every search hits all three shards; "
            "the search thread pool (7 threads for 4 cores) and its 1,000-request queue "
            "(Elasticsearch 8 defaults) reject the excess with 429. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 32,
                "storageGib": 500,
                "technology": "elasticsearch",
                "maxConnections": 7,
                "queueCapacity": 1000,
                "shards": 3,
                "readReplicas": 1,
                "readServiceTimeMs": _lognormal(12, 50),
                "writeServiceTimeMs": _lognormal(20, 100),
                "costPerHour": 0.45,
            },
            ("search", "full-text"),
            sources={"maxConnections": _ES_POOLS, "queueCapacity": _ES_POOLS},
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.partitions() * (1 + config.read_replicas) * config.max_connections)

    def uses_keys(self, config: Any) -> bool:
        return bool(config.uses_key_state())

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        name = node.label or node.id
        if config.consistency != "one" and config.replication_factor == 1:
            issues.append(
                warning(
                    "database.consistency_unused",
                    f"{name} uses consistency '{config.consistency}' with a replication factor of "
                    "1, so the consistency level has no effect.",
                    node_id=node.id,
                    path="consistency",
                    hint="Raise the replication factor (e.g. 3) or set consistency to 'one'.",
                )
            )
        parts = config.partitions()
        if config.data_size_gib is not None and config.storage_gib is not None:
            per = config.data_size_gib / parts
            if per > config.storage_gib:
                issues.append(
                    error(
                        "database.storage_full",
                        f"{name} stores about {per:,.0f} GiB per instance ({config.data_size_gib:,.0f} "
                        f"GiB over {parts} shard(s)) but has {config.storage_gib:,.0f} GiB of "
                        "storage.",
                        node_id=node.id,
                        path="storageGib",
                        hint="Add storage, add shards, or lower the data size.",
                    )
                )
            elif per > 0.85 * config.storage_gib:
                issues.append(
                    warning(
                        "database.storage_nearly_full",
                        f"{name} would use {per / config.storage_gib:.0%} of its storage per "
                        "instance; leave room for growth, indexes and compaction.",
                        node_id=node.id,
                        path="storageGib",
                    )
                )
        if config.working_set_gib is not None and config.memory_gib is None:
            issues.append(
                warning(
                    "database.working_set_without_memory",
                    f"{name} has a hot working set but no memory, so the buffer cache is not "
                    "modelled.",
                    node_id=node.id,
                    path="memoryGib",
                    hint="Set the memory per instance.",
                )
            )
        if parts > view.workload.key_count:
            issues.append(
                warning(
                    "database.shards_exceed_keys",
                    f"{name} has {parts} shards but the workload only touches "
                    f"{view.workload.key_count} distinct keys, so some shards stay empty.",
                    node_id=node.id,
                    path="shards",
                )
            )
        if config.metered() and config.shards != 1:
            issues.append(
                warning(
                    "database.shards_from_capacity",
                    f"{name} meters capacity units, so its partitions follow from capacity and size "
                    f"({parts}); the shards setting ({config.shards}) is ignored.",
                    node_id=node.id,
                    path="shards",
                )
            )
        if (
            config.technology == "postgresql"
            and config.cpu_cores
            and config.max_connections > 4 * config.cpu_cores
        ):
            issues.append(
                warning(
                    "database.too_many_connections",
                    f"{name} runs up to {config.max_connections} queries at once on "
                    f"{config.cpu_cores:g} vCPU. PostgreSQL throughput usually peaks at a few "
                    "active connections per core; more mostly adds contention.",
                    node_id=node.id,
                    path="maxConnections",
                    hint="Pool connections (e.g. PgBouncer) so active queries stay near 2-4 per core.",
                )
            )
        if config.setting("read_from") == "replicas" and config.read_replicas == 0:
            issues.append(
                warning(
                    "database.no_replicas_to_read",
                    f"{name} reads from replicas but has none, so reads go to the primary.",
                    node_id=node.id,
                    path="readFrom",
                )
            )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return DatabaseRuntime(ctx, node, config)
