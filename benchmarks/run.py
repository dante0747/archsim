"""ArchSim accuracy benchmarks: calibrate at low load, predict with ArchSim, measure, compare.

    uv run python benchmarks/run.py postgres      # pg-mixed and pg-hot-row
    uv run python benchmarks/run.py cache         # cache-aside
    uv run python benchmarks/run.py kafka         # kafka
    uv run python benchmarks/run.py all
    uv run python benchmarks/run.py smoke chain   # start a stack, 20 req/s for 10 s, keep nothing
    uv run python benchmarks/run.py compare       # recompute every comparison.json (no Docker)
    uv run python benchmarks/run.py resources     # predict CPU and memory for older results
    uv run python benchmarks/run.py resimulate    # today's model against every kept run
    uv run python benchmarks/run.py envcheck      # characterise this machine
    uv run python benchmarks/run.py all --runs 3  # three independent runs of each experiment
    uv run python benchmarks/report.py            # rewrite docs/validation.md from the results

Protocol v5 (docs/validation.md): for each experiment the runner
1. starts the stack (benchmarks/compose.yaml) and settles it: VACUUM ANALYZE and a checkpoint
   after the data load (run 3's first calibration pass met the post-load autovacuum), then a
   discarded 30 s warm-up at the calibration rate;
2. calibrates: 20 s at rest (each container's idle CPU), then a staircase of the experiment's
   traffic from 50 req/s up to about a third of the service's core, down again and the top rate
   once more, reading what the service measured (CPU per request on the event loop, the time of
   each PostgreSQL, Redis and Kafka call, the database's CPU per query, Redis's own command
   time); each top pass gives one replicate set of model inputs, derived by formulas recorded in
   calibration.json, and the inputs are their medians;
3. predicts: builds the ArchSim design from the calibration and simulates it, finding the knee
   (the highest load with p95 <= 50 ms and >= 99% success), the knee of each replicate set (the
   predicted range) and every step of the load ladder (0.25x to 1.5x the predicted knee);
   predictions.json is written, and its sha256 logged, before step 4 starts;
4. measures: runs k6 at each step three times (5 s warm-up, 20 s measured, WARMUP_S and
   DURATION_S) and records p50, p95, p99, throughput and errors, and each container's CPU (busy
   cores) and memory (working set) in the measured window;
5. compares: comparison.json with errors per step and the knee error, and the predicted against
   the measured CPU and memory of each container the model has.

Held-out experiments (java-mixed, go-mixed, python-mixed, chain, cache-ttl; docs/validation.md)
are sealed: until the engine running is a frozen one (benchmarks/engines.json) and nothing is
uncommitted, the runner refuses to calibrate or measure them, and only ``smoke`` starts their
stacks (it checks that every request succeeds and is counted, and keeps no latency or results).

Only the Python standard library, Docker and the engine are needed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from collections.abc import Iterable, Mapping, Sequence
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from dataclasses import dataclass
from datetime import UTC, datetime
from itertools import pairwise
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
K6_OUT = RESULTS / ".k6"
API = "http://127.0.0.1:18080"
CONSUMER = "http://127.0.0.1:18081"

WARMUP_S = 5
DURATION_S = 20
PAUSE_S = 3
REPEATS = 3
TIMEOUT_MS = 2000
OBJECTIVE_P95_MS = 50.0
MIN_SUCCESS = 0.99
CALIBRATION_RPS = 50
CALIBRATION_S = 30
CALIBRATION_UTILIZATION = 0.25
"""Protocol v4's second pass: this share of the pessimistic capacity the first pass implies."""
IDLE_S = 20
"""Protocol v5: seconds of no load before the staircase, to measure each container's idle CPU."""
STAIRCASE_CPU_CAP = 0.35
"""Protocol v5: a rising calibration pass may use at most this share of the service's core."""
STAIRCASE_P95_CAP_MS = 10.0
"""Protocol v5: the staircase stops rising once a pass's p95 exceeds this."""
STAIRCASE_MAX_RISES = 3
STAIRCASE_MIN_RISE = 1.25
"""Protocol v5: a next step must be at least this many times the last, or the staircase stops."""
REPLICATES = 3
"""Protocol v5: passes at the staircase's top rate (up, down, up again): each gives its own
model inputs and predicted knee, hence the predicted range."""
DISTURBED_TAIL = 10.0
"""A calibration pass whose p95 is more than this many times its median was disturbed (a stall
the low load cannot explain); it is recorded, discarded and run once more."""
SETTLE_S = 30
MAX_EXTENSIONS = 4
STEP_FRACTIONS = (0.25, 0.5, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5)
SEED = 42
PG_POOL = 10
KAFKA_PARTITIONS = 6
KAFKA_CONSUMERS = 3
CACHE_TTL_S = 30
Z95 = 1.6448536269514722
SAMPLE_S = 5.0
"""Seconds between readings of each container's CPU and memory in a measured window (few, so
that reading them adds next to no load)."""
LOAD_START_TIMEOUT_S = 120
SERVICE_MEMORY_GIB = 1024
"""Memory of the service node in the model. The containers have no memory limit; this one is
large enough never to bind, so the model only reports the memory it predicts (with memory set,
a request that would not fit is refused)."""
CACHE_MEMORY_GIB = 4
"""Memory of the Redis node in the model: room for every key (3.3 million at CACHE_ITEM_KIB), so
nothing is evicted, as in the real Redis, which has no limit."""
CACHE_ITEM_KIB = 1.0
"""Memory per cached key in the model: ArchSim's default (not calibrated; the benchmark does not
measure Redis's bytes per key yet)."""
CONTAINER_NODES = {
    "api": "api",
    "producer": "api",
    "postgres": "db",
    "redis": "cache",
    "api-java": "api",
    "api-go": "api",
    "api-python": "api",
    "gateway": "gateway",
    "chain-api": "api",
    "api-ttl": "api",
    "redis-ttl": "cache",
}
"""The model node of each container. The consumer and the Kafka broker have none: the model's
queue node has no CPU or memory."""
SERVICE_CONTAINERS = ("api", "producer")
ENGINES = HERE / "engines.json"
"""Engine versions and what each was tuned on; a frozen one (frozenAt and tag set) may run
held-out experiments."""
SMOKE_RPS = 20
SMOKE_S = 10


@dataclass(frozen=True)
class HeldOut:
    """An experiment registered before any engine was tuned on it: it stays sealed (smoke tests
    only) until a frozen engine has predicted it."""

    registered: str
    """The date it was registered."""
    commit: str
    """The commit that registered it (its stack, workload and model)."""


@dataclass(frozen=True)
class Runtime:
    """How a stack's service runs, as its container runs it and as the ArchSim node models it."""

    technology: str
    """ArchSim's runtime profile (technology.py SERVICE_PROFILES)."""
    label: str
    cpu_cores: int
    """The container's CPUs (its cpuset)."""
    workers: int | None = None
    """Requests served at once (threads or worker processes); None for an event loop."""
    image: str = "node:24-alpine + pg 8.23.1, redis 4.7.1, kafkajs 2.2.4"


PYTHON_WORKERS = 10
"""gunicorn's sync workers (compose.yaml WEB_WORKERS), each with a pool of 1 connection: the
same 10 connections as the other stacks."""
RUNTIMES = {
    "nodejs": Runtime("nodejs", "Node.js service", 1),
    # Tomcat's thread pool as Spring Boot ships it (server.tomcat.threads.max)
    "java": Runtime(
        "java",
        "Spring Boot service",
        2,
        200,
        "eclipse-temurin:21-jre-alpine + Spring Boot 3.5.7 (Tomcat, HikariCP), G1, 1 GiB heap",
    ),
    # a goroutine per request, without a limit: the model's largest pool
    "go": Runtime("go", "Go service", 2, 4096, "alpine:3.22 + Go 1.25 net/http, pgx 5.9.2"),
    "python": Runtime(
        "python",
        "Flask service (gunicorn)",
        2,
        PYTHON_WORKERS,
        "python:3.13-slim + gunicorn 23.0.0 (sync), Flask 3.1.3, psycopg 3.2.10",
    ),
}


@dataclass(frozen=True)
class Experiment:
    id: str
    stack: str
    title: str
    mix: str
    read_ratio: float
    keys: int
    skew: float
    calibration: tuple[str, ...]
    runtime: str = "nodejs"
    """The service's runtime (RUNTIMES); chain's gateway and API are both Node.js."""
    ttl_s: int = CACHE_TTL_S
    """The cache's TTL (cache stacks)."""
    held_out: HeldOut | None = None


REGISTERED = HeldOut("2026-10-30", "90f067d6d40d9dae0ee7549907a562cf88bd2beb")
"""The five held-out experiments' registration (W19): the commit that added them."""


EXPERIMENTS = {
    e.id: e
    for e in (
        Experiment(
            "pg-mixed",
            "postgres",
            "Node.js + PostgreSQL: 90% reads, 10% writes over 100,000 rows",
            "read:0.9,write:0.1",
            0.9,
            100_000,
            0.0,
            ("read:0.9,write:0.1", "write_async:1"),
        ),
        Experiment(
            "pg-hot-row",
            "postgres",
            "Node.js + PostgreSQL: every request updates the same row",
            "hot:1",
            0.0,
            1,
            0.0,
            ("hot:1", "write_async:1"),
        ),
        Experiment(
            "cache-aside",
            "cache",
            "Cache-aside: Node.js, Redis (30 s TTL) and PostgreSQL; Zipf 0.99 over 100,000 keys",
            "cached:1",
            1.0,
            100_000,
            0.99,
            ("cached:1",),
        ),
        Experiment(
            "kafka",
            "kafka",
            "Kafka: Node.js producer, 6 partitions, 3 consumers inserting into PostgreSQL",
            "event:1",
            0.0,
            100_000,
            0.0,
            ("event:1", "write:1", "write_async:1"),
        ),
        # ---- held out (sealed; docs/validation.md, "Held-out experiments") ----------------------
        Experiment(
            "java-mixed",
            "java-mixed",
            "Spring Boot (Tomcat, HikariCP) + PostgreSQL: 90% reads, 10% writes over 100,000 rows",
            "read:0.9,write:0.1",
            0.9,
            100_000,
            0.0,
            ("read:0.9,write:0.1", "write_async:1"),
            runtime="java",
            held_out=REGISTERED,
        ),
        Experiment(
            "go-mixed",
            "go-mixed",
            "Go (net/http, pgx) + PostgreSQL: 90% reads, 10% writes over 100,000 rows",
            "read:0.9,write:0.1",
            0.9,
            100_000,
            0.0,
            ("read:0.9,write:0.1", "write_async:1"),
            runtime="go",
            held_out=REGISTERED,
        ),
        Experiment(
            "python-mixed",
            "python-mixed",
            "Flask on gunicorn (10 sync workers) + PostgreSQL: 90% reads, 10% writes over 100,000 rows",
            "read:0.9,write:0.1",
            0.9,
            100_000,
            0.0,
            ("read:0.9,write:0.1", "write_async:1"),
            runtime="python",
            held_out=REGISTERED,
        ),
        Experiment(
            "chain",
            "chain",
            "Node.js gateway, Node.js API, PostgreSQL in a chain: 90% reads, 10% writes over 100,000 rows",
            "read:0.9,write:0.1",
            0.9,
            100_000,
            0.0,
            ("read:0.9,write:0.1", "write_async:1"),
            held_out=REGISTERED,
        ),
        Experiment(
            "cache-ttl",
            "cache-ttl",
            "Cache-aside: Node.js, Redis (5 s TTL) and PostgreSQL; Zipf 1.1 over 1,000,000 keys",
            "cached:1",
            1.0,
            1_000_000,
            # Zipf 1.1, not the 0.8 first proposed: over 1,000,000 keys with a 5 s TTL, 0.8 gives a
            # hit ratio of 9% to 24% (250 to 4,000 req/s); 1.1 gives the intended 52% to 69%,
            # rising with load (sum of p_k x_k / (1 + x_k), x_k = rate x TTL x p_k).
            1.1,
            ("cached:1",),
            ttl_s=5,
            held_out=REGISTERED,
        ),
    )
}
STACKS = {
    "postgres": ["pg-mixed", "pg-hot-row"],
    "cache": ["cache-aside"],
    "kafka": ["kafka"],
    "java-mixed": ["java-mixed"],
    "go-mixed": ["go-mixed"],
    "python-mixed": ["python-mixed"],
    "chain": ["chain"],
    "cache-ttl": ["cache-ttl"],
}
DB_STACKS = ("postgres", "java-mixed", "go-mixed", "python-mixed", "chain")
"""Stacks whose service calls PostgreSQL directly (one service, or chain's API)."""
CACHE_STACKS = ("cache", "cache-ttl")


# ---------------------------------------------------------------------------------------------
# docker and HTTP helpers
# ---------------------------------------------------------------------------------------------


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def compose(*args: str, check: bool = True, capture: bool = False) -> str:
    """``docker compose`` with the benchmark stack. A failing command raises when ``check``;
    with ``capture``, what Docker printed is shown first (it would be lost otherwise)."""
    env = {**os.environ, "BENCH_REDIS_URL": os.environ.get("BENCH_REDIS_URL", "")}
    cmd = ["docker", "compose", "-f", str(HERE / "compose.yaml"), *args]
    proc = subprocess.run(cmd, cwd=HERE, env=env, check=False, text=True, capture_output=capture)
    if check and proc.returncode != 0:
        if capture:
            log(f"docker compose {' '.join(args[:4])} failed (exit {proc.returncode}):")
            print((proc.stdout or "")[-4000:], (proc.stderr or "")[-4000:], sep="\n", flush=True)
        raise subprocess.CalledProcessError(proc.returncode, cmd, proc.stdout, proc.stderr)
    return proc.stdout if capture else ""


def container(service: str) -> str:
    return compose("ps", "-q", service, capture=True).strip()


def cpu_usec(service: str) -> int | None:
    """CPU time a container has used (cgroup v2), in microseconds."""
    cid = container(service)
    if not cid:
        return None
    out = subprocess.run(
        ["docker", "exec", cid, "cat", "/sys/fs/cgroup/cpu.stat"],
        text=True,
        capture_output=True,
        check=False,
    ).stdout
    for line in out.splitlines():
        if line.startswith("usage_usec"):
            return int(line.split()[1])
    return None


def cpu_ms(before: int | None, after: int | None) -> float | None:
    """CPU used between two cpu_usec readings, in milliseconds."""
    return None if before is None or after is None else (after - before) / 1000


CGROUP_FILES = ("cpu.stat", "memory.stat", "memory.current")


@dataclass(frozen=True)
class Reading:
    """One container's cgroup v2 counters at one moment."""

    t: float
    """time.time() halfway through the read."""
    usage_usec: int | None
    working_set: int | None
    """Bytes: memory.current minus inactive_file (the page cache the kernel would reclaim
    first), as Kubernetes and cAdvisor count a container's working set."""
    anon: int | None
    """Bytes of anonymous memory (heaps and stacks): what the processes allocated."""


def parse_cgroup(text: str) -> dict[str, int]:
    """cpu.stat, memory.stat and memory.current, concatenated: every ``key value`` line (the
    first of each key) and memory.current's bare number as ``current``."""
    out: dict[str, int] = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            out.setdefault(parts[0], int(parts[1]))
        elif len(parts) == 1 and parts[0].isdigit():
            out["current"] = int(parts[0])
    return out


def reading(t: float, text: str) -> Reading:
    v = parse_cgroup(text)
    current = v.get("current")
    working = None if current is None else max(0, current - v.get("inactive_file", 0))
    return Reading(t, v.get("usage_usec"), working, v.get("anon"))


def cgroup_dir(cid: str) -> Path | None:
    """A container's cgroup directory when this host can read it (Linux with cgroup v2, as on
    GitHub's runners); None elsewhere (Docker Desktop runs containers in a VM)."""
    for d in (
        Path(f"/sys/fs/cgroup/system.slice/docker-{cid}.scope"),
        Path(f"/sys/fs/cgroup/docker/{cid}"),
    ):
        if (d / "cpu.stat").is_file():
            return d
    return None


def read_cgroup(cid: str) -> Reading:
    """A container's CPU and memory counters now: from the host when it can see them, else
    through ``docker exec`` (a ``cat`` in the container, about a millisecond of its CPU)."""
    t0 = time.time()
    d = cgroup_dir(cid)
    if d is not None:
        text = "\n".join((d / f).read_text() for f in CGROUP_FILES if (d / f).is_file())
    else:
        text = subprocess.run(
            ["docker", "exec", cid, "cat", *(f"/sys/fs/cgroup/{f}" for f in CGROUP_FILES)],
            text=True,
            capture_output=True,
            check=False,
        ).stdout
    return reading((t0 + time.time()) / 2, text)


