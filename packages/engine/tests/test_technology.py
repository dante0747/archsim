"""Technology behaviour: each technology's mechanisms, the calculations and the request traces."""

from __future__ import annotations

from typing import Any

from archsim_engine import create_default_registry, run_simulation
from engine_builders import doc, edge, node, node_by_id

CONST = lambda v: {"kind": "constant", "value": v}  # noqa: E731
EXP = lambda v: {"kind": "exponential", "mean": v}  # noqa: E731


def clients_to(target: dict[str, Any], *, rps: float, **workload: Any) -> dict[str, Any]:
    return doc(
        [node("clients", "traffic_source", timeoutMs=600_000), target],
        [edge("c-t", "clients", target["id"])],
        rps=rps,
        **workload,
    )


def behaviour_ids(report: dict[str, Any], node_id: str) -> set[str]:
    return {b["id"] for b in node_by_id(report, node_id)["behaviour"]}


# -- databases ---------------------------------------------------------------------------------


def test_postgres_row_locks_serialize_writes_to_a_hot_row() -> None:
    db = dict(
        maxConnections=50, writeServiceTimeMs=CONST(10), queueCapacity=10_000, queueTimeoutMs=None
    )
    wl = dict(rps=150, durationS=20, warmupS=4, readRatio=0.0, keyCount=1)
    locked = run_simulation(clients_to(node("db", "database", **db), **wl))
    free = run_simulation(clients_to(node("db", "database", rowLocks="off", **db), **wl))
    s = node_by_id(locked, "db")["summary"]
    # one row: each write holds its lock ~10 ms + the log flush, so < 100 writes/s get through
    assert locked["summary"]["throughputRps"] < 100
    assert free["summary"]["throughputRps"] > 140
    assert s["meanLockWaitMs"] > 100
    assert "lockWaitMs" in node_by_id(locked, "db")["timeseries"]
    assert "row_locks" in behaviour_ids(locked, "db")
    assert "lock_contention" in {f["kind"] for f in locked["findings"]}
    assert "row_locks" not in behaviour_ids(free, "db")


def test_group_commit_shares_flushes() -> None:
    db = dict(
        maxConnections=200,
        writeServiceTimeMs=CONST(1),
        commitFlushMs=CONST(5),
        queueCapacity=10_000,
        queueTimeoutMs=None,
    )
    wl = dict(rps=1000, durationS=15, warmupS=3, readRatio=0.0, keyCount=100_000, keySkew=0.0)
    r = run_simulation(clients_to(node("db", "database", **db), **wl))
    s = node_by_id(r, "db")["summary"]
    assert s["commitsPerFlush"] > 3  # many commits per 5 ms flush at 1,000 writes/s
    assert 5 <= s["meanCommitWaitMs"] <= 10.5  # between one and two flushes
    off = run_simulation(clients_to(node("db", "database", commitFlush="off", **db), **wl))
    assert node_by_id(off, "db")["summary"]["meanHoldMs"] < s["meanHoldMs"] - 3


def test_async_replicas_lag_and_serve_stale_reads() -> None:
    db = dict(
        readReplicas=1,
        maxConnections=100,
        readServiceTimeMs=CONST(1),
        writeServiceTimeMs=CONST(5),
        replicaApplyFactor=1.0,
        queueCapacity=10_000,
        queueTimeoutMs=None,
    )
    wl = dict(rps=500, durationS=20, warmupS=2, readRatio=0.5, keyCount=50, keySkew=1.0)
    pg = run_simulation(
        clients_to(node("db", "database", technology="postgresql", rowLocks="off", **db), **wl)
    )
    n = node_by_id(pg, "db")
    # 250 writes/s x 5 ms of replay on one worker = 125% busy: the lag grows all run
    assert n["summary"]["maxReplicationLagMs"] > 2000
    assert n["summary"]["counters"].get("staleReads", 0) > 0
    assert "replicationLagMs" in n["timeseries"] and "staleReadRps" in n["timeseries"]
    assert "replication_lag" in {f["kind"] for f in pg["findings"]}
    mysql = run_simulation(
        clients_to(node("db", "database", technology="mysql", rowLocks="off", **db), **wl)
    )
    assert (
        node_by_id(mysql, "db")["summary"]["maxReplicationLagMs"] < 100
    )  # 4 apply workers keep up


