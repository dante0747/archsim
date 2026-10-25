"""Technology behaviour profiles: what makes PostgreSQL behave unlike MongoDB, Redis unlike
Memcached, Kafka unlike SQS.

Each component kind keeps one config model. Mechanism settings that differ by technology (row
locks, group commit, replica apply, event loops, visibility timeouts...) are config fields whose
default is "auto": the value comes from the selected technology's profile below. Setting a field
explicitly overrides the profile. The resolved values and the traits they switch on are reported
per node (``nodes[].behaviour``) and listed in the catalog, each with a short explanation and a
link to the vendor's documentation.

Numbers marked ``illustrative`` are typical values, not vendor guarantees; the rest are documented
defaults (see each trait's ``source``).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class Trait:
    """One behaviour a technology brings, shown to the user with where it comes from."""

    id: str
    title: str
    detail: str
    source: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "title": self.title, "detail": self.detail, "source": self.source}


@dataclass(frozen=True)
class Profile:
    """Defaults for the "auto" settings of one technology, plus the traits they produce."""

    values: dict[str, Any] = field(default_factory=dict)
    traits: tuple[Trait, ...] = ()


def resolve(explicit: Any, profile: Profile, key: str, fallback: Any) -> Any:
    """The setting in effect: the explicit value unless it is "auto"/None, else the profile's."""
    if explicit is not None and explicit != "auto":
        return explicit
    return profile.values.get(key, fallback)


def traits_catalog(profiles: dict[str, Profile]) -> dict[str, list[dict[str, Any]]]:
    return {tech: [t.to_dict() for t in p.traits] for tech, p in profiles.items() if p.traits}


def defaults_catalog(profiles: dict[str, Profile]) -> dict[str, dict[str, Any]]:
    """camelCase field -> {technology: default} for the inspector's "Auto" hints."""
    out: dict[str, dict[str, Any]] = {}
    for tech, p in profiles.items():
        for key, value in p.values.items():
            out.setdefault(_camel(key), {})[tech] = value
    return out


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(w[:1].upper() + w[1:] for w in rest)


def lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


# ---------------------------------------------------------------------------------------------
# Databases
# ---------------------------------------------------------------------------------------------

_PG = "https://www.postgresql.org/docs/current/"
_MYSQL = "https://dev.mysql.com/doc/refman/8.0/en/"
_MONGO = "https://www.mongodb.com/docs/manual/"

ROW_LOCKS = Trait(
    "row_locks",
    "Row locks on writes",
    "Two writes to the same row (key) cannot run at once: the second waits until the first "
    "commits, holding its connection while it waits. With a skewed keyspace the hottest rows "
    "become a queue of their own.",
    _PG + "explicit-locking.html",
)
MVCC_READS = Trait(
    "mvcc_reads",
    "Readers never wait for writers",
    "Multi-version concurrency control: a read sees the last committed version instead of "
    "waiting for a row lock.",
    _PG + "mvcc-intro.html",
)
GROUP_COMMIT = Trait(
    "group_commit",
    "Commits wait for the log flush (group commit)",
    "A write is durable only when the write-ahead log reaches disk. Commits that arrive while a "
    "flush is running share the next flush, so write throughput is not capped by fsyncs, but "
    "every write waits up to about 1.5 flushes.",
    _PG + "wal-async-commit.html",
)
ASYNC_REPLICAS = Trait(
    "async_replicas",
    "Asynchronous replicas with lag",
    "Replicas replay the primary's log after the commit, on a limited number of workers (in "
    "PostgreSQL a single startup process replays the WAL), so under heavy writes the lag grows "
    "and replica reads can return stale data.",
    _PG + "warm-standby.html#STREAMING-REPLICATION",
)


def _relational(apply_workers: int, extra: tuple[Trait, ...] = (), **values: Any) -> Profile:
    base = {
        "row_locks": "on",
        "reads_wait_for_writes": "off",
        "commit_flush": "on",
        "replication": "async",
        "replica_apply_workers": apply_workers,
        "read_from": "all",
    }
    base.update(values)
    return Profile(base, (ROW_LOCKS, MVCC_READS, GROUP_COMMIT, ASYNC_REPLICAS, *extra))


