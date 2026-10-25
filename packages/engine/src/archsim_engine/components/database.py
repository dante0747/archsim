"""Database: from a single relational primary to sharded, replicated and leaderless stores.

One component type covers many technologies. Behaviour always comes from the config values below
(connections, read replicas, shards, replication factor and consistency, provisioned throughput);
the ``technology`` field is a label, and technology presets only fill in illustrative defaults.

Topology at run time::

    shard 0: primary [+ read replica 1 .. n]     each instance: connection pool + wait queue
    shard 1: primary [+ read replica 1 .. n]
    ...

* Requests are routed to a shard by hashing the request key (keys follow the workload's Zipf
  popularity, so a skewed keyspace produces a hot shard).
* Writes go to the shard's primary; reads are spread round-robin over primary and replicas.
* With ``replicationFactor`` > 1 an operation holds its connection for the k-th fastest of RF
  independently sampled replica times (k = 1 / majority / RF for one / quorum / all).
* ``throughputLimitRps`` caps each shard (provisioned capacity); excess requests are throttled.
"""

from __future__ import annotations

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
    storage_field,
)
from archsim_engine.distributions import Distribution, LogNormal
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
)
from archsim_engine.schema import CamelModel, NodeSpec

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


class DatabaseConfig(CamelModel):
    technology: DatabaseTechnology = Field(
        default="postgresql",
        description=(
            "Technology label. It does not change behaviour by itself; presets set the values "
            "below to illustrative defaults for that technology."
        ),
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    max_connections: int = Field(
        default=10,
        ge=1,
        le=10_000,
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
        description="Execution time of a write query including commit (ms).",
        json_schema_extra=ui(
            label="Write query time", unit="ms", group="Performance", assumption=True
        ),
    )
    read_replicas: int = Field(
        default=0,
        ge=0,
        le=15,
        description=(
            "Read-only copies per shard. Reads are spread round-robin over the primary and its "
            "replicas; writes always go to the primary. Replication lag is not modeled."
        ),
        json_schema_extra=ui(label="Read replicas", group="Topology", headline=True),
    )
    shards: int = Field(
        default=1,
        ge=1,
        le=256,
        description=(
            "Partitions of the data, each with its own primary (and replicas). Requests are routed "
            "by hashing the key, so popular keys concentrate load on one shard."
        ),
        json_schema_extra=ui(label="Shards (partitions)", group="Topology"),
    )
    replication_factor: int = Field(
        default=1,
        ge=1,
        le=7,
        description=(
            "Copies of each item kept by a leaderless or consensus store (e.g. Cassandra, "
            "DynamoDB-style, distributed SQL). Combined with the consistency level it decides how "
            "many replica responses an operation waits for."
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
    data_size_gib: float | None = Field(
        default=None,
        gt=0,
        le=100_000_000,
        description="Total data stored (GiB), across all shards. Used to check storage.",
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

    def instances(self) -> int:
        """Instances that are paid for: per shard, the larger of replicas+1 and the RF."""
        return self.shards * max(1 + self.read_replicas, self.replication_factor)

    def buffer_hit_ratio(self) -> float | None:
        """Share of reads served from memory, or None when memory or working set is not set."""
        if self.memory_gib is None or self.working_set_gib is None:
            return None
        per_shard = self.working_set_gib / self.shards
        return min(1.0, 0.75 * self.memory_gib / per_shard)

    def wait_for(self) -> int:
        """Replica responses an operation waits for (k of RF)."""
        rf = self.replication_factor
        if self.consistency == "all":
            return rf
        if self.consistency == "quorum":
            return rf // 2 + 1
        return 1


def shard_of(key: int, shards: int) -> int:
    """Deterministic key -> shard mapping (multiplicative hash, so key ranks do not cluster)."""
    return (((key + 1) * 2654435761) & 0xFFFFFFFF) % shards


class DatabaseRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: DatabaseConfig) -> None:
        per_shard = 1 + config.read_replicas
        super().__init__(ctx, node, config, config.shards * per_shard * config.max_connections)
        self.groups = [
            [
                WorkerPool(
                    ctx.env,
                    config.max_connections,
                    config.queue_capacity,
                    config.queue_timeout_ms,
                    self.m,
                )
                for _ in range(per_shard)
            ]
            for _ in range(config.shards)
        ]
        self.core_busy = Gauge(ctx.tl) if config.cpu_cores is not None else None
        self.cores = [
            [
                Cores(ctx.env, config.cpu_cores, config.max_connections, self.core_busy)
                for _ in range(per_shard)
            ]
            for _ in range(config.shards)
        ]
        self.hit = config.buffer_hit_ratio()
        self.buffer_rng = ctx.rng(f"node:{node.id}:buffer")
        self.rr = [0] * config.shards
        self.buckets = (
            [TokenBucket(config.throughput_limit_rps) for _ in range(config.shards)]
            if config.throughput_limit_rps
            else None
        )
        self.k = config.wait_for()
        self.rng = ctx.rng(f"node:{node.id}:service")

    def _op_time(self, dist: Distribution) -> float:
        rf = self.config.replication_factor
        if rf <= 1:
            return self.sample_time(dist, self.rng)
        samples = sorted(self.sample_time(dist, self.rng) for _ in range(rf))
        return samples[self.k - 1]

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: DatabaseConfig = self.config
        m.arrive(env.now)
        n_shards = len(self.groups)
        s = shard_of(req.key, n_shards) if n_shards > 1 else 0
        if n_shards > 1:
            m.count(f"shard{s}", env.now)
        if self.buckets is not None and not self.buckets[s].try_take(env.now):
            m.reject("throttled", env.now)
            return rejected("throttled", self.id)
        group = self.groups[s]
        index = 0
        if req.op == "read" and len(group) > 1:
            index = self.rr[s]
            self.rr[s] = (index + 1) % len(group)
        pool = group[index]
        tok, reason = yield from pool.acquire()
        if tok is None:
            m.reject(reason or "rejected", env.now)
            return rejected(reason or "rejected", self.id)
        start = env.now
        dist = cfg.read_service_time_ms if req.op == "read" else cfg.write_service_time_ms
        st = self._op_time(dist)
        if cfg.cpu_cores is None:
            if st > 0:
                yield env.timeout(st)
        else:
            cpu = st * cfg.cpu_share
            yield from self.cores[s][index].run(cpu)
            if st - cpu > 0:
                yield env.timeout(st - cpu)
        # a read whose data is not in the buffer cache goes to disk (draws only when it can miss)
        if (
            self.hit is not None
            and self.hit < 1.0
            and req.op == "read"
            and self.buffer_rng.random() >= self.hit
        ):
            m.count("diskReads", env.now)
            d = cfg.disk_read_ms.sample(self.buffer_rng)
            if d > 0:
                yield env.timeout(d)
        pool.release(tok)
        m.service.record(env.now, st)
        m.hold.record(env.now, env.now - start)
        m.count("reads" if req.op == "read" else "writes", env.now)
        if index > 0:
            m.count("replicaReads", env.now)
        m.depart(env.now, OK, req)
        return OK

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
        instances = cfg.shards * (1 + cfg.read_replicas)
        return {"cpu": cfg.cpu_cores * instances * 1000.0 / (service_ms * cfg.cpu_share)}

    def extra_summary(self) -> dict[str, Any]:
        cfg: DatabaseConfig = self.config
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            instances = cfg.shards * (1 + cfg.read_replicas)
            out["cpuCoreUtilization"] = core_utilization(
                self.core_busy, self.cores[0][0].n * instances
            )
        if self.hit is not None:
            out["bufferHitRatio"] = round(self.hit, 4)
        return out


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


_ILLUSTRATIVE = "Defaults are illustrative assumptions, not vendor benchmarks."


class DatabaseModel(ComponentModel):
    type = "database"
    display_name = "Database"
    category = "storage"
    description = (
        "Executes read and write queries on a limited number of connections per instance. Model a "
        "single relational primary, a primary with read replicas, a sharded cluster, or a "
        "leaderless store with tunable consistency; technology presets fill in illustrative "
        "defaults."
    )
    icon = "database"
    config_model = DatabaseConfig
    ports = (PortSpec("in", "in", "Queries", "Read and write queries."),)
    modeled = (
        "Connection limit with a bounded wait queue and acquire timeout, per instance",
        "Separate read/write query time distributions",
        "Read replicas: reads round-robin over primary and replicas, writes to the primary",
        "Key-hash sharding; a skewed (Zipf) keyspace creates hot shards",
        "Replication factor + consistency level: latency is the k-th fastest of RF replicas",
        "Provisioned throughput per shard; excess requests are throttled",
        "CPU cores (when set): the CPU share of each query needs a core; queries queue for cores",
        "Buffer cache (memory and working set set): reads of data that does not fit pay a disk read",
        "Storage (with a data size): checked per instance",
    )
    not_modeled = (
        "Locks, MVCC contention, write I/O and checkpoints, eviction dynamics of the buffer cache",
        "Replication lag and stale reads; failover and leader election",
        "Replica load in leaderless writes (an operation holds one connection, not RF)",
        "Cross-shard queries and transactions, rebalancing, query planning",
    )
    presets = (
        Preset(
            "postgresql",
            "PostgreSQL",
            "postgresql",
            "Relational",
            "Single PostgreSQL primary behind an application connection pool. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "postgresql",
                "maxConnections": 20,
                "readServiceTimeMs": _lognormal(4, 15),
                "writeServiceTimeMs": _lognormal(8, 30),
                "costPerHour": 0.35,
            },
            ("relational", "sql", "acid"),
        ),
        Preset(
            "postgresql-replicas",
            "PostgreSQL + 2 read replicas",
            "postgresql",
            "Relational",
            "Primary for writes, two streaming replicas that share the reads. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "postgresql",
                "maxConnections": 20,
                "readReplicas": 2,
                "readServiceTimeMs": _lognormal(4, 15),
                "writeServiceTimeMs": _lognormal(8, 30),
                "costPerHour": 0.35,
            },
            ("relational", "sql", "read-scaling"),
        ),
        Preset(
            "mysql",
            "MySQL",
            "mysql",
            "Relational",
            "Single MySQL (InnoDB) primary. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 200,
                "technology": "mysql",
                "maxConnections": 20,
                "readServiceTimeMs": _lognormal(4, 16),
                "writeServiceTimeMs": _lognormal(9, 35),
                "costPerHour": 0.33,
            },
            ("relational", "sql", "acid"),
        ),
        Preset(
            "sqlserver",
            "SQL Server",
            "sqlserver",
            "Relational",
            "Single SQL Server instance; the hourly price includes a licence. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 32,
                "storageGib": 250,
                "technology": "sqlserver",
                "maxConnections": 30,
                "readServiceTimeMs": _lognormal(5, 20),
                "writeServiceTimeMs": _lognormal(10, 40),
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
                "writeServiceTimeMs": _lognormal(14, 60),
                "costPerHour": 0.5,
            },
            ("relational", "sql", "distributed", "consensus"),
        ),
        Preset(
            "mongodb-replica-set",
            "MongoDB replica set",
            "mongodb",
            "Document",
            "Primary plus two secondaries; reads use secondaries (secondaryPreferred). "
            + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 250,
                "technology": "mongodb",
                "maxConnections": 50,
                "readReplicas": 2,
                "readServiceTimeMs": _lognormal(3, 12),
                "writeServiceTimeMs": _lognormal(6, 25),
                "costPerHour": 0.3,
            },
            ("nosql", "document", "read-scaling"),
        ),
        Preset(
            "mongodb-sharded",
            "MongoDB sharded cluster",
            "mongodb",
            "Document",
            "Four shards, each a replica set (primary + two secondaries), routed by shard key. A "
            "skewed key distribution makes one shard hot. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 250,
                "technology": "mongodb",
                "maxConnections": 50,
                "shards": 4,
                "readReplicas": 2,
                "readServiceTimeMs": _lognormal(3, 12),
                "writeServiceTimeMs": _lognormal(6, 25),
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
            "Managed key-value store with four partitions and provisioned throughput per partition; "
            "requests above it are throttled. The hourly price stands in for provisioned "
            "capacity. " + _ILLUSTRATIVE,
            {
                "technology": "dynamodb",
                "maxConnections": 1000,
                "queueCapacity": 1000,
                "shards": 4,
                "throughputLimitRps": 1000,
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
            "Three primary shards with one replica each; searches are slower than key lookups and "
            "indexing is the expensive write. " + _ILLUSTRATIVE,
            {
                "cpuCores": 4,
                "memoryGib": 32,
                "storageGib": 500,
                "technology": "elasticsearch",
                "maxConnections": 16,
                "shards": 3,
                "readReplicas": 1,
                "readServiceTimeMs": _lognormal(15, 60),
                "writeServiceTimeMs": _lognormal(25, 120),
                "costPerHour": 0.45,
            },
            ("search", "full-text"),
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.shards * (1 + config.read_replicas) * config.max_connections)

    def uses_keys(self, config: Any) -> bool:
        return bool(config.shards > 1)

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
        if config.data_size_gib is not None and config.storage_gib is not None:
            per = config.data_size_gib / config.shards
            if per > config.storage_gib:
                issues.append(
                    error(
                        "database.storage_full",
                        f"{name} stores about {per:,.0f} GiB per instance ({config.data_size_gib:,.0f} "
                        f"GiB over {config.shards} shard(s)) but has {config.storage_gib:,.0f} GiB of "
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
        if config.shards > view.workload.key_count:
            issues.append(
                warning(
                    "database.shards_exceed_keys",
                    f"{name} has {config.shards} shards but the workload only touches "
                    f"{view.workload.key_count} distinct keys, so some shards stay empty.",
                    node_id=node.id,
                    path="shards",
                )
            )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return DatabaseRuntime(ctx, node, config)