def test_mongodb_waits_for_a_majority_and_reads_the_primary() -> None:
    db = dict(
        technology="mongodb",
        readReplicas=2,
        maxConnections=500,
        readServiceTimeMs=CONST(1),
        writeServiceTimeMs=CONST(2),
        replicationDelayMs=CONST(1),
    )
    wl = dict(rps=200, durationS=15, warmupS=2, readRatio=0.5, keyCount=10_000)
    r = run_simulation(clients_to(node("db", "database", **db), **wl))
    s = node_by_id(r, "db")["summary"]
    assert s["counters"].get("replicaReads", 0) == 0  # read preference primary
    assert s["meanReplicaAckMs"] >= 2  # ship + replay (1 ms) + ack network
    assert {"majority_writes", "primary_reads", "tickets"} <= behaviour_ids(r, "db")
    pref = run_simulation(clients_to(node("db", "database", readFrom="replicas", **db), **wl))
    assert node_by_id(pref, "db")["summary"]["counters"]["replicaReads"] > 0


def test_mongodb_tickets_limit_concurrency_beyond_connections() -> None:
    db = dict(
        technology="mongodb",
        maxConnections=500,
        readTickets=2,
        readServiceTimeMs=CONST(10),
        queueCapacity=10_000,
        queueTimeoutMs=None,
    )
    r = run_simulation(clients_to(node("db", "database", **db), rps=300, durationS=15, warmupS=3))
    assert r["summary"]["throughputRps"] < 210  # 2 tickets x 100/s each
    assert "ticketWaitMs" in node_by_id(r, "db")["timeseries"]


def test_elasticsearch_searches_fan_out_to_every_shard() -> None:
    es = node(
        "es",
        "database",
        technology="elasticsearch",
        shards=3,
        maxConnections=50,
        readServiceTimeMs=EXP(5),
    )
    r = run_simulation(clients_to(es, rps=100, durationS=15, warmupS=3))
    s = node_by_id(r, "es")["summary"]
    assert abs(s["counters"]["scatterReads"] - s["counters"]["reads"]) <= 2
    assert "fan_out" in {e["id"] for e in node_by_id(r, "es")["explain"]}
    # the slowest of three exponential(5) shard queries plus 1 ms of merging: ~9.2 + 1 ms
    assert 8 < s["latencyMs"]["mean"] < 13
    # every search loads every shard, so no shard is hot (a search is counted once, on shard 0)
    assert not [f for f in r["findings"] if f["kind"] == "hot_shard"]


def test_dynamodb_throttles_a_hot_key_but_not_a_spread_keyspace() -> None:
    ddb = dict(technology="dynamodb", maxConnections=5000, writeServiceTimeMs=CONST(3))
    hot = run_simulation(
        clients_to(node("t", "database", **ddb), rps=1500, durationS=15, readRatio=0.0, keyCount=1)
    )
    s = node_by_id(hot, "t")["summary"]
    # one key = one partition = at most 1,000 write units/s
    assert 0.25 < s["rejected"]["throttled"] / s["arrivals"] < 0.4
    spread = run_simulation(
        clients_to(
            node("t", "database", **ddb),
            rps=1500,
            durationS=15,
            readRatio=0.0,
            keyCount=100_000,
            keySkew=0.0,
        )
    )
    assert node_by_id(spread, "t")["summary"]["rejectedTotal"] == 0
    # on-demand starts at 12,000 RCU + 4,000 WCU: 12000/3000 + 4000/1000 = 8 partitions
    assert node_by_id(spread, "t")["summary"]["partitions"] == 8
    ts = node_by_id(spread, "t")["timeseries"]
    assert max(v for v in ts["consumedWriteUnits"] if v is not None) > 1000


# -- caches ------------------------------------------------------------------------------------


def test_redis_runs_commands_on_one_thread_per_shard() -> None:
    reg = create_default_registry(include_plugins=False)
    model = reg.get("cache")
    redis = model.parse_config({"technology": "redis", "concurrency": 8})
    memcached = model.parse_config({"technology": "memcached", "concurrency": 8})
    cluster = model.parse_config({"technology": "redis", "shards": 4})
    assert model.capacity_slots(redis) == 1
    assert model.capacity_slots(memcached) == 8
    assert model.capacity_slots(cluster) == 4