DATABASE_PROFILES: dict[str, Profile] = {
    "postgresql": _relational(1),
    "mysql": _relational(
        4,
        (
            Trait(
                "parallel_replica",
                "Multithreaded replica apply",
                "MySQL 8.0.27+ replicas apply transactions on 4 worker threads by default "
                "(replica_parallel_workers), so lag builds later than in PostgreSQL.",
                _MYSQL + "replication-options-replica.html#sysvar_replica_parallel_workers",
            ),
        ),
    ),
    "mariadb": _relational(4),
    "oracle": _relational(4),
    "sqlserver": Profile(
        {
            "row_locks": "on",
            "reads_wait_for_writes": "on",
            "commit_flush": "on",
            "replication": "async",
            "replica_apply_workers": 4,
            "read_from": "all",
        },
        (
            ROW_LOCKS,
            Trait(
                "locking_reads",
                "Readers wait for writers",
                "Under the default READ COMMITTED isolation (without read committed snapshot) a "
                "read of a row that is being written waits for the writer's exclusive lock.",
                "https://learn.microsoft.com/en-us/sql/relational-databases/"
                "sql-server-transaction-locking-and-row-versioning-guide",
            ),
            GROUP_COMMIT,
            ASYNC_REPLICAS,
        ),
    ),
    "cockroachdb": Profile(
        {
            "row_locks": "on",
            "reads_wait_for_writes": "off",
            "commit_flush": "on",
            "replication": "async",
            "replica_apply_workers": 4,
            "read_from": "all",
        },
        (
            ROW_LOCKS,
            Trait(
                "raft",
                "Consensus writes (Raft)",
                "Every write is replicated to a majority of the range's replicas (3 by "
                "default) before it commits: set the replication factor and quorum consistency "
                "to see the cost.",
                "https://docs.cockroachlabs.com/docs/stable/architecture/replication-layer",
            ),
            GROUP_COMMIT,
        ),
    ),
    "yugabytedb": Profile(
        {
            "row_locks": "on",
            "reads_wait_for_writes": "off",
            "commit_flush": "on",
            "replication": "async",
            "replica_apply_workers": 4,
            "read_from": "all",
        },
        (ROW_LOCKS, GROUP_COMMIT),
    ),
    "mongodb": Profile(
        {
            "row_locks": "on",
            "reads_wait_for_writes": "off",
            "commit_flush": "on",
            "replication": "majority",
            "replica_apply_workers": 16,
            "read_from": "primary",
            "read_tickets": 128,
            "write_tickets": 128,
            "router_overhead_ms": 0.5,
        },
        (
            Trait(
                "document_locks",
                "Document-level concurrency",
                "WiredTiger lets writes to different documents run in parallel; two writes to "
                "the same document conflict and one waits.",
                _MONGO + "core/wiredtiger/",
            ),
            Trait(
                "tickets",
                "Concurrent transaction tickets",
                "Each node runs at most 128 read and 128 write storage-engine transactions at "
                "once (since 7.0 MongoDB adjusts the limit dynamically, never above 128); more "
                "queue inside the server even when connections are free.",
                _MONGO + "core/wiredtiger/",
            ),
            Trait(
                "majority_writes",
                'Write concern "majority" by default',
                "A write is acknowledged only after a majority of the replica set has it (the "
                "implicit default for replica sets without arbiters), so every write waits for "
                "the fastest secondary; slow or lagging secondaries slow writes down.",
                _MONGO + "reference/write-concern/",
            ),
            Trait(
                "primary_reads",
                "Reads go to the primary by default",
                "The default read preference is primary: secondaries only serve reads when you "
                "choose secondaryPreferred (read from replicas).",
                _MONGO + "core/read-preference/",
            ),
            Trait(
                "mongos",
                "Router hop and broadcast queries when sharded",
                "With shards, mongos routes each operation; queries without the shard key are "
                "sent to every shard and wait for the slowest.",
                _MONGO + "core/sharded-cluster-query-router/",
            ),
        ),
    ),
    "couchbase": Profile(
        {
            "row_locks": "off",
            "commit_flush": "off",
            "replication": "async",
            "replica_apply_workers": 4,
            "read_from": "primary",
        },
        (),
    ),
    "cassandra": Profile(
        {
            "row_locks": "off",
            "commit_flush": "off",
            "replication": "none",
            "read_from": "all",
            "sstables_per_read": 2.0,
        },
        (
            Trait(
                "lsm_writes",
                "Cheap writes (log-structured storage)",
                "Writes append to the commit log and an in-memory table: no read before write, "
                "no row locks, and the commit log syncs periodically rather than per write.",
                "https://cassandra.apache.org/doc/latest/cassandra/architecture/storage-engine.html",
            ),
            Trait(
                "read_amplification",
                "Reads merge several SSTables",
                "A read that misses memory may touch more than one SSTable on disk (2 on average "
                "here, illustrative), so cold reads cost more than writes.",
                "https://cassandra.apache.org/doc/latest/cassandra/architecture/storage-engine.html",
            ),
        ),
    ),
    "scylladb": Profile(
        {
            "row_locks": "off",
            "commit_flush": "off",
            "replication": "none",
            "read_from": "all",
            "sstables_per_read": 1.5,
        },
        (),
    ),
    "dynamodb": Profile(
        {
            "row_locks": "off",
            "commit_flush": "off",
            "replication": "none",
            "read_from": "all",
            "capacity_units": "on",
        },
        (
            Trait(
                "partition_limits",
                "Per-partition throughput limits",
                "Each partition serves at most 3,000 read units and 1,000 write units per "
                "second. Keys hash to partitions, so a hot key is throttled even when the table "
                "has capacity left.",
                "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/"
                "bp-partition-key-design.html",
            ),
            Trait(
                "capacity_units",
                "Capacity units",
                "A strongly consistent read of up to 4 KB costs 1 read unit (eventually "
                "consistent: half); a write of up to 1 KB costs 1 write unit. Requests beyond "
                "the table's capacity are throttled.",
                "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/"
                "provisioned-capacity-mode.html",
            ),
            Trait(
                "burst",
                "Burst capacity",
                "Provisioned tables bank up to 300 seconds of unused capacity and spend it on "
                "spikes (here the bank starts full).",
                "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/"
                "burst-adaptive-capacity.html",
            ),
        ),
    ),
    "elasticsearch": Profile(
        {
            "row_locks": "off",
            "commit_flush": "off",
            "replication": "all",
            "replica_apply_workers": 4,
            "read_from": "all",
            "scatter_reads": 1.0,
            "coordinator_ms": 1.0,
        },
        (
            Trait(
                "fan_out",
                "Every search hits every shard",
                "A search runs on one copy of each shard and the coordinating node merges the "
                "results, so search latency follows the slowest shard and each search costs one "
                "query per shard.",
                "https://www.elastic.co/docs/deploy-manage/distributed-architecture/"
                "reading-and-writing-documents",
            ),
            Trait(
                "sync_replicas",
                "Index writes wait for replicas",
                "An indexing operation is acknowledged after the primary and its in-sync "
                "replicas have it.",
                "https://www.elastic.co/docs/deploy-manage/distributed-architecture/"
                "reading-and-writing-documents",
            ),
            Trait(
                "thread_pools",
                "Bounded thread pools (HTTP 429)",
                "Searches run on a fixed pool of (cores x 3 / 2) + 1 threads with a bounded "
                "queue (1,000 in 8.x, 1,000 per thread since 9.0); past it the node rejects "
                "with 429. The preset sizes connections and queue that way.",
                "https://www.elastic.co/docs/reference/elasticsearch/configuration-reference/"
                "thread-pool-settings",
            ),
        ),
    ),
}
DATABASE_PROFILES["opensearch"] = DATABASE_PROFILES["elasticsearch"]
DATABASE_PROFILES["generic"] = Profile(
    {
        "row_locks": "off",
        "reads_wait_for_writes": "off",
        "commit_flush": "off",
        "replication": "none",
        "read_from": "all",
    },
    (),
)