def window_resources(readings: Sequence[Reading]) -> dict[str, float] | None:
    """One container's CPU and memory over a measured window, from its readings: busy cores (CPU
    time over wall time between the first and the last reading), the busiest interval between
    two readings, and the highest working set and anonymous memory (MiB)."""
    out: dict[str, float] = {}
    cpu = [(r.t, r.usage_usec) for r in readings if r.usage_usec is not None]
    rates = [(u1 - u0) / 1e6 / (t1 - t0) for (t0, u0), (t1, u1) in pairwise(cpu) if t1 > t0]
    if rates:
        out["cores"] = round((cpu[-1][1] - cpu[0][1]) / 1e6 / (cpu[-1][0] - cpu[0][0]), 4)
        out["maxCores"] = round(max(rates), 4)
    working = [r.working_set for r in readings if r.working_set is not None]
    if working:
        out["workingSetMiB"] = round(max(working) / 2**20, 1)
    anon = [r.anon for r in readings if r.anon is not None]
    if anon:
        out["anonMiB"] = round(max(anon) / 2**20, 1)
    return out or None


class ResourceSampler:
    """Reads each container's CPU and memory through the window k6 measures, beside a step.

    The load starts when the service first counts a finished request (its counters start at
    /reset, which runs just before k6); the window starts WARMUP_S later and lasts DURATION_S,
    less a second for the late detection of the start. k6's own container start and stop (several
    seconds) are outside it, unlike containerCpuShare, which spans the whole k6 command. A
    calibration pass, which k6 records from its start, is sampled from the start (``warm`` 0)
    for CALIBRATION_S."""

    def __init__(
        self, services: Sequence[str], warm: float | None = None, duration: float | None = None
    ) -> None:
        self.services = list(services)
        self.warm = WARMUP_S if warm is None else warm
        self.duration = DURATION_S if duration is None else duration
        self.readings: dict[str, list[Reading]] = {s: [] for s in self.services}
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self) -> ResourceSampler:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._stop.set()
        self._thread.join(timeout=60)

    def _load_started(self) -> float | None:
        deadline = time.time() + LOAD_START_TIMEOUT_S
        while not self._stop.is_set() and time.time() < deadline:
            try:
                if http_json(f"{API}/stats").get("requests", 0) > 0:
                    return time.time()
            except OSError:
                pass
            self._stop.wait(0.2)
        return None

    def _run(self) -> None:
        ids = {s: c for s in self.services if (c := container(s))}
        start = self._load_started()
        if start is None or not ids:
            return
        t, end = start + self.warm, start + self.warm + self.duration - 1
        with ThreadPoolExecutor(max_workers=len(ids)) as pool:
            while not self._stop.wait(max(0.0, t - time.time())):
                for s, r in zip(ids, pool.map(read_cgroup, ids.values()), strict=True):
                    self.readings[s].append(r)
                if t >= end:
                    break
                t = min(t + SAMPLE_S, end)

    def result(self) -> dict[str, Any] | None:
        containers = {s: w for s, rs in self.readings.items() if (w := window_resources(rs))}
        times = [r.t for rs in self.readings.values() for r in rs]
        if not containers:
            return None
        return {
            "method": "window",
            "seconds": round(max(times) - min(times), 2),
            "readings": max(len(rs) for rs in self.readings.values()),
            "containers": containers,
        }