def _cache_doc(**cache: Any) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000),
            node(
                "cache",
                "cache",
                hitModel="ttl_keyspace",
                ttlSeconds=600,
                memoryGib=0.001,
                itemSizeKb=1,
                **cache,
            ),
            node("db", "database", maxConnections=100, readServiceTimeMs=CONST(1)),
        ],
        [
            edge("c-k", "clients", "cache"),
            edge("k-db", "cache", "db", source_port="miss", request_types=["read"]),
        ],
        rps=300,
        durationS=15,
        warmupS=2,
        keyCount=10_000,
        keySkew=0.5,
    )


def test_noeviction_refuses_new_keys_instead_of_evicting() -> None:
    lru = node_by_id(run_simulation(_cache_doc()), "cache")["summary"]["counters"]
    noev = node_by_id(run_simulation(_cache_doc(evictionPolicy="noeviction")), "cache")["summary"][
        "counters"
    ]
    assert lru.get("evictions", 0) > 0 and lru.get("oomErrors", 0) == 0
    assert noev.get("oomErrors", 0) > 0 and noev.get("evictions", 0) == 0


def test_rdb_snapshots_stall_the_command_thread() -> None:
    r = run_simulation(_cache_doc(persistence="rdb", snapshotIntervalS=2, forkMsPerGib=10_000))
    n = node_by_id(r, "cache")
    assert n["summary"]["counters"]["forks"] >= 5
    assert any(v and v > 0 for v in n["timeseries"]["forkPauseMs"])


# -- queues ------------------------------------------------------------------------------------


def _queue_doc(
    queue: dict[str, Any], rps: float = 200, consumer_ms: float = 5, **wl: Any
) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000),
            node(
                "q",
                "message_queue",
                consumerTimeMs=CONST(consumer_ms),
                publishTimeMs=CONST(1),
                **queue,
            ),
            node("worker", "service", replicas=1, workersPerReplica=1000, serviceTimeMs=CONST(0.1)),
        ],
        [edge("c-q", "clients", "q"), edge("q-w", "q", "worker")],
        rps=rps,
        durationS=15,
        warmupS=2,
        drainS=5,
        **wl,
    )


def test_kafka_hot_key_backs_up_one_partition() -> None:
    kafka = dict(technology="kafka", partitions=8, consumers=8)
    wl = dict(keyCount=20, keySkew=2.0)
    keyed = node_by_id(run_simulation(_queue_doc(kafka, rps=400, consumer_ms=4, **wl)), "q")
    spread = node_by_id(
        run_simulation(
            _queue_doc({**kafka, "partitioning": "round_robin"}, rps=400, consumer_ms=4, **wl)
        ),
        "q",
    )
    # 8 consumers x 250/s would keep up evenly; the hottest key alone exceeds one consumer
    assert max(keyed["timeseries"]["partitionLagMax"]) > 10 * max(
        spread["timeseries"]["partitionLagMax"] + [1]
    )
    assert "acks_all" in {b["id"] for b in keyed["behaviour"]}


def test_rabbitmq_memory_alarm_blocks_publishers() -> None:
    rabbit = dict(
        technology="rabbitmq", consumers=1, memoryGib=0.0001, messageSizeKb=1, maxDepth=1_000_000
    )
    r = run_simulation(_queue_doc(rabbit, rps=300, consumer_ms=5))
    q = node_by_id(r, "q")
    assert q["summary"]["rejectedTotal"] == 0
    assert q["summary"]["counters"]["memoryAlarms"] > 0
    assert max(v for v in q["timeseries"]["publishBlockedMs"] if v is not None) > 10


def test_sqs_visibility_timeout_redelivers_slow_messages() -> None:
    sqs = dict(
        technology="sqs", consumers=20, partitions=10_000, maxDepth=None, visibilityTimeoutS=0.05
    )
    q = node_by_id(run_simulation(_queue_doc(sqs, rps=50, consumer_ms=100)), "q")
    assert q["summary"]["counters"]["redeliveries"] > 0
    assert "redeliveryRps" in q["timeseries"]