# ---------------------------------------------------------------------------------------------
# Caches
# ---------------------------------------------------------------------------------------------

_REDIS = "https://redis.io/docs/latest/"

SINGLE_THREAD = Trait(
    "single_thread",
    "One thread executes commands",
    "Commands run one at a time on a single thread per node, however many cores it has; scale "
    "out with cluster shards (keys are split across 16,384 hash slots).",
    _REDIS + "operate/oss_and_stack/management/optimization/latency/",
)
EVICTION = Trait(
    "eviction",
    "Eviction policy",
    "With allkeys-lru the least recently used keys make room for new ones; with noeviction "
    "(Redis's own default) writes fail with an out-of-memory error once memory is full.",
    _REDIS + "develop/reference/eviction/",
)
FORK = Trait(
    "fork_stall",
    "Snapshot fork stalls",
    "With RDB snapshots on, each snapshot forks the process; the fork blocks the command thread "
    "for about 10-13 ms per GB of data on modern hardware (Redis's own measurements), far more "
    "on some older virtual machines.",
    _REDIS + "operate/oss_and_stack/management/optimization/latency/",
)

CACHE_PROFILES: dict[str, Profile] = {
    "redis": Profile(
        {"single_threaded": "on", "eviction_policy": "allkeys_lru", "persistence": "none"},
        (SINGLE_THREAD, EVICTION, FORK),
    ),
    "valkey": Profile(
        {"single_threaded": "on", "eviction_policy": "allkeys_lru", "persistence": "none"},
        (
            Trait(
                "single_thread",
                "One thread executes commands",
                "Valkey offloads socket I/O to I/O threads, which shortens each command, but "
                "commands still execute one at a time per node; scale out with cluster shards.",
                "https://valkey.io/blog/unlock-one-million-rps/",
            ),
            EVICTION,
            FORK,
        ),
    ),
    "memcached": Profile(
        {"single_threaded": "off", "eviction_policy": "allkeys_lru", "persistence": "none"},
        (
            Trait(
                "threads",
                "Multi-threaded",
                "Worker threads (4 by default) execute requests in parallel; memory is managed "
                "in slabs with LRU eviction and nothing is persisted.",
                "https://docs.memcached.org/serverguide/configuring/",
            ),
        ),
    ),
    "cdn": Profile({"single_threaded": "off", "eviction_policy": "allkeys_lru"}, ()),
    "varnish": Profile({"single_threaded": "off", "eviction_policy": "allkeys_lru"}, ()),
    "generic": Profile({"single_threaded": "off", "eviction_policy": "allkeys_lru"}, ()),
}


