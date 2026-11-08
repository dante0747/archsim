"""Write whole simulations of small designs for the Java engine, or check the committed file.

The Java simulator keeps the frozen engine's order of events and draws its random numbers, so a
Java run of a design follows the Python run of it. This script simulates small designs, each
reaching one feature of the runtime (clients with timeouts and retries, client groups, traffic
patterns, faults, delays of zero, no traffic at all, a calibrated scenario of a priced API,
services (autoscaling, serverless and Kubernetes ones too), Kubernetes clusters, load
balancers, hosts, caches, databases, message queues, and each component as later slices add
them), with the frozen engine (0.16.0) and records each design, the scenario simulated and the
report.
``SimulationCasesTests`` simulates each design in Java and compares the whole reports: digests,
counts and events processed exactly, numbers to a relative 1e-9.

The report's machine-dependent provenance (``wallSeconds``, ``python``, ``simpy``) is blanked.

Usage:
    uv run python scripts/export_simulation_cases.py           # write the cases
    uv run python scripts/export_simulation_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine import run_simulation

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/simulator/cases.json.gz"
)
GENERATOR = "scripts/export_simulation_cases.py (archsim_engine 0.16.0)"
Json = dict[str, Any]


def lognormal(median: float, p95: float) -> Json:
    return {"kind": "lognormal", "median": median, "p95": p95}


def design(nodes: list[Json], edges: list[Json], workload: Json) -> Json:
    """A design of the current schema, with no layout, auto-sizing off."""
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "case"},
        "architecture": {"nodes": nodes, "edges": edges, "autoSize": False},
        "workload": {"seed": 7, "warmupS": 1, "durationS": 6, "readRatio": 0.8, **workload},
    }


def clients(node_id: str = "clients", **config: Any) -> Json:
    return {"id": node_id, "type": "traffic_source", "label": "Clients", "config": config}


def external(node_id: str = "api", **config: Any) -> Json:
    return {"id": node_id, "type": "external_api", "label": "API", "config": config}


def service(node_id: str, label: str, **config: Any) -> Json:
    return {"id": node_id, "type": "service", "label": label, "config": config}


def balancer(node_id: str = "lb", **config: Any) -> Json:
    return {"id": node_id, "type": "load_balancer", "label": "Balancer", "config": config}


def cache(node_id: str, label: str, **config: Any) -> Json:
    return {"id": node_id, "type": "cache", "label": label, "config": config}


def database(node_id: str, label: str, **config: Any) -> Json:
    return {"id": node_id, "type": "database", "label": label, "config": config}


def queue(node_id: str, label: str, **config: Any) -> Json:
    return {"id": node_id, "type": "message_queue", "label": label, "config": config}


def cluster(node_id: str = "k8s", **config: Any) -> Json:
    return {"id": node_id, "type": "kubernetes_cluster", "label": "Cluster", "config": config}


def link(source: str, target: str, **config: Any) -> Json:
    return {
        "id": f"{source}-{target}",
        "source": source,
        "sourcePort": "out",
        "target": target,
        "targetPort": "in",
        "config": {"latencyMs": {"kind": "constant", "value": 1}, **config},
    }


def miss(source: str, target: str, **config: Any) -> Json:
    """A cache's read-through link, from its miss port."""
    edge = link(source, target, requestTypes=["read"], **config)
    edge["sourcePort"] = "miss"
    return edge


def calibrated(doc: Json) -> Json:
    """The design auto-sized, with a measured input, a calibration and a scenario that
    overrides the workload and the API: the report's assumptions, cost and provenance
    reach their other branches."""
    doc["architecture"]["autoSize"] = True
    doc["provenance"] = {
        "nodes/api/latencyMs": {
            "kind": "measured",
            "source": "Prometheus",
            "fitError": 0.08,
            "fittedAt": "2026-10-01T12:00:00Z",
            "value": lognormal(40, 150),
        }
    }
    doc["calibration"] = {
        "at": "2026-10-01T12:00:00Z",
        "source": "Prometheus",
        "inputs": 1,
        "notes": ["The API's latency is fitted from a week of calls."],
    }
    doc["scenarios"] = [
        {
            "id": "peak",
            "name": "Peak hour",
            "overrides": [
                {"target": {"kind": "workload"}, "path": "rps", "value": 220},
                {"target": {"kind": "node", "id": "api"}, "path": "errorRate", "value": 0.02},
            ],
        }
    ]
    return doc


def on_host(doc: Json, host: Json) -> Json:
    """The design on one host machine, which every part with a CPU size shares."""
    doc["architecture"]["host"] = host
    return doc