def test_sqs_fifo_publish_limit() -> None:
    fifo = dict(
        technology="sqs",
        fifo=True,
        fifoLimitRps=300,
        consumers=50,
        partitions=10_000,
        maxDepth=None,
    )
    q = node_by_id(run_simulation(_queue_doc(fifo, rps=600, consumer_ms=1)), "q")
    assert 0.4 < q["summary"]["rejected"]["throttled"] / q["summary"]["arrivals"] < 0.6


def test_at_most_once_drops_overflow_silently() -> None:
    nats = dict(technology="nats", delivery="at_most_once", consumers=1, maxDepth=10)
    r = run_simulation(_queue_doc(nats, rps=300, consumer_ms=10))
    q = node_by_id(r, "q")
    assert q["summary"]["rejectedTotal"] == 0 and r["summary"]["successRate"] == 1.0
    assert q["summary"]["counters"]["dropped"] > 0


# -- load balancers ----------------------------------------------------------------------------


def _lb_doc(lb: dict[str, Any], service_ms: float, rps: float = 20) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000),
            node("lb", "load_balancer", **lb),
            node(
                "svc",
                "service",
                replicas=1,
                workersPerReplica=1000,
                serviceTimeMs=CONST(service_ms),
            ),
        ],
        [edge("c-lb", "clients", "lb"), edge("lb-s", "lb", "svc")],
        rps=rps,
        durationS=20,
        warmupS=2,
        drainS=30,
    )


def test_envoy_route_timeout_returns_504_but_nginx_waits() -> None:
    envoy = run_simulation(_lb_doc({"technology": "envoy"}, service_ms=20_000, rps=2))
    reasons = {row["reason"] for row in envoy["summary"]["failureBreakdown"]}
    assert "gateway_timeout" in reasons
    nginx = run_simulation(_lb_doc({"technology": "nginx"}, service_ms=20_000, rps=2))
    assert nginx["summary"]["successRate"] == 1.0


def test_envoy_circuit_breaker_overflows_with_503() -> None:
    r = run_simulation(
        _lb_doc({"technology": "envoy", "circuitBreakerMaxRequests": 5}, service_ms=200, rps=50)
    )
    lb = node_by_id(r, "lb")
    assert lb["summary"]["counters"]["circuitOpen"] > 0
    assert "circuit_open" in {row["reason"] for row in r["summary"]["failureBreakdown"]}


# -- service runtimes --------------------------------------------------------------------------


def test_nodejs_runs_cpu_work_on_one_event_loop() -> None:
    svc = node(
        "api",
        "service",
        technology="nodejs",
        replicas=1,
        workersPerReplica=64,
        serviceTimeMs=CONST(5),
        queueCapacity=10_000,
        queueTimeoutMs=None,
    )
    r = run_simulation(clients_to(svc, rps=300, durationS=15, warmupS=3))
    n = node_by_id(r, "api")
    assert n["summary"]["slots"] == 1  # the loop, not the 64 workers
    assert r["summary"]["throughputRps"] < 205  # 1000 / 5 ms
    assert "eventLoopLagMs" in n["timeseries"]
    two = node(
        "api",
        "service",
        technology="nodejs",
        processesPerReplica=2,
        replicas=1,
        serviceTimeMs=CONST(5),
        queueCapacity=10_000,
        queueTimeoutMs=None,
    )
    assert (
        run_simulation(clients_to(two, rps=300, durationS=15, warmupS=3))["summary"][
            "throughputRps"
        ]
        > 290
    )


def test_java_gc_pauses_stretch_the_tail() -> None:
    base = dict(technology="java", replicas=1, workersPerReplica=50, serviceTimeMs=CONST(5))
    gc = run_simulation(
        clients_to(
            node("api", "service", gcEveryRequests=200, gcPauseMs=40, **base), rps=200, durationS=20
        )
    )
    none = run_simulation(
        clients_to(node("api", "service", gcPauseMs=0, **base), rps=200, durationS=20)
    )
    n = node_by_id(gc, "api")
    assert n["summary"]["gcPauses"] > 10
    assert any(v and v > 0 for v in n["timeseries"]["gcPauseMs"])
    assert gc["summary"]["latencyMs"]["p99"] > none["summary"]["latencyMs"]["p99"] + 20