# ---------------------------------------------------------------------------------------------
# Message queues
# ---------------------------------------------------------------------------------------------

QUEUE_PROFILES: dict[str, Profile] = {
    "kafka": Profile(
        {
            "partitioning": "key",
            "acks": "all",
            "linger_ms": 5.0,
            "delivery": "at_least_once",
            "visibility_timeout_s": None,
            "flow_control": "off",
        },
        (
            Trait(
                "partitions",
                "Ordered partitions, one consumer each",
                "Messages with the same key land on the same partition and are processed in "
                "order by the one consumer that owns it. A skewed key makes one partition, and "
                "its consumer, fall behind while others idle.",
                "https://kafka.apache.org/documentation/#intro_concepts_and_terms",
            ),
            Trait(
                "acks_all",
                "acks=all",
                "The producer's acknowledgement waits until the in-sync replicas have the "
                "message (the default since Kafka 3.0).",
                "https://kafka.apache.org/documentation/#producerconfigs_acks",
            ),
            Trait(
                "linger",
                "Batching (linger.ms)",
                "The producer holds a message up to linger.ms to batch it with others (5 ms by "
                "default in Kafka 4.0).",
                "https://kafka.apache.org/documentation/#producerconfigs_linger.ms",
            ),
        ),
    ),
    "rabbitmq": Profile(
        {
            "partitioning": "none",
            "acks": "leader",
            "linger_ms": 0.0,
            "delivery": "at_least_once",
            "visibility_timeout_s": None,
            "flow_control": "on",
        },
        (
            Trait(
                "memory_alarm",
                "Memory alarm blocks publishers",
                "When queued messages use more memory than the high watermark, the broker "
                "blocks publishing connections until consumers catch up: producers slow down "
                "instead of getting errors. Needs memory and message size set.",
                "https://www.rabbitmq.com/docs/memory",
            ),
        ),
    ),
    "sqs": Profile(
        {
            "partitioning": "none",
            "acks": "leader",
            "linger_ms": 0.0,
            "delivery": "at_least_once",
            "visibility_timeout_s": 30.0,
            "flow_control": "off",
        },
        (
            Trait(
                "visibility_timeout",
                "Visibility timeout (30 s)",
                "A received message is hidden for the visibility timeout. If processing takes "
                "longer, the message becomes visible again and another consumer processes it "
                "too: duplicate work.",
                "https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/"
                "sqs-visibility-timeout.html",
            ),
            Trait(
                "fifo_limit",
                "FIFO throughput limit",
                "FIFO queues accept about 300 publishes per second without batching; standard "
                "queues are effectively unlimited.",
                "https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/"
                "quotas-messages.html",
            ),
        ),
    ),
    "pubsub": Profile(
        {
            "partitioning": "none",
            "acks": "leader",
            "linger_ms": 0.0,
            "delivery": "at_least_once",
            "visibility_timeout_s": 10.0,
            "flow_control": "off",
        },
        (
            Trait(
                "ack_deadline",
                "Ack deadline (10 s)",
                "A message not acknowledged within the ack deadline is delivered again.",
                "https://docs.cloud.google.com/pubsub/docs/subscription-properties",
            ),
        ),
    ),
    "nats": Profile(
        {
            "partitioning": "none",
            "acks": "leader",
            "linger_ms": 0.0,
            "delivery": "at_least_once",
            "visibility_timeout_s": None,
            "flow_control": "off",
        },
        (
            Trait(
                "core_vs_jetstream",
                "Core NATS drops, JetStream persists",
                "Core NATS is at-most-once: a slow consumer's overflow is dropped and the "
                "publisher never knows. Choose at-least-once for JetStream.",
                "https://docs.nats.io/nats-concepts/core-nats",
            ),
        ),
    ),
    "redis_streams": Profile(
        {"partitioning": "none", "acks": "leader", "linger_ms": 0.0, "flow_control": "off"}, ()
    ),
    "generic": Profile(
        {"partitioning": "none", "acks": "leader", "linger_ms": 0.0, "flow_control": "off"}, ()
    ),
}