def http_json(url: str, method: str = "GET") -> dict[str, Any]:
    req = urllib.request.Request(url, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def wait_http(url: str, seconds: float = 120) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            http_json(url)
            return
        except OSError:
            time.sleep(1)
    raise SystemExit(f"{url} did not answer within {seconds:.0f} s")


def redis_cmdstats() -> dict[str, dict[str, float]]:
    out = compose("exec", "-T", "redis", "redis-cli", "INFO", "commandstats", capture=True)
    stats: dict[str, dict[str, float]] = {}
    for line in out.splitlines():
        if line.startswith("cmdstat_"):
            name, rest = line.split(":", 1)
            stats[name.removeprefix("cmdstat_")] = {
                k: float(v)
                for k, v in (kv.split("=") for kv in rest.split(","))
                if k in ("calls", "usec")
            }
    return stats


def k6(
    target: str,
    mix: str,
    rate: float,
    warm: int,
    duration: int,
    out: str,
    keys: int,
    skew: float,
) -> dict[str, Any]:
    K6_OUT.mkdir(parents=True, exist_ok=True)
    # The grafana/k6 image runs as uid 12345: on Linux the bind-mounted directory must be
    # writable by it or handleSummary cannot write the summary. Not tested on Linux yet (Docker
    # Desktop on Windows, where run 4 ran, maps ownership itself).
    K6_OUT.chmod(0o777)
    path = K6_OUT / f"{out}.json"
    path.unlink(missing_ok=True)
    compose(
        "--profile", "load", "run", "--rm",
        "-e", f"TARGET={target}", "-e", f"MIX={mix}", "-e", f"RATE={rate:g}",
        "-e", f"WARMUP_S={warm}", "-e", f"DURATION_S={duration}", "-e", f"KEYS={keys}",
        "-e", f"SKEW={skew:g}", "-e", f"TIMEOUT_MS={TIMEOUT_MS}", "-e", f"OUT={out}",
        "k6", "run", "-q", "/scripts/load.js",
        capture=True,
    )  # fmt: skip
    return json.loads(path.read_text(encoding="utf-8"))


def k6_measured(summary: dict[str, Any], duration: int) -> dict[str, Any]:
    """The measured window of a k6 summary: latency of successful requests, success, throughput.

    With no successful request k6 still reports the latency trend, as zeros; that is no latency
    at all, so p50, p95, p99 and the mean are None (run 4 recorded such repeats as 0 ms)."""
    ok = summary.get("http_req_duration{scenario:measure,expected_response:true}", {}).get(
        "values", {}
    )
    reqs = summary.get("http_reqs{scenario:measure}", {}).get("values", {})
    failed = summary.get("http_req_failed{scenario:measure}", {}).get("values", {})
    dropped = summary.get("dropped_iterations{scenario:measure}", {}).get("values", {})
    sent = int(reqs.get("count", 0))
    n_dropped = int(dropped.get("count", 0))
    n_failed = int(failed.get("passes", 0))
    succeeded = int(ok.get("count", 0))
    attempted = sent + n_dropped
    latency = ok if succeeded else {}
    return {
        "p50": latency.get("med"),
        "p95": latency.get("p(95)"),
        "p99": latency.get("p(99)"),
        "mean": latency.get("avg"),
        "sent": sent,
        "dropped": n_dropped,
        "failed": n_failed,
        "succeeded": succeeded,
        "successRate": succeeded / attempted if attempted else None,
        "throughputRps": succeeded / duration,
    }


# ---------------------------------------------------------------------------------------------
# stack lifecycle
# ---------------------------------------------------------------------------------------------


K6_CPUS = {
    "postgres": "0,1,6,7",
    "cache": "0,6,7",
    "kafka": "6,7",
    "java-mixed": "0,1,6,7",
    "go-mixed": "0,1,6,7",
    "python-mixed": "0,1,6,7",
    "chain": "6,7",
    "cache-ttl": "6,7",
}
"""k6's cpuset per stack: the CPUs the stack's other containers leave free, except the service's
hyperthread sibling (CPU 3 on the 4-core, 8-thread laptop), which stays idle. On the laptop
k6 on that sibling cost the service 10-15% more CPU per request and raised p95 at 1,600 req/s
from 1.4 to 2.2 ms (run.py envcheck, 30 Oct; runs up to 6 used 0,1,3,6,7 and 0,3,6,7).
The held-out stacks give no container a physical core shared with k6 or another container:
chain and cache-ttl leave CPUs 1 and 3 idle (their second service, or Redis, takes CPU 0) and
give k6 one core. compose.yaml's default for k6 is only used when the runner is bypassed."""
USER_K6_CPUS = os.environ.get("BENCH_CPUS_K6")
STACK_SERVICES = {
    "postgres": ["api", "postgres"],
    "cache": ["api", "postgres", "redis"],
    "kafka": ["producer", "consumer", "postgres", "kafka"],
    "java-mixed": ["api-java", "postgres"],
    "go-mixed": ["api-go", "postgres"],
    "python-mixed": ["api-python", "postgres"],
    # chain: the gateway first, the hop k6 calls and whose /stats (port 18080) calibration reads.
    # Both hops have one CPU; the API's CPU per request is in the gateway's /stats as `upstream`,
    # so a CPU cap should take the busier of the two.
    "chain": ["gateway", "chain-api", "postgres"],
    "cache-ttl": ["api-ttl", "postgres", "redis-ttl"],
}
"""Each stack's containers, the service under test first (the one k6 calls, ENTRY)."""
ENTRY = {
    "kafka": "producer",
    "java-mixed": "api-java",
    "go-mixed": "api-go",
    "python-mixed": "api-python",
    "chain": "gateway",
    "cache-ttl": "api-ttl",
}
"""The container k6 calls and API (port 18080) reaches, when it is not ``api``."""
DEFAULT_ITEMS = 100_000
"""Rows init.sql loads; a stack whose keyspace is larger gets the rest (db/extra-rows.sh)."""


def compose_cpusets() -> dict[str, tuple[str, str]]:
    """Each container's cpuset in compose.yaml: (environment variable, default)."""
    out: dict[str, tuple[str, str]] = {}
    service = None
    for line in (HERE / "compose.yaml").read_text(encoding="utf-8").splitlines():
        if m := re.match(r"^  ([\w-]+):\s*$", line):
            service = m.group(1)
        elif (m := re.match(r'^\s+cpuset:\s*"\$\{(\w+):-([^}]*)\}"', line)) and service:
            out[service] = (m.group(1), m.group(2))
    return out


def cpusets(stack: str, env: Mapping[str, str] | None = None) -> dict[str, str]:
    """The CPUs each container of a stack is pinned to, k6 included, as the runner starts it:
    BENCH_CPUS_* when set, else compose.yaml's defaults (k6: K6_CPUS). ``env`` defaults to the
    runner's environment as it started (stack_up overwrites BENCH_CPUS_K6 per stack)."""
    user_k6 = USER_K6_CPUS if env is None else env.get("BENCH_CPUS_K6")
    env = os.environ if env is None else env
    declared = compose_cpusets()
    out = {}
    for service in STACK_SERVICES[stack]:
        var, default = declared[service]
        out[service] = env.get(var) or default
    out["k6"] = user_k6 or K6_CPUS[stack]
    return out


def cpu_list(cpuset: str) -> list[int]:
    """The CPUs a cpuset ("0,1,3" or "0-3") names."""
    out: list[int] = []
    for part in cpuset.split(","):
        lo, _, hi = part.strip().partition("-")
        out += list(range(int(lo), int(hi) + 1)) if hi else [int(lo)]
    return out


def cpu_count(cpuset: str) -> int:
    """How many CPUs a cpuset ("0,1,3" or "0-3") names."""
    return len(cpu_list(cpuset))


def cpu_siblings() -> dict[int, list[int]] | None:
    """Each CPU of Docker's VM and the CPUs on its physical core (itself included: hyperthread
    siblings), as the kernel inside the VM reports them; None when Docker cannot say."""
    script = (
        "for c in /sys/devices/system/cpu/cpu[0-9]*; do "
        'echo "${c##*cpu} $(cat $c/topology/thread_siblings_list)"; done'
    )
    proc = subprocess.run(
        ["docker", "run", "--rm", "--entrypoint", "sh", "postgres:16-alpine", "-c", script],
        text=True,
        capture_output=True,
        check=False,
    )
    out: dict[int, list[int]] = {}
    for line in proc.stdout.splitlines():
        cpu, _, sib = line.strip().partition(" ")
        if cpu.isdigit() and sib:
            out[int(cpu)] = cpu_list(sib)
    return out or None


def core_of(cpu: int, siblings: Mapping[int, Sequence[int]]) -> tuple[int, ...]:
    """A CPU's physical core, named by its CPUs."""
    return tuple(sorted(siblings.get(cpu, [cpu])))


def topology(sets: Mapping[str, str], siblings: Mapping[int, Sequence[int]]) -> dict[str, Any]:
    """How a stack's cpusets fall on physical cores: each container's physical cores, and the
    pairs of containers that share one (hyperthread siblings slow each other down)."""
    cores = {name: {core_of(c, siblings) for c in cpu_list(s)} for name, s in sets.items()}
    names = list(sets)
    shared = [
        {"containers": [a, b], "cores": [list(c) for c in sorted(cores[a] & cores[b])]}
        for i, a in enumerate(names)
        for b in names[i + 1 :]
        if cores[a] & cores[b]
    ]
    return {
        "siblings": {str(c): list(s) for c, s in sorted(siblings.items())},
        "physicalCores": {name: len(c) for name, c in cores.items()},
        "sharedCores": shared,
    }


def stack_items(stack: str) -> int:
    """Rows of items PostgreSQL loads for a stack: enough for every key its experiments name."""
    return max(DEFAULT_ITEMS, *(EXPERIMENTS[e].keys for e in STACKS[stack]))


def stack_up(stack: str) -> None:
    os.environ["BENCH_CPUS_K6"] = cpusets(stack)["k6"]
    if stack == "cache":
        os.environ["BENCH_REDIS_URL"] = "redis://redis:6379"
    else:
        os.environ["BENCH_REDIS_URL"] = ""
    os.environ["BENCH_ITEMS"] = str(stack_items(stack))
    log(f"starting stack '{stack}'")
    compose("--profile", stack, "down", "-v", "--remove-orphans", check=False, capture=True)
    compose("--profile", stack, "up", "-d", "--build", "--wait", capture=True)
    wait_http(f"{API}/health")
    if stack == "kafka":
        wait_http(f"{CONSUMER}/health")
        time.sleep(5)  # consumer group join


def stack_down(stack: str) -> None:
    log(f"stopping stack '{stack}'")
    compose("--profile", stack, "down", "-v", "--remove-orphans", check=False, capture=True)


def target_for(exp: Experiment) -> str:
    return f"http://{ENTRY.get(exp.stack, 'api')}:8080"


def services_for(exp: Experiment) -> list[str]:
    return STACK_SERVICES[exp.stack]


# ---------------------------------------------------------------------------------------------
# 1. calibration
# ---------------------------------------------------------------------------------------------


def settle(exp: Experiment) -> dict[str, Any]:
    """After the data load: VACUUM ANALYZE and a checkpoint (so autovacuum and the first
    checkpoint do not land in a calibration pass), then a discarded warm-up at the calibration
    rate (caches, connection pools, JIT)."""
    log(f"settling {exp.id}: VACUUM ANALYZE, CHECKPOINT, {SETTLE_S} s warm-up")
    t0 = time.time()
    compose(
        "exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-q",
        "-c", "VACUUM ANALYZE", "-c", "CHECKPOINT",
        capture=True,
    )  # fmt: skip
    k6(
        target_for(exp),
        exp.calibration[0],
        CALIBRATION_RPS,
        0,
        SETTLE_S,
        "settle",
        exp.keys,
        exp.skew,
    )
    return {
        "at": now(),
        "seconds": round(time.time() - t0, 1),
        "steps": [
            "VACUUM ANALYZE",
            "CHECKPOINT",
            f"{SETTLE_S} s warm-up at {CALIBRATION_RPS} req/s",
        ],
    }


def disturbed(entry: dict[str, Any]) -> bool:
    k = entry["k6"]
    return bool(k.get("p50")) and (k.get("p95") or 0) > DISTURBED_TAIL * k["p50"]


def steady_pass(
    exp: Experiment, mix: str, rate: float, label: str, discarded: list[dict[str, Any]]
) -> dict[str, Any]:
    """A calibration pass, run once more when it was disturbed (both kept in the record)."""
    entry = calibration_pass(exp, mix, rate, label)
    if disturbed(entry):
        log(
            f"{exp.id}: {label} pass disturbed (p95 {entry['k6']['p95']:.1f} ms, p50 {entry['k6']['p50']:.2f} ms); running it again"
        )
        discarded.append(
            {**entry, "label": label, "reason": f"p95 above {DISTURBED_TAIL:g}x the median"}
        )
        entry = calibration_pass(exp, mix, rate, label)
    return entry


def calibration_pass(exp: Experiment, mix: str, rate: float, label: str) -> dict[str, Any]:
    """One calibration run: 10 s warm-up at the rate, statistics reset, 30 s recorded."""
    target = target_for(exp)
    log(f"calibrating {exp.id}: {mix} at {rate:g} req/s ({label})")
    k6(target, mix, rate, 0, 10, "cal-warm", exp.keys, exp.skew)
    if exp.stack == "cache":
        compose("exec", "-T", "redis", "redis-cli", "CONFIG", "RESETSTAT", capture=True)
    http_json(f"{API}/reset", "POST")
    if exp.stack == "kafka":
        http_json(f"{CONSUMER}/reset", "POST")
    cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
    # each container's memory (and CPU) inside the pass, for the memory fit (memory_inputs)
    with ResourceSampler(services_for(exp), warm=0, duration=CALIBRATION_S) as sampler:
        summary = k6(
            target, mix, rate, 0, CALIBRATION_S, f"cal-{exp.id}-{label}", exp.keys, exp.skew
        )
    time.sleep(2)  # let the consumer finish the last messages
    cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
    entry: dict[str, Any] = {
        "mix": mix,
        "rateRps": rate,
        "k6": k6_measured(summary, CALIBRATION_S),
        "service": http_json(f"{API}/stats"),
        "containerCpuMs": {s: cpu_ms(cpu0[s], cpu1[s]) for s in cpu0},
    }
    if (resources := sampler.result()) is not None:
        entry["resources"] = resources
    if exp.stack == "kafka":
        entry["consumer"] = http_json(f"{CONSUMER}/stats")
    if exp.stack == "cache":
        entry["redis"] = redis_cmdstats()
    return entry


def hops(svc: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    """The service's own stats and, behind chain's gateway, the API's (``upstream``)."""
    return [svc, *([svc["upstream"]] if svc.get("upstream") else [])]


def capacity_bound(exp: Experiment, low: Mapping[str, Any]) -> float:
    """The most pessimistic capacity the low pass implies (req/s): each hop's CPU at its
    low-load cost per request, the connection pool, the hot row's lock, the consumers."""
    svc = low["service"]
    t = svc["timings"]
    bounds = [1000 * service_cpus(exp) / h["cpuMsPerRequest"] for h in hops(svc)]
    for h in hops(svc):
        for name in ("db_read", "db_write"):
            if name in h["timings"]:
                bounds.append(PG_POOL * 1000 / h["timings"][name]["mean"])
    if exp.id == "pg-hot-row":
        bounds.append(1000 / t["db_write"]["mean"])  # one row: one write at a time
    if exp.stack == "kafka":
        cons = low["consumer"]
        bounds.append(KAFKA_CONSUMERS * 1000 / cons["timings"]["consume"]["mean"])
    return min(bounds)


def idle_pass(exp: Experiment) -> dict[str, Any]:
    """IDLE_S of no load: each container's CPU, and the service's own counter, at rest. Protocol
    v5 takes it off every pass's CPU, so CPU per request is what requests cost."""
    log(f"calibrating {exp.id}: {IDLE_S} s idle")
    http_json(f"{API}/reset", "POST")
    cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
    time.sleep(IDLE_S)
    cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
    svc = http_json(f"{API}/stats")
    # each container's working set at rest: the memory fit's point at 0 req/s (memory_inputs)
    rest = {s: read_cgroup(c).working_set for s in cpu0 if (c := container(s))}
    return {
        "seconds": IDLE_S,
        "containerCpuMsPerSecond": {
            s: (round(ms / IDLE_S, 4) if (ms := cpu_ms(cpu0[s], cpu1[s])) is not None else None)
            for s in cpu0
        },
        "serviceCpuMsPerSecond": round((svc.get("cpuMs") or 0.0) / IDLE_S, 4),
        "workingSetMiB": {s: round(b / 2**20, 1) for s, b in rest.items() if b is not None},
    }


def service_cpus(exp: Experiment) -> int:
    """The CPUs of the service the staircase caps: the stack's first container (STACK_SERVICES
    lists the service under test first). A Node.js event loop uses one of them however many it
    has, which its cpuset of one CPU reflects."""
    return cpu_count(cpusets(exp.stack)[STACK_SERVICES[exp.stack][0]])


def next_rise(exp: Experiment, entry: Mapping[str, Any]) -> float | None:
    """The staircase's next rate after a pass, or None to stop: half the most pessimistic
    capacity the pass implies, but no more than STAIRCASE_CPU_CAP of the service's CPUs at the
    pass's CPU per request (the whole process's: every thread or worker); stop when the pass's p95
    is above STAIRCASE_P95_CAP_MS or the next rate would not be STAIRCASE_MIN_RISE times this
    one."""
    rate = entry["rateRps"]
    p95 = entry["k6"].get("p95")
    if p95 is not None and p95 > STAIRCASE_P95_CAP_MS:
        return None
    cpu = max(h["cpuMsPerRequest"] for h in hops(entry["service"]))  # chain: the busier hop
    cap = STAIRCASE_CPU_CAP * 1000 * service_cpus(exp) / max(cpu, 1e-6)
    nxt = min(0.5 * capacity_bound(exp, entry), cap)
    return float(round(nxt)) if nxt >= rate * STAIRCASE_MIN_RISE else None


def calibrate(exp: Experiment) -> dict[str, Any]:
    """Protocol v5. After settling and IDLE_S at rest, a staircase of the experiment's traffic:
    50 req/s, then each pass at half the capacity the previous one implies, up to
    STAIRCASE_CPU_CAP of the service's core (at most STAIRCASE_MAX_RISES rises). Then down
    again, and the top rate once more: every rate twice, the top REPLICATES times. Each top pass,
    with the auxiliary mixes (writes without the WAL flush) run right after it at the same rate,
    gives one replicate set of model inputs (CPU per request net of idle, timings from the pass);
    the inputs are their medians. ``cpuByLoad`` keeps each rate's CPU per request."""
    main_mix = exp.calibration[0]
    settled = settle(exp)
    idle = idle_pass(exp)
    discarded: list[dict[str, Any]] = []
    rates: list[float] = []
    runs: list[dict[str, Any]] = []
    rate: float | None = CALIBRATION_RPS
    while rate is not None:
        rates.append(rate)
        entry = steady_pass(exp, main_mix, rate, f"up {len(rates)}", discarded)
        runs.append({**entry, "label": f"up {len(rates)}"})
        rate = next_rise(exp, entry) if len(rates) <= STAIRCASE_MAX_RISES else None
    top = rates[-1]

    def top_set(entry: dict[str, Any], i: int) -> dict[str, Any]:
        aux = {
            mix: steady_pass(exp, mix, top, f"{mix.split(':')[0]} {i}", discarded)
            for mix in exp.calibration[1:]
        }
        return {"low": runs[0], "high": entry, "idle": idle, "staircase": runs, **aux}

    sets = [top_set(runs[-1], 1)]
    for r in reversed(rates):
        entry = steady_pass(exp, main_mix, r, f"down {rates.index(r) + 1}", discarded)
        runs.append({**entry, "label": f"down {rates.index(r) + 1}"})
        if r == top:
            sets.append(top_set(entry, len(sets) + 1))

    # A top pass still disturbed after its retry gives no replicate: run another in its place
    # (at most two more), so one stall cannot set an input or widen the range.
    def clean() -> list[dict[str, Any]]:
        return [s for s in sets if not disturbed(s["high"])]

    while len(clean()) < REPLICATES and len(sets) < REPLICATES + 2:
        entry = steady_pass(exp, main_mix, top, f"top {len(sets) + 1}", discarded)
        runs.append({**entry, "label": f"top {len(sets) + 1}"})
        sets.append(top_set(entry, len(sets) + 1))
    used = clean() if len(clean()) >= 2 else sets
    replicate_inputs = []
    for s in used:
        inputs = model_inputs(exp, s)
        anchor(exp, inputs, s["high"])
        replicate_inputs.append(inputs)
    return {
        "experiment": exp.id,
        "at": now(),
        "protocol": "v5",
        "method": (
            "staircase up and down, CPU per request net of idle at the top rate, median of "
            f"{len(used)} replicates, anchored at the top rate, settled first (v5)"
        ),
        "settle": settled,
        "idle": idle,
        "staircaseRps": rates,
        "capacityBoundRps": round(capacity_bound(exp, runs[0]), 1),
        # one view the v4 readers (report.py, the low-load reference) understand
        "passes": used[0],
        "staircase": runs,
        "replicates": [
            {k: v for k, v in s.items() if k not in ("low", "idle", "staircase")} for s in used
        ],
        # top passes still disturbed after their retry, kept for the record only
        "disturbedReplicates": [s["high"] for s in sets if s not in used],
        "discardedPasses": discarded,
        "cpuByLoad": cpu_by_load(runs, idle),
        "inputReplicates": replicate_inputs,
        "inputs": median_inputs(replicate_inputs),
    }


def net_cpu_ms(entry: Mapping[str, Any], idle: Mapping[str, Any], container: str) -> float | None:
    """A container's CPU in a pass less what it burns at rest over the pass (protocol v5)."""
    ms = entry["containerCpuMs"].get(container)
    rest = idle["containerCpuMsPerSecond"].get(container)
    if ms is None:
        return None
    return max(0.0, ms - (rest or 0.0) * CALIBRATION_S)


def cpu_by_load(runs: Sequence[Mapping[str, Any]], idle: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Each staircase rate's CPU per request (median over its passes), net of idle: the
    service's own counter and each container's cgroup."""
    out = []
    for rate in sorted({r["rateRps"] for r in runs}):
        at = [r for r in runs if r["rateRps"] == rate]
        svc_rest = idle["serviceCpuMsPerSecond"] * CALIBRATION_S
        service = [
            max(0.0, r["service"]["cpuMs"] - svc_rest) / max(r["service"]["requests"], 1)
            for r in at
        ]
        containers = {
            c: median(
                (ms / max(r["service"]["requests"], 1))
                if (ms := net_cpu_ms(r, idle, c)) is not None
                else None
                for r in at
            )
            for c in at[0]["containerCpuMs"]
        }
        out.append(
            {
                "rateRps": rate,
                "passes": len(at),
                "serviceCpuMsPerRequest": round(median(service) or 0.0, 4),
                "containerCpuMsPerRequest": {
                    c: (round(v, 4) if v is not None else None) for c, v in containers.items()
                },
            }
        )
    return out


def primary(value: Any) -> float | None:
    """The number an input is ranked by across replicates: itself, or a distribution's median
    (its mean when it has no median)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    if isinstance(value, dict):
        for k in ("median", "mean", "value"):
            if isinstance(value.get(k), int | float):
                return float(value[k])
    return None


def median_inputs(replicates: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """The model inputs from the replicate sets. Each input is taken whole from the replicate
    whose value is the median (by ``primary``), so a distribution's median and p95 come from the
    same pass and its formula is that pass's; a disturbed pass falls to the edge and drops out.
    Records without a value (the low-load reference, the anchor) take each number's median."""

    def merge(values: list[Any]) -> Any:
        first = values[0]
        if isinstance(first, bool) or not isinstance(first, int | float | dict):
            return first
        if isinstance(first, dict):
            return {k: merge([v[k] for v in values if k in v]) for k in first}
        nums = [v for v in values if isinstance(v, int | float)]
        m = median(nums)
        return round(m, 4) if isinstance(m, float) else m

    out: dict[str, Any] = {}
    n = len(replicates)
    for key, first in replicates[0].items():
        items = [r[key] for r in replicates if key in r]
        if isinstance(first, dict) and "value" in first:
            ranked = sorted(
                (i for i, x in enumerate(items) if primary(x["value"]) is not None),
                key=lambda i: primary(items[i]["value"]) or 0.0,
            )
            pick = items[ranked[(len(ranked) - 1) // 2]] if ranked else first
            out[key] = {
                **pick,
                "how": f"the median of {n} replicates ({ordinal(items.index(pick))}): {pick['how']}",
            }
        else:
            out[key] = merge(items)
    return out


def ordinal(i: int) -> str:
    return ("first", "second", "third", "fourth", "fifth")[i] if i < 5 else f"number {i + 1}"


def anchor(exp: Experiment, inputs: dict[str, Any], high: dict[str, Any]) -> None:
    """Make the model reproduce the calibration pass itself. The client-to-service latency is the
    one input not measured directly; it absorbs the difference between the measured median and
    the model's median at that rate (simulated with no client latency), floored at zero. Without
    this the model counts the database driver's CPU twice (in the CPU per request and inside the
    measured query time) and starts about 2x too slow (run 2)."""
    rate = high["rateRps"]
    probe = {**inputs, "clientLinkLatencyMs": {"value": 0.0}}
    sim = simulate(design(exp, probe, rate, warm=5, duration=CALIBRATION_S))
    measured = high["k6"]["p50"]
    one_way = max(0.0, (measured - (sim["p50"] or 0.0)) / 2)
    inputs["clientLinkLatencyMs"] = {
        "value": round(one_way, 4),
        "how": (
            f"anchored: (measured median {measured:.3f} ms - the model's median without it "
            f"{sim['p50'] or 0:.3f} ms at {rate:g} req/s) / 2, one way, at least 0"
        ),
    }
    inputs["anchor"] = {
        "rateRps": rate,
        "measuredP50": measured,
        "modelP50WithoutClientLatency": sim["p50"],
        "residualMs": round((sim["p50"] or 0.0) + 2 * one_way - measured, 4),
    }


def lognormal(median: float, p95: float) -> dict[str, Any]:
    median = max(median, 0.001)
    return {
        "kind": "lognormal",
        "median": round(median, 4),
        "p95": round(max(p95, median * 1.01), 4),
    }


def lognormal_with_mean(mean: float, shape: dict[str, float]) -> dict[str, Any]:
    """A lognormal with the given mean and the p95/median ratio of ``shape``."""
    ratio = max(shape["p95"] / max(shape["median"], 1e-9), 1.01)
    sigma = math.log(ratio) / Z95
    median = mean / math.exp(sigma**2 / 2)
    return lognormal(median, median * ratio)


def timing(stats: dict[str, Any], name: str) -> dict[str, float]:
    t = stats["timings"].get(name)
    if not t:
        raise SystemExit(f"calibration recorded no '{name}' timings")
    return t


def slope(low: float | None, high: float | None, n_low: float, n_high: float) -> float | None:
    """Extra resource per extra unit of work between two passes of equal length."""
    if low is None or high is None or n_high <= n_low:
        return None
    return (high - low) / (n_high - n_low)


def model_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """Every model input with the measured numbers and the formula it came from: the service's,
    then the queue's (Kafka), the database's, the cache's and the services' memory. Each part's
    inputs come from a function of their own, so a change to one part's calibration leaves the
    others alone. The memory fit reads the whole staircase (``passes["staircase"]``)."""
    out = service_inputs(exp, passes)
    if exp.stack == "kafka":
        out |= queue_inputs(exp, passes)
    out |= database_inputs(exp, passes)
    if exp.stack in CACHE_STACKS:
        out |= cache_inputs(exp, passes)
    out |= memory_inputs(exp, passes)
    return out


def service_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """The service's CPU per request, the client-to-service latency and the low-load reference;
    for Java and Go its garbage collection, and for chain the API behind the gateway."""
    low, high = passes["low"], passes["high"]
    svc, svc_low = high["service"], low["service"]
    own = timing(svc, "own")
    handler = timing(svc, "handler")
    who = "Node" if exp.runtime == "nodejs" else f"the {RUNTIMES[exp.runtime].label}"
    shape = (
        f"shape (p95/median {own['p95']:.4f}/{own['median']:.4f} ms) from the handler's own time"
    )
    if (idle := passes.get("idle")) is not None:  # protocol v5: the top pass, net of idle
        rest = idle["serviceCpuMsPerSecond"] * CALIBRATION_S
        demand = max(svc["cpuMs"] - rest, 0.0) / max(svc["requests"], 1)
        if demand <= 0:
            demand = svc["cpuMsPerRequest"]
        how = (
            f"mean = {who}'s CPU per request at {high['rateRps']:g} req/s net of idle: "
            f"({svc['cpuMs']:.0f} - {rest:.0f} ms at rest) / {svc['requests']} requests = "
            f"{demand:.4f} ms; {shape}"
        )
    else:  # protocol v2 to v4: the slope between two passes
        slope_ms = slope(svc_low["cpuMs"], svc["cpuMs"], svc_low["requests"], svc["requests"])
        demand = slope_ms if slope_ms is not None and slope_ms > 0 else svc["cpuMsPerRequest"]
        how = (
            f"mean = {who}'s CPU per extra request between the passes: ({svc['cpuMs']:.0f} - "
            f"{svc_low['cpuMs']:.0f} ms) / ({svc['requests']} - {svc_low['requests']} requests) = "
            f"{demand:.4f} ms (at {low['rateRps']:g} req/s alone: {svc_low['cpuMsPerRequest']:.4f} ms); "
            f"{shape}"
        )
    out = {
        "serviceTimeMs": {"value": lognormal_with_mean(demand, own), "how": how},
        "clientLinkLatencyMs": {
            "value": round(max(0.0, (high["k6"]["p50"] - handler["median"]) / 2), 4),
            "how": f"(k6 median {high['k6']['p50']:.3f} ms - handler median {handler['median']:.3f} ms) / 2, one way",
        },
        "measuredLowLoad": {"rateRps": high["rateRps"], **high["k6"]},
    }
    if exp.runtime != "nodejs":
        out |= gc_inputs(exp, svc)
    if exp.stack == "chain":
        out |= chain_inputs(passes)
    return out


def gc_inputs(exp: Experiment, stats: Mapping[str, Any]) -> dict[str, Any]:
    """Stop-the-world garbage collection as the runtime counted it in the pass: the pause the
    model samples (lognormal, p95 2.5x its median, so the median is set to give the measured
    mean) and the requests between collections. Their CPU is already in the CPU per request; the
    pause adds the stall every request on the replica waits through. gunicorn's workers are
    processes of their own whose collections stall only their own request, which the model's
    replica-wide pause would overstate, so Python's are left out."""
    if exp.runtime == "python":
        return {
            "gcPauseMs": {
                "value": 0,
                "how": "not modelled as a pause: each gunicorn worker collects on its own and stalls only its own request; the collections' CPU is in the CPU per request",
            }
        }
    gc = stats.get("gc") or {}
    n, pause = gc.get("collections") or 0, gc.get("pauseMs") or 0.0
    requests = stats.get("requests") or 0
    if n <= 0 or pause <= 0 or requests <= 0:
        return {
            "gcPauseMs": {
                "value": 0,
                "how": f"no stop-the-world pause in the pass ({n} collections, {pause:g} ms)",
            }
        }
    mean = pause / n
    sigma = math.log(2.5) / Z95
    return {
        "gcPauseMs": {
            "value": round(mean / math.exp(sigma**2 / 2), 4),
            "how": (
                f"the runtime's stop-the-world pauses in the pass: {pause:.2f} ms over {n} "
                f"collections, a mean of {mean:.4f} ms (the model's median: mean / 1.17)"
            ),
        },
        "gcEveryRequests": {
            "value": max(1, round(requests / n)),
            "how": f"{requests} requests / {n} collections in the pass",
        },
    }


def chain_inputs(passes: dict[str, Any]) -> dict[str, Any]:
    """chain's API behind the gateway (its /stats are in the gateway's, as ``upstream``): its CPU
    per request, found as the gateway's is, and the gateway-to-API latency."""
    low, high = passes["low"], passes["high"]
    api, api_low = high["service"]["upstream"], low["service"]["upstream"]
    own = timing(api, "own")
    handler = timing(api, "handler")
    call = timing(high["service"], "upstream")
    demand = slope(api_low["cpuMs"], api["cpuMs"], api_low["requests"], api["requests"])
    if demand is None or demand <= 0:
        demand = api["cpuMsPerRequest"]
    return {
        "apiServiceTimeMs": {
            "value": lognormal_with_mean(demand, own),
            "how": (
                f"mean = the API's CPU per extra request between the passes: ({api['cpuMs']:.0f} - "
                f"{api_low['cpuMs']:.0f} ms) / ({api['requests']} - {api_low['requests']} requests) = "
                f"{demand:.4f} ms; shape (p95/median {own['p95']:.4f}/{own['median']:.4f} ms) from its handler's own time"
            ),
        },
        "apiLinkLatencyMs": {
            "value": round(max(0.0, (call["median"] - handler["median"]) / 2), 4),
            "how": (
                f"(the gateway's call to the API, median {call['median']:.3f} ms - the API's handler "
                f"median {handler['median']:.3f} ms) / 2, one way"
            ),
        },
    }


def db_client(exp: Experiment, entry: Mapping[str, Any]) -> dict[str, Any]:
    """The /stats of the service that calls PostgreSQL in a calibration pass: chain's API (inside
    the gateway's), else the service k6 calls."""
    svc: dict[str, Any] = entry["service"]
    return svc["upstream"] if exp.stack == "chain" else svc


def db_cpu_share(
    passes: dict[str, Any], q_low: float, q_high: float, mean_ms: float
) -> dict[str, Any] | None:
    """PostgreSQL's CPU per query over the mean query time, or None without a usable figure:
    at the top pass net of idle (protocol v5), else per extra query between the two passes."""
    if (idle := passes.get("idle")) is not None:
        net = net_cpu_ms(passes["high"], idle, "postgres")
        pg = net / q_high if net is not None and q_high > 0 else None
        what = f"PostgreSQL CPU per query at the top pass net of idle ({pg or 0:.4f} ms)"
    else:
        pg = slope(
            passes["low"]["containerCpuMs"].get("postgres"),
            passes["high"]["containerCpuMs"].get("postgres"),
            q_low,
            q_high,
        )
        what = f"PostgreSQL CPU per extra query between the passes ({pg or 0:.4f} ms)"
    if pg is None or pg <= 0 or mean_ms <= 0:
        return None
    return {
        "value": round(min(1.0, max(0.02, pg / mean_ms)), 4),
        "how": f"{what} / mean query time ({mean_ms:.3f} ms)",
    }


KAFKA_FETCH_FALLBACK = {"median": 0.93, "p95": 3.88, "polls": 17577}
"""The consumer's fetch overhead per poll (ms) for runs calibrated before the service reported
kafkajs's fetch cycle (W23, 31 Oct 2026): what the W23 trial measured on the same laptop at
569 req/s, the top of the kept runs' calibration staircases, with the stack as benchmarked
(benchmarks/results/trials/2026-10-31-kafka-w23/diagnostics.json: two passes, medians 1.00
and 0.86 ms, p95 4.19 and 3.56 ms; their means)."""
MIN_POLLS = 50
"""Fewest fetch cycles a pass must record for its fetch overhead to be used."""


def queue_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """Kafka: the producer's publish time; the consumer's own time per message, how many
    partitions it processes at once and what each poll costs beyond the messages (the fetch and
    the offset commit)."""
    high = passes["high"]
    consume_own = timing(high["consumer"], "consume_own")
    send = timing(high["service"], "kafka_send")
    return {
        "publishTimeMs": {
            "value": lognormal(send["median"], send["p95"]),
            "how": f"producer.send with acks=all on one broker: median {send['median']:.3f}, p95 {send['p95']:.3f} ms",
        },
        "consumerTimeMs": {
            "value": lognormal(consume_own["median"], consume_own["p95"]),
            "how": f"consumer's own time per message (handling minus the INSERT): median {consume_own['median']:.3f}, p95 {consume_own['p95']:.3f} ms",
        },
        "consumerConcurrency": {
            "value": KAFKA_CONSUMERS,
            "how": (
                f"the stack's setting, not measured: one kafkajs consumer processes {KAFKA_CONSUMERS} "
                f"of its {KAFKA_PARTITIONS} partitions at once (partitionsConsumedConcurrently)"
            ),
        },
        "fetchOverheadMs": fetch_overhead(high["consumer"]),
    }


def fetch_overhead(cons: Mapping[str, Any]) -> dict[str, Any]:
    """What each of the consumer's polls costs beyond processing its messages: per fetch cycle
    the cycle less its batches' least possible span on the consumer's workers (the service's
    ``cycle_overhead``: the fetch, the last batch's offset commit and kafkajs's bookkeeping).
    Runs calibrated before the service reported it take the W23 trial's figure."""
    t = cons["timings"].get("cycle_overhead")
    if t and t["count"] >= MIN_POLLS:
        fetch = cons["timings"].get("cycle_fetch") or {}
        after = cons["timings"].get("cycle_after") or {}
        return {
            "value": lognormal(max(t["median"], 0.01), max(t["p95"], 0.02)),
            "how": (
                f"kafkajs's fetch cycle in the pass ({t['count']} polls): each cycle less its "
                f"batches' least span on {KAFKA_CONSUMERS} workers, median {t['median']:.3f}, p95 "
                f"{t['p95']:.3f} ms (the fetch: median {fetch.get('median') or 0:.3f} ms; the last "
                f"batch's commit: median {after.get('median') or 0:.3f} ms)"
            ),
        }
    f = KAFKA_FETCH_FALLBACK
    return {
        "value": lognormal(f["median"], f["p95"]),
        "how": (
            "not recorded in this run (the service reports kafkajs's fetch cycle since W23): the "
            f"W23 trial's top calibration passes on the same laptop ({f['polls']} polls), median "
            f"{f['median']:.3f}, p95 {f['p95']:.3f} ms"
        ),
    }


def database_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """PostgreSQL: query times, the WAL flush per commit and the CPU share of a query. On the
    Kafka stack the queries are the consumer's INSERTs; on chain, the API's (behind the gateway)."""
    low, high = passes["low"], passes["high"]
    svc, svc_low = db_client(exp, high), db_client(exp, low)
    out: dict[str, Any] = {}
    share: dict[str, Any] | None
    if exp.stack == "kafka":
        cons, cons_low = high["consumer"], low["consumer"]
        insert = timing(cons, "db_insert")
        sync = timing(passes["write:1"]["service"], "db_write")
        async_ = timing(passes["write_async:1"]["service"], "db_write_async")
        flush_med = max(0.01, sync["median"] - async_["median"])
        out["commitFlushMs"] = {
            "value": lognormal(flush_med, max(flush_med * 1.01, sync["p95"] - async_["p95"])),
            "how": (
                f"UPDATE with synchronous_commit on minus off: median {sync['median']:.3f} - {async_['median']:.3f} ms, "
                f"p95 {sync['p95']:.3f} - {async_['p95']:.3f} ms"
            ),
        }
        out["writeServiceTimeMs"] = {
            "value": lognormal(
                max(0.01, insert["median"] - flush_med), max(0.02, insert["p95"] - flush_med)
            ),
            "how": f"consumer's INSERT time (median {insert['median']:.3f}, p95 {insert['p95']:.3f} ms) minus the flush median",
        }
        share = db_cpu_share(passes, cons_low["consumed"], cons["consumed"], insert["mean"])
    else:
        t, t_low = svc["timings"], svc_low["timings"]
        names = [n for n in ("db_read", "db_write") if n in t]
        q_high = sum(t[n]["count"] for n in names)
        q_low = sum(t_low[n]["count"] for n in names if n in t_low)
        mean_q = sum(t[n]["mean"] * t[n]["count"] for n in names) / max(q_high, 1)
        if "db_read" in t:
            r = timing(svc, "db_read")
            out["readServiceTimeMs"] = {
                "value": lognormal(r["median"], r["p95"]),
                "how": f"SELECT by key as the service sees it (round trip): median {r['median']:.3f}, p95 {r['p95']:.3f} ms",
            }
        if "db_write" in t:
            w = timing(svc, "db_write")
            a = timing(db_client(exp, passes["write_async:1"]), "db_write_async")
            flush_med = max(0.01, w["median"] - a["median"])
            out["writeServiceTimeMs"] = {
                "value": lognormal(a["median"], a["p95"]),
                "how": f"UPDATE with synchronous_commit off (no WAL flush wait): median {a['median']:.3f}, p95 {a['p95']:.3f} ms",
            }
            out["commitFlushMs"] = {
                "value": lognormal(flush_med, max(flush_med * 1.01, w["p95"] - a["p95"])),
                "how": (
                    f"UPDATE with synchronous_commit on minus off: median {w['median']:.3f} - {a['median']:.3f} ms, "
                    f"p95 {w['p95']:.3f} - {a['p95']:.3f} ms"
                ),
            }
        share = db_cpu_share(passes, q_low, q_high, mean_q)
    if share is not None:
        out["dbCpuShare"] = share
    return out


def redis_commands(entry: Mapping[str, Any]) -> tuple[float, float]:
    """A pass's GET and SET calls and their command time (us) from Redis's INFO commandstats.
    cache-ttl's service reports them in its own /stats (REDIS_STATS=1)."""
    rs = entry["redis"] if "redis" in entry else entry["service"]["redis"]
    cmds = [rs.get(c, {"calls": 0, "usec": 0}) for c in ("get", "set")]
    return sum(c["calls"] for c in cmds), sum(c["usec"] for c in cmds)


def redis_cpu_per_command(exp: Experiment, passes: Mapping[str, Any]) -> tuple[float, str] | None:
    """Redis's CPU per GET or SET (ms) and how it was found, or None without a usable figure: its
    container's CPU at the top pass net of idle (protocol v5), else per extra command between
    the two passes. Redis runs commands and its network reads and writes on one thread, so this
    is what one command keeps that thread busy for."""
    container_name = "redis-ttl" if exp.stack == "cache-ttl" else "redis"
    high = passes["high"]
    calls, _ = redis_commands(high)
    if (idle := passes.get("idle")) is not None:
        net = net_cpu_ms(high, idle, container_name)
        if net is None or calls <= 0 or net <= 0:
            return None
        rest = (idle["containerCpuMsPerSecond"].get(container_name) or 0.0) * CALIBRATION_S
        cpu = net / calls
        return cpu, (
            f"Redis's CPU per command at {high['rateRps']:g} req/s net of idle: "
            f"({high['containerCpuMs'][container_name]:.0f} - {rest:.0f} ms at rest) / "
            f"{calls:.0f} GET/SET calls = {cpu:.4f} ms"
        )
    low = passes["low"]
    calls_low, _ = redis_commands(low)
    cpu_slope = slope(
        low["containerCpuMs"].get(container_name),
        high["containerCpuMs"].get(container_name),
        calls_low,
        calls,
    )
    if cpu_slope is None or cpu_slope <= 0:
        return None
    return cpu_slope, (
        f"Redis's CPU per extra command between the passes: "
        f"({high['containerCpuMs'][container_name]:.0f} - "
        f"{low['containerCpuMs'][container_name]:.0f} ms) / ({calls:.0f} - {calls_low:.0f} "
        f"GET/SET calls) = {cpu_slope:.4f} ms"
    )


def cache_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """Redis: the time a command keeps its one thread busy, and the service-to-Redis latency.

    The command time is Redis's CPU per command (``redis_cpu_per_command``): INFO commandstats'
    own time leaves out reading the request and writing the reply, about nine tenths of Redis's
    CPU (cache-aside's latest calibration: 0.003 ms of 0.036 ms). The link latency is what is left of the GET round trip's
    median after the command time's median, so the calibrated latency does not move. Without a
    CPU figure the command time is commandstats' own time, as before."""
    high = passes["high"]
    calls, usec = redis_commands(high)
    own_ms = usec / max(calls, 1) / 1000
    app_get = timing(high["service"], "redis_get")
    cpu = redis_cpu_per_command(exp, passes)
    own = f"INFO commandstats: {usec:.0f} us over {calls:.0f} GET/SET calls = {own_ms:.4f} ms"
    if cpu is not None:
        mean = cpu[0]
        how = f"mean = {cpu[1]} (its command time alone, {own}; the rest is reading requests and writing replies)"
    else:
        mean = own_ms
        how = f"Redis {own} (no CPU figure for its container)"
    mean = round(max(mean, 0.001), 5)
    command_median = mean * math.log(2)  # the median of an exponential
    return {
        "cacheServiceTimeMs": {
            "value": {"kind": "exponential", "mean": mean},
            "how": how,
        },
        "cacheLinkLatencyMs": {
            "value": round(max(0.0, (app_get["median"] - command_median) / 2), 4),
            "how": (
                f"(GET round trip median {app_get['median']:.3f} ms - the command time's median "
                f"{command_median:.4f} ms) / 2, one way"
            ),
        },
    }


MEMORY_MIN_RATES = 2
"""Staircase rates (besides rest) the memory fit needs: memory per req/s from rest and one rate
alone would rest on two readings."""


def memory_containers(exp: Experiment) -> dict[str, str]:
    """The services whose memory is calibrated: their input prefix (``service_node``'s
    ``memory``) and the container measured for it. The service k6 calls, and chain's API."""
    out = {"service": STACK_SERVICES[exp.stack][0]}
    if exp.stack == "chain":
        out["api"] = "chain-api"
    return out


def memory_points(passes: Mapping[str, Any], container_name: str) -> list[tuple[float, float]]:
    """(req/s, MiB) for one container: its working set at rest (the idle pass) and the highest
    reading in each staircase pass (calibration_pass's ``resources``), against the rate k6
    achieved. Calibrations before W24 recorded neither, and give no points."""
    pts: list[tuple[float, float]] = []
    rest = ((passes.get("idle") or {}).get("workingSetMiB") or {}).get(container_name)
    if rest is not None:
        pts.append((0.0, float(rest)))
    for p in passes.get("staircase") or []:
        c = ((p.get("resources") or {}).get("containers") or {}).get(container_name) or {}
        rate = p["k6"].get("throughputRps") or p["rateRps"]
        if c.get("workingSetMiB") is not None and rate is not None:
            pts.append((float(rate), float(c["workingSetMiB"])))
    return pts


def memory_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """Each service's memory: at idle, per req/s and per request held, fitted (W17's
    ``fit_memory``, non-negative least squares) to its container's working set over the
    staircase and at rest. A garbage-collected heap grows with the rate it allocates at, not
    with requests held, so the fit is against the rate; it explains the whole working set, so
    memory per request held is its floor. Nothing without passes at MEMORY_MIN_RATES rates or
    more (a staircase that did not rise): the model keeps its defaults (256 MiB at idle, 2 MiB per
    request held)."""
    from archsim_engine.calibration.resources import fit_memory

    out: dict[str, Any] = {}
    for prefix, name in memory_containers(exp).items():
        pts = memory_points(passes, name)
        if len({r for r, _ in pts if r > 0}) < MEMORY_MIN_RATES:
            continue
        fit = fit_memory([w for _, w in pts], rate=[r for r, _ in pts])
        if fit is None:
            continue
        rates = [r for r, _ in pts if r > 0]
        error = f"{fit.error:.0%}" if fit.error is not None else "-"
        what = (
            f"the {name} container's working set (the highest reading in each pass) against "
            f"req/s over {len(pts)} points ({'at rest and ' if len(rates) < len(pts) else ''}"
            f"{len(rates)} passes, {min(rates):,.0f} to {max(rates):,.0f} req/s), by "
            f"non-negative least squares: {fit.baseline_mb:.1f} MiB + {fit.per_rps_mb:.4f} MiB "
            f"per req/s, largest error at and above the median rate {error}"
            + "".join(f"; {n}" for n in fit.notes)
        )
        out |= {
            f"{prefix}MemoryBaselineMb": {"value": fit.baseline_mb, "how": f"at idle: {what}"},
            f"{prefix}MemoryPerRpsMb": {"value": fit.per_rps_mb, "how": f"per req/s: {what}"},
            f"{prefix}MemoryPerRequestMb": {
                "value": fit.per_request_mb,
                "how": (
                    "per request held: the floor, since the fit against req/s explains the "
                    "whole working set (held requests' memory would count twice otherwise)"
                ),
            },
        }
    return out


# ---------------------------------------------------------------------------------------------
# 2. the ArchSim model
# ---------------------------------------------------------------------------------------------


def _edge(
    eid: str, src: str, dst: str, latency: float, port: str = "out", types: list[str] | None = None
) -> dict[str, Any]:
    return {
        "id": eid,
        "source": src,
        "sourcePort": port,
        "target": dst,
        "targetPort": "in",
        "config": {
            "requestTypes": types or ["read", "write"],
            "latencyMs": {"kind": "constant", "value": latency},
        },
    }


MEMORY_KEYS = {
    "memoryBaselineMb": "MemoryBaselineMb",
    "memoryPerRpsMb": "MemoryPerRpsMb",
    "memoryPerRequestMb": "MemoryPerRequestMb",
}
"""A service node's memory settings and the suffix of the inputs (``memory_inputs``) they
come from."""


def service_memory(v: Mapping[str, Any], memory: str) -> dict[str, Any]:
    """A service node's calibrated memory settings, or none (ArchSim's defaults) when its
    memory was not calibrated."""
    if f"{memory}MemoryBaselineMb" not in v:
        return {}
    return {key: v[f"{memory}{suffix}"] for key, suffix in MEMORY_KEYS.items()}


def service_node(
    v: Mapping[str, Any],
    runtime: str = "nodejs",
    node_id: str = "api",
    time_key: str = "serviceTimeMs",
    label: str | None = None,
    memory: str = "service",
) -> dict[str, Any]:
    """A service on its runtime's ArchSim profile: Node.js is one event loop on one CPU; Java
    (Tomcat's 200 threads), Go (a goroutine per request) and Python (gunicorn's sync workers) are
    a pool of workers that each wait for their own PostgreSQL calls, on the container's 2 CPUs.
    Their garbage collection is calibrated (``gc_inputs``); the JIT warm-up of the Java profile
    applies only to replicas started during a run, so it does not apply here. Memory comes from
    the ``<memory>Memory*`` inputs when calibrated (``memory_inputs``), else ArchSim's defaults."""
    rt = RUNTIMES[runtime]
    mem = service_memory(v, memory)
    if rt.workers is None:
        return {
            "id": node_id,
            "type": "service",
            "label": label or rt.label,
            "config": {
                "technology": rt.technology,
                "platform": "docker",
                "replicas": 1,
                "processesPerReplica": 1,
                "queueCapacity": 100_000,
                "queueTimeoutMs": None,
                "serviceTimeMs": v[time_key],
                "gcPauseMs": 0,  # measured CPU per request already includes GC
                "warmupFactor": 1,
                "cpuCores": rt.cpu_cores,
                "memoryGib": SERVICE_MEMORY_GIB,
                **mem,
            },
        }
    return {
        "id": node_id,
        "type": "service",
        "label": label or rt.label,
        "config": {
            "technology": rt.technology,
            "platform": "docker",
            "replicas": 1,
            "workersPerReplica": rt.workers,
            "blockingDownstream": True,
            "queueCapacity": 100_000,
            "queueTimeoutMs": None,
            "serviceTimeMs": v[time_key],
            "gcPauseMs": v.get("gcPauseMs", 0),
            **({"gcEveryRequests": v["gcEveryRequests"]} if "gcEveryRequests" in v else {}),
            "cpuCores": rt.cpu_cores,
            "memoryGib": SERVICE_MEMORY_GIB,
            **mem,
        },
    }


def database_node(v: Mapping[str, Any]) -> dict[str, Any]:
    """PostgreSQL behind the service's pool of connections."""
    return {
        "id": "db",
        "type": "database",
        "label": "PostgreSQL",
        "config": {
            "technology": "postgresql",
            "maxConnections": PG_POOL,  # the service's connection pool
            "queueCapacity": 100_000,
            "queueTimeoutMs": None,
            "cpuCores": 2,
            "cpuShare": v.get("dbCpuShare", 0.5),
            **({"readServiceTimeMs": v["readServiceTimeMs"]} if "readServiceTimeMs" in v else {}),
            **(
                {"writeServiceTimeMs": v["writeServiceTimeMs"]} if "writeServiceTimeMs" in v else {}
            ),
            **({"commitFlushMs": v["commitFlushMs"]} if "commitFlushMs" in v else {}),
        },
    }


def cache_node(v: Mapping[str, Any], ttl_s: int = CACHE_TTL_S) -> dict[str, Any]:
    """Redis with a TTL over the keyspace, filled on a miss."""
    return {
        "id": "cache",
        "type": "cache",
        "label": "Redis",
        "config": {
            "technology": "redis",
            "hitModel": "ttl_keyspace",
            "ttlSeconds": ttl_s,
            "populateOnMiss": True,
            "serviceTimeMs": v["cacheServiceTimeMs"],
            "queueCapacity": 100_000,
            "cpuCores": 1,
            "memoryGib": CACHE_MEMORY_GIB,
            "itemSizeKb": CACHE_ITEM_KIB,
        },
    }


def queue_node(v: Mapping[str, Any]) -> dict[str, Any]:
    """Kafka: one broker and the real stack's partitions, read by one kafkajs consumer that polls
    in fetch cycles and processes its partitions ``consumerConcurrency`` at a time."""
    return {
        "id": "kafka",
        "type": "message_queue",
        "label": "Kafka",
        "config": {
            "technology": "kafka",
            "partitions": KAFKA_PARTITIONS,
            "consumers": 1,
            "consumerConcurrency": v["consumerConcurrency"],
            "fetchOverheadMs": v["fetchOverheadMs"],
            "brokers": 1,
            "maxDepth": None,
            "acks": "leader",  # one broker: acks=all is the leader's write
            "lingerMs": 0,  # kafkajs sends each message at once
            "publishTimeMs": v["publishTimeMs"],
            "consumerTimeMs": v["consumerTimeMs"],
            "maxDeliveries": 1,
        },
    }


def design(
    exp: Experiment,
    inputs: dict[str, Any],
    rps: float,
    warm: int = WARMUP_S,
    duration: int = DURATION_S,
) -> dict[str, Any]:
    """The ArchSim design of an experiment's stack at one offered load, from its model inputs.
    Each part's node comes from a function of its own."""
    v = {k: x["value"] for k, x in inputs.items() if isinstance(x, dict) and "value" in x}
    nodes: list[dict[str, Any]] = [
        {
            "id": "clients",
            "type": "traffic_source",
            "label": "k6",
            "config": {"timeoutMs": TIMEOUT_MS, "maxAttempts": 1},
        },
    ]
    if exp.stack == "chain":  # the gateway calls the API, which calls PostgreSQL, one after another
        nodes += [
            service_node(v, node_id="gateway", label="Node.js gateway"),
            service_node(v, time_key="apiServiceTimeMs", label="Node.js API", memory="api"),
        ]
        edges = [
            _edge("e-clients", "clients", "gateway", v["clientLinkLatencyMs"]),
            _edge("e-api", "gateway", "api", v["apiLinkLatencyMs"]),
        ]
    else:
        nodes.append(service_node(v, exp.runtime))
        edges = [_edge("e-clients", "clients", "api", v["clientLinkLatencyMs"])]
    if exp.stack in DB_STACKS:
        nodes.append(database_node(v))
        edges.append(_edge("e-db", "api", "db", 0.0))
    elif exp.stack in CACHE_STACKS:
        nodes += [cache_node(v, exp.ttl_s), database_node(v)]
        edges += [
            _edge("e-cache", "api", "cache", v["cacheLinkLatencyMs"], types=["read"]),
            _edge("e-miss", "cache", "db", 0.0, port="miss", types=["read"]),
        ]
    else:  # kafka
        nodes += [queue_node(v), database_node(v)]
        edges += [_edge("e-kafka", "api", "kafka", 0.0), _edge("e-db", "kafka", "db", 0.0)]
    return {
        "schemaVersion": "1.0",
        "kind": "archsim.architecture",
        "metadata": {"name": f"benchmark {exp.id}"},
        "architecture": {"nodes": nodes, "edges": edges, "autoSize": False},
        "workload": {
            "rps": rps,
            "durationS": warm + duration,
            "warmupS": warm,
            "readRatio": exp.read_ratio,
            "keyCount": exp.keys,
            "keySkew": exp.skew,
            "seed": SEED,
            "arrivalProcess": "deterministic",
        },
        "scenarios": [],
    }


def simulate(doc: dict[str, Any]) -> dict[str, Any]:
    from archsim_engine import run_simulation

    r = run_simulation(doc)
    s = r["summary"]
    lat = s["latencyMs"]
    nodes = {n["id"]: n["summary"] for n in r["nodes"]}
    out: dict[str, Any] = {
        "offeredRps": doc["workload"]["rps"],
        "p50": lat.get("p50"),
        "p95": lat.get("p95"),
        "p99": lat.get("p99"),
        "mean": lat.get("mean"),
        "successRate": s.get("successRate"),
        "throughputRps": s.get("throughputRps"),
        "resultDigest": r["provenance"]["resultDigest"],
        "resources": node_resources(doc, r),
    }
    if "db" in nodes:
        # how much the model's commits shared a WAL flush, and what it made them wait for
        db = nodes["db"]
        out["database"] = {
            k: db.get(k) for k in ("commitsPerFlush", "meanLockWaitMs", "meanCommitWaitMs")
        }
    if "cache" in nodes:
        out["cacheHitRatio"] = nodes["cache"].get("hitRatio")
    if "kafka" in nodes:
        k = nodes["kafka"]
        processed = (k.get("counters") or {}).get("processed", 0)
        out["queue"] = {
            "consumedRps": round(
                processed / (doc["workload"]["durationS"] - doc["workload"]["warmupS"]), 2
            ),
            "backlogAtEnd": k.get("backlogAtEnd"),
            "meanMessageAgeMs": k.get("meanMessageAgeMs"),
            "p95MessageAgeMs": k.get("p95MessageAgeMs"),
        }
    return out


def node_resources(doc: Mapping[str, Any], result: Mapping[str, Any]) -> dict[str, Any]:
    """The model's CPU (busy cores) and memory (MiB) of each node that has them. Every node of
    the benchmark designs is one instance, so its busy cores are its CPU utilization times its
    cores. A service's memory is its peak (idle memory, plus memory per request times the most
    requests it held, plus memory per req/s times its busiest rate); the cache's is its keys at
    the end (none expire within a step) times the memory per key."""
    configs = {n["id"]: n["config"] for n in doc["architecture"]["nodes"]}
    out: dict[str, Any] = {}
    for n in result["nodes"]:
        s, cfg = n["summary"], configs.get(n["id"], {})
        res: dict[str, float] = {}
        if (util := s.get("cpuCoreUtilization")) is not None and cfg.get("cpuCores"):
            res["cores"] = round(util * cfg["cpuCores"] * cfg.get("replicas", 1), 4)
        if s.get("memoryPeakGiB") is not None:
            res["memoryMiB"] = round(s["memoryPeakGiB"] * 1024, 1)
        if s.get("keysCached") is not None and cfg.get("itemSizeKb"):
            res["memoryMiB"] = round(s["keysCached"] * cfg["itemSizeKb"] / 1024, 1)
        if res:
            out[n["id"]] = res
    return out


def meets(r: dict[str, Any]) -> bool:
    return (
        (r["successRate"] or 0) >= MIN_SUCCESS
        and r["p95"] is not None
        and r["p95"] <= OBJECTIVE_P95_MS
    )


def predicted_knee(exp: Experiment, inputs: dict[str, Any]) -> dict[str, Any]:
    """Highest load meeting the objective, by doubling then bisection (to 1%)."""
    lo, hi = 0.0, 100.0
    tried: list[dict[str, Any]] = []
    while True:
        r = simulate(design(exp, inputs, hi))
        tried.append(r)
        if not meets(r):
            break
        lo, hi = hi, hi * 2
        if hi > 200_000:
            break
    while hi - lo > max(1.0, 0.01 * hi):
        mid = round((lo + hi) / 2)
        r = simulate(design(exp, inputs, mid))
        tried.append(r)
        lo, hi = (mid, hi) if meets(r) else (lo, mid)
    return {
        "kneeRps": lo,
        "tried": [{k: t[k] for k in ("offeredRps", "p95", "successRate")} for t in tried],
    }


def knee_range_of(nominal: float, values: Sequence[float]) -> dict[str, Any] | None:
    """The predicted range of the knee: the knees of the replicate input sets, lowest to highest
    (None with fewer than two). With R replicates a new draw from the same spread falls inside
    their range with probability (R - 1) / (R + 1): the nominal coverage."""
    if len(values) < 2:
        return None
    r = len(values)
    return {
        "lowRps": min(values),
        "highRps": max(values),
        "nominalRps": nominal,
        "values": list(values),
        "replicates": r,
        "nominalCoverage": round((r - 1) / (r + 1), 4),
    }


def in_range(knee_range: Mapping[str, Any] | None, measured: Mapping[str, Any]) -> bool | None:
    """Whether the measured knee fell inside the predicted range; None without a range or a
    measured knee (one above the ladder is inside when the range reaches past the top step)."""
    if not knee_range:
        return None
    lo, hi = knee_range["lowRps"], knee_range["highRps"]
    if measured.get("kneeRps") is not None:
        return bool(lo <= measured["kneeRps"] <= hi)
    if measured.get("bound") == "above" and measured.get("aboveRps") is not None:
        return bool(hi >= measured["aboveRps"])
    if measured.get("bound") == "below" and measured.get("belowRps") is not None:
        return bool(lo <= measured["belowRps"])
    return None


def predict(exp: Experiment, calibration: dict[str, Any]) -> dict[str, Any]:
    from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION

    inputs = calibration["inputs"]
    replicates = calibration.get("inputReplicates") or []
    log(f"predicting {exp.id}: searching the knee ({len(replicates)} replicates for the range)")
    with ProcessPoolExecutor(max_workers=min(len(replicates) + 1, os.cpu_count() or 2)) as pool:
        knees = list(pool.map(predicted_knee, [exp] * (len(replicates) + 1), [inputs, *replicates]))
    knee = knees[0]
    k = knee["kneeRps"]
    knee_range = knee_range_of(k, [x["kneeRps"] for x in knees[1:]])
    steps = sorted({max(10, round(k * f)) for f in STEP_FRACTIONS})
    log(f"predicted knee {k:g} req/s; steps {steps}")
    with ProcessPoolExecutor(max_workers=min(len(steps), os.cpu_count() or 2)) as pool:
        results = list(pool.map(simulate, [design(exp, inputs, s) for s in steps]))
    return {
        "experiment": exp.id,
        "at": now(),
        "engineVersion": ENGINE_VERSION,
        "reportVersion": REPORT_VERSION,
        "provenance": provenance(),
        "objective": {"p95Ms": OBJECTIVE_P95_MS, "minSuccess": MIN_SUCCESS},
        "knee": knee,
        **({"kneeRange": knee_range} if knee_range else {}),
        "steps": results,
        "design": design(exp, inputs, k),
    }


# ---------------------------------------------------------------------------------------------
# 3. measurement
# ---------------------------------------------------------------------------------------------


def measure_once(exp: Experiment, rps: float, run: int) -> dict[str, Any]:
    target = target_for(exp)
    log(f"measuring {exp.id} at {rps:g} req/s (run {run})")
    if exp.stack in CACHE_STACKS:
        redis = "redis-ttl" if exp.stack == "cache-ttl" else "redis"
        compose("exec", "-T", redis, "redis-cli", "FLUSHALL", capture=True)
    http_json(f"{API}/reset", "POST")
    cons0 = http_json(f"{CONSUMER}/stats") if exp.stack == "kafka" else None
    cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
    t0 = time.time()
    with ResourceSampler(services_for(exp)) as sampler:
        summary = k6(
            target,
            exp.mix,
            rps,
            WARMUP_S,
            DURATION_S,
            f"step-{exp.id}-{rps:g}-{run}",
            exp.keys,
            exp.skew,
        )
    elapsed = time.time() - t0
    row: dict[str, Any] = {"offeredRps": rps, **k6_measured(summary, DURATION_S)}
    svc = http_json(f"{API}/stats")
    row["serviceCpuMsPerRequest"] = svc["cpuMsPerRequest"]
    cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
    # Over the whole k6 command, its container's start and stop included (kept for older runs).
    row["containerCpuShare"] = {
        s: round((after - before) / 1e6 / elapsed, 3)
        for s, after in cpu1.items()
        if (before := cpu0.get(s)) is not None and after is not None
    }
    if (resources := sampler.result()) is not None:
        row["resources"] = resources
    c = svc.get("counters", {})
    if exp.stack in CACHE_STACKS:
        hits, misses = c.get("cache_hit:measure", 0), c.get("cache_miss:measure", 0)
        row["cacheHitRatio"] = hits / (hits + misses) if hits + misses else None
    if exp.stack == "kafka":
        time.sleep(0.5)
        cons = http_json(f"{CONSUMER}/stats")
        produced = svc["counters"].get("produced:measure", 0) + svc["counters"].get(
            "produced:warm", 0
        )
        consumed_now = cons["consumed"] - (cons0 or {}).get("consumed", 0)
        e2e = cons["timings"].get("end_to_end:measure", {})
        row["queue"] = {
            "produced": produced,
            "consumedDuringStep": consumed_now,
            "consumedRps": round(consumed_now / (WARMUP_S + DURATION_S), 2),
            "backlogAtEnd": max(0, produced - consumed_now),
            "p95MessageAgeMs": e2e.get("p95"),
            "meanMessageAgeMs": e2e.get("mean"),
        }
        # let the backlog drain before the next step (bounded)
        deadline = time.time() + 120
        while time.time() < deadline:
            st = http_json(f"{CONSUMER}/stats")
            if st["consumed"] - (cons0 or {}).get("consumed", 0) >= produced:
                break
            time.sleep(2)
        http_json(f"{CONSUMER}/reset", "POST")
    else:
        row["drain"] = drain()
    row["at"] = now()
    log(
        f"  p50 {row['p50'] or 0:.2f} ms, p95 {row['p95'] or 0:.2f} ms, success {row['successRate'] or 0:.3f}, "
        f"{row['throughputRps']:.0f} req/s"
    )
    time.sleep(PAUSE_S)
    return row


DRAIN_S = 60


def drain(seconds: float = DRAIN_S) -> dict[str, Any]:
    """Before the next repeat: wait (bounded) until the service answers its health check and
    PostgreSQL has no query left running. A step past the knee can leave queued requests or a
    restarting service behind (run 4's hot-row at 1,126 req/s: after the first repeat no request
    succeeded), and the next repeat must not start on top of them. Added after run 4 and not yet
    run against the stacks."""
    t0 = time.time()
    healthy = False
    idle = False
    while time.time() - t0 < seconds:
        if not healthy:
            try:
                http_json(f"{API}/health")
                healthy = True
            except OSError:
                time.sleep(1)
                continue
        active = compose(
            "exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-tAq", "-c",
            "SELECT count(*) FROM pg_stat_activity WHERE datname = 'bench' "
            "AND state = 'active' AND pid <> pg_backend_pid()",
            capture=True, check=False,
        )  # fmt: skip
        if active.strip() == "0":
            idle = True
            break
        time.sleep(1)
    if not (healthy and idle):
        log(
            f"  not drained after {seconds:g} s (service healthy: {healthy}, database idle: {idle})"
        )
    return {"seconds": round(time.time() - t0, 1), "healthy": healthy, "databaseIdle": idle}


LATENCY_KEYS = ("p50", "p95", "p99", "mean")
QUEUE_KEYS = ("consumedRps", "backlogAtEnd", "p95MessageAgeMs", "meanMessageAgeMs")


def failed_repeat(run: Mapping[str, Any]) -> bool:
    """A repeat in which k6 tried to send requests and none succeeded: the system failed (unlike a
    repeat in which k6 sent nothing, which is the load generator failing)."""
    attempted = (run.get("sent") or 0) + (run.get("dropped") or 0)
    return run.get("succeeded") == 0 and attempted > 0


def repeat_value(run: Mapping[str, Any], key: str) -> Any:
    """One repeat's metric; a failed repeat's latency is infinite (worse than any measured one).
    Runs recorded before k6_measured returned None for them carry k6's zeros instead."""
    if key in LATENCY_KEYS and failed_repeat(run):
        return math.inf
    return run.get(key)


def median(values: Iterable[float | None]) -> float | None:
    """The median of the values that exist (the mean of the middle two for an even count); None
    when there are none or when the median is infinite (most repeats failed)."""
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    m = statistics.median(vals)
    return None if math.isinf(m) else m


def aggregate(runs: list[dict[str, Any]]) -> dict[str, Any]:
    """A step's metrics from its repeats: the median of each (robust to one stall). A failed
    repeat counts as slower than any measured repeat, so one failed repeat out of three moves the
    median to the slower of the other two, and two or more leave the step without a latency
    (None; the step fails the objective). A step is unstable when its repeats' p95s differ by more
    than 2x across the objective (a failed repeat's p95 is infinite)."""
    out: dict[str, Any] = {}
    for key in (*LATENCY_KEYS, "successRate", "throughputRps", "cacheHitRatio"):
        if any(key in r for r in runs):
            out[key] = median(repeat_value(r, key) for r in runs)
    if any("queue" in r for r in runs):
        out["queue"] = {
            k: median(r["queue"].get(k) for r in runs if "queue" in r) for k in QUEUE_KEYS
        }
    p95s = [v for v in (repeat_value(r, "p95") for r in runs) if v is not None]
    out["unstable"] = bool(p95s) and max(p95s) > 2 * min(p95s) and max(p95s) > OBJECTIVE_P95_MS
    out["repeats"] = len(runs)
    out["failedRepeats"] = sum(failed_repeat(r) for r in runs)
    if (resources := aggregate_resources(runs)) is not None:
        out["resources"] = resources
    return out


RESOURCE_KEYS = ("cores", "maxCores", "workingSetMiB", "anonMiB")


def run_resources(run: Mapping[str, Any]) -> dict[str, Any] | None:
    """One repeat's CPU (and memory) per container in its measured window.

    Repeats measured since the window was sampled carry it (``ResourceSampler``). Older ones
    only have containerCpuShare, CPU over the whole k6 command, whose container start and stop
    add several idle seconds that vary by step. Those are scaled to the window by the service's
    own CPU, which the service counts itself (process.cpuUsage, the same as its container's CPU
    within 1% in every calibration pass): busy cores = the service's CPU per request x the rate k6
    sent x (the container's share / the service container's share). This assumes each container
    is idle while k6 starts and stops; the Kafka broker is not (about half a core at rest), so its
    figure reads high."""
    if run.get("resources"):
        return dict(run["resources"])
    share = run.get("containerCpuShare") or {}
    svc = next((c for c in SERVICE_CONTAINERS if share.get(c)), None)
    per_request, sent = run.get("serviceCpuMsPerRequest"), run.get("sent")
    if svc is None or not per_request or not sent:
        return None
    scale = per_request * sent / DURATION_S / 1000 / share[svc]
    return {
        "method": "scaled",
        "containers": {
            c: {"cores": round(v * scale, 4)} for c, v in share.items() if v is not None
        },
    }


def aggregate_resources(runs: Sequence[Mapping[str, Any]]) -> dict[str, Any] | None:
    """A step's CPU and memory per container: the median of its repeats (``run_resources``)."""
    per = [r for r in (run_resources(x) for x in runs) if r]
    if not per:
        return None
    methods = sorted({r["method"] for r in per})
    containers: dict[str, dict[str, float]] = {}
    for c in dict.fromkeys(c for r in per for c in r["containers"]):
        values = {k: median(r["containers"].get(c, {}).get(k) for r in per) for k in RESOURCE_KEYS}
        kept = {k: round(v, 4) for k, v in values.items() if v is not None}
        if kept:
            containers[c] = kept
    return {
        "method": methods[0] if len(methods) == 1 else "mixed",
        "repeats": len(per),
        "containers": containers,
    }


def measure_step(exp: Experiment, rps: float) -> dict[str, Any]:
    """REPEATS runs of one step, aggregated (``aggregate``). A run in which k6 sent nothing (the
    load generator failed) is not a measurement: it is recorded as discarded and run once more. A
    run in which requests were sent and none succeeded is a measurement of a failing system."""
    runs, discarded = [], []
    for i in range(REPEATS):
        r = measure_once(exp, rps, i + 1)
        if not r.get("sent"):
            log(f"{exp.id}: k6 sent nothing at {rps:g} req/s (run {i + 1}); running it again")
            discarded.append(r)
            r = measure_once(exp, rps, i + 1)
        runs.append(r)
    row: dict[str, Any] = {"offeredRps": rps, "runs": runs, "at": now()}
    if discarded:
        row["discardedRuns"] = discarded
    row.update(aggregate(runs))
    return row


# ---------------------------------------------------------------------------------------------
# 4. comparison
# ---------------------------------------------------------------------------------------------


def err(pred: float | None, meas: float | None) -> float | None:
    if pred is None or meas is None or meas == 0:
        return None
    return (pred - meas) / meas


def grid_knee(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Highest load meeting the objective, interpolated between the last passing and the first
    failing step (linear in p95, or in success rate when that is what fails)."""
    rows = sorted(rows, key=lambda r: r["offeredRps"])
    last = None
    for r in rows:
        if meets(r):
            last = r
            continue
        if last is None:
            return {"kneeRps": None, "bound": "below", "belowRps": r["offeredRps"]}
        x0, x1 = last["offeredRps"], r["offeredRps"]
        cands = []
        if r["p95"] is None or r["p95"] > OBJECTIVE_P95_MS:
            if r["p95"] is not None and r["p95"] > last["p95"]:
                cands.append(
                    x0 + (x1 - x0) * (OBJECTIVE_P95_MS - last["p95"]) / (r["p95"] - last["p95"])
                )
            else:
                cands.append(x0)
        if (r["successRate"] or 0) < MIN_SUCCESS:
            s0, s1 = last["successRate"], r["successRate"] or 0
            cands.append(x0 + (x1 - x0) * (s0 - MIN_SUCCESS) / (s0 - s1) if s0 > s1 else x0)
        return {"kneeRps": round(min(cands), 1), "bound": "interpolated", "between": [x0, x1]}
    return {"kneeRps": None, "bound": "above", "aboveRps": rows[-1]["offeredRps"] if rows else None}


def at_or_below_knee(rows: list[dict[str, Any]], knee: Mapping[str, Any]) -> list[dict[str, Any]]:
    """The steps at or below a measured knee (all of them when every step met the objective)."""
    if knee.get("kneeRps") is not None:
        return [r for r in rows if r["offeredRps"] <= knee["kneeRps"]]
    return list(rows) if knee.get("bound") == "above" else []


def at_or_below_both_knees(
    rows: list[dict[str, Any]], measured: Mapping[str, Any], predicted: Mapping[str, Any]
) -> list[dict[str, Any]]:
    """The steps at or below both the measured knee and the model's own, where CPU and memory are
    compared. Past the model's knee its queue holds every request the real service would have
    timed out, so its CPU reads fully busy and its memory grows without bound: that is the knee's
    error, counted there, not the resource model's."""
    model = {id(r) for r in at_or_below_knee(rows, predicted)}
    return [r for r in at_or_below_knee(rows, measured) if id(r) in model]


def measured_steps(meas: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Each measured step aggregated from its repeats (``aggregate``), so that results recorded
    before a fix to the aggregation are compared the way new ones are. Steps of run 1 had no
    repeats: the step is its one run."""
    return [
        {"offeredRps": s["offeredRps"], **aggregate(s.get("runs") or [s])} for s in meas["steps"]
    ]


def resource_rows(
    measured: Mapping[str, Any] | None, predicted: Mapping[str, Any] | None
) -> dict[str, Any] | None:
    """One step's CPU and memory per container, measured against the model's node for it."""
    if not measured:
        return None
    out: dict[str, Any] = {}
    for c, m in measured["containers"].items():
        p = (predicted or {}).get(CONTAINER_NODES.get(c, ""), {})
        meas = {"cores": m.get("cores"), "memoryMiB": m.get("workingSetMiB")}
        pred = {"cores": p.get("cores"), "memoryMiB": p.get("memoryMiB")}
        out[c] = {
            "measured": meas,
            "predicted": pred,
            "error": {
                "cores": err(pred["cores"], meas["cores"]),
                "memory": err(pred["memoryMiB"], meas["memoryMiB"]),
            },
        }
    return out


def resources_below_knee(below: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """Per container, how far the predicted CPU and memory were from the measured ones at the
    given steps (``at_or_below_both_knees``)."""
    out: dict[str, Any] = {}
    names = dict.fromkeys(c for r in below for c in r.get("resources", {}))
    for c in names:
        cells = [r["resources"][c] for r in below if c in r.get("resources", {})]
        summary: dict[str, Any] = {"steps": len(cells)}
        for key, name in (("cores", "Cores"), ("memory", "Memory")):
            errs = [abs(e) for x in cells if (e := x["error"][key]) is not None]
            summary[f"medianAbs{name}Error"] = median(errs)
            summary[f"maxAbs{name}Error"] = max(errs) if errs else None
        out[c] = summary
    return out


def compare(
    exp: Experiment,
    pred: dict[str, Any],
    meas: dict[str, Any],
    predicted_resources: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Measured against predicted, step by step. ``predicted_resources`` is
    resources-predicted.json for results whose predictions recorded no CPU or memory; results
    with neither (older kept runs) are compared on latency alone."""
    by = {s["offeredRps"]: s for s in pred["steps"]}
    backfill = {
        s["offeredRps"]: s["resources"] for s in (predicted_resources or {}).get("steps", [])
    }
    in_predictions = any("resources" in s for s in pred["steps"])
    with_resources = in_predictions or bool(backfill)
    steps = measured_steps(meas)
    rows = []
    for m in steps:
        p = by[m["offeredRps"]]
        row = {
            "offeredRps": m["offeredRps"],
            "measured": {
                k: m.get(k) for k in ("p50", "p95", "p99", "successRate", "throughputRps")
            },
            "predicted": {
                k: p.get(k) for k in ("p50", "p95", "p99", "successRate", "throughputRps")
            },
            "error": {k: err(p.get(k), m.get(k)) for k in ("p50", "p95", "p99", "throughputRps")},
            "unstable": m["unstable"],
            "repeats": m["repeats"],
            "failedRepeats": m["failedRepeats"],
        }
        if "cacheHitRatio" in m:
            row["measured"]["cacheHitRatio"] = m["cacheHitRatio"]
            row["predicted"]["cacheHitRatio"] = p.get("cacheHitRatio")
        if "queue" in m:
            row["measured"]["queue"] = m["queue"]
            row["predicted"]["queue"] = p.get("queue")
        predicted = p["resources"] if in_predictions else backfill.get(m["offeredRps"])
        if with_resources and (res := resource_rows(m.get("resources"), predicted)) is not None:
            row["resources"] = res
        rows.append(row)
    mk = grid_knee(steps)
    pk = grid_knee(pred["steps"])
    knee_err = err(pk["kneeRps"], mk["kneeRps"])
    below = at_or_below_knee(rows, mk)
    p95_errs = [abs(r["error"]["p95"]) for r in below if r["error"]["p95"] is not None]
    out: dict[str, Any] = {
        "experiment": exp.id,
        "title": exp.title,
        "objective": {"p95Ms": OBJECTIVE_P95_MS, "minSuccess": MIN_SUCCESS},
        "measuredKnee": mk,
        "predictedKnee": pk,
        "predictedKneeFine": pred["knee"]["kneeRps"],
        "kneeError": knee_err,
        "belowKnee": {
            "steps": len(below),
            "medianAbsP95Error": median(p95_errs),
            "maxAbsP95Error": max(p95_errs) if p95_errs else None,
        },
        "rows": rows,
    }
    if pred.get("kneeRange"):
        out["kneeRange"] = {
            **pred["kneeRange"],
            "containsMeasured": in_range(pred["kneeRange"], mk),
        }
    methods = sorted({s["resources"]["method"] for s in steps if "resources" in s})
    if with_resources and methods:
        out["resources"] = {
            "measuredBy": methods[0] if len(methods) == 1 else "mixed",
            # predictions.json itself, or the later re-simulation (its engine, date and method)
            "predictedBy": (
                "predictions"
                if in_predictions
                else {k: v for k, v in (predicted_resources or {}).items() if k != "steps"}
            ),
            # the key keeps its name: steps at or below both knees (at_or_below_both_knees)
            "belowKnee": resources_below_knee(at_or_below_both_knees(rows, mk, pk)),
        }
    return out


def result_dirs(results: Path = RESULTS) -> list[tuple[str, Path]]:
    """Every run kept for each experiment, oldest first: run-1/, run-2/, ... then the latest (the
    experiment's own directory)."""

    def number(d: Path) -> int:
        return int(d.name.removeprefix("run-"))

    out = []
    for exp in EXPERIMENTS:
        base = results / exp
        older = sorted(
            (d for d in base.glob("run-*") if d.is_dir() and d.name[4:].isdigit()), key=number
        )
        out += [(exp, d) for d in [*older, base] if (d / "measured.json").exists()]
    return out


def archive(exp_id: str, results: Path = RESULTS) -> Path | None:
    """Move an experiment's latest results into its next run-N/ before a new run replaces them,
    so report.py's history keeps every run (N counts this experiment's kept runs). None when
    there is nothing to keep."""
    base = results / exp_id
    if not (base / "measured.json").exists():
        return None
    taken = [int(d.name[4:]) for d in base.glob("run-*") if d.is_dir() and d.name[4:].isdigit()]
    dest = base / f"run-{max(taken, default=0) + 1}"
    dest.mkdir()
    for f in sorted(base.iterdir()):
        if f.is_file():
            f.rename(dest / f.name)
    return dest


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def comparison_of(exp: str, d: Path) -> dict[str, Any]:
    """A results directory's comparison, from its predictions.json, measured.json and (when
    there) resources-predicted.json."""
    return compare(
        EXPERIMENTS[exp],
        read_json(d / "predictions.json"),
        read_json(d / "measured.json"),
        read_json(d / "resources-predicted.json"),
    )


def recompare(results: Path = RESULTS) -> list[Path]:
    """Recompute every comparison.json from its measured.json and predictions.json (no Docker)."""
    written = []
    for exp, d in result_dirs(results):
        if not (d / "predictions.json").exists():
            continue
        write_json(d / "comparison.json", comparison_of(exp, d))
        written.append(d / "comparison.json")
    return written


RESIMULATED = (
    "Simulated again after the measurements, from calibration.json's inputs only (the inputs "
    "predictions.json used; no measured step is used), because the predictions of these results "
    "recorded no CPU or memory. The latency predictions are predictions.json's, unchanged."
)


def predict_resources(results: Path = RESULTS) -> list[Path]:
    """resources-predicted.json for each experiment's latest results whose predictions recorded
    no CPU or memory: every predicted step simulated again with this engine (no Docker)."""
    from archsim_engine.version import ENGINE_VERSION

    written = []
    for exp_id in EXPERIMENTS:
        d = results / exp_id
        pred, cal = read_json(d / "predictions.json"), read_json(d / "calibration.json")
        if not (pred and cal) or any("resources" in s for s in pred["steps"]):
            continue
        exp = EXPERIMENTS[exp_id]
        steps = [s["offeredRps"] for s in pred["steps"]]
        log(f"{exp_id}: simulating {len(steps)} steps again for their CPU and memory")
        with ProcessPoolExecutor(max_workers=min(len(steps), os.cpu_count() or 2)) as pool:
            sims = list(pool.map(simulate, [design(exp, cal["inputs"], s) for s in steps]))
        write_json(
            d / "resources-predicted.json",
            {
                "experiment": exp_id,
                "at": now(),
                "engineVersion": ENGINE_VERSION,
                "how": RESIMULATED,
                "steps": [
                    {"offeredRps": s, "resources": r["resources"]}
                    for s, r in zip(steps, sims, strict=True)
                ],
            },
        )
        written.append(d / "resources-predicted.json")
    return written


# ---------------------------------------------------------------------------------------------
# development checks: today's model against kept measurements (in-sample, never a prediction)
# ---------------------------------------------------------------------------------------------

DEV_KIND = "in-sample re-simulation"
DEV_HOW = (
    "Inputs derived again from this run's calibration passes with today's run.py, and its "
    "measured load steps simulated with today's engine. It is designed after the measurements "
    "were seen, so it checks a model change against data the change may have been tuned on: it "
    "is never a prediction and never shown as one."
)
LATENCY_FIELDS = ("p50", "p95", "p99", "successRate", "throughputRps")


def rederive_inputs(exp: Experiment, calibration: Mapping[str, Any]) -> dict[str, Any]:
    """A run's model inputs derived again from its calibration passes alone, and anchored again,
    as calibrate() would with today's code: under protocol v5 one set per replicate (each top
    pass with its auxiliary mixes, the staircase's first pass, the idle pass and the whole
    staircase for the memory fit), and their medians (``median_inputs``)."""
    passes = calibration["passes"]
    sets = [
        {
            **r,
            "low": passes["low"],
            "idle": passes.get("idle", calibration.get("idle")),
            "staircase": calibration.get("staircase") or [],
        }
        for r in calibration.get("replicates") or []
    ]
    derived = []
    for s in sets or [passes]:
        inputs = model_inputs(exp, s)
        anchor(exp, inputs, s["high"])
        derived.append(inputs)
    return median_inputs(derived) if sets else derived[0]


def dev_check(exp_id: str, d: Path, results: Path = RESULTS) -> dict[str, Any] | None:
    """One kept run checked with today's model: its inputs derived again, its measured ladder
    and the knee simulated, and the comparison with what it measured. None for a run whose
    calibration today's code cannot read (run 1 had one pass, not two)."""
    from archsim_engine.version import ENGINE_VERSION

    exp = EXPERIMENTS[exp_id]
    cal, meas = read_json(d / "calibration.json"), read_json(d / "measured.json")
    if not (cal and meas) or not {"low", "high"} <= set(cal.get("passes", {})):
        return None
    try:
        inputs = rederive_inputs(exp, cal)
    except (KeyError, SystemExit) as e:  # an early protocol lacks a pass today's code reads
        log(f"{exp_id} ({d.name}): today's inputs cannot be derived from it ({e})")
        return None
    sims = [simulate(design(exp, inputs, s["offeredRps"])) for s in meas["steps"]]
    knee = predicted_knee(exp, inputs)
    committed = read_json(d / "predictions.json") or {}
    by = {s["offeredRps"]: s for s in committed.get("steps", [])}
    same = [
        s["offeredRps"] in by
        and all(s.get(k) == by[s["offeredRps"]].get(k) for k in LATENCY_FIELDS)
        for s in sims
    ]
    return {
        "experiment": exp_id,
        "results": d.relative_to(results).as_posix(),
        "calibratedAt": cal.get("at"),
        "calibrationMethod": cal.get("method"),
        "kind": DEV_KIND,
        "designedAfterMeasurements": True,
        "how": DEV_HOW,
        "at": now(),
        "engineVersion": ENGINE_VERSION,
        "provenance": provenance(),
        # True when today's run.py derives the very inputs the run's predictions used
        "inputsAsCalibrated": inputs == cal.get("inputs"),
        "sameLatencyAsPredictions": {
            "steps": sum(same),
            "of": len(sims),
            "predictedWith": committed.get("engineVersion"),
        },
        "inputs": inputs,
        "knee": knee,
        "steps": sims,
        "comparison": compare(exp, {"knee": knee, "steps": sims}, meas),
    }


def dev_path(results: Path, engine: str, exp_id: str, check: Mapping[str, Any]) -> Path:
    """results/dev/<engine>/<experiment>/<calibrated at>.json: named by the calibration, which
    stays with the run when a newer run moves it into run-N/."""
    name = str(check.get("calibratedAt") or check["results"].replace("/", "_"))
    return results / "dev" / engine / exp_id / f"{name.replace(':', '-')}.json"


def resimulate(results: Path = RESULTS, only: str | None = None) -> list[Path]:
    """A development check of every kept run (``dev_check``), written under results/dev/. One
    process per run; no Docker."""
    from archsim_engine.version import ENGINE_VERSION

    runs = [(e, d) for e, d in result_dirs(results) if only in (None, e)]
    log(f"checking {len(runs)} kept runs with engine {ENGINE_VERSION} (in-sample)")
    with ProcessPoolExecutor(max_workers=min(len(runs) or 1, os.cpu_count() or 2)) as pool:
        checks = list(
            pool.map(dev_check, [e for e, _ in runs], [d for _, d in runs], [results] * len(runs))
        )
    written = []
    for (exp_id, d), check in zip(runs, checks, strict=True):
        if check is None:
            log(f"{exp_id} ({d.name}): skipped, no two-pass calibration")
            continue
        path = dev_path(results, ENGINE_VERSION, exp_id, check)
        path.parent.mkdir(parents=True, exist_ok=True)
        write_json(path, check)
        written.append(path)
        c = check["comparison"]
        log(
            f"{exp_id} ({d.name}): knee measured {c['measuredKnee'].get('kneeRps')}, "
            f"simulated {c['predictedKnee'].get('kneeRps')} (error {c['kneeError']}); "
            f"inputs as calibrated: {check['inputsAsCalibrated']}"
        )
    return written


# ---------------------------------------------------------------------------------------------
# environment check: the machine itself, before a run is trusted (no prediction)
# ---------------------------------------------------------------------------------------------

ENVCHECK_RATES = (50, 100, 200, 400, 800, 1600)
ENVCHECK_S = 15
TIMER_TICK_MS = 15.625
"""Windows' default timer interval: latencies that pile up near it come from the host."""


def vm_cpu_times() -> tuple[int, int] | None:
    """(steal, total) jiffies of Docker's VM, from /proc/stat inside the stack's PostgreSQL."""
    text = compose("exec", "-T", "postgres", "cat", "/proc/stat", capture=True, check=False)
    for line in text.splitlines():
        if line.startswith("cpu "):
            v = [int(x) for x in line.split()[1:]]
            return (v[7] if len(v) > 7 else 0), sum(v[:8])
    return None


def sweep_step(exp: Experiment, rate: float) -> dict[str, Any]:
    """One rate of the environment check: 5 s warm-up, statistics reset, ENVCHECK_S recorded."""
    target = target_for(exp)
    log(f"envcheck: {rate:g} req/s (k6 on CPUs {os.environ.get('BENCH_CPUS_K6')})")
    k6(target, exp.mix, rate, 0, 5, "env-warm", exp.keys, exp.skew)
    http_json(f"{API}/reset", "POST")
    cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
    t0 = vm_cpu_times()
    summary = k6(target, exp.mix, rate, 0, ENVCHECK_S, "env", exp.keys, exp.skew)
    t1 = vm_cpu_times()
    cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
    svc = http_json(f"{API}/stats")
    m = k6_measured(summary, ENVCHECK_S)
    pg_ms = cpu_ms(cpu0.get("postgres"), cpu1.get("postgres"))
    requests = svc.get("requests") or 0
    return {
        "rateRps": rate,
        "k6": {k: m.get(k) for k in ("p50", "p95", "p99", "successRate")},
        "serviceCpuMsPerRequest": svc.get("cpuMsPerRequest"),
        "postgresCpuMsPerRequest": round(pg_ms / requests, 4) if pg_ms and requests else None,
        "nearTimerTick": [
            k for k in ("p95", "p99") if m.get(k) and abs(m[k] - TIMER_TICK_MS) <= 1.5
        ],
        "vmSteal": (round((t1[0] - t0[0]) / max(t1[1] - t0[1], 1), 4) if t0 and t1 else None),
    }


def envcheck(results: Path = RESULTS) -> Path:
    """Characterise the machine on pg-mixed's workload: which CPUs share a physical core, how
    much CPU Docker's VM loses to steal, and the service's and PostgreSQL's CPU per request and
    the latency across rates, with k6 on the CPUs the runner gives it and again with the
    service's physical core left to the service alone. Writes results/envcheck/<at>.json."""
    exp = EXPERIMENTS["pg-mixed"]
    siblings = cpu_siblings() or {}
    default = cpusets("postgres")
    service_core = {c for cpu in cpu_list(default["api"]) for c in core_of(cpu, siblings)}
    alone = ",".join(str(c) for c in cpu_list(default["k6"]) if c not in service_core)
    layouts = {"runner": default["k6"], "serviceCoreAlone": alone}
    started = now()
    stack_up("postgres")
    try:
        settle(exp)
        sweeps: dict[str, dict[str, Any]] = {}
        for name, k6_cpus in layouts.items():
            os.environ["BENCH_CPUS_K6"] = k6_cpus
            sweeps[name] = {
                "k6Cpus": k6_cpus,
                "topology": topology({**default, "k6": k6_cpus}, siblings),
                "steps": [sweep_step(exp, r) for r in ENVCHECK_RATES],
            }
    finally:
        stack_down("postgres")
    out = {
        "at": started,
        "environment": environment(),
        "workload": exp.id,
        "how": (
            f"each rate: 5 s warm-up, then {ENVCHECK_S} s recorded; CPU per request from the "
            "service's own counter and PostgreSQL's cgroup; steal from the VM's /proc/stat"
        ),
        "layouts": sweeps,
    }
    path = results / "envcheck" / f"{started.replace(':', '-')}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    write_json(path, out)
    for name, sw in sweeps.items():
        for s in sw["steps"]:
            log(
                f"{name:16} {s['rateRps']:>5} req/s: service {s['serviceCpuMsPerRequest']} ms, "
                f"postgres {s['postgresCpuMsPerRequest']} ms per request, p95 {s['k6']['p95']}, "
                f"steal {s['vmSteal']}{' NEAR TIMER TICK' if s['nearTimerTick'] else ''}"
            )
    return path


# ---------------------------------------------------------------------------------------------


def now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _run(*cmd: str) -> str:
    return subprocess.run(cmd, text=True, capture_output=True, check=False).stdout.strip()


def cpu_name() -> str:
    """The host's CPU model, as the OS names it."""
    cpu = platform.processor()
    if sys.platform == "win32":
        return (
            _run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name")
            or cpu
        )
    if Path("/proc/cpuinfo").exists():
        return next(
            (
                ln.split(":", 1)[1].strip()
                for ln in Path("/proc/cpuinfo").read_text().splitlines()
                if ln.startswith("model name")
            ),
            cpu,
        )
    return cpu


def machine_id(cpu: str | None = None) -> str:
    """A short name for the machine a run measured on: BENCH_MACHINE when set, else the CPU
    model ("11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz" -> "i7-1165g7"). Never the host
    name, which can identify a person."""
    if named := os.environ.get("BENCH_MACHINE"):
        return named
    text = (cpu if cpu is not None else cpu_name()).lower()
    # vendor words go; a word inside a model name ("64-core") stays
    text = re.sub(
        r"@.*|\(r\)|\(tm\)|\b\d+(st|nd|rd|th) gen\b|(?<![\w-])(intel|amd|core|cpu|processor)(?![\w-])",
        " ",
        text,
    )
    return "-".join(text.split()) or "unknown"


HARNESS = (
    "benchmarks/run.py",
    "benchmarks/compose.yaml",
    "benchmarks/service",
    "benchmarks/service-java",
    "benchmarks/service-go",
    "benchmarks/service-python",
    "benchmarks/k6",
    "benchmarks/db",
)
"""What turns calibration passes into predictions and measurements, besides the engine."""


def git(*args: str) -> str | None:
    try:
        proc = subprocess.run(
            ["git", *args], cwd=HERE.parent, text=True, capture_output=True, check=False
        )
    except OSError:
        return None
    return proc.stdout.strip() if proc.returncode == 0 else None


def provenance() -> dict[str, Any]:
    """What produced a prediction: the commit, the engine's source tree and the harness's (git
    object ids, so the same on every machine and line-ending setting), whether either had
    uncommitted changes (then the ids do not describe what ran), and the machine."""
    parts = [f"{p} {git('rev-parse', f'HEAD:{p}')}" for p in HARNESS]
    status = git("status", "--porcelain", "--", "packages/engine", *HARNESS)
    return {
        "commit": git("rev-parse", "HEAD"),
        "engineTree": git("rev-parse", "HEAD:packages/engine"),
        "harnessHash": hashlib.sha256("\n".join(parts).encode()).hexdigest(),
        "dirty": None if status is None else bool(status),
        "machineId": machine_id(),
    }


POWER_MODES = {
    "961cc777-2547-4f9d-8174-7d86181b8a7a": "Best power efficiency",
    "00000000-0000-0000-0000-000000000000": "Balanced",
    "ded574b5-45a0-4f42-8737-46345c09c238": "Best performance",
}
"""Windows 11's power modes (Settings > System > Power), by the overlay scheme each sets."""


def power_mode() -> str | None:
    """Windows' power mode on mains power, or None elsewhere or when it cannot be read. It moves
    the knee: on the laptop, Best performance raised pg-mixed's from 2,430 to about 4,000 req/s
    and cache-aside's from 4,848 to about 7,000 (docs/validation.md), so runs under different
    modes are not repeats of each other."""
    if sys.platform != "win32":
        return None
    import winreg

    key = r"SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes"
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key) as k:
            guid, _ = winreg.QueryValueEx(k, "ActiveOverlayAcPowerScheme")
    except OSError:
        return None
    return POWER_MODES.get(str(guid).lower(), str(guid))


def environment() -> dict[str, Any]:
    cpu = cpu_name()
    return {
        "cpu": cpu,
        "machineId": machine_id(cpu),
        "os": platform.platform(),
        "powerMode": power_mode(),
        "docker": _run(
            "docker", "version", "--format", "{{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})"
        ),
        "dockerCpus": _run("docker", "info", "--format", "{{.NCPU}}"),
        "dockerMemory": _run("docker", "info", "--format", "{{.MemTotal}}"),
        "images": {
            "postgres": "postgres:16-alpine",
            "redis": "redis:7-alpine",
            "kafka": "apache/kafka:3.8.0",
            "k6": "grafana/k6:1.3.0",
            "service": RUNTIMES["nodejs"].image,
        },
        "python": platform.python_version(),
    }


def save(exp_id: str, name: str, data: dict[str, Any]) -> None:
    d = RESULTS / exp_id
    d.mkdir(parents=True, exist_ok=True)
    write_json(d / f"{name}.json", data)


# ---------------------------------------------------------------------------------------------
# held-out experiments: sealed until a frozen engine predicts them
# ---------------------------------------------------------------------------------------------


def frozen_engines(path: Path | None = None) -> list[dict[str, Any]]:
    """The engines in engines.json frozen for prospective predictions: frozenAt and tag set."""
    return [
        e
        for e in read_json(ENGINES if path is None else path) or []
        if e.get("frozenAt") and e.get("tag") and e.get("engineTree")
    ]


def seal(
    exp: Experiment,
    prov: Mapping[str, Any] | None = None,
    engines: Sequence[Mapping[str, Any]] | None = None,
) -> str | None:
    """Why a held-out experiment may not be calibrated or measured now, or None when it may:
    the engine here must be one frozen in engines.json (its source tree, as provenance() names
    it), with nothing uncommitted in the engine or the harness, so that the prediction saved
    before the first measurement is the frozen engine's. Other experiments are never sealed."""
    if exp.held_out is None:
        return None
    prov = provenance() if prov is None else prov
    frozen = frozen_engines() if engines is None else engines
    tree = prov.get("engineTree")
    sealed = (
        f"{exp.id} is a held-out experiment (registered {exp.held_out.registered}, commit "
        f"{exp.held_out.commit[:12]}) and stays sealed"
    )
    if not any(e.get("engineTree") == tree for e in frozen):
        return (
            f"{sealed}: this engine (tree {str(tree)[:12]}) is not frozen in "
            f"benchmarks/engines.json, so it may not be calibrated or measured. "
            f"`run.py smoke {exp.id}` checks its stack without measuring it."
        )
    if prov.get("dirty") is not False:
        return (
            f"{sealed}: packages/engine or the harness has uncommitted changes (or git cannot "
            "tell), so the frozen engine's tree does not describe what would run."
        )
    return None


def require_unsealed(exp: Experiment) -> None:
    if (why := seal(exp)) is not None:
        raise SystemExit(why)


def smoke_problems(exp: Experiment, sent: Mapping[str, Any], stats: Mapping[str, Any]) -> list[str]:
    """What a smoke test found wrong (none: it passed). ``sent``: k6_measured of the run;
    ``stats``: the service's /stats after it. Every request k6 meant to send was sent and
    succeeded, and the service counted each one: as a request, in its handler and PostgreSQL
    timings (on chain, at the gateway and at the API behind it), as a cache hit or miss, or as
    a produced message."""
    want = SMOKE_RPS * SMOKE_S
    n = sent.get("sent") or 0
    out = []
    if n < 0.95 * want:
        out.append(f"k6 sent {n} requests of the {want} it should have")
    if sent.get("dropped"):
        out.append(f"k6 dropped {sent['dropped']} requests (no VU free to send them)")
    if sent.get("failed") or sent.get("succeeded") != n:
        out.append(f"{n - (sent.get('succeeded') or 0)} of {n} requests failed")
    services = [("the service", dict(stats))]
    if exp.stack == "chain":
        services.append(("the API behind the gateway", dict(stats.get("upstream") or {})))
    for who, s in services:
        if s.get("requests") != n:
            out.append(f"{who} counted {s.get('requests')} requests, k6 sent {n}")
        handled = (s.get("timings", {}).get("handler") or {}).get("count")
        if handled != n:
            out.append(f"{who} timed {handled} handlers, k6 sent {n}")
        if not s.get("cpuMs"):
            out.append(f"{who} reported no CPU time")
    if exp.stack in DB_STACKS:
        timings = db_client(exp, {"service": stats}).get("timings", {})
        queries = sum((timings.get(q) or {}).get("count", 0) for q in ("db_read", "db_write"))
        if queries != n:
            out.append(f"PostgreSQL calls timed: {queries}, k6 sent {n}")
    counters = stats.get("counters") or {}
    if exp.stack in CACHE_STACKS:
        looked = counters.get("cache_hit:measure", 0) + counters.get("cache_miss:measure", 0)
        if looked != n:
            out.append(f"the cache counted {looked} hits and misses, k6 sent {n}")
    if exp.stack == "cache-ttl":  # Redis's own command statistics, through the service's /stats
        gets = ((stats.get("redis") or {}).get("get") or {}).get("calls")
        if gets != n:
            out.append(f"Redis counted {gets} GET commands, k6 sent {n}")
    if exp.runtime != "nodejs" and "collections" not in (stats.get("gc") or {}):
        out.append("the service reported no garbage collection counts")
    if exp.runtime == "python" and stats.get("workers") != PYTHON_WORKERS:
        out.append(
            f"{stats.get('workers')} gunicorn workers hold a statistics slot, not {PYTHON_WORKERS}"
        )
    if exp.stack == "kafka" and counters.get("produced:measure", 0) != n:
        out.append(
            f"the producer counted {counters.get('produced:measure', 0)} messages, k6 sent {n}"
        )
    return out


def smoke(exp: Experiment, keep: bool = False) -> list[str]:
    """Start an experiment's stack, send its traffic at SMOKE_RPS req/s for SMOKE_S s (no settle,
    no calibration, no warm-up) and check it (``smoke_problems``). Nothing is kept: no latency is
    shown or written, and k6's summary is deleted. The one way to run a sealed experiment's
    stack. Returns the problems found."""
    stack_up(exp.stack)
    try:
        http_json(f"{API}/reset", "POST")
        try:
            summary = k6(
                target_for(exp), exp.mix, SMOKE_RPS, 0, SMOKE_S, "smoke", exp.keys, exp.skew
            )
        finally:
            (K6_OUT / "smoke.json").unlink(missing_ok=True)
        problems = smoke_problems(exp, k6_measured(summary, SMOKE_S), http_json(f"{API}/stats"))
        if problems:
            logs = compose(
                "logs", "--no-color", "--tail", "30", *STACK_SERVICES[exp.stack],
                capture=True, check=False,
            )  # fmt: skip
            print(logs, flush=True)
    finally:
        if not keep:
            stack_down(exp.stack)
    return problems


def run_experiment(exp: Experiment) -> None:
    require_unsealed(exp)  # a held-out experiment: only with a frozen engine and a clean tree
    calibration = calibrate(exp)
    save(exp.id, "calibration", calibration)
    predictions = predict(exp, calibration)
    save(exp.id, "predictions", predictions)  # written before any measured step runs
    # Its fingerprint goes to the log and into measured.json before the first measured step.
    sealed = hashlib.sha256((RESULTS / exp.id / "predictions.json").read_bytes()).hexdigest()
    log(f"{exp.id}: predictions saved, sha256 {sealed}")
    # These predictions carry CPU and memory: a re-simulation left by older results is stale.
    (RESULTS / exp.id / "resources-predicted.json").unlink(missing_ok=True)
    measured: dict[str, Any] = {
        "experiment": exp.id,
        "at": now(),
        "predictionsSha256": sealed,
        "steps": [],
    }
    for step in [s["offeredRps"] for s in predictions["steps"]]:
        measured["steps"].append(measure_step(exp, step))
        save(exp.id, "measured", measured)
    # Still meeting the objective at the top of the ladder: the prediction was too pessimistic.
    # Climb further, each new step predicted (and saved) before it is measured.
    extensions = 0
    while meets(measured["steps"][-1]) and extensions < MAX_EXTENSIONS:
        extensions += 1
        nxt = round(measured["steps"][-1]["offeredRps"] * 1.3)
        log(f"{exp.id}: still meeting the objective; predicting and then measuring {nxt} req/s")
        predictions["steps"].append(
            {**simulate(design(exp, calibration["inputs"], nxt)), "extension": True, "at": now()}
        )
        save(exp.id, "predictions", predictions)
        measured["steps"].append(measure_step(exp, nxt))
        save(exp.id, "measured", measured)
    comparison = compare(exp, predictions, measured)
    save(exp.id, "comparison", comparison)
    pg = compose("logs", "--no-color", "postgres", capture=True, check=False)
    events = [
        ln
        for ln in pg.splitlines()
        if "checkpoint" in ln or "automatic vacuum" in ln or "automatic analyze" in ln
    ]
    (RESULTS / exp.id / "postgres-events.log").write_text(
        "".join(f"{e}\n" for e in events), encoding="utf-8", newline="\n"
    )
    mk, pk = comparison["measuredKnee"], comparison["predictedKnee"]
    log(
        f"{exp.id}: knee measured {mk.get('kneeRps')} vs predicted {pk.get('kneeRps')} req/s (error {comparison['kneeError']})"
    )


def runnable(
    stacks: Sequence[str], only: str | None = None, refuse: bool = True
) -> dict[str, list[str]]:
    """The experiments to run on each stack, before any stack starts. A sealed held-out
    experiment (``seal``) stops the run (``refuse``: it was asked for by its stack) or is left
    out, saying why (``all``). Stacks with nothing to run are left out."""
    plan: dict[str, list[str]] = {}
    for stack in stacks:
        for exp_id in STACKS[stack]:
            if only and exp_id != only:
                continue
            if (why := seal(EXPERIMENTS[exp_id])) is not None:
                if refuse:
                    raise SystemExit(why)
                log(f"leaving out {exp_id}: {why}")
                continue
            plan.setdefault(stack, []).append(exp_id)
    return plan


def exp_image(exp: Experiment) -> str:
    """The service image of an experiment's stack, as environment.json records it."""
    if exp.stack == "chain":
        return f"{RUNTIMES['nodejs'].image} (gateway and API)"
    return RUNTIMES[exp.runtime].image


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "stack",
        choices=[*STACKS, "all", "smoke", "compare", "resources", "resimulate", "envcheck"],
        help="a stack to run, all of them (held-out experiments only with a frozen engine), "
        "'smoke' EXPERIMENT: start its stack, send 20 req/s for 10 s and check every request "
        "succeeded and was counted (keeps nothing), 'compare': recompute every comparison.json, "
        "'resources': predict CPU and memory for results whose predictions have none, then "
        "compare, 'resimulate': check today's model against every kept run (in-sample, written "
        "under results/dev/), or 'envcheck': characterise this machine (CPU siblings, steal, CPU "
        "per request across rates; about 8 minutes of Docker)",
    )
    ap.add_argument("experiment", nargs="?", help="with smoke: the experiment to smoke-test")
    ap.add_argument("--keep", action="store_true", help="leave the stack running afterwards")
    ap.add_argument("--only", choices=list(EXPERIMENTS), help="run one experiment of the stack")
    ap.add_argument(
        "--archive",
        action="store_true",
        help="first move each experiment's latest results into its next run-N/ (kept history)",
    )
    ap.add_argument(
        "--runs",
        type=int,
        default=1,
        help="independent runs of each experiment, each on a fresh stack, rotating through the "
        "experiments; with more than one, every run first archives the one before",
    )
    args = ap.parse_args(argv)
    if (args.experiment is not None) != (args.stack == "smoke"):
        ap.error("an experiment is named with smoke, and only with smoke")
    if args.stack == "smoke":
        if args.experiment not in EXPERIMENTS:
            ap.error(f"smoke: no experiment '{args.experiment}' (one of {', '.join(EXPERIMENTS)})")
        problems = smoke(EXPERIMENTS[args.experiment], keep=args.keep)
        for p in problems:
            log(f"smoke {args.experiment}: {p}")
        log(f"smoke {args.experiment}: {'FAILED' if problems else 'passed'}")
        return 1 if problems else 0
    if args.stack == "resimulate":
        for path in resimulate(only=args.only):
            log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
        return 0
    if args.stack == "envcheck":
        log(f"wrote {envcheck().relative_to(HERE.parent).as_posix()}")
        return 0
    if args.stack == "resources":
        for path in predict_resources():
            log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
    if args.stack in ("compare", "resources"):
        for path in recompare():
            log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
        return 0
    stacks = list(STACKS) if args.stack == "all" else [args.stack]
    plan = runnable(stacks, args.only, refuse=args.stack != "all")
    env = environment()
    siblings = cpu_siblings()
    # Several runs rotate through the experiments (run 1 of each, then run 2, ...), so slow drift
    # on the machine spreads over all of them; each run starts its stack afresh.
    for run_no in range(1, args.runs + 1):
        for stack, exp_ids in plan.items():
            stack_up(stack)
            try:
                for exp_id in exp_ids:
                    if (args.archive or args.runs > 1) and (kept := archive(exp_id)):
                        log(
                            f"{exp_id}: kept the previous results in {kept.relative_to(HERE.parent)}"
                        )
                    if args.runs > 1:
                        log(f"{exp_id}: run {run_no} of {args.runs}")
                    sets = cpusets(stack)
                    save(
                        exp_id,
                        "environment",
                        {
                            **env,
                            "images": {**env["images"], "service": exp_image(EXPERIMENTS[exp_id])},
                            "cpusets": sets,
                            **({"topology": topology(sets, siblings)} if siblings else {}),
                        },
                    )
                    run_experiment(EXPERIMENTS[exp_id])
            finally:
                if not args.keep or run_no < args.runs:
                    stack_down(stack)
    return 0


if __name__ == "__main__":
    sys.exit(main())