def test_jit_warmup_slows_new_replicas() -> None:
    svc = node(
        "api",
        "service",
        technology="java",
        platform="kubernetes",
        autoscaling=True,
        replicas=1,
        minReplicas=1,
        maxReplicas=6,
        workersPerReplica=4,
        serviceTimeMs=CONST(10),
        scaleIntervalS=2,
        startupS=1,
        gcPauseMs=0,
    )
    r = run_simulation(clients_to(svc, rps=600, durationS=30, warmupS=2))
    ts = node_by_id(r, "api")["timeseries"]
    assert max(v for v in ts["warmingReplicas"] if v is not None) >= 1


def test_serverless_cold_start_depends_on_runtime_and_scale_rate_throttles() -> None:
    def fn(tech: str, **extra: Any) -> dict[str, Any]:
        return node(
            "fn",
            "service",
            platform="serverless",
            technology=tech,
            keepWarmS=0,
            serviceTimeMs=CONST(10),
            **extra,
        )

    java = run_simulation(clients_to(fn("java"), rps=20, durationS=15, warmupS=2))
    go = run_simulation(clients_to(fn("go"), rps=20, durationS=15, warmupS=2))
    assert java["summary"]["latencyMs"]["mean"] > 3 * go["summary"]["latencyMs"]["mean"]
    capped = run_simulation(
        clients_to(fn("nodejs", scaleOutRate=5), rps=200, durationS=15, warmupS=2)
    )
    assert node_by_id(capped, "fn")["summary"]["rejected"].get("throttled", 0) > 0


# -- calculations and traces -------------------------------------------------------------------


def test_explain_matches_queueing_theory_for_mmc() -> None:
    svc = node(
        "svc",
        "service",
        replicas=1,
        workersPerReplica=4,
        serviceTimeMs=EXP(10),
        queueCapacity=100_000,
        queueTimeoutMs=None,
    )
    r = run_simulation(clients_to(svc, rps=300, durationS=60, warmupS=10, seed=3))
    entries = {e["id"]: e for e in node_by_id(r, "svc")["explain"]}
    assert {"arrival_rate", "slot_capacity", "utilization", "littles_law", "mmc_wait"} <= set(
        entries
    )
    measured = node_by_id(r, "svc")["summary"]["meanQueueWaitMs"]
    predicted = entries["mmc_wait"]["result"]
    # M/M/4 at rho 0.75: Erlang C predicts ~5.1 ms of waiting
    assert abs(measured - predicted) / predicted < 0.3
    lit = entries["littles_law"]
    assert (
        abs(lit["result"] - node_by_id(r, "svc")["summary"]["meanInSystem"]) < 0.15 * lit["result"]
    )


def test_traces_and_latency_breakdown(starter: dict[str, Any]) -> None:
    r = run_simulation(starter)
    traces = r["traces"]
    assert 10 <= len(traces) <= 40
    assert any("slowest" in t["tags"] for t in traces) and any(
        "median" in t["tags"] for t in traces
    )
    t = traces[0]
    assert t["spans"] and all(sp["endMs"] >= sp["startMs"] for sp in t["spans"])
    assert {sp["kind"] for tr in traces for sp in tr["spans"]} >= {"network", "handle"}
    rows = r["explain"]["latencyBreakdown"]
    assert rows and abs(sum(x["share"] for x in rows) - 1) < 0.01
    again = run_simulation(starter)
    assert again["provenance"]["resultDigest"] == r["provenance"]["resultDigest"]
    assert again["traces"] == traces


def test_catalog_lists_technology_traits_and_defaults() -> None:
    cat = {c["type"]: c for c in create_default_registry(include_plugins=False).catalog()}
    db = cat["database"]
    assert any(t["id"] == "majority_writes" for t in db["technologyTraits"]["mongodb"])
    assert db["technologyDefaults"]["replicaApplyWorkers"]["postgresql"] == 1
    assert all(
        t["source"] is None or t["source"].startswith("https://")
        for ts in db["technologyTraits"].values()
        for t in ts
    )