# ---------------------------------------------------------------------------------------------
# Load balancers
# ---------------------------------------------------------------------------------------------

LB_PROFILES: dict[str, Profile] = {
    "nginx": Profile(
        {"upstream_timeout_ms": 60_000.0},
        (
            Trait(
                "proxy_read_timeout",
                "Upstream read timeout 60 s",
                "nginx gives up on an upstream response after proxy_read_timeout (60 s by "
                "default) and returns 504.",
                "https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_read_timeout",
            ),
            Trait(
                "two_connections",
                "Two connections per proxied request",
                "worker_connections counts every connection, including those to upstream "
                "servers, so each proxied request uses two and a worker serves half as many "
                "requests (the preset sizes it that way).",
                "https://nginx.org/en/docs/ngx_core_module.html#worker_connections",
            ),
        ),
    ),
    "envoy": Profile(
        {"upstream_timeout_ms": 15_000.0, "circuit_breaker_max_requests": 1024},
        (
            Trait(
                "circuit_breakers",
                "Circuit breakers (1,024 requests)",
                "Each upstream cluster allows 1,024 concurrent requests by default; beyond that "
                "Envoy answers 503 at once instead of queueing.",
                "https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/cluster/v3/"
                "circuit_breaker.proto",
            ),
            Trait(
                "route_timeout",
                "Route timeout 15 s",
                "A route waits 15 s for the upstream response by default, then returns 504.",
                "https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/route/v3/"
                "route_components.proto",
            ),
        ),
    ),
    "aws_alb": Profile(
        {"upstream_timeout_ms": 60_000.0},
        (
            Trait(
                "idle_timeout",
                "Idle timeout 60 s",
                "The load balancer closes a connection idle for 60 s (default), which ends a "
                "slow upstream response with 504.",
                "https://docs.aws.amazon.com/elasticloadbalancing/latest/application/"
                "edit-load-balancer-attributes.html",
            ),
        ),
    ),
    "api_gateway": Profile(
        {"upstream_timeout_ms": 29_000.0},
        (
            Trait(
                "integration_timeout",
                "Integration timeout 29 s",
                "The gateway waits at most 29 s for the backend by default, then returns 504 "
                "(regional and private REST APIs can request more).",
                "https://docs.aws.amazon.com/apigateway/latest/developerguide/"
                "api-gateway-execution-service-limits-table.html",
            ),
            Trait(
                "account_throttle",
                "Account throttle",
                "10,000 requests per second with a 5,000 burst per account and Region by "
                "default (token bucket; lower in some newer Regions); excess gets 429. The "
                "preset sets these.",
                "https://docs.aws.amazon.com/apigateway/latest/developerguide/limits.html",
            ),
        ),
    ),
    "kong": Profile(
        {"upstream_timeout_ms": 60_000.0},
        (
            Trait(
                "service_timeouts",
                "Upstream timeouts 60 s",
                "A Kong service waits 60 s (read_timeout) for the upstream by default.",
                "https://developer.konghq.com/gateway/entities/service/",
            ),
        ),
    ),
    "haproxy": Profile({"upstream_timeout_ms": None}, ()),
    "traefik": Profile({"upstream_timeout_ms": None}, ()),
    "generic": Profile({"upstream_timeout_ms": None}, ()),
}