CASES: dict[str, Json] = {
    "constantRateToAnApi": design(
        [clients(), external(latencyMs={"kind": "constant", "value": 20})],
        [link("clients", "api")],
        {"rps": 100, "arrivalProcess": "deterministic"},
    ),
    "poissonToAnApiThatFails": design(
        [clients(), external(latencyMs=lognormal(30, 120), errorRate=0.05)],
        [link("clients", "api", latencyMs={"kind": "exponential", "mean": 2})],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "twoClientGroups": design(
        [
            clients("web", trafficShare=3, timeoutMs=500),
            clients("mobile", trafficShare=1, timeoutMs=200, maxAttempts=2, retryBackoffMs=20),
            external(latencyMs=lognormal(20, 60)),
        ],
        [
            link("web", "api"),
            link("mobile", "api", latencyMs={"kind": "uniform", "min": 5, "max": 15}),
        ],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "aRampWithAnOutageAndASlowdown": design(
        [clients(timeoutMs=300), external(latencyMs={"kind": "exponential", "mean": 25})],
        [link("clients", "api")],
        {
            "rps": 150,
            "arrivalProcess": "poisson",
            "pattern": {"kind": "ramp", "startRps": 50, "endRps": 250},
            "faults": [
                {"id": "down", "kind": "outage", "target": "api", "startS": 2, "durationS": 1},
                {
                    "id": "slow",
                    "kind": "slowdown",
                    "target": "api",
                    "startS": 4,
                    "durationS": 1,
                    "factor": 3,
                },
                {
                    "id": "lag",
                    "kind": "latency",
                    "target": "clients-api",
                    "startS": 3.5,
                    "durationS": 1,
                    "extraMs": 50,
                },
            ],
        },
    ),
    "aBurst": design(
        [clients(), external(latencyMs=lognormal(10, 30))],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "poisson",
            "pattern": {"kind": "burst", "startS": 2, "durationS": 1, "burstRps": 600},
        },
    ),
    "aTracedPattern": design(
        [clients(), external(latencyMs={"kind": "constant", "value": 15})],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "deterministic",
            "pattern": {"kind": "trace", "points": [[0, 50], [2, 300], [4, 100], [6, 100]]},
        },
    ),
    "aSetDrainAndAQuietStart": design(
        [clients(timeoutMs=400), external(latencyMs=lognormal(15, 45), errorRate=0.01)],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "deterministic",
            "readRatio": 0.5,
            "drainS": 2,
            "pattern": {"kind": "trace", "points": [[0, 0], [1, 0], [1.5, 200], [6, 200]]},
        },
    ),
    "retriesWithoutBackoff": design(
        [
            clients(timeoutMs=30, maxAttempts=3, retryBackoffMs=0),
            external(latencyMs=lognormal(25, 80)),
        ],
        [link("clients", "api")],
        {"rps": 120, "arrivalProcess": "poisson"},
    ),
    "zeroDelays": design(
        [clients(timeoutMs=50), external(latencyMs={"kind": "constant", "value": 0})],
        [link("clients", "api", latencyMs={"kind": "constant", "value": 0})],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "noTraffic": design(
        [clients(), external()],
        [link("clients", "api")],
        {"rps": 0},
    ),
    "clientsThatRetryAndTimeOut": design(
        [
            clients(timeoutMs=60, maxAttempts=3, retryBackoffMs=10),
            external(latencyMs=lognormal(40, 200), rateLimitRps=150, concurrencyLimit=8),
        ],
        [link("clients", "api")],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "aBlockingServiceWithRetryingLinks": design(
        [
            clients(timeoutMs=400),
            service(
                "svc",
                "Orders",
                replicas=2,
                workersPerReplica=3,
                queueCapacity=10,
                queueTimeoutMs=80,
                serviceTimeMs=lognormal(8, 25),
            ),
            external(latencyMs=lognormal(15, 60), errorRate=0.05),
        ],
        [
            link("clients", "svc"),
            link("svc", "api", timeoutMs=60, maxAttempts=3, retryBackoffMs=5),
        ],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "anEventLoopServiceOnContendedCores": design(
        [
            clients(timeoutMs=800),
            service(
                "svc",
                "Gateway",
                technology="nodejs",
                replicas=1,
                processesPerReplica=2,
                cpuCores=1,
                cpuShare=0.7,
                queueCapacity=100,
                queueTimeoutMs=None,
                gcEveryRequests=100,
                serviceTimeMs=lognormal(3, 9),
                serviceTimeByLoad=[{"rps": 50, "factor": 1}, {"rps": 400, "factor": 1.6}],
            ),
            external(latencyMs={"kind": "exponential", "mean": 12}),
        ],
        [link("clients", "svc"), link("svc", "api")],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "aJavaServiceOutOfMemoryWithALoadCurve": design(
        [
            clients(timeoutMs=1500),
            service(
                "svc",
                "Catalog",
                technology="java",
                replicas=2,
                workersPerReplica=40,
                memoryGib=0.26,
                memoryPerRpsMb=0.5,
                gcEveryRequests=200,
                warmupFactor=3,
                warmupS=30,
                serviceTimeMs=lognormal(30, 90),
                serviceTimeByLoad=[{"rps": 20, "factor": 1}, {"rps": 200, "factor": 2}],
            ),
        ],
        [link("clients", "svc")],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "aGatewayCallingInOrder": design(
        [
            clients(timeoutMs=600),
            service(
                "gw",
                "Gateway",
                blockingDownstream=False,
                cpuCores=0.5,
                serviceTimeMs={"kind": "constant", "value": 2},
            ),
            service("users", "Users", replicas=1, workersPerReplica=8),
            external("orders", errorRate=0.2, latencyMs=lognormal(10, 40)),
            service("audit", "Audit", replicas=1, serviceTimeMs={"kind": "constant", "value": 4}),
        ],
        [
            link("clients", "gw"),
            link("gw", "users", order=1, requestTypes=["read"]),
            link("gw", "orders", order=2, continueOnFailure=True, timeoutMs=50),
            link("gw", "audit", requestTypes=["write"]),
        ],
        {"rps": 120, "arrivalProcess": "poisson", "readRatio": 0.6},
    ),
    "aServiceNearItsCpuCeiling": design(
        [
            clients(timeoutMs=2000),
            service(
                "svc",
                "Pricing",
                replicas=1,
                workersPerReplica=4,
                queueCapacity=50,
                cpuCores=2,
                cpuShare=0.8,
                serviceTimeMs=lognormal(10, 30),
            ),
        ],
        [link("clients", "svc")],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "aServiceWithCoresToSpare": design(
        [
            clients(),
            service(
                "svc",
                "Search",
                replicas=3,
                workersPerReplica=4,
                cpuCores=8,
                cpuShare=0.5,
                serviceTimeMs=lognormal(20, 50),
            ),
        ],
        [link("clients", "svc")],
        {"rps": 150, "arrivalProcess": "deterministic"},
    ),
    "aBalancerRetryingAnotherReplica": design(
        [
            clients(timeoutMs=1000),
            balancer(technology="nginx", policy="round_robin"),
            service(
                "svc",
                "Orders",
                replicas=3,
                workersPerReplica=2,
                queueCapacity=5,
                queueTimeoutMs=100,
                serviceTimeMs=lognormal(15, 60),
            ),
        ],
        [
            link("clients", "lb"),
            link("lb", "svc", timeoutMs=200, maxAttempts=2, retryBackoffMs=5),
        ],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "anEnvoyWithACircuitBreakerAndAnUpstreamTimeout": design(
        [
            clients(timeoutMs=1000),
            balancer(
                technology="envoy",
                policy="least_connections",
                circuitBreakerMaxRequests=6,
                upstreamTimeoutMs=80,
                overheadMs={"kind": "exponential", "mean": 0.5},
            ),
            service(
                "svc", "Search", replicas=2, workersPerReplica=3, serviceTimeMs=lognormal(20, 90)
            ),
            external("api", latencyMs=lognormal(30, 150)),
        ],
        [
            link("clients", "lb"),
            link("lb", "svc", maxAttempts=2),
            link("lb", "api", requestTypes=["read"], timeoutMs=120),
        ],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "aRandomBalancerWithARateLimit": design(
        [
            clients(timeoutMs=800),
            balancer(
                technology="api_gateway",
                policy="random",
                rateLimitRps=150,
                rateLimitBurst=20,
                maxConnections=8,
            ),
            service(
                "svc",
                "Profile",
                replicas=2,
                workersPerReplica=3,
                queueCapacity=2,
                serviceTimeMs=lognormal(25, 70),
            ),
        ],
        [link("clients", "lb"), link("lb", "svc", maxAttempts=2)],
        {"rps": 250, "arrivalProcess": "poisson", "readRatio": 0.85},
    ),
    "aDefaultBalancerOverTwoServices": design(
        [
            clients(timeoutMs=1500),
            balancer(),
            service("a", "Orders", replicas=2, workersPerReplica=2, queueCapacity=1),
            service("b", "Orders (canary)", replicas=1, workersPerReplica=2, queueCapacity=1),
        ],
        [
            link("clients", "lb"),
            link("lb", "a", maxAttempts=2, retryBackoffMs=10),
            link("lb", "b", maxAttempts=2, retryBackoffMs=10),
        ],
        {"rps": 400, "arrivalProcess": "poisson"},
    ),
    "aBalancerRateLimitedBelowOneRequestASecond": design(
        [clients(), balancer(rateLimitRps=0.5), service("svc", "Reports", replicas=1)],
        [link("clients", "lb"), link("lb", "svc")],
        {"rps": 20, "arrivalProcess": "deterministic"},
    ),
    "servicesSharingAHost": on_host(
        design(
            [
                clients(timeoutMs=1000),
                service(
                    "gw",
                    "Gateway",
                    technology="nodejs",
                    replicas=1,
                    processesPerReplica=2,
                    cpuCores=2,
                    cpuShare=0.8,
                    serviceTimeMs=lognormal(4, 12),
                ),
                service(
                    "svc",
                    "Orders",
                    replicas=2,
                    workersPerReplica=4,
                    cpuCores=2,
                    cpuShare=0.6,
                    serviceTimeMs=lognormal(10, 30),
                ),
                service(
                    "audit", "Audit", replicas=1, serviceTimeMs={"kind": "constant", "value": 3}
                ),
            ],
            [link("clients", "gw"), link("gw", "svc"), link("gw", "audit", requestTypes=["write"])],
            {
                "rps": 150,
                "arrivalProcess": "poisson",
                "pattern": {"kind": "ramp", "startRps": 100, "endRps": 250},
            },
        ),
        {
            "physicalCores": 2,
            "siblingSpeed": 0.6,
            "allCoreClock": 0.85,
            "otherBusyThreads": 0.5,
            "clientCpuMs": 0.3,
        },
    ),
    "aHostWithNoCpuWork": on_host(
        design(
            [clients(), external(latencyMs={"kind": "constant", "value": 20})],
            [link("clients", "api")],
            {"rps": 20, "arrivalProcess": "deterministic"},
        ),
        {"physicalCores": 2, "clientCpuMs": 0.2},
    ),
    "servicesOnTheirOwnHyperthreads": design(
        [
            clients(timeoutMs=1000),
            service(
                "gw",
                "Gateway",
                technology="nodejs",
                replicas=1,
                processesPerReplica=2,
                cpuCores=2,
                siblingSpeed=0.7,
                serviceTimeMs=lognormal(3, 9),
            ),
            service(
                "svc",
                "Search",
                replicas=2,
                workersPerReplica=8,
                cpuCores=4,
                cpuShare=0.9,
                siblingSpeed=0.6,
                serviceTimeMs=lognormal(15, 40),
            ),
        ],
        [link("clients", "gw"), link("gw", "svc")],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "aReadThroughCacheWithAFixedHitRatio": design(
        [
            clients(timeoutMs=800),
            service(
                "svc", "Catalog", replicas=1, workersPerReplica=16, serviceTimeMs=lognormal(2, 6)
            ),
            cache(
                "cache",
                "Memcached",
                technology="memcached",
                hitRatio=0.7,
                concurrency=4,
                cpuCores=2,
                siblingSpeed=0.7,
                queueCapacity=3,
                queueTimeoutMs=8,
                serviceTimeMs=lognormal(1.5, 6),
            ),
            external("db", latencyMs=lognormal(20, 60), errorRate=0.05),
        ],
        [
            link("clients", "svc"),
            link("svc", "cache", continueOnFailure=True),
            miss("cache", "db", maxAttempts=2, timeoutMs=100),
        ],
        {"rps": 400, "arrivalProcess": "poisson"},
    ),
    "aTtlCacheWithShardsEvictingKeys": design(
        [
            clients(timeoutMs=1000),
            cache(
                "cache",
                "Redis",
                technology="redis",
                hitModel="ttl_keyspace",
                ttlSeconds=2,
                shards=3,
                concurrency=4,
                cpuCores=1,
                memoryGib=0.01,
                itemSizeKb=100,
                serviceTimeMs=lognormal(0.5, 2),
            ),
            service(
                "db", "Database", replicas=1, workersPerReplica=8, serviceTimeMs=lognormal(8, 20)
            ),
        ],
        [link("clients", "cache"), miss("cache", "db")],
        {"rps": 300, "arrivalProcess": "poisson", "keyCount": 2000, "keySkew": 0.9},
    ),
    "aFullCacheRefusingNewKeysWhileSnapshotting": design(
        [
            clients(timeoutMs=1000),
            cache(
                "cache",
                "Redis",
                technology="redis",
                hitModel="ttl_keyspace",
                ttlSeconds=3,
                evictionPolicy="noeviction",
                persistence="rdb",
                snapshotIntervalS=1,
                forkMsPerGib=10000,
                memoryGib=0.01,
                itemSizeKb=100,
                serviceTimeMs=lognormal(0.3, 1),
            ),
            external("db", latencyMs=lognormal(10, 30)),
        ],
        [link("clients", "cache"), miss("cache", "db")],
        {"rps": 200, "arrivalProcess": "poisson", "keyCount": 500, "keySkew": 0.6},
    ),
    "cacheAsideFromAService": design(
        [
            clients(timeoutMs=1000),
            service(
                "svc", "Products", replicas=2, workersPerReplica=8, serviceTimeMs=lognormal(3, 9)
            ),
            cache(
                "local",
                "Memcached",
                technology="memcached",
                hitModel="ttl_keyspace",
                ttlSeconds=2,
                evictionPolicy="noeviction",
                memoryGib=0.005,
                itemSizeKb=100,
                concurrency=2,
                queueCapacity=1,
                serviceTimeMs=lognormal(0.8, 3),
            ),
            cache(
                "redis",
                "Valkey",
                technology="valkey",
                hitRatio=0.6,
                shards=2,
                persistence="rdb",
                snapshotIntervalS=1,
                memoryGib=1,
                forkMsPerGib=50,
                serviceTimeMs=lognormal(0.3, 1),
            ),
            cache(
                "hot",
                "Hot keys",
                technology="generic",
                hitRatio=1.0,
                queueCapacity=0,
                serviceTimeMs=lognormal(0.5, 2),
            ),
            service(
                "db",
                "Database",
                replicas=1,
                workersPerReplica=3,
                queueCapacity=2,
                serviceTimeMs=lognormal(12, 40),
            ),
        ],
        [
            link("clients", "svc"),
            link("svc", "local", continueOnFailure=True),
            link("svc", "redis"),
            link("svc", "hot", requestTypes=["read"], continueOnFailure=True),
            link("svc", "db", requestTypes=["read"], readsOnMiss=True, continueOnFailure=True),
        ],
        {"rps": 250, "arrivalProcess": "poisson", "keyCount": 1000},
    ),
    "readThroughCachesThatNeitherFillNorFork": design(
        [
            clients(timeoutMs=1000),
            service("svc", "Pages", replicas=1, workersPerReplica=8, serviceTimeMs=lognormal(2, 6)),
            cache(
                "ttl",
                "Redis",
                hitModel="ttl_keyspace",
                ttlSeconds=1,
                persistence="rdb",
                forkMsPerGib=0,
                cpuCores=1,
                serviceTimeMs={"kind": "constant", "value": 0},
            ),
            cache(
                "edge",
                "CDN",
                technology="cdn",
                hitRatio=0.9,
                persistence="rdb",
                populateOnMiss=False,
            ),
            external("api", latencyMs=lognormal(15, 45)),
        ],
        [
            link("clients", "svc"),
            link("svc", "ttl", requestTypes=["read"]),
            link("svc", "edge"),
            miss("ttl", "api"),
            miss("edge", "api"),
        ],
        {"rps": 150, "arrivalProcess": "poisson", "keyCount": 300},
    ),
    "cacheAsideWithoutWritingBack": design(
        [
            clients(timeoutMs=1000),
            service(
                "svc", "Products", replicas=1, workersPerReplica=8, serviceTimeMs=lognormal(3, 9)
            ),
            cache("cache", "Redis", hitRatio=0.7, populateOnMiss=False),
            external("db", latencyMs=lognormal(10, 30), errorRate=0.1),
        ],
        [
            link("clients", "svc"),
            link("svc", "cache"),
            link("svc", "db", requestTypes=["read"], readsOnMiss=True),
        ],
        {"rps": 100, "arrivalProcess": "poisson"},
    ),
    "aPostgresLockingHotRowsWithGroupCommit": design(
        [
            clients(timeoutMs=1500),
            service(
                "svc", "Orders", replicas=1, workersPerReplica=16, serviceTimeMs=lognormal(2, 6)
            ),
            database(
                "db",
                "PostgreSQL",
                maxConnections=4,
                queueCapacity=8,
                queueTimeoutMs=150,
                cpuCores=2,
                cpuShare=0.6,
                memoryGib=4,
                workingSetGib=10,
                readServiceTimeMs=lognormal(3, 10),
                writeServiceTimeMs=lognormal(6, 20),
            ),
        ],
        [link("clients", "svc"), link("svc", "db")],
        {"rps": 250, "arrivalProcess": "poisson", "readRatio": 0.5, "keyCount": 40, "keySkew": 1.2},
    ),
    "aShardedMongoWaitingForTickets": design(
        [
            clients(timeoutMs=1000),
            database(
                "db",
                "MongoDB",
                technology="mongodb",
                shards=3,
                readTickets=2,
                writeTickets=1,
                replicationFactor=3,
                consistency="quorum",
                throughputLimitRps=90,
                readServiceTimeMs=lognormal(4, 12),
                writeServiceTimeMs=lognormal(5, 15),
            ),
        ],
        [link("clients", "db")],
        {"rps": 300, "arrivalProcess": "poisson", "readRatio": 0.7, "keyCount": 200},
    ),
    "aSqlServerWhoseReadsWaitForWriters": design(
        [
            clients(timeoutMs=1000),
            database(
                "db",
                "SQL Server",
                technology="sqlserver",
                maxConnections=8,
                cpuCores=1.5,
                siblingSpeed=0.7,
                readServiceTimeMs=lognormal(2, 6),
                writeServiceTimeMs=lognormal(8, 25),
                commitFlushMs=lognormal(2, 5),
            ),
        ],
        [link("clients", "db")],
        {"rps": 120, "arrivalProcess": "poisson", "readRatio": 0.6, "keyCount": 15, "keySkew": 1.0},
    ),
    "aScyllaReadingSstablesFromDisk": design(
        [
            clients(timeoutMs=1000),
            database(
                "db",
                "ScyllaDB",
                technology="scylladb",
                replicationFactor=3,
                consistency="all",
                readReplicas=1,
                memoryGib=2,
                workingSetGib=8,
                diskReadMs=lognormal(2, 6),
            ),
        ],
        [link("clients", "db")],
        {"rps": 150, "arrivalProcess": "poisson", "readRatio": 0.9},
    ),
    "searchStoresScatteringReads": design(
        [
            clients(timeoutMs=1000),
            service(
                "svc", "Search API", replicas=1, workersPerReplica=16, serviceTimeMs=lognormal(2, 6)
            ),
            database(
                "es",
                "Elasticsearch",
                technology="elasticsearch",
                shards=3,
                readServiceTimeMs=lognormal(4, 12),
            ),
            database(
                "os",
                "OpenSearch",
                technology="opensearch",
                shards=4,
                scatterReads=0.5,
                routerOverheadMs=1.0,
                maxConnections=2,
                queueCapacity=1,
                readServiceTimeMs=lognormal(5, 15),
            ),
        ],
        [link("clients", "svc"), link("svc", "es"), link("svc", "os")],
        {"rps": 120, "arrivalProcess": "poisson", "readRatio": 0.85},
    ),
    "aStoreReadingFromItsReplicas": design(
        [
            clients("web", timeoutMs=1000, trafficShare=3),
            clients("batch", timeoutMs=1000, trafficShare=1),
            database(
                "db",
                "Store",
                technology="generic",
                readReplicas=2,
                readFrom="replicas",
                shards=2,
                maxConnections=1,
                queueCapacity=2,
                queueTimeoutMs=None,
                readServiceTimeMs=lognormal(8, 25),
            ),
            database(
                "primary",
                "Store (primary reads)",
                technology="generic",
                readReplicas=1,
                readFrom="primary",
                replicationFactor=2,
            ),
        ],
        [link("web", "db"), link("batch", "primary")],
        {"rps": 400, "arrivalProcess": "poisson", "readRatio": 0.8},
    ),
    "aPostgresWhoseReplicasLag": design(
        [
            clients(timeoutMs=1500),
            database(
                "db",
                "PostgreSQL",
                readReplicas=2,
                replicaApplyFactor=1.0,
                cpuCores=2,
                cpuShare=0.5,
                readServiceTimeMs=lognormal(2, 6),
                writeServiceTimeMs=lognormal(8, 25),
                replicationDelayMs=lognormal(1, 3),
            ),
        ],
        [link("clients", "db")],
        {"rps": 300, "arrivalProcess": "poisson", "readRatio": 0.5, "keyCount": 30, "keySkew": 1.1},
    ),
    "aMongoReplicaSetWaitingForAMajority": design(
        [
            clients(timeoutMs=1500),
            database(
                "db",
                "MongoDB",
                technology="mongodb",
                readReplicas=2,
                replicaApplyWorkers=2,
                replicaApplyFactor=0.8,
                replicationDelayMs=lognormal(2, 6),
                writeServiceTimeMs=lognormal(5, 15),
            ),
        ],
        [link("clients", "db")],
        {"rps": 200, "arrivalProcess": "poisson", "readRatio": 0.6, "keyCount": 100},
    ),
    "aStoreWaitingForEveryReplica": design(
        [
            clients(timeoutMs=1500),
            database(
                "db",
                "Store",
                technology="generic",
                replication="all",
                readReplicas=2,
                readFrom="replicas",
                shards=2,
                replicaApplyWorkers=1,
                cpuCores=1,
                cpuShare=1.0,
            ),
        ],
        [link("clients", "db")],
        {"rps": 150, "arrivalProcess": "poisson", "readRatio": 0.7, "keyCount": 50},
    ),
    "aReadOnlyProvisionedDynamoDbTable": design(
        [
            clients(timeoutMs=1000),
            database(
                "db",
                "DynamoDB",
                technology="dynamodb",
                capacityMode="provisioned",
                readCapacityUnits=2,
                writeCapacityUnits=2,
                itemSizeKb=6,
                dataSizeGib=25,
                readServiceTimeMs=lognormal(4, 10),
                writeServiceTimeMs=lognormal(6, 15),
            ),
        ],
        [link("clients", "db")],
        {"rps": 300, "arrivalProcess": "poisson", "readRatio": 1.0},
    ),
    "anOnDemandDynamoDbTableWithAHotKey": design(
        [
            clients(timeoutMs=1000),
            database(
                "db",
                "DynamoDB",
                technology="dynamodb",
                consistentReads="strong",
                itemSizeKb=8,
                maxConnections=200,
                readServiceTimeMs=lognormal(3, 8),
                writeServiceTimeMs=lognormal(5, 12),
            ),
        ],
        [link("clients", "db")],
        {
            "rps": 1000,
            "arrivalProcess": "poisson",
            "readRatio": 0.6,
            "keyCount": 10,
            "keySkew": 2.0,
        },
    ),
    "aKafkaTopicWithAHotKey": design(
        [
            clients(timeoutMs=1000),
            queue(
                "topic",
                "Kafka",
                technology="kafka",
                partitions=6,
                consumers=3,
                consumerTimeMs=lognormal(4, 12),
                maxDeliveries=2,
            ),
            external("api", latencyMs=lognormal(10, 30), errorRate=0.2),
            service(
                "ledger", "Ledger", replicas=1, workersPerReplica=4, serviceTimeMs=lognormal(2, 5)
            ),
        ],
        [
            link("clients", "topic"),
            link("topic", "api", maxAttempts=1, order=2),
            link("topic", "ledger", order=1),
        ],
        {"rps": 300, "arrivalProcess": "poisson", "keyCount": 50, "keySkew": 1.5},
    ),
    "aRabbitMqBlockingPublishers": design(
        [
            clients(timeoutMs=2000),
            queue(
                "q",
                "RabbitMQ",
                technology="rabbitmq",
                consumers=2,
                memoryGib=0.001,
                messageSizeKb=10,
                consumerTimeMs=lognormal(12, 30),
            ),
            service(
                "worker", "Worker", replicas=1, workersPerReplica=4, serviceTimeMs=lognormal(3, 9)
            ),
        ],
        [link("clients", "q"), link("q", "worker")],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "anSqsQueueRedeliveringSlowMessages": design(
        [
            clients(timeoutMs=1000),
            queue(
                "q",
                "SQS",
                technology="sqs",
                consumers=4,
                visibilityTimeoutS=0.05,
                fifo=True,
                fifoLimitRps=120,
                maxDepth=40,
                consumerTimeMs=lognormal(30, 90),
            ),
            external("api", latencyMs=lognormal(15, 40)),
        ],
        [link("clients", "q"), link("q", "api")],
        {"rps": 150, "arrivalProcess": "poisson"},
    ),
    "aNatsSubjectDroppingOverflow": design(
        [
            clients(timeoutMs=1000),
            queue(
                "q",
                "NATS",
                technology="nats",
                delivery="at_most_once",
                maxDepth=20,
                consumers=1,
                consumerTimeMs=lognormal(8, 20),
            ),
            external("api", latencyMs={"kind": "constant", "value": 2}),
        ],
        [link("clients", "q"), link("q", "api")],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "consumersPollingInCycles": design(
        [
            clients(timeoutMs=1000),
            service(
                "svc", "Orders", replicas=1, workersPerReplica=16, serviceTimeMs=lognormal(2, 6)
            ),
            queue(
                "events",
                "Kafka",
                technology="kafka",
                partitions=4,
                consumers=2,
                consumerConcurrency=2,
                fetchOverheadMs=lognormal(3, 8),
                consumerTimeMs=lognormal(5, 15),
            ),
            queue(
                "jobs",
                "Jobs",
                technology="generic",
                consumers=1,
                consumerConcurrency=2,
                consumerTimeMs=lognormal(60, 150),
            ),
            external("api", latencyMs=lognormal(5, 15)),
        ],
        [
            link("clients", "svc"),
            link("svc", "events"),
            link("svc", "jobs", requestTypes=["write"]),
            link("events", "api"),
            link("jobs", "api"),
        ],
        {"rps": 200, "arrivalProcess": "poisson", "keyCount": 100},
    ),
    "aQueueSpreadOverPartitionsInTurn": design(
        [
            clients(timeoutMs=1000),
            queue(
                "q",
                "Queue",
                technology="generic",
                partitioning="round_robin",
                partitions=3,
                consumers=5,
                consumerTimeMs=lognormal(6, 18),
            ),
            external("api", latencyMs=lognormal(5, 15)),
        ],
        [link("clients", "q"), link("q", "api")],
        {"rps": 300, "arrivalProcess": "poisson", "drainS": 3},
    ),
    "anAutoscalingServiceRampingUpAndDown": design(
        [
            clients(timeoutMs=2000),
            balancer(policy="round_robin"),
            service(
                "svc",
                "Checkout",
                technology="java",
                replicas=2,
                autoscaling=True,
                minReplicas=1,
                maxReplicas=6,
                targetUtilization=0.5,
                scaleIntervalS=0.5,
                startupS=0.4,
                workersPerReplica=4,
                gcPauseMs=20,
                gcEveryRequests=100,
                warmupS=0.8,
                serviceTimeMs=lognormal(12, 36),
            ),
            external("api", latencyMs=lognormal(5, 15)),
        ],
        [link("clients", "lb"), link("lb", "svc"), link("svc", "api")],
        {
            "rps": 200,
            "arrivalProcess": "poisson",
            "durationS": 10,
            "pattern": {
                "kind": "trace",
                "points": [[0, 60], [2, 450], [5, 450], [6, 30], [8.5, 30], [8.6, 0], [10, 0]],
            },
        },
    ),
    "anAutoscalingServiceOnCpuBehindABalancer": design(
        [
            clients(timeoutMs=1500),
            balancer(policy="least_connections"),
            service(
                "svc",
                "Search",
                replicas=1,
                autoscaling=True,
                minReplicas=1,
                maxReplicas=4,
                targetUtilization=0.6,
                scaleIntervalS=0.5,
                startupS=0,
                workersPerReplica=8,
                cpuCores=1,
                cpuShare=0.8,
                serviceTimeMs=lognormal(8, 20),
                serviceTimeByLoad=[{"rps": 20, "factor": 1}, {"rps": 200, "factor": 1.5}],
            ),
        ],
        [link("clients", "lb"), link("lb", "svc")],
        {"rps": 250, "arrivalProcess": "poisson", "durationS": 8},
    ),
    "aJavaFunctionThrottledAsItScalesOut": design(
        [
            clients(timeoutMs=4000),
            service(
                "fn",
                "Resize",
                technology="java",
                platform="serverless",
                concurrencyLimit=24,
                scaleOutRate=20,
                keepWarmS=1,
                cpuCores=0.5,
                cpuShare=0.6,
                serviceTimeMs=lognormal(20, 60),
                serviceTimeByLoad=[{"rps": 5, "factor": 1}, {"rps": 40, "factor": 1.4}],
            ),
            external("api", latencyMs=lognormal(5, 15)),
        ],
        [link("clients", "fn"), link("fn", "api")],
        {
            "rps": 200,
            "arrivalProcess": "poisson",
            "durationS": 10,
            "pattern": {
                "kind": "trace",
                "points": [[0, 40], [2, 400], [4, 400], [4.5, 5], [8, 5], [8.5, 250], [10, 250]],
            },
        },
    ),
    "aFunctionBehindAServiceWithASlowdown": design(
        [
            clients(timeoutMs=3000),
            service(
                "svc", "Orders", replicas=2, workersPerReplica=16, serviceTimeMs=lognormal(4, 10)
            ),
            service(
                "fn",
                "Notify",
                technology="go",
                platform="serverless",
                concurrencyLimit=200,
                scaleOutRate=1000,
                keepWarmS=0.05,
                coldStartMs={"kind": "constant", "value": 80},
                serviceTimeMs=lognormal(6, 18),
            ),
            service(
                "audit",
                "Audit",
                platform="serverless",
                cpuCores=2,
                cpuShare=0.5,
                serviceTimeMs=lognormal(3, 9),
            ),
        ],
        [
            link("clients", "svc"),
            link("svc", "fn", requestTypes=["read"]),
            link("svc", "audit", requestTypes=["write"]),
        ],
        {
            "rps": 150,
            "arrivalProcess": "poisson",
            "durationS": 6,
            "readRatio": 0.8,
            "faults": [
                {
                    "id": "slow",
                    "kind": "slowdown",
                    "target": "fn",
                    "startS": 2,
                    "durationS": 1,
                    "factor": 3,
                },
            ],
        },
    ),
    "aFunctionNoRequestReaches": design(
        [
            clients(),
            service("svc", "Reports", serviceTimeMs=lognormal(4, 10)),
            service("fn", "Export", platform="serverless"),
        ],
        [link("clients", "svc"), link("svc", "fn", requestTypes=["write"])],
        {"rps": 50, "durationS": 3, "readRatio": 1.0},
    ),
    "podsWaitingForNodes": design(
        [
            clients(timeoutMs=3000),
            balancer(policy="round_robin"),
            service(
                "web",
                "Web",
                platform="kubernetes",
                replicas=4,
                cpuCores=0.8,
                startupS=0.3,
                workersPerReplica=8,
                serviceTimeMs=lognormal(6, 18),
            ),
            service(
                "jobs",
                "Jobs",
                platform="kubernetes",
                replicas=2,
                memoryGib=7,
                startupS=0.2,
                workersPerReplica=1,
                serviceTimeMs=lognormal(10, 30),
            ),
            cluster(
                nodes=1,
                nodeVcpu=2,
                nodeMemoryGib=8,
                maxNodes=4,
                nodeProvisionS=1.5,
                scanIntervalS=1,
                imagePullS=0.4,
            ),
        ],
        [
            link("clients", "lb"),
            link("lb", "web"),
            link("web", "jobs", requestTypes=["write"]),
        ],
        {"rps": 120, "arrivalProcess": "poisson", "durationS": 6, "readRatio": 0.7},
    ),
    "aBalancerWhosePodsStayPending": design(
        [
            clients(timeoutMs=1000),
            balancer(policy="least_connections"),
            service("hog", "Batch", platform="kubernetes", replicas=1, cpuCores=3),
            service("api", "API", platform="kubernetes", replicas=2, cpuCores=3),
            cluster(nodes=1, clusterAutoscaler=False, minNodes=1, maxNodes=1),
        ],
        [link("clients", "lb"), link("lb", "api")],
        {"rps": 40, "durationS": 3},
    ),
    "aClusterRemovingItsEmptyNodes": design(
        [
            clients(),
            service(
                "svc", "Shop", platform="kubernetes", replicas=1, serviceTimeMs=lognormal(5, 15)
            ),
            cluster(nodes=4, minNodes=2, maxNodes=5, scanIntervalS=0.5, scaleDownUnneededS=1),
        ],
        [link("clients", "svc")],
        {"rps": 60, "arrivalProcess": "poisson", "durationS": 4},
    ),
    "anAutoscalingServiceOnAGrowingCluster": design(
        [
            clients(timeoutMs=3000),
            service(
                "svc",
                "Checkout",
                platform="kubernetes",
                autoscaling=True,
                replicas=1,
                minReplicas=1,
                maxReplicas=6,
                targetUtilization=0.5,
                scaleIntervalS=0.5,
                startupS=1.5,
                cpuCores=0.8,
                workersPerReplica=4,
                serviceTimeMs=lognormal(15, 45),
            ),
            cluster(
                nodes=1,
                nodeVcpu=2,
                nodeMemoryGib=8,
                minNodes=1,
                maxNodes=3,
                nodeProvisionS=0,
                scanIntervalS=1,
                scaleDownUnneededS=1,
                imagePullS=0.3,
            ),
        ],
        [link("clients", "svc")],
        {
            "rps": 150,
            "arrivalProcess": "poisson",
            "durationS": 10,
            "pattern": {
                "kind": "trace",
                "points": [[0, 30], [2, 300], [4, 300], [4.2, 20], [10, 20]],
            },
        },
    ),
    "pendingPodsCancelledAsTheLoadFalls": design(
        [
            clients(timeoutMs=3000),
            service(
                "svc",
                "Search",
                platform="kubernetes",
                autoscaling=True,
                replicas=2,
                minReplicas=1,
                maxReplicas=8,
                targetUtilization=0.5,
                scaleIntervalS=0.5,
                startupS=0.2,
                cpuCores=1,
                workersPerReplica=4,
                serviceTimeMs=lognormal(15, 45),
            ),
            cluster(
                nodes=1,
                nodeVcpu=2.5,
                nodeMemoryGib=8,
                minNodes=1,
                maxNodes=4,
                nodeProvisionS=8,
                scanIntervalS=1,
                scaleDownUnneededS=2,
                imagePullS=0.3,
            ),
        ],
        [link("clients", "svc")],
        {
            "rps": 150,
            "arrivalProcess": "poisson",
            "durationS": 14,
            "pattern": {
                "kind": "trace",
                "points": [[0, 40], [0.5, 400], [1.5, 400], [2, 20], [14, 20]],
            },
        },
    ),
    "anAutoscalingServiceWhosePodsWait": design(
        [
            clients(timeoutMs=2000),
            service("hog", "Batch", platform="kubernetes", replicas=1, cpuCores=3),
            service(
                "svc",
                "API",
                platform="kubernetes",
                autoscaling=True,
                replicas=2,
                minReplicas=1,
                maxReplicas=4,
                targetUtilization=0.6,
                scaleIntervalS=0.5,
                startupS=0.2,
                cpuCores=1,
                memoryGib=1,
                memoryPerRpsMb=2,
                serviceTimeMs=lognormal(8, 20),
            ),
            service("report", "Reports", platform="kubernetes", replicas=1, cpuCores=3),
            cluster(
                nodes=1, minNodes=1, maxNodes=2, nodeProvisionS=3, scanIntervalS=1, imagePullS=0.5
            ),
        ],
        [link("clients", "svc")],
        {"rps": 30, "arrivalProcess": "poisson", "durationS": 8},
    ),
    "aCalibratedScenarioOfAPricedApi": calibrated(
        design(
            [
                clients(timeoutMs=250),
                external(
                    technology="payments",
                    latencyMs=lognormal(40, 150),
                    concurrencyLimit=20,
                    costPerMillionRequests=500,
                ),
            ],
            [link("clients", "api")],
            {"rps": 150, "arrivalProcess": "poisson"},
        )
    ),
}

# The scenario each case simulates; a case left out simulates the design itself.
SCENARIOS = {"aCalibratedScenarioOfAPricedApi": "peak"}


def cases() -> list[Json]:
    out = []
    for name, doc in CASES.items():
        scenario_id = SCENARIOS.get(name)
        report = run_simulation(copy.deepcopy(doc), scenario_id=scenario_id)
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        out.append({"name": name, "design": doc, "scenarioId": scenario_id, "report": report})
    return out


def export() -> bytes:
    text = json.dumps(
        {"generator": GENERATOR, "cases": cases()}, sort_keys=True, separators=(",", ":")
    )
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    data = export()
    if args.check:
        if not OUT.exists() or gzip.decompress(OUT.read_bytes()) != gzip.decompress(data):
            print(f"{OUT.name} differs from a fresh export: run scripts/export_simulation_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's simulations")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