# ---------------------------------------------------------------------------------------------
# Service runtimes
# ---------------------------------------------------------------------------------------------

EVENT_LOOP = Trait(
    "event_loop",
    "Single-threaded event loop",
    "JavaScript runs on one thread per process: CPU work of every request queues for that one "
    "loop (event-loop lag), while I/O waits don't hold it. Add processes per replica (cluster "
    "mode) to use more cores.",
    "https://nodejs.org/en/learn/asynchronous-work/dont-block-the-event-loop",
)
JIT = Trait(
    "jit_warmup",
    "JIT warm-up",
    "A new instance starts with interpreted or lightly optimised code and runs slower until the "
    "JIT has compiled the hot paths (factor and duration are illustrative).",
    "https://docs.oracle.com/en/java/javase/21/vm/java-virtual-machine-technology-overview.html",
)
GC_JAVA = Trait(
    "gc_pauses",
    "Stop-the-world GC pauses",
    "The garbage collector (G1 by default) pauses all application threads for a few milliseconds "
    "when the young generation fills up, more often under load (pause and frequency are "
    "illustrative).",
    "https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html",
)

SERVICE_PROFILES: dict[str, Profile] = {
    "nodejs": Profile(
        {
            "runtime_model": "event_loop",
            "gc_pause_ms": 2.0,
            "gc_every_requests": 1500,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(250, 700),
        },
        (
            EVENT_LOOP,
            Trait(
                "v8_gc",
                "Short GC pauses",
                "V8's scavenger pauses the loop for a couple of milliseconds now and then "
                "(illustrative).",
                "https://v8.dev/blog/trash-talk",
            ),
        ),
    ),
    "java": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 12.0,
            "gc_every_requests": 2000,
            "warmup_factor": 2.5,
            "warmup_s": 45.0,
            "cold_start_ms": lognormal(1200, 3500),
        },
        (
            Trait(
                "thread_per_request",
                "Thread per request",
                "Spring MVC on Tomcat serves each request on a pool thread (200 by default) "
                "that stays busy during downstream calls.",
                "https://docs.spring.io/spring-boot/appendix/application-properties/index.html",
            ),
            GC_JAVA,
            JIT,
        ),
    ),
    "dotnet": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 6.0,
            "gc_every_requests": 2000,
            "warmup_factor": 1.5,
            "warmup_s": 20.0,
            "cold_start_ms": lognormal(600, 1500),
        },
        (
            Trait(
                "tiered_compilation",
                "Tiered compilation warm-up",
                "Methods start unoptimised and are recompiled once hot, so a fresh instance is "
                "slower at first (factor and duration are illustrative).",
                "https://learn.microsoft.com/en-us/dotnet/core/runtime-config/compilation",
            ),
            Trait(
                "gc_pauses",
                "GC pauses",
                "Gen0 collections pause managed threads briefly (illustrative).",
                "https://learn.microsoft.com/en-us/dotnet/standard/garbage-collection/fundamentals",
            ),
        ),
    ),
    "go": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 0.1,
            "gc_every_requests": 1000,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(100, 300),
        },
        (
            Trait(
                "goroutines",
                "Goroutines on every core",
                "Requests run as goroutines scheduled across all cores; the concurrent GC's "
                "stop-the-world pauses are usually under 100 microseconds.",
                "https://go.dev/doc/go1.8",
            ),
        ),
    ),
    "rust": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 0.0,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(60, 200),
        },
        (
            Trait(
                "no_gc",
                "No garbage collector",
                "Memory is freed deterministically: no GC pauses and no JIT warm-up.",
                "https://doc.rust-lang.org/book/ch04-01-what-is-ownership.html",
            ),
        ),
    ),
    "python": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 1.0,
            "gc_every_requests": 2000,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(250, 700),
        },
        (
            Trait(
                "worker_processes",
                "Worker processes",
                "Gunicorn-style sync workers handle one request each; each process is held to "
                "one core by the GIL (the docs suggest 2 x cores + 1 workers).",
                "https://gunicorn.org/design/",
            ),
        ),
    ),
    "php": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 0.0,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(300, 900),
        },
        (
            Trait(
                "process_per_request",
                "Process per request (PHP-FPM)",
                "Each request takes a PHP-FPM child; pm.max_children (5 in the shipped "
                "www.conf) caps concurrency and memory is freed when the request ends.",
                "https://www.php.net/manual/en/install.fpm.configuration.php",
            ),
        ),
    ),
    "ruby": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 3.0,
            "gc_every_requests": 1500,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(300, 900),
        },
        (),
    ),
    "generic": Profile(
        {
            "runtime_model": "thread_pool",
            "gc_pause_ms": 0.0,
            "warmup_factor": 1.0,
            "warmup_s": 0.0,
            "cold_start_ms": lognormal(300, 900),
        },
        (),
    ),
}

SERVERLESS_TRAITS = (
    Trait(
        "scale_rate",
        "Scale-out rate",
        "A function adds at most 1,000 concurrent instances every 10 seconds; requests beyond "
        "the instances it may start are throttled.",
        "https://docs.aws.amazon.com/lambda/latest/dg/scaling-behavior.html",
    ),
    Trait(
        "runtime_cold_start",
        "Cold start by runtime",
        "Cold starts range from under 100 ms to over a second. AWS publishes no per-runtime "
        "figures; the defaults here (JVM and .NET slower than Go, Rust, Node.js or Python) are "
        "illustrative, so measure your own.",
        "https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html",
    ),
)
