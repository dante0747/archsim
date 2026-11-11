"""ArchSim accuracy benchmarks: calibrate at low load, predict with ArchSim, measure, compare.

    uv run python benchmarks/run.py postgres      # pg-mixed and pg-hot-row
    uv run python benchmarks/run.py cache         # cache-aside
    uv run python benchmarks/run.py kafka         # kafka
    uv run python benchmarks/run.py all
    uv run python benchmarks/run.py smoke chain   # start a stack, 20 req/s for 10 s, keep nothing
    uv run python benchmarks/run.py compare       # recompute every comparison.json (no Docker)
    uv run python benchmarks/run.py resources     # predict CPU and memory for older results
    uv run python benchmarks/run.py resimulate    # today's model against every kept run
    uv run python benchmarks/run.py range-fit     # fit the knee's predicted range (in-sample)
    uv run python benchmarks/run.py envcheck      # characterise this machine
    uv run python benchmarks/run.py all --runs 3  # three independent runs of each experiment
    uv run python benchmarks/report.py            # rewrite docs/validation.md from the results

Protocol v6 (docs/validation.md): for each experiment the runner
1. starts the stack (benchmarks/compose.yaml) and settles it: VACUUM ANALYZE and a checkpoint
   after the data load (run 3's first calibration pass met the post-load autovacuum), then a
   discarded 30 s warm-up at the calibration rate;
2. calibrates: 20 s at rest (each container's idle CPU), then a staircase of the experiment's
   traffic from 50 req/s up to about two thirds of the service's core (v5: a third), down again
   and the top rate once more, reading what the service measured (CPU per request on the event
   loop, the time of each PostgreSQL, Redis and Kafka call, the database's CPU per query,
   Redis's own command time); each top pass gives one replicate set of model inputs, derived by
   formulas recorded in calibration.json, and the inputs are their medians;
3. predicts: builds the ArchSim design from the calibration and simulates it, finding the knee
   (the highest load with p95 <= 50 ms and >= 99% success), its predicted 80% range (how much
   the knee moves with each input, times that input's run-to-run variation, and the model's own
   error, both fitted in-sample: knee-range.json, ``run.py range-fit``) and every step of the
   load ladder (0.25x to 1.5x the predicted knee);
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

Only the Python standard library, Docker and the Java engine are needed: its CLI, installed by
``./gradlew :archsim-cli:installDist``, simulates (``archsim run``) and answers the engine's
functions (engine_functions.py, ADR-0061). ``BENCH_ENGINE=python`` simulates on the frozen
Python engine instead, until stage 5 of the Java port deletes it (ADR-0062).
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

import engine_functions

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
PROTOCOL = "v6"
"""The calibration protocol calibrate() runs (docs/validation.md, Protocol)."""
STAIRCASE_CPU_CAP = 0.65
"""Protocol v6: a rising calibration pass may use at most this share of the service's CPUs (v5:
0.35, about a third of the knee). The kept runs' ladders put the service's p95 at 1.4 to 2.2 ms
at about half a core and 8 to 11 ms (pg-mixed) by three quarters; 0.65 is the most the
in-sample stacks took with p95 still a few milliseconds."""
STAIRCASE_BOUND_SHARE = 0.5
"""A rising pass may use at most this share of every other capacity the pass before implies
(the connection pool, the hot row's lock, the Kafka consumers), as in protocol v5: those bounds
come from times that include the network and driver, and past them a backlog would carry into
the next pass."""
STAIRCASE_P95_CAP_MS = 10.0
"""The staircase stops rising once a pass's p95 exceeds this (v5 and v6)."""
STAIRCASE_MAX_RISES = 5
"""Protocol v6: at most this many rises above 50 req/s (v5: 3)."""
STAIRCASE_MIN_RISE = 1.1
"""Protocol v6: a next step must be at least this many times the last, or the staircase stops
(v5: 1.25, which stopped the climb about a fifth below its cap)."""
STAIRCASE_V5 = {"cpuCap": 0.35, "boundShare": 0.5, "p95CapMs": 10.0, "maxRises": 3, "minRise": 1.25}
"""Protocol v5's staircase, for the record (the kept runs' calibrations ran it)."""
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
SIM_WALL_S = 3600.0
"""Wall-clock limit of one simulation here (the engine's default, 300 s, guards the API server).
An overloaded ladder step holds up to 100,000 queued requests, which takes minutes when other
work shares the CPU; the limit only decides whether a run finishes, never what it returns."""
ENGINE_VARIABLE = "BENCH_ENGINE"
"""``python`` to simulate on the frozen Python engine instead of the Java engine's ``archsim run``,
the default (ADR-0051, ADR-0062); ``java``, empty or unset for the Java engine."""
JAVA_CLI = engine_functions.CLI
"""The Java engine's command, as ``./gradlew :archsim-cli:installDist`` installs it."""
JAVA_ENGINE_SOURCES = ("archsim-engine", "archsim-model", "archsim-cli")
"""The Java engine's source trees, which provenance names (and counts changes to) on it."""


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
    pg0 = pg_begin()
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
    if (pg := pg_end(pg0)) is not None:
        entry["postgres"] = pg
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
    return min(cpu_bound(exp, low), other_bound(exp, low))


def cpu_bound(exp: Experiment, entry: Mapping[str, Any]) -> float:
    """The rate at which the service's CPUs would fill at a pass's CPU per request (req/s): the
    whole process's, idle included, so pessimistic at low rates; chain: the busier hop."""
    cpu = max(h["cpuMsPerRequest"] for h in hops(entry["service"]))
    return 1000 * service_cpus(exp) / max(cpu, 1e-6)


def other_bound(exp: Experiment, low: Mapping[str, Any]) -> float:
    """The most pessimistic capacity a pass implies other than the CPU's (req/s): the
    connection pool, the hot row's lock, the consumers (infinite when none applies)."""
    svc = low["service"]
    t = svc["timings"]
    bounds = [math.inf]
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
    """The staircase's next rate after a pass, or None to stop (protocol v6):
    STAIRCASE_CPU_CAP of the service's CPUs at the pass's CPU per request (the whole process's:
    every thread or worker, idle included, so the climb undershoots while the cost still falls),
    but no more than STAIRCASE_BOUND_SHARE of any other capacity the pass implies; stop when the
    pass's p95 is above STAIRCASE_P95_CAP_MS or the next rate would not be STAIRCASE_MIN_RISE
    times this one."""
    rate = entry["rateRps"]
    p95 = entry["k6"].get("p95")
    if p95 is not None and p95 > STAIRCASE_P95_CAP_MS:
        return None
    nxt = min(
        STAIRCASE_CPU_CAP * cpu_bound(exp, entry), STAIRCASE_BOUND_SHARE * other_bound(exp, entry)
    )
    return float(round(nxt)) if nxt >= rate * STAIRCASE_MIN_RISE else None


def calibrate(exp: Experiment) -> dict[str, Any]:
    """Protocol v6. After settling and IDLE_S at rest, a staircase of the experiment's traffic:
    50 req/s, then each pass at STAIRCASE_CPU_CAP of the service's CPUs at the previous pass's
    cost (and at most half of any other capacity it implies; ``next_rise``), at most
    STAIRCASE_MAX_RISES rises. Then down again, and the top rate once more: every rate twice,
    the top REPLICATES times. Protocol v5 was the same staircase capped at a third of the core
    (STAIRCASE_V5). Each top pass,
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
    by_load = cpu_by_load(runs, idle)
    replicate_inputs = []
    for s in used:
        inputs = model_inputs(exp, {**s, "cpuByLoad": by_load})
        anchor(exp, inputs, s["high"])
        replicate_inputs.append(inputs)
    return {
        "experiment": exp.id,
        "at": now(),
        "protocol": PROTOCOL,
        "method": (
            "staircase up and down, CPU per request net of idle at the top rate, median of "
            f"{len(used)} replicates, anchored at the top rate, settled first ({PROTOCOL})"
        ),
        "settle": settled,
        "idle": idle,
        "staircaseRule": staircase_rule(),
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
        "cpuByLoad": by_load,
        "inputReplicates": replicate_inputs,
        "inputs": median_inputs(replicate_inputs),
    }


def staircase_rule() -> dict[str, float | int]:
    """The staircase's settings as calibrate() ran them, recorded in calibration.json (a v5
    calibration has none: STAIRCASE_V5)."""
    return {
        "cpuCap": STAIRCASE_CPU_CAP,
        "boundShare": STAIRCASE_BOUND_SHARE,
        "p95CapMs": STAIRCASE_P95_CAP_MS,
        "maxRises": STAIRCASE_MAX_RISES,
        "minRise": STAIRCASE_MIN_RISE,
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
    Records without a value (the low-load reference, the anchor) take each number's median.

    An input only some replicates have is the median of those that have it: a pass without a
    mark-compact gives no ``gcEveryRequests``, and before the v6 campaign a first replicate
    without one dropped it altogether, so the model spaced the median pause by the runtime
    profile's default (1,500 requests for Node.js instead of about 32,500; cache-aside's latest
    run). It is left out when the median pause is 0."""

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
    keys = list(dict.fromkeys(k for r in replicates for k in r))  # a key any replicate has
    for key in keys:
        having = [i for i, r in enumerate(replicates) if key in r]
        items = [replicates[i][key] for i in having]
        first = items[0]
        if isinstance(first, dict) and "value" in first:
            ranked = sorted(
                (i for i, x in enumerate(items) if primary(x["value"]) is not None),
                key=lambda i: primary(items[i]["value"]) or 0.0,
            )
            pick = items[ranked[(len(ranked) - 1) // 2]] if ranked else first
            of = (
                f"{n} replicates"
                if len(items) == n
                else f"the {len(items)} of {n} replicates with it"
            )
            out[key] = {
                **pick,
                "how": f"the median of {of} ({ordinal(having[items.index(pick)])}): {pick['how']}",
            }
        else:
            out[key] = merge(items)
    if "gcEveryRequests" in out and not (out.get("gcPauseMs") or {}).get("value"):
        del out["gcEveryRequests"]  # no pause at the median: nothing to space out
    return out


def ordinal(i: int) -> str:
    return ("first", "second", "third", "fourth", "fifth")[i] if i < 5 else f"number {i + 1}"


SPREAD_S = 10
"""Seconds simulated per try when the service time's spread is anchored (``anchor_spread``)."""
SPREAD_STEPS = 6
"""Bisection steps of ``anchor_spread``, on the log of the p95/median ratio."""
SPREAD_TOLERANCE = 0.05
"""``anchor_spread`` stops once the model's p95 - p50 is within this share of k6's."""
MIN_SPREAD = 1.01
"""The narrowest p95/median ratio ``anchor_spread`` gives (``lognormal``'s floor)."""


def anchor_spread(exp: Experiment, inputs: dict[str, Any], high: dict[str, Any]) -> None:
    """Narrow the service time's spread until the model's p95 - p50 at the top calibration pass
    is k6's, keeping its mean (the CPU per request). The spread comes from the handler's own
    time (``service_inputs``), which is under a tenth of the CPU per request (HTTP parsing and
    the drivers are the rest, and they vary less), and where the database's or the cache's
    replies come in batches it holds the wait behind the requests resolved ahead of it, which
    the model simulates again: cache-aside's own time spreads 11 to 13 times from median to p95
    against 2 to 3 elsewhere, and the model put its p95 at a fifth of the measured knee 2 to 3
    times too high (protocol v6). Kept when the model's p95 - p50 there is no wider than k6's
    (pg-mixed, pg-hot-row and Kafka in v6): a tail the model misses can come from the
    database, the broker or the network, so the spread is never widened to fit it. Bisection
    on the log of the ratio (``SPREAD_STEPS``, ``SPREAD_TOLERANCE``), each try ``SPREAD_S``
    seconds at the pass's rate without the client latency, which moves p95 and p50 alike."""
    st = (inputs.get("serviceTimeMs") or {}).get("value") or {}
    k6 = high.get("k6") or {}
    if st.get("kind") != "lognormal" or k6.get("p95") is None or k6.get("p50") is None:
        return
    rate, target = high["rateRps"], k6["p95"] - k6["p50"]
    mean, widest = mean_ms(st), st["p95"] / st["median"]

    def spread(ratio: float) -> float:
        value = lognormal_with_mean(mean, {"median": 1.0, "p95": ratio})
        probe = {**inputs, "serviceTimeMs": {"value": value}, "clientLinkLatencyMs": {"value": 0.0}}
        sim = simulate(design(exp, probe, rate, warm=5, duration=SPREAD_S))
        return (sim["p95"] or 0.0) - (sim["p50"] or 0.0)

    if widest <= MIN_SPREAD or (model := spread(widest)) <= target:
        return
    lo, hi = math.log(MIN_SPREAD), math.log(widest)
    ratio = widest
    for _ in range(SPREAD_STEPS):  # the closest try wins
        mid = (lo + hi) / 2
        got = spread(math.exp(mid))
        if abs(got - target) < abs(model - target):
            ratio, model = math.exp(mid), got
        if abs(got - target) <= SPREAD_TOLERANCE * target:
            break
        lo, hi = (lo, mid) if got > target else (mid, hi)
    if ratio == widest:  # no narrower spread came closer: the tail is not the service's
        return
    inputs["serviceTimeMs"] = {
        "value": lognormal_with_mean(mean, {"median": 1.0, "p95": ratio}),
        "how": (
            f"{inputs['serviceTimeMs']['how']}; spread anchored: p95/median {ratio:.2f} instead "
            f"of {widest:.2f}, so that the model's p95 - p50 at {rate:g} req/s is k6's: "
            f"{model:.3f} against {target:.3f} ms"
        ),
    }


def anchor(exp: Experiment, inputs: dict[str, Any], high: dict[str, Any]) -> None:
    """Make the model reproduce the calibration pass itself: first its spread (the service
    time's, ``anchor_spread``), then its median. The client-to-service latency is the one input
    not measured directly; it absorbs the difference between the measured median and the
    model's median at that rate (simulated with no client latency), floored at zero. Without
    this the model counts the database driver's CPU twice (in the CPU per request and inside the
    measured query time) and starts about 2x too slow (run 2)."""
    anchor_spread(exp, inputs, high)
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
    machine_inputs(exp, passes, out)
    return out


def service_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """The service's CPU per request, the client-to-service latency and the low-load reference;
    how its CPU per request changed over the staircase (``service_time_by_load``); its garbage
    collection (``gc_inputs``; Node.js: ``node_gc_inputs``, whose pauses come off the CPU per
    request); and for chain the API behind the gateway."""
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
            f"{demand:.4f} ms"
        )
    else:  # protocol v2 to v4: the slope between two passes
        slope_ms = slope(svc_low["cpuMs"], svc["cpuMs"], svc_low["requests"], svc["requests"])
        demand = slope_ms if slope_ms is not None and slope_ms > 0 else svc["cpuMsPerRequest"]
        how = (
            f"mean = {who}'s CPU per extra request between the passes: ({svc['cpuMs']:.0f} - "
            f"{svc_low['cpuMs']:.0f} ms) / ({svc['requests']} - {svc_low['requests']} requests) = "
            f"{demand:.4f} ms (at {low['rateRps']:g} req/s alone: {svc_low['cpuMsPerRequest']:.4f} ms)"
        )
    gc, gc_per_request = node_gc_inputs(svc) if exp.runtime == "nodejs" else ({}, 0.0)
    if gc_per_request > 0:
        # the pauses the model adds were on the event loop, inside the CPU per request
        net = max(demand - gc_per_request, 0.5 * demand)
        how += (
            f", less the {gc_per_request:.4f} ms per request of the pauses modelled as GC "
            f"(gcPauseMs) = {net:.4f} ms"
        )
        demand = net
    how += f"; {shape}"
    out = {
        "serviceTimeMs": {"value": lognormal_with_mean(demand, own), "how": how},
        "clientLinkLatencyMs": {
            "value": round(max(0.0, (high["k6"]["p50"] - handler["median"]) / 2), 4),
            "how": f"(k6 median {high['k6']['p50']:.3f} ms - handler median {handler['median']:.3f} ms) / 2, one way",
        },
        "measuredLowLoad": {"rateRps": high["rateRps"], **high["k6"]},
    }
    curve = service_time_by_load(
        passes.get("cpuByLoad"),
        staircase_cpu(passes.get("staircase") or [], passes.get("idle")),
        service_cpus(exp),
    )
    if curve is not None:
        out["serviceTimeByLoad"] = curve
    out |= gc if exp.runtime == "nodejs" else gc_inputs(exp, svc)
    if exp.stack == "chain":
        out |= chain_inputs(passes)
    return out


CURVE_FORM = "flat"
"""How the service's CPU per request continues above the staircase's top rate: ``flat``, the top
rate's cost (engine 0.15.0's rule, and the default); ``log``, W26's ``extend_load_curve``: the
upper staircase's fall carried on linearly in log(rate), at most one measured stretch, up to the
CPUs' capacity. Off by default until a protocol v6 campaign shows it helps: it was checked only
on staircases built from the kept runs' ladders (docs/validation.md, Protocol), and on those it
helped cache-aside and left pg-mixed alone but raised Kafka's knee, which is not the CPU's."""


def staircase_cpu(
    runs: Sequence[Mapping[str, Any]], idle: Mapping[str, Any] | None
) -> list[tuple[float, float]]:
    """Each staircase pass's (rate, the service's CPU per request net of idle in ms), as
    ``cpu_by_load`` takes their median (empty without the idle pass: before protocol v5)."""
    if not idle:
        return []
    rest = idle["serviceCpuMsPerSecond"] * CALIBRATION_S
    return [
        (
            float(r["rateRps"]),
            max(0.0, r["service"]["cpuMs"] - rest) / max(r["service"]["requests"], 1),
        )
        for r in runs
        if r.get("service", {}).get("requests")
    ]


def service_time_by_load(
    by_load: Sequence[Mapping[str, Any]] | None,
    passes: Sequence[tuple[float, float]] = (),
    cores: float = 1.0,
    form: str | None = None,
) -> dict[str, Any] | None:
    """How the service's CPU per request changed with load over the calibration staircase
    (``cpuByLoad``: each rate's CPU per request net of idle, the median of its passes), as
    ``serviceTimeByLoad``: the engine's never-rising fit (``fit_load_curve``, one bin per rate),
    relative to the top rate, whose CPU per request the service time is. Above the top rate the
    engine keeps the top rate's factor (1), unless ``form`` (default CURVE_FORM) is ``log``: then
    the fall the upper staircase measured is carried on, at most one measured stretch, until the
    service's ``cores`` fill (``extend_load_curve`` over each pass's CPU per request,
    ``passes``), when the top kept the CPUs at least EXTEND_MIN_BUSY busy and the fall stands out
    of the passes' scatter. A protocol v5 staircase stops at a third of the core, so its curve
    stays flat above the top either way. None without a staircase (protocols before v5), or when
    the curve spans no more than the engine's ``min_curve_change()``."""
    pts = [
        (float(b["rateRps"]), b["rateRps"] * b["serviceCpuMsPerRequest"] / 1000.0)
        for b in by_load or []
        if b.get("serviceCpuMsPerRequest")
    ]
    fit = engine_functions.fit_load_curve(pts, max_bins=len(pts)) if len(pts) >= 2 else None
    if fit is None:
        return None
    form = form or CURVE_FORM
    ext = engine_functions.extend_load_curve(passes, cores) if passes and form == "log" else None
    top = fit.points[-1][1]
    curve = [(r, f / top) for r, f in fit.points] + (ext.factors() if ext else [])
    factors = [f for _, f in curve]
    if max(factors) / min(factors) - 1.0 <= engine_functions.min_curve_change():
        return None
    table = [{"rps": round(r, 1), "factor": round(f, 4)} for r, f in curve]
    measured = ", ".join(f"{m:.4f} ms at {r:g}" for r, m in fit.measured)
    # not extended: worded as engine 0.15.0 wrote it, so every kept run's inputs stay the same
    above = (
        f"above it {ext.reason}, then flat" if ext and ext.points else "flat outside the staircase"
    )
    return {
        "value": table,
        "how": (
            f"the staircase's CPU per request net of idle ({measured} req/s), fitted never "
            f"rising with rate and divided by the top rate's ({fit.measured[-1][1]:.4f} ms); "
            f"{above}"
        ),
    }


GC_SCAVENGE = "minor"
"""V8's scavenges of the young generation: short and frequent (about 1 ms every 150 requests in
the W21 trial), left in the CPU per request rather than modelled as pauses."""


def node_gc_inputs(stats: Mapping[str, Any]) -> tuple[dict[str, Any], float]:
    """Node.js's garbage collection from the service's /stats (``gc``: V8's 'gc' performance
    entries, the main thread's time in each collection by kind) and the pause per request the
    model adds, to take off the CPU per request (the event loop's pauses are in it, and the
    model's pause stalls the loop without counting as CPU).

    The model's pause is the mark-compact cycle: its final pause and the incremental-marking and
    weak-callback steps of the pass, per mark-compact (``gcEveryRequests`` requests apart). They
    are rare and long (10 to 45 ms in the W21 trial), which is what stalls a busy event loop
    long enough to move p95. Scavenges (``GC_SCAVENGE``) stay in the CPU per request: a model
    pause has one size, and averaging them in would shrink it to about 2 ms. The median is set
    from the mean as for Java and Go (``gc_inputs``: lognormal with p95 2.5x its median)."""
    gc = stats.get("gc")
    if not gc or not gc.get("byKind"):  # a service before W21: none modelled, as then
        return {}, 0.0
    kinds = gc["byKind"]
    source = f" ({gc['source']})" if gc.get("source") else ""
    scav = kinds.get(GC_SCAVENGE) or {}
    cycles = float((kinds.get("major") or {}).get("collections") or 0)
    pause = sum(float(v.get("pauseMs") or 0.0) for k, v in kinds.items() if k != GC_SCAVENGE)
    requests = stats.get("requests") or 0
    left = (
        f"{scav.get('collections', 0):g} scavenges, {float(scav.get('pauseMs') or 0):.1f} ms, "
        "left in the CPU per request"
    )
    if cycles <= 0 or pause <= 0 or requests <= 0:
        return {
            "gcPauseMs": {
                "value": 0,
                "how": f"no mark-compact in the pass{source}; {left}",
            }
        }, 0.0
    mean = pause / cycles
    sigma = math.log(2.5) / Z95
    steps = ", ".join(
        f"{k} {float(v.get('pauseMs') or 0):.1f} ms" for k, v in kinds.items() if k != GC_SCAVENGE
    )
    return {
        "gcPauseMs": {
            "value": round(mean / math.exp(sigma**2 / 2), 4),
            "how": (
                f"V8's mark-compact cycles in the pass{source}: {pause:.1f} ms ({steps}) over "
                f"{cycles:g} cycles, a mean of {mean:.3f} ms (the model's median: mean / 1.17); "
                f"{left}"
            ),
        },
        "gcEveryRequests": {
            "value": max(1, round(requests / cycles)),
            "how": f"{requests} requests / {cycles:g} mark-compact cycles in the pass{source}",
        },
    }, pause / requests


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


# PostgreSQL's own statistics over a calibration pass (compose.yaml loads pg_stat_statements and
# times the WAL's I/O): each statement's time inside the server, the WAL flushes and their time,
# the commits, the time backends spent active, and the CPU of each kind of PostgreSQL process.

PG_STATS_SQL = """
SELECT json_build_object(
  'statements', (
    SELECT coalesce(json_agg(json_build_object(
      'query', s.query, 'calls', s.calls, 'rows', s.rows,
      'meanExecMs', s.mean_exec_time, 'stddevExecMs', s.stddev_exec_time,
      'minExecMs', s.min_exec_time, 'maxExecMs', s.max_exec_time) ORDER BY s.calls DESC), '[]')
    FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
    WHERE d.datname = current_database() AND s.query !~* 'pg_stat'),
  'wal', (SELECT json_build_object(
    'records', wal_records, 'bytes', wal_bytes, 'buffersFull', wal_buffers_full,
    'writes', wal_write, 'syncs', wal_sync, 'writeMs', wal_write_time,
    'syncMs', wal_sync_time) FROM pg_stat_wal),
  'database', (SELECT json_build_object(
    'commits', xact_commit, 'rollbacks', xact_rollback, 'activeMs', active_time,
    'sessionMs', session_time) FROM pg_stat_database WHERE datname = current_database()))
"""
PG_PROCESSES_SH = (
    'for d in /proc/[0-9]*; do printf "%s\\t%s\\n" "$(tr "\\0" " " < "$d/cmdline" 2>/dev/null)" '
    '"$(cat "$d/stat" 2>/dev/null)"; done'
)
CLOCK_TICKS = 100
"""Linux's USER_HZ: /proc/<pid>/stat counts CPU time in hundredths of a second."""


def pg_process_kind(cmdline: str) -> str | None:
    """A PostgreSQL process by its title: the client backends (connections from the services),
    the WAL writer, the other background processes, or None for this probe's own processes."""
    t = cmdline.strip()
    if not t.startswith("postgres"):
        return None  # sh, cat, psql: the probe
    if "[local]" in t:
        return None  # the probe's own backend
    if t.startswith("postgres: walwriter"):
        return "walWriter"
    if re.match(r"postgres: \S+ \S+ \d", t):
        return "backends"
    return "background"


def pg_processes() -> dict[str, tuple[str, int]] | None:
    """Each PostgreSQL process's CPU so far (ticks), by pid: its kind and utime + stime. A backend
    that ends during a pass (a pool closing an idle connection) is missing from the pass's
    figures, so the backends' total can fall short; the background processes' does not."""
    proc = subprocess.run(
        ["docker", "compose", "-f", str(HERE / "compose.yaml"), "exec", "-T", "postgres",
         "sh", "-c", PG_PROCESSES_SH],
        cwd=HERE, text=True, capture_output=True, check=False,
    )  # fmt: skip
    if proc.returncode != 0:
        return None
    out: dict[str, tuple[str, int]] = {}
    for line in proc.stdout.splitlines():
        cmd, _, stat = line.partition("\t")
        kind = pg_process_kind(cmd)
        if kind is None or ")" not in stat:
            continue
        pid = stat.split(" ", 1)[0]
        fields = stat.rsplit(")", 1)[1].split()  # from field 3 (state): utime is 14, stime 15
        out[pid] = (kind, int(fields[11]) + int(fields[12]))
    return out


def pg_snapshot() -> dict[str, Any] | None:
    """PostgreSQL's statistics now (PG_STATS_SQL) and its processes' CPU, or None when the
    stack's PostgreSQL has no pg_stat_statements (stacks started before compose.yaml loaded it)."""
    proc = subprocess.run(
        ["docker", "compose", "-f", str(HERE / "compose.yaml"), "exec", "-T", "postgres",
         "psql", "-U", "bench", "-d", "bench", "-At", "-c", PG_STATS_SQL],
        cwd=HERE, text=True, capture_output=True, check=False,
    )  # fmt: skip
    if proc.returncode != 0 or not proc.stdout.strip():
        log(f"no PostgreSQL statistics: {(proc.stderr or proc.stdout).strip()[-300:]}")
        return None
    return {"stats": json.loads(proc.stdout), "processes": pg_processes()}


def pg_begin() -> dict[str, Any] | None:
    """Before a calibration pass: clear pg_stat_statements (its means then cover the pass alone)
    and read the counters the pass is measured against."""
    compose(
        "exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-qAt",
        "-c", "SELECT pg_stat_statements_reset()",
        check=False, capture=True,
    )  # fmt: skip
    return pg_snapshot()


def statement_kind(query: str) -> str | None:
    """What a benchmark statement does, whatever the service's driver made of its text: a read by
    key, an update by key, or the consumer's insert; None for anything else (BEGIN, pool checks)."""
    q = " ".join(query.lower().split())
    if q.startswith("select") and " from items" in q:
        return "read"
    if q.startswith("update items"):
        return "write"
    if q.startswith("insert into events"):
        return "insert"
    return None


def pg_end(before: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """After a calibration pass: what PostgreSQL did during it. ``statements`` are
    pg_stat_statements since ``pg_begin`` (the time each statement executed, from start to end
    of the executor: planning, the commit and its WAL flush are not in it); ``wal``,
    ``database`` and ``cpuMsByProcess`` are counted from ``before``. Counters that backends
    flush to the statistics once a second can lag by up to a second at either end, alike at
    both ends of a steady pass."""
    after = pg_snapshot() if before is not None else None
    if before is None or after is None:
        return None
    return pg_window(before, after)


def pg_window(before: Mapping[str, Any], after: Mapping[str, Any]) -> dict[str, Any]:
    """What PostgreSQL did between two ``pg_snapshot``s (``pg_end``)."""
    b, a = before["stats"], after["stats"]
    statements = [
        {"kind": statement_kind(s["query"]), **s}
        for s in a["statements"]
        if s["calls"] > 0 and statement_kind(s["query"]) is not None
    ]
    out: dict[str, Any] = {
        "statements": statements,
        "wal": {k: round(a["wal"][k] - b["wal"][k], 4) for k in a["wal"]},
        "database": {k: round(a["database"][k] - b["database"][k], 4) for k in a["database"]},
    }
    p0, p1 = before.get("processes"), after.get("processes")
    if p0 is not None and p1 is not None:
        cpu: dict[str, float] = {}
        for pid, (kind, ticks) in p1.items():
            cpu[kind] = (
                cpu.get(kind, 0.0) + (ticks - p0.get(pid, (kind, 0))[1]) * 1000 / CLOCK_TICKS
            )
        out["cpuMsByProcess"] = {k: round(v, 1) for k, v in sorted(cpu.items())}
    return out


def pg_statement(entry: Mapping[str, Any], kind: str) -> dict[str, Any] | None:
    """A pass's statement of one kind (``statement_kind``) as pg_stat_statements timed it, all
    its forms together (calls-weighted mean), or None when the pass recorded none."""
    rows = [s for s in (entry.get("postgres") or {}).get("statements", []) if s["kind"] == kind]
    calls = sum(s["calls"] for s in rows)
    if not calls:
        return None
    return {
        "calls": calls,
        "meanExecMs": sum(s["meanExecMs"] * s["calls"] for s in rows) / calls,
    }


def pg_flush(entry: Mapping[str, Any]) -> dict[str, float] | None:
    """A pass's WAL flushes: their number, each one's time (writing the WAL and syncing it, which
    run one after another under the WAL write lock) and the commits that shared one, or None
    without a flush or the statistics."""
    pg = entry.get("postgres") or {}
    wal = pg.get("wal") or {}
    if not wal.get("syncs") or not wal.get("syncMs"):  # no flush, or track_wal_io_timing off
        return None
    commits = sum(s["calls"] for s in pg["statements"] if s["kind"] in ("write", "insert"))
    return {
        "syncs": wal["syncs"],
        "flushMs": (wal["writeMs"] + wal["syncMs"]) / wal["syncs"],
        "syncMs": wal["syncMs"] / wal["syncs"],
        "commitsPerFlush": commits / wal["syncs"],
    }


def db_client(exp: Experiment, entry: Mapping[str, Any]) -> dict[str, Any]:
    """The /stats of the service that calls PostgreSQL in a calibration pass: chain's API (inside
    the gateway's), else the service k6 calls."""
    svc: dict[str, Any] = entry["service"]
    return svc["upstream"] if exp.stack == "chain" else svc


def mean_ms(dist: Mapping[str, Any]) -> float:
    """The mean of a model input's distribution as the engine samples it (ms)."""
    return engine_functions.expected_ms(dist)


def db_cpu_share(
    passes: dict[str, Any], q_low: float, q_high: float, model_ms: float, model_how: str
) -> dict[str, Any] | None:
    """PostgreSQL's CPU per query over the mean time the model gives a query, or None without a
    usable figure: at the top pass net of idle (protocol v5), else per extra query between the
    two passes. The model spends ``cpuShare`` x each query's sampled time on a core, so the
    share is taken against the mean of the times it samples (``model_ms``: writes without their
    WAL flush, which uses no CPU in the model), not against the mean the service measured
    (engine 0.14.0 did, which counted the flush and the measured tail and left PostgreSQL's busy
    cores 16% to 69% low)."""
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
    if pg is None or pg <= 0 or model_ms <= 0:
        return None
    return {
        "value": round(min(1.0, max(0.02, pg / model_ms)), 4),
        "how": f"{what} / the model's mean time per query ({model_how})",
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


FLUSH_FIT_S = 15
"""Simulated seconds (after 2 s of warm-up) of each step of ``fit_flush``, longer when needed for
FLUSH_FIT_COMMITS commits (at most FLUSH_FIT_MAX_S)."""
FLUSH_FIT_COMMITS = 1000
FLUSH_FIT_MAX_S = 120


def database_only(
    exp: Experiment, v: Mapping[str, Any], rate: float, read_ratio: float, seconds: float
) -> dict[str, Any]:
    """A design of PostgreSQL alone: the pass's queries sent at its rate over the
    service-to-database link (``fit_flush``)."""
    return {
        "schemaVersion": "1.0",
        "kind": "archsim.architecture",
        "metadata": {"name": f"benchmark {exp.id}: PostgreSQL alone"},
        "architecture": {
            "nodes": [
                {
                    "id": "clients",
                    "type": "traffic_source",
                    "label": "service",
                    "config": {"timeoutMs": TIMEOUT_MS, "maxAttempts": 1},
                },
                database_node(v),
            ],
            "edges": [_edge("e-db", "clients", "db", v.get("dbLinkLatencyMs", 0.0))],
            "autoSize": False,
        },
        "workload": {
            "rps": rate,
            "durationS": 2 + seconds,
            "warmupS": 2,
            "readRatio": read_ratio,
            "keyCount": exp.keys,
            "keySkew": exp.skew,
            "seed": SEED,
            "arrivalProcess": "deterministic",
        },
        "scenarios": [],
    }


def fit_flush(
    exp: Experiment,
    v: Mapping[str, Any],
    rate: float,
    read_ratio: float,
    wait_ms: float,
    shape: float,
) -> tuple[float, dict[str, Any]]:
    """The median WAL flush (p95/median ``shape``) with which the model's writes wait
    ``wait_ms`` on average for their row lock and their flush, at the pass's rate and mix
    (``database_only``), and the model's database there. What the service measured as that
    wait (synchronous_commit on minus off) already includes the other commits' flushes it
    queued behind (group commit) and, on one row, the writes ahead of it holding the lock; the
    model adds both again, so the measured wait itself is too long a flush. Engine 0.14.0 used
    it: on Kafka's write pass (569 commits/s, run 7) the model's commits then waited 1.09 ms
    where the service's had waited 0.92."""
    sigma = math.log(max(shape, 1.01)) / Z95
    median = wait_ms / math.exp(sigma**2 / 2)  # a flush alone, with nothing to wait for
    # enough commits that their flushes' mean is the distribution's, not one sample's
    writes = rate * (1 - read_ratio)
    seconds = min(FLUSH_FIT_MAX_S, max(FLUSH_FIT_S, FLUSH_FIT_COMMITS / max(writes, 1e-9)))
    lo, hi = 0.0, math.inf  # the wait only grows with the flush: keep the answer bracketed
    db: dict[str, Any] = {}
    for _ in range(12):
        flush = lognormal(median, median * shape)
        db = simulate(database_only(exp, {**v, "commitFlushMs": flush}, rate, read_ratio, seconds))[
            "database"
        ]
        waited = (db.get("meanLockWaitMs") or 0.0) + (db.get("meanCommitWaitMs") or 0.0)
        if waited <= 0 or abs(waited / wait_ms - 1) < 2e-3:
            break
        lo, hi = (lo, median) if waited > wait_ms else (median, hi)
        nxt = median * wait_ms / waited
        median = nxt if lo < nxt < hi else (lo + hi) / 2 if hi < math.inf else 2 * median
    return median, db


def lognormal_moments(dist: Mapping[str, Any]) -> tuple[float, float]:
    """Mean and variance of a lognormal model input (ms, ms^2)."""
    sigma = math.log(dist["p95"] / dist["median"]) / Z95
    mean = dist["median"] * math.exp(sigma**2 / 2)
    return mean, mean**2 * (math.exp(sigma**2) - 1)


def pg_cpu_per_query(
    passes: Mapping[str, Any],
    entry: Mapping[str, Any],
    queries: float,
    low: Mapping[str, Any] | None = None,
    queries_low: float = 0.0,
) -> float | None:
    """PostgreSQL's CPU per query in a pass (ms): net of its CPU at rest (protocol v5), else per
    extra query over ``low`` (protocols v2 to v4); None without either (a pass's own CPU over
    its queries would count PostgreSQL's CPU at rest too)."""
    if (idle := passes.get("idle")) is not None:
        net = net_cpu_ms(entry, idle, "postgres")
        return net / queries if net is not None and queries > 0 and net > 0 else None
    if low is None:
        return None
    per = slope(
        low["containerCpuMs"].get("postgres"),
        entry["containerCpuMs"].get("postgres"),
        queries_low,
        queries,
    )
    return per if per is not None and per > 0 else None


def server_how(what: str, cpu_ms: float | None, rt_words: str, note: str = "") -> str:
    """How a query's time in PostgreSQL was found (``server_time``)."""
    if cpu_ms is None:
        return f"no usable PostgreSQL CPU per {what} in the pass: {rt_words} as the service measured it"
    return f"PostgreSQL's CPU per {what} ({cpu_ms:.4f} ms{note}), shaped like {rt_words}"


def server_time(cpu_ms: float | None, rt: Mapping[str, float]) -> dict[str, Any]:
    """A query's time inside PostgreSQL: its CPU per query (calibration on 31 Oct found the
    backends busy on the CPU for nearly all of it: statement, parse, plan and the protocol
    around them), shaped like the round trip the service measured, and never longer than that
    round trip. Without a CPU figure, the round trip itself (engine 0.14.0)."""
    whole = lognormal(rt["median"], rt["p95"])
    if cpu_ms is None:
        return whole
    return lognormal_with_mean(min(cpu_ms, mean_ms(whole)), dict(rt))


def link_leg(rt: Mapping[str, float], server: Mapping[str, Any]) -> float | dict[str, Any]:
    """One way of the service-to-database link: the round trip less the server's time, split
    into two equal legs that keep the round trip's mean and variance (each leg a lognormal of
    half the mean and half the variance, Fenton-Wilkinson), or 0 when nothing is left."""
    m_rt, v_rt = lognormal_moments(lognormal(rt["median"], rt["p95"]))
    m_s, v_s = lognormal_moments(server)
    m, v = (m_rt - m_s) / 2, max(0.0, v_rt - v_s) / 2
    if m < 0.001:
        return 0.0
    if v <= 0:
        return round(m, 4)
    s2 = math.log(1 + v / m**2)
    median = m / math.exp(s2 / 2)
    return lognormal(median, median * math.exp(Z95 * math.sqrt(s2)))


def describe(dist: float | Mapping[str, Any]) -> str:
    """A link latency in words."""
    if isinstance(dist, Mapping):
        return f"median {dist['median']:.4f}, p95 {dist['p95']:.4f} ms"
    return f"{dist:.4f} ms"


def commit_flush(
    exp: Experiment,
    v: Mapping[str, Any],
    high: Mapping[str, Any],
    sync_pass: Mapping[str, Any],
    sync: Mapping[str, float],
    async_: Mapping[str, float],
    read_ratio: float,
) -> dict[str, Any]:
    """``commitFlushMs``: the time of one WAL flush.

    With PostgreSQL's own statistics in the top pass (``pg_flush``, since 31 Oct), the mean
    time of its flushes there (writing the WAL and syncing it), shaped like the commit wait the
    service measured. Without them (earlier runs), the flush with which the model's writes wait
    as long for their lock and flush as the service's did (``fit_flush``): synchronous_commit on
    minus off in ``sync_pass`` and the pass without the flush at the same rate, from the
    medians and p95s (a mean takes in the service's own stalls: one of Kafka's write passes on
    31 Oct had a mean of 2.65 ms and a median of 0.82). On the trial of 31 Oct the two agreed
    within 5% (hot-row: 0.80 ms per flush measured by PostgreSQL, 0.81 waited)."""
    med = max(0.01, sync["median"] - async_["median"])
    shape = max(1.01, (sync["p95"] - async_["p95"]) / med)
    waited = mean_ms(lognormal(med, med * shape))
    measured = pg_flush(high)
    if measured is not None:
        flush = lognormal_with_mean(measured["flushMs"], {"median": 1.0, "p95": shape})
        db = simulate(
            database_only(
                exp, {**v, "commitFlushMs": flush}, high["rateRps"], read_ratio, FLUSH_FIT_S
            )
        )["database"]
        how = (
            f"PostgreSQL's own WAL flushes at {high['rateRps']:g} req/s (pg_stat_wal): "
            f"{measured['syncs']:.0f} flushes of {measured['flushMs']:.3f} ms on average, "
            f"{measured['commitsPerFlush']:.2f} commits per flush (the model: "
            f"{db.get('commitsPerFlush') or 0:.2f}); shaped like the commit wait the service "
            f"measured (p95/median {shape:.2f}, mean {waited:.3f} ms)"
        )
    else:
        rate = sync_pass["rateRps"]
        median, db = fit_flush(exp, v, rate, read_ratio, waited, shape)
        flush = lognormal(median, median * shape)
        how = (
            f"fitted so that the model's writes wait as long for their lock and flush as the "
            f"service's did at {rate:g} req/s (synchronous_commit on minus off: median "
            f"{sync['median']:.3f} - {async_['median']:.3f} ms, p95/median {shape:.2f}, a mean "
            f"of {waited:.3f} ms); in the model there: lock {db.get('meanLockWaitMs') or 0:.3f} "
            f"ms, flush {db.get('meanCommitWaitMs') or 0:.3f} ms, "
            f"{db.get('commitsPerFlush') or 0:.2f} commits per flush"
        )
    out: dict[str, Any] = {
        "value": flush,
        "how": how,
        "model": {k: db.get(k) for k in ("meanLockWaitMs", "meanCommitWaitMs", "commitsPerFlush")},
    }
    if measured is not None:
        out["postgres"] = {k: round(x, 4) for k, x in measured.items()}
    return out


def database_inputs(exp: Experiment, passes: dict[str, Any]) -> dict[str, Any]:
    """PostgreSQL: the time a query spends in the server, the service-to-database link, the WAL
    flush per commit and the CPU share of a query. On the Kafka stack the queries are the
    consumer's INSERTs; on chain, the API's (behind the gateway).

    A query's time in the server is PostgreSQL's CPU per query (``server_time``), so a write
    holds its row lock for that and its flush only; the rest of the round trip the service
    measured (its driver, the network) is the link (``dbLinkLatencyMs``, each way). Engine
    0.14.0 put the whole round trip in the database, under the row lock: hot-row's lock was
    held for Node's driver and network time too."""
    low, high = passes["low"], passes["high"]
    svc, svc_low = db_client(exp, high), db_client(exp, low)
    out: dict[str, Any] = {}
    share: dict[str, Any] | None
    leg: float | dict[str, Any] = 0.0
    if exp.stack == "kafka":
        cons, cons_low = high["consumer"], low["consumer"]
        insert = timing(cons, "db_insert")
        sync_pass = passes["write:1"]
        sync = timing(sync_pass["service"], "db_write")
        async_ = timing(passes["write_async:1"]["service"], "db_write_async")
        wait_med = sync["median"] - async_["median"]
        rt = {  # the INSERT without its commit wait
            "median": max(0.01, insert["median"] - wait_med),
            "p95": max(0.02, insert["p95"] - wait_med),
        }
        cpu = pg_cpu_per_query(passes, high, cons["consumed"], low, cons_low["consumed"])
        s = server_time(cpu, rt)
        out["writeServiceTimeMs"] = {
            "value": s,
            "how": server_how(
                "INSERT",
                cpu,
                f"the consumer's INSERT less its commit wait (median {insert['median']:.3f} - "
                f"{wait_med:.3f} ms, p95 {insert['p95']:.3f} - {wait_med:.3f} ms)",
            ),
        }
        leg = link_leg(rt, s)
        ins_ms = mean_ms(s)
        share = db_cpu_share(
            passes, cons_low["consumed"], cons["consumed"], ins_ms, f"the INSERT, {ins_ms:.3f} ms"
        )
    else:
        t, t_low = svc["timings"], svc_low["timings"]
        names = [n for n in ("db_read", "db_write") if n in t]
        q_high = sum(t[n]["count"] for n in names)
        q_low = sum(t_low[n]["count"] for n in names if n in t_low)
        n_w = t["db_write"]["count"] if "db_write" in t else 0
        cpu_all = pg_cpu_per_query(passes, high, q_high, low, q_low)
        cpu_w = None
        if "db_write" in t:
            w = timing(svc, "db_write")
            async_pass = passes["write_async:1"]
            a = timing(db_client(exp, async_pass), "db_write_async")
            cpu_w = pg_cpu_per_query(passes, async_pass, a["count"])
        if cpu_all is None or ("db_write" in t and cpu_w is None):
            # a CPU figure for each kind of query, or none: the reads would set a link that the
            # writes' whole round trips would then pay again (protocols before v5 have no CPU
            # figure for the pass without the flush)
            cpu_all = cpu_w = None
        if "db_write" in t:
            s_w = server_time(cpu_w, a)
            out["writeServiceTimeMs"] = {
                "value": s_w,
                "how": server_how(
                    "UPDATE with synchronous_commit off",
                    cpu_w,
                    f"its round trip (median {a['median']:.3f}, p95 {a['p95']:.3f} ms)",
                ),
            }
            leg = link_leg(a, s_w)
        if "db_read" in t:
            r = timing(svc, "db_read")
            cpu_r = cpu_all
            if cpu_all is not None and cpu_w is not None and n_w:  # the reads' share of the CPU
                cpu_r = max(0.25 * cpu_all, (cpu_all * q_high - cpu_w * n_w) / max(r["count"], 1))
            s_r = server_time(cpu_r, r)
            out["readServiceTimeMs"] = {
                "value": s_r,
                "how": server_how(
                    "SELECT",
                    cpu_r,
                    f"its round trip (median {r['median']:.3f}, p95 {r['p95']:.3f} ms)",
                    ", the pass's less its UPDATEs'" if cpu_r != cpu_all else "",
                ),
            }
            leg = link_leg(r, s_r)  # most queries are reads: they set the link
        mix = [
            (t[n]["count"], mean_ms(out[k]["value"]), what)
            for n, k, what in (
                ("db_read", "readServiceTimeMs", "reads"),
                ("db_write", "writeServiceTimeMs", "writes without their flush"),
            )
            if n in t
        ]
        model_ms = sum(c * ms for c, ms, _ in mix) / max(q_high, 1)
        share = db_cpu_share(
            passes,
            q_low,
            q_high,
            model_ms,
            " and ".join(f"{c} {what} at {ms:.3f} ms" for c, ms, what in mix)
            + f": {model_ms:.3f} ms",
        )
    out["dbLinkLatencyMs"] = {
        "value": leg,
        "how": (
            "each way: the round trip the service measured less PostgreSQL's time, in two legs "
            f"that keep its mean and variance ({describe(leg)})"
        ),
    }
    if share is not None:
        out["dbCpuShare"] = share
    for key, entry, kind in (  # PostgreSQL's executor time, next to the model's (a check)
        ("readServiceTimeMs", high, "read"),
        ("writeServiceTimeMs", passes.get("write_async:1", {}), "write"),
        ("writeServiceTimeMs", high, "insert"),
    ):
        if key in out and (st := pg_statement(entry, kind)) is not None:
            out[key]["postgres"] = {"executorMs": round(st["meanExecMs"], 4)}
    v = {k: x["value"] for k, x in out.items()}
    if exp.stack == "kafka":  # the write pass: UPDATEs over the keys at its own rate
        out["commitFlushMs"] = commit_flush(exp, v, high, sync_pass, sync, async_, 0.0)
    elif "db_write" in svc["timings"]:
        out["commitFlushMs"] = commit_flush(exp, v, high, high, w, a, exp.read_ratio)
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
    out: dict[str, Any] = {}
    for prefix, name in memory_containers(exp).items():
        pts = memory_points(passes, name)
        if len({r for r, _ in pts if r > 0}) < MEMORY_MIN_RATES:
            continue
        fit = engine_functions.fit_memory([w for _, w in pts], rate=[r for r, _ in pts])
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
    eid: str,
    src: str,
    dst: str,
    latency: float | Mapping[str, Any],
    port: str = "out",
    types: list[str] | None = None,
) -> dict[str, Any]:
    """A link; ``latency`` (ms, each way) is a constant or a distribution."""
    dist = dict(latency) if isinstance(latency, Mapping) else {"kind": "constant", "value": latency}
    return {
        "id": eid,
        "source": src,
        "sourcePort": port,
        "target": dst,
        "targetPort": "in",
        "config": {
            "requestTypes": types or ["read", "write"],
            "latencyMs": dist,
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
    Their garbage collection is calibrated (``gc_inputs``, and ``node_gc_inputs`` for Node.js's
    mark-compact pauses), and so is how the service time changes with load
    (``service_time_by_load``); both apply to the service under test (``serviceTimeMs``), not to
    chain's API behind the gateway. The JIT warm-up of the Java profile applies only to replicas
    started during a run, so it does not apply here. Memory comes from the ``<memory>Memory*``
    inputs when calibrated (``memory_inputs``), else ArchSim's defaults."""
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
                # V8's mark-compact pauses (node_gc_inputs); 0 when calibration measured none
                "gcPauseMs": v.get("gcPauseMs", 0) if time_key == "serviceTimeMs" else 0,
                **(
                    {"gcEveryRequests": v["gcEveryRequests"]}
                    if time_key == "serviceTimeMs" and "gcEveryRequests" in v
                    else {}
                ),
                **(
                    {"serviceTimeByLoad": v["serviceTimeByLoad"]}
                    if time_key == "serviceTimeMs" and "serviceTimeByLoad" in v
                    else {}
                ),
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
            **({"serviceTimeByLoad": v["serviceTimeByLoad"]} if "serviceTimeByLoad" in v else {}),
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


# ---------------------------------------------------------------------------------------------
# the machine's threads (W27, engine 0.16.0): every container of a stack, k6 and Windows share
# the laptop's physical cores, and CPU work slows as they fill (engine: architecture.host)
# ---------------------------------------------------------------------------------------------

HOST_QUIET_THREADS = 1.0
"""A contention check counts for the machine's model only when the machine at rest (the stack up
and idle) had fewer busy threads than this: one taken with other work running measures that."""
HOST_FIT_MAX_BUSY = 7.9
"""Probe conditions with the machine's logical processors all busy are left out of the sibling
fit: past them the demand is not seen (the processors take turns)."""


def host_checks(results: Path = RESULTS) -> list[Path]:
    """This machine's contention checks (``run.py contention``), oldest first."""
    return sorted((results / "envcheck").glob("*-contention.json"))


def host_fit(
    check: Mapping[str, Any], service: Mapping[str, Any] | None = None
) -> dict[str, Any] | None:
    """The machine's thread model from a contention check's probe and (``service``, by default
    the same check) a quiet check's service part, or None when that was not quiet or a reading
    is missing:

    * physical cores: the distinct cores of the VM's topology;
    * all-core clock: Windows' actual frequency against the machine's busy logical processors,
      a line through every probe condition, at 8 busy threads over 1;
    * sibling speed: the probe's CPU per unit of work against the busy threads, given that clock
      (the engine's ``Machine.speed``), least squares in log over a grid;
    * background: the machine's busy threads with the stack up and at rest;
    * k6's CPU per request: its container's busy cores over the rate, the median over the
      as-benchmarked steps below 90% of the service's core."""
    service = check if service is None else service
    rest = service.get("serviceAtRest") or {}
    background = rest.get("busyLogicalProcessors")
    if background is None or background >= HOST_QUIET_THREADS:
        return None
    siblings = (check.get("topology") or {}).get("siblings") or {}
    cores = len({tuple(v) for v in siblings.values()}) or None
    threads = len(siblings) or None
    if not cores or not threads:
        return None
    pts = [
        (c["host"]["busyLogicalProcessors"], c["host"]["frequencyMHz"], c["probe"]["cpuUsPerUnit"])
        for r in check.get("probeRounds") or []
        for c in r
        if c.get("probe") and c.get("host") and c["host"].get("frequencyMHz")
    ]
    if len(pts) < 4:
        return None
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    slope_mhz = sum((x - mx) * (y - my) for x, y in zip(xs, ys, strict=True)) / sum(
        (x - mx) ** 2 for x in xs
    )
    f1 = my + slope_mhz * (1 - mx)
    fn = my + slope_mhz * (threads - mx)
    clock = min(1.0, max(0.3, fn / f1))
    fit_pts = [p for p in pts if p[0] < HOST_FIT_MAX_BUSY]
    best: tuple[float, float, float] | None = None
    for s100 in range(20, 101):
        machine = {"cores": cores, "threadsPerCore": threads // cores,
                   "siblingSpeed": s100 / 100, "allCoreClock": clock}  # fmt: skip
        speeds = engine_functions.machine_speeds(machine, [d for d, _, _ in fit_pts])
        logs = [math.log(c * v) for (_, _, c), v in zip(fit_pts, speeds, strict=True)]
        mid = statistics.fmean(logs)
        err = sum((v - mid) ** 2 for v in logs)
        if best is None or err < best[0]:
            best = (err, s100 / 100, math.exp(mid))
    assert best is not None
    k6 = [
        st["k6Cores"] * 1000.0 / st["rateRps"]
        for st in service.get("service") or []
        if st.get("condition") == "as benchmarked"
        and st.get("k6Cores")
        and (st.get("serviceCores") or 1.0) < 0.9
    ]
    if not k6:
        return None
    rms = math.sqrt(best[0] / len(fit_pts))
    return {
        "physicalCores": cores,
        "threadsPerCore": threads // cores,
        "siblingSpeed": best[1],
        "allCoreClock": round(clock, 3),
        "backgroundThreads": round(background, 3),
        "k6CpuMsPerRequest": round(statistics.median(k6), 4),
        "how": (
            f"contention check {check.get('at')}"
            + (f" (service part {service.get('at')})" if service is not check else "")
            + f": {cores} physical cores of {threads} threads "
            f"(the VM's topology); clock {f1:,.0f} MHz with 1 busy thread, {fn:,.0f} with "
            f"{threads} (a line through {len(pts)} probe conditions) = {clock:.3f}; a thread "
            f"sharing its core at {best[1]:.2f} of its speed alone (the probe's CPU per unit of "
            f"work against the busy threads, {len(fit_pts)} conditions, rms {rms:.1%} in log); "
            f"{background:.2f} threads busy at rest; k6 {statistics.median(k6):.4f} ms of CPU "
            f"per request (median of {len(k6)} steps)"
        ),
    }


_HOST_FIT: dict[str, Any] | bool | None = False


HOST_SWITCH = "BENCH_HOST"
"""The benchmark designs get a host machine only with ``BENCH_HOST=on``; by default they have
none, as before engine 0.16.0 (the in-sample what-if did not clear the accuracy gate: see
IMPLEMENTATION_STATUS.md, W27). ``BENCH_HOST_FIT`` names a JSON file with a fit's keys
(``host_fit``) to use instead of the contention checks, for what-ifs; ``BENCH_HOST_CLOCK`` (0 to
1) replaces the fitted all-core clock."""


def machine_fit(results: Path = RESULTS) -> dict[str, Any] | None:
    """With ``BENCH_HOST=on``, the thread model (``host_fit``) from the latest contention check
    with a probe and the latest quiet one with a service part (or ``BENCH_HOST_FIT``'s), read
    once; None otherwise (then the designs have no host machine, as before engine 0.16.0)."""
    global _HOST_FIT
    if _HOST_FIT is False:
        _HOST_FIT = None
        if os.environ.get(HOST_SWITCH, "").lower() != "on":
            return None
        fit: dict[str, Any] | None = None
        if given := os.environ.get(f"{HOST_SWITCH}_FIT"):
            fit = read_json(Path(given))
        else:
            checks = [read_json(p) for p in reversed(host_checks(results))]
            probes = [c for c in checks if any(c.get("probeRounds") or [])]
            for service in checks:
                if probes and (fit := host_fit(probes[0], service)) is not None:
                    break
        if fit is not None and (clock := os.environ.get(f"{HOST_SWITCH}_CLOCK")):
            fit = {
                **fit,
                "allCoreClock": float(clock),
                "how": f"{fit['how']}; clock set to {clock} (what-if)",
            }
        _HOST_FIT = fit
    return _HOST_FIT if isinstance(_HOST_FIT, dict) else None


MACHINE_SCALED = ("serviceTimeMs", "apiServiceTimeMs", "cacheServiceTimeMs")
"""CPU-time inputs measured at a calibration pass, which ran at the machine's thread speed then:
the model's are at full speed (the database's is its CPU share, ``dbCpuShare``)."""


def scale_value(value: Any, k: float) -> Any:
    """A time input (a number or a distribution) times ``k``."""
    if isinstance(value, int | float) and not isinstance(value, bool):
        return round(value * k, 6)
    if isinstance(value, dict):
        out = dict(value)
        for key in ("median", "p95", "mean", "value", "min", "max"):
            if isinstance(out.get(key), int | float):
                out[key] = round(out[key] * k, 6)
        return out
    return value


def fitted_machine(fit: Mapping[str, Any]) -> dict[str, float]:
    """The engine's machine of a contention fit (no other load), as ``machine_speeds`` takes it."""
    return {
        "cores": fit["physicalCores"],
        "threadsPerCore": fit["threadsPerCore"],
        "siblingSpeed": fit["siblingSpeed"],
        "allCoreClock": fit["allCoreClock"],
    }


def pass_speed(entry: Mapping[str, Any]) -> tuple[float, float] | None:
    """(busy threads, thread speed) of the machine during one calibration pass: every
    container's CPU over the pass, k6's (the rate times its CPU per request) and the background
    at rest; None without a quiet contention check or the pass's CPU readings. A pass's CPU per
    request divided by nothing else was measured at this speed: times it, at full speed."""
    fit = machine_fit()
    wall_s = (entry.get("service") or {}).get("wallMs", 0) / 1000.0
    used = entry.get("containerCpuMs") or {}
    rate = float(entry.get("rateRps") or 0.0)
    if fit is None or wall_s <= 0 or rate <= 0 or not used:
        return None
    cores = sum(ms / 1000.0 / wall_s for ms in used.values() if ms is not None)
    busy = cores + rate * fit["k6CpuMsPerRequest"] / 1000.0 + fit["backgroundThreads"]
    return busy, engine_functions.machine_speeds(fitted_machine(fit), [busy])[0]


def machine_inputs(exp: Experiment, passes: Mapping[str, Any], inputs: dict[str, Any]) -> None:
    """The design's host machine (``architecture.host``) from the machine's contention check
    (``machine_fit``) and this pass's own load on it, and the CPU-time inputs at full speed.

    At the top pass the machine had ``D`` busy threads: every container's CPU over the pass,
    k6's (the rate times its CPU per request) and the background at rest. The pass's CPU per
    request was measured at ``speed(D)``, so the model's inputs are that times ``speed(D)``
    (``MACHINE_SCALED``, ``dbCpuShare``). Containers the model gives no CPU (Kafka's broker and
    consumer) join the host's other load: their CPU at rest as busy threads, the rest per
    request with k6's. Without a quiet contention check nothing changes."""
    fit = machine_fit()
    high = passes["high"]
    at_pass = pass_speed(high)
    if fit is None or at_pass is None:
        return
    busy, speed = at_pass
    wall_s = high["service"]["wallMs"] / 1000.0
    rate = float(high["rateRps"])
    idle = (passes.get("idle") or {}).get("containerCpuMsPerSecond") or {}
    cores = {c: ms / 1000.0 / wall_s for c, ms in high["containerCpuMs"].items() if ms is not None}
    others = [c for c in cores if c not in CONTAINER_NODES]
    other_busy = fit["backgroundThreads"] + sum(idle.get(c, 0.0) / 1000.0 for c in others)
    other_ms = sum(max(0.0, cores[c] - idle.get(c, 0.0) / 1000.0) * 1000.0 / rate for c in others)
    client_ms = fit["k6CpuMsPerRequest"] + other_ms
    at = (
        f"x {speed:.4f}, the machine's thread speed at the pass ({busy:.2f} busy threads: the "
        f"containers {sum(cores.values()):.2f}, k6 {rate * fit['k6CpuMsPerRequest'] / 1000:.2f}, "
        f"at rest {fit['backgroundThreads']:.2f})"
    )
    for key in MACHINE_SCALED:
        if key in inputs and "value" in inputs[key]:
            inputs[key] = {
                **inputs[key],
                "value": scale_value(inputs[key]["value"], speed),
                "how": f"{inputs[key]['how']}; {at}",
            }
    if "dbCpuShare" in inputs:
        inputs["dbCpuShare"] = {
            **inputs["dbCpuShare"],
            "value": round(min(1.0, inputs["dbCpuShare"]["value"] * speed), 4),
            "how": f"{inputs['dbCpuShare']['how']}; {at}",
        }
    unmodelled = (
        f" and {', '.join(others)} (no CPU in the model: {sum(idle.get(c, 0.0) for c in others) / 1000:.2f} "
        f"cores at rest, {other_ms:.4f} ms per request)"
        if others
        else ""
    )
    inputs["host"] = {
        "value": {
            "physicalCores": fit["physicalCores"],
            "threadsPerCore": fit["threadsPerCore"],
            "siblingSpeed": fit["siblingSpeed"],
            "allCoreClock": fit["allCoreClock"],
            "otherBusyThreads": round(other_busy, 4),
            "clientCpuMs": round(client_ms, 4),
        },
        "how": f"{fit['how']}; other load: the background, k6{unmodelled}",
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
        edges.append(_edge("e-db", "api", "db", v.get("dbLinkLatencyMs", 0.0)))
    elif exp.stack in CACHE_STACKS:
        nodes += [cache_node(v, exp.ttl_s), database_node(v)]
        edges += [
            _edge("e-cache", "api", "cache", v["cacheLinkLatencyMs"], types=["read"]),
            _edge(
                "e-miss", "cache", "db", v.get("dbLinkLatencyMs", 0.0), port="miss", types=["read"]
            ),
        ]
    else:  # kafka
        nodes += [queue_node(v), database_node(v)]
        edges += [
            _edge("e-kafka", "api", "kafka", 0.0),
            _edge("e-db", "kafka", "db", v.get("dbLinkLatencyMs", 0.0)),
        ]
    return {
        "schemaVersion": "1.7" if "host" in v else "1.0",
        "kind": "archsim.architecture",
        "metadata": {"name": f"benchmark {exp.id}"},
        "architecture": {
            "nodes": nodes,
            "edges": edges,
            "autoSize": False,
            # the machine every container shares (machine_inputs; none without a contention check)
            **({"host": v["host"]} if "host" in v else {}),
        },
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
    r = engine_report(doc)
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


def java_engine() -> bool:
    """Whether the Java engine simulates: unless ``BENCH_ENGINE=python`` asks for the frozen
    Python engine (ADR-0062). Another value is refused rather than taken for either."""
    engine = os.environ.get(ENGINE_VARIABLE) or "java"
    if engine not in ("java", "python"):
        raise RuntimeError(f"{ENGINE_VARIABLE} is java or python, not {engine!r}")
    return engine == "java"


def engine_label() -> str:
    """The engine as what the harness writes names it: its version, the frozen Python engine's or
    the installed Java engine's (1.0.0 on), so the two engines' development checks and range fits
    never share a name or a path. Before it had a version of its own the Java engine wrote
    ``0.16.0-java``, which stays its record."""
    if java_engine():
        return java_engine_version()
    from archsim_engine.version import ENGINE_VERSION

    return ENGINE_VERSION


def java_engine_version() -> str:
    """The installed Java engine's version, as ``archsim --version`` prints it
    (``archsim, engine 1.0.0``)."""
    engine_functions.require_installed(JAVA_CLI)
    ran = subprocess.run(
        [str(JAVA_CLI), "--version"], capture_output=True, text=True, encoding="utf-8"
    )
    if ran.returncode != 0:
        raise RuntimeError(f"archsim --version failed ({ran.returncode}): {ran.stderr.strip()}")
    printed = ran.stdout.strip()
    match = re.fullmatch(r"archsim, engine (\S+)", printed)
    if match is None:
        raise RuntimeError(
            f"archsim --version printed {printed!r}, not 'archsim, engine <version>'"
        )
    return match.group(1)


def dev_label(label: str | None = None) -> str:
    """The folder under results/dev/ that ``resimulate`` writes: the engine's label, or a label
    that starts with it (``0.16.0-port`` for checks kept apart from earlier ones). It never names
    the other engine, so neither writes over the other's checks; the frozen engine never writes
    ``0.16.0-java``, the Java engine's record."""
    engine = engine_label()
    if label is None:
        return engine
    if not label.startswith(f"{engine}-") or (
        not java_engine() and label.startswith(f"{engine}-java")
    ):
        raise ValueError(
            f"a development check's label starts with {engine}- and names this engine: not {label}"
        )
    return label


def report_version() -> str:
    """The version of the reports of the engine that simulates."""
    if java_engine():
        return engine_functions.report_version()
    from archsim_engine.version import REPORT_VERSION

    return REPORT_VERSION


def range_fit_path() -> Path:
    """Where ``range-fit`` writes: the committed fit on the Java engine, the default; on the
    frozen Python engine, a fit labelled with its version (ADR-0062)."""
    return RANGE_FIT if java_engine() else HERE / f"knee-range-{engine_label()}.json"


def java_build() -> str | None:
    """The installed Java engine: a SHA-256 over its jars, by name, or None when it is not
    installed. It may be older than the sources provenance names."""
    jars = sorted((JAVA_CLI.parent.parent / "lib").glob("*.jar"))
    if not jars:
        return None
    digest = hashlib.sha256()
    for jar in jars:
        digest.update(jar.name.encode() + b"\0" + hashlib.sha256(jar.read_bytes()).digest())
    return digest.hexdigest()


def engine_report(doc: dict[str, Any]) -> dict[str, Any]:
    """The report of a run of ``doc`` within ``SIM_WALL_S``: on the Java engine, or on the frozen
    Python engine when ``BENCH_ENGINE=python``."""
    if java_engine():
        return java_report(doc)
    from archsim_engine import run_simulation
    from archsim_engine.engine import SimulationLimits

    return run_simulation(doc, limits=SimulationLimits(max_wall_seconds=SIM_WALL_S))


def java_report(doc: dict[str, Any]) -> dict[str, Any]:
    """The Java engine's report of a run of ``doc``: ``archsim run --out``, from a file each way."""
    import tempfile

    with tempfile.TemporaryDirectory() as folder:
        design, out = Path(folder) / "design.json", Path(folder) / "report.json"
        design.write_text(json.dumps(doc), encoding="utf-8")
        ran = subprocess.run(
            [
                str(JAVA_CLI),
                "run",
                str(design),
                "--out",
                str(out),
                "--max-wall-seconds",
                f"{SIM_WALL_S:g}",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if ran.returncode != 0:
            raise RuntimeError(f"archsim run failed ({ran.returncode}): {ran.stderr.strip()}")
        result: dict[str, Any] = json.loads(out.read_text(encoding="utf-8"))
        return result


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


def predicted_knee(
    exp: Experiment,
    inputs: dict[str, Any],
    near: float | None = None,
    warm: int = WARMUP_S,
    duration: int = DURATION_S,
) -> dict[str, Any]:
    """Highest load meeting the objective, by doubling then bisection (to 1%). With ``near`` (a
    knee already found for nearly the same inputs) the bracket is found by 25% steps from it
    instead of doubling from 100 req/s: fewer simulations, the same answer to within 1%.
    ``warm`` and ``duration``: the simulated seconds of each load (the measured steps' by
    default)."""

    def sim(rps: float) -> dict[str, Any]:
        return simulate(design(exp, inputs, rps, warm=warm, duration=duration))

    lo, hi = 0.0, 100.0
    tried: list[dict[str, Any]] = []
    if near:
        r = sim(near)
        tried.append(r)
        up = meets(r)
        lo, hi = (near, near * 1.25) if up else (near * 0.8, near)
        while lo >= 10 and hi <= 200_000:
            r = sim(round(hi if up else lo))
            tried.append(r)
            if meets(r) != up:
                break
            lo, hi = (hi, hi * 1.25) if up else (lo * 0.8, lo)
        lo = 0.0 if lo < 10 else round(lo)
        hi = round(hi)
    else:
        while True:
            r = sim(hi)
            tried.append(r)
            if not meets(r):
                break
            lo, hi = hi, hi * 2
            if hi > 200_000:
                break
    while hi - lo > max(1.0, 0.01 * hi):
        mid = round((lo + hi) / 2)
        r = sim(mid)
        tried.append(r)
        lo, hi = (mid, hi) if meets(r) else (lo, mid)
    return {
        "kneeRps": lo,
        "tried": [{k: t[k] for k in ("offeredRps", "p95", "successRate")} for t in tried],
    }


# ---------------------------------------------------------------------------------------------
# the predicted range of the knee (W28)
# ---------------------------------------------------------------------------------------------

RANGE_COVERAGE = 0.8
"""The predicted range's nominal coverage: the share of runs whose measured knee it should
hold. 80%: its low end is a load to plan on that about one run in ten falls below. A 50% range
is missed every other run, too often to plan on; a 90% range's ends would rest on the one or two
largest errors of a dozen runs."""
RANGE_STEP = 0.2
"""How far each input is moved up, in log terms (x e^0.2 = +22%), to read how much the knee
moves with it (``knee_sensitivity``): several times its run-to-run variation, so the knee
search's 1% resolution moves the slope by at most about 0.05."""
RANGE_SIM_S = (2, 8)
"""Simulated seconds (warm-up, measured) of each load in the sensitivity's knee searches: a
third of a measured step's, since a search past the knee is slow to simulate (its queue grows
all window long). The unperturbed knee is searched the same way, so the slope compares like
with like."""
RANGE_FIT = HERE / "knee-range.json"
"""The run-to-run variation and the model's error the range is built from (``range_fit``,
written by ``run.py range-fit``)."""
RANGE_INPUTS = (
    "serviceTimeMs",
    "apiServiceTimeMs",
    "clientLinkLatencyMs",
    "apiLinkLatencyMs",
    "readServiceTimeMs",
    "writeServiceTimeMs",
    "commitFlushMs",
    "dbCpuShare",
    "dbLinkLatencyMs",
    "cacheServiceTimeMs",
    "cacheLinkLatencyMs",
    "publishTimeMs",
    "consumerTimeMs",
    "fetchOverheadMs",
)
"""The model inputs calibration measures afresh each run, whose run-to-run variation the range
carries: each is scaled as a whole (a distribution keeps its shape)."""
RANGE_FIXED = (
    "measuredLowLoad",
    "anchor",
    "serviceTimeByLoad",
    "gcPauseMs",
    "gcEveryRequests",
    "consumerConcurrency",
    "serviceMemoryBaselineMb",
    "serviceMemoryPerRpsMb",
    "serviceMemoryPerRequestMb",
    "apiMemoryBaselineMb",
    "apiMemoryPerRpsMb",
    "apiMemoryPerRequestMb",
    "host",
)
"""Inputs the range leaves as calibrated: records, settings of the real stack, relative curves,
memory (it sets no knee), GC pauses, which the kept runs take from one trial and which move no
knee (W21), and the host machine (W27: a property of the machine, not of a calibration, and off
in the benchmark designs). Every input is in one of the two lists (a test checks)."""
RANGE_METHOD = (
    "the knee's run-to-run variation from a fresh calibration (each input's variation between "
    "calibrations times how much the knee moves with it) and the model's own error (the spread "
    "of measured over predicted knees in the in-sample runs), as a lognormal around the "
    "predicted knee"
)


def input_scale(value: Any) -> float | None:
    """The number an input's run-to-run variation is measured on: itself, or the mean of a
    distribution as the engine samples it (for the service's CPU per request, the CPU itself)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    if isinstance(value, Mapping) and "kind" in value:
        return mean_ms(value)
    return None


def scaled(value: Any, factor: float) -> Any:
    """A model input's value times ``factor``: a number, or every time of a distribution (its
    shape stays)."""
    if isinstance(value, bool) or not isinstance(value, int | float | Mapping):
        return value
    if isinstance(value, int | float):
        return value * factor
    out = dict(value)
    for k in ("value", "mean", "median", "p95", "min", "max"):
        if isinstance(out.get(k), int | float) and not isinstance(out[k], bool):
            out[k] = out[k] * factor
    if out.get("kind") == "empirical":
        out["quantiles"] = [[p, v * factor] for p, v in value["quantiles"]]
    return out


def perturbed(inputs: Mapping[str, Any], key: str, factor: float) -> dict[str, Any]:
    """A copy of the inputs with one of them times ``factor`` (dbCpuShare at most 1: it is the
    share of the query time spent on PostgreSQL's CPU)."""
    out = dict(inputs)
    value = scaled(inputs[key]["value"], factor)
    if key == "dbCpuShare":
        value = min(value, 1.0)
    out[key] = {**inputs[key], "value": value}
    return out


def range_keys(inputs: Mapping[str, Any]) -> list[str]:
    """The inputs of a design whose variation the range carries (RANGE_INPUTS), with a scale."""
    return [
        k
        for k in RANGE_INPUTS
        if isinstance(inputs.get(k), Mapping)
        and (s := input_scale(inputs[k].get("value"))) is not None
        and s > 0
    ]


def knee_near(exp: Experiment, inputs: dict[str, Any], near: float) -> float:
    """The knee of slightly different inputs, searched from a knee already found, on the
    sensitivity's shorter simulations (RANGE_SIM_S)."""
    warm, duration = RANGE_SIM_S
    return float(predicted_knee(exp, inputs, near, warm, duration)["kneeRps"])


def sensitivity_jobs(
    exp: Experiment, inputs: Mapping[str, Any], knee: float
) -> list[tuple[Experiment, dict[str, Any], float]]:
    """The knee searches ``knee_sensitivity`` needs: the inputs as they are, then each input of
    ``range_keys`` RANGE_STEP up, all searched from ``knee``."""
    return [(exp, dict(inputs), knee)] + [
        (exp, perturbed(inputs, k, math.exp(RANGE_STEP)), knee) for k in range_keys(inputs)
    ]


def sensitivity_of(inputs: Mapping[str, Any], knees: Sequence[float]) -> dict[str, float]:
    """d ln(knee) / d ln(input) for each input of ``range_keys``, from the knees of
    ``sensitivity_jobs`` (in its order): -1 when the knee falls by the share the input rose."""
    base = knees[0]
    out = {}
    for i, k in enumerate(range_keys(inputs)):
        up = knees[1 + i]
        slope = (math.log(up) - math.log(base)) / RANGE_STEP if up > 0 and base > 0 else 0.0
        out[k] = round(slope, 3)
    return out


def knee_sensitivity(exp: Experiment, inputs: dict[str, Any], knee: float) -> dict[str, float]:
    """How much the knee moves with each input that varies between calibrations: the knee with
    the input RANGE_STEP up against the unperturbed knee, each searched in parallel on the
    shorter simulations (``sensitivity_of``). A linear reading in log terms, taken on the side
    where the input costs more: where the knee switches between two limits it reads the one
    that binds as the input grows."""
    jobs = sensitivity_jobs(exp, inputs, knee)
    if not jobs:
        return {}
    with ProcessPoolExecutor(max_workers=min(len(jobs), os.cpu_count() or 2)) as pool:
        knees = list(pool.map(knee_near, *zip(*jobs, strict=True)))
    return sensitivity_of(inputs, knees)


def input_sigma(fit: Mapping[str, Any], key: str) -> float:
    """An input's run-to-run variation (log standard deviation) in the fit, or the fit's
    fallback (the median over the inputs it measured) for one it never saw vary."""
    rec = (fit.get("inputs") or {}).get(key) or {}
    sigma = rec.get("sigma")
    return float(sigma if sigma is not None else fit["fallbackSigma"])


def calibration_sigma(sensitivity: Mapping[str, float], fit: Mapping[str, Any]) -> float:
    """The spread (log standard deviation) of the knee a fresh calibration would give: each
    input's variation times the knee's sensitivity to it, with one common correlation ``rho``
    between any two inputs (a faster or slower day moves several at once):
    (1 - rho) * sum(a^2) + rho * (sum a)^2, a = sensitivity x variation."""
    a = [s * input_sigma(fit, k) for k, s in sensitivity.items()]
    rho = float(fit.get("correlation") or 0.0)
    var = (1 - rho) * sum(x * x for x in a) + rho * sum(a) ** 2
    return math.sqrt(max(var, 0.0))


def knee_range_of(
    nominal: float,
    sensitivity: Mapping[str, float],
    fit: Mapping[str, Any] | None,
    coverage: float = RANGE_COVERAGE,
) -> dict[str, Any] | None:
    """The predicted range of the knee (None without a fit or a knee): a lognormal around the
    predicted knee whose spread adds the calibration's run-to-run variation for this design
    (``calibration_sigma``) and the model's own error (``fit["model"]["sigma"]``), cut at the
    central ``coverage`` share. It holds the measured knee that share of the time if the
    in-sample runs it was fitted on are like the runs it is used on (the held-out campaign
    tests that)."""
    if fit is None or not nominal:
        return None
    cal = calibration_sigma(sensitivity, fit)
    model = float(fit["model"]["sigma"])
    sigma = math.sqrt(cal**2 + model**2)
    z = statistics.NormalDist().inv_cdf((1 + coverage) / 2)
    return {
        "method": RANGE_METHOD,
        "lowRps": round(nominal * math.exp(-z * sigma)),
        "highRps": round(nominal * math.exp(z * sigma)),
        "nominalRps": nominal,
        "nominalCoverage": coverage,
        "sigma": round(sigma, 4),
        "calibrationSigma": round(cal, 4),
        "modelSigma": round(model, 4),
        "sensitivity": dict(sensitivity),
        "fit": {k: fit.get(k) for k in ("at", "engineVersion", "commit", "runs", "experiments")},
    }


def read_range_fit(path: Path = RANGE_FIT) -> dict[str, Any] | None:
    """The committed fit the range is built from, or None (no range is predicted then)."""
    return read_json(path)


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
    inputs = calibration["inputs"]
    log(f"predicting {exp.id}: searching the knee")
    knee = predicted_knee(exp, inputs)
    k = knee["kneeRps"]
    fit = read_range_fit()
    knee_range = None
    if fit is None:
        log(f"no {RANGE_FIT.name}: predicting no range for the knee (run.py range-fit)")
    elif k:
        log(f"the knee's sensitivity to {len(range_keys(inputs))} inputs, for its range")
        knee_range = knee_range_of(k, knee_sensitivity(exp, inputs, k), fit)
    steps = sorted({max(10, round(k * f)) for f in STEP_FRACTIONS})
    log(f"predicted knee {k:g} req/s; steps {steps}")
    with ProcessPoolExecutor(max_workers=min(len(steps), os.cpu_count() or 2)) as pool:
        results = list(pool.map(simulate, [design(exp, inputs, s) for s in steps]))
    return {
        "experiment": exp.id,
        "at": now(),
        "engineVersion": engine_label(),
        "reportVersion": report_version(),
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
                "engineVersion": engine_label(),
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
    pass with its auxiliary mixes, the staircase's first pass, the idle pass, the whole staircase
    for the memory fit and the staircase's CPU by load), and their medians (``median_inputs``)."""
    passes = calibration["passes"]
    sets = [
        {
            **r,
            "low": passes["low"],
            "idle": passes.get("idle", calibration.get("idle")),
            "staircase": calibration.get("staircase") or [],
            "cpuByLoad": calibration.get("cpuByLoad"),
        }
        for r in calibration.get("replicates") or []
    ]
    derived = []
    for s in sets or [passes]:
        inputs = model_inputs(exp, s)
        anchor(exp, inputs, s["high"])
        derived.append(inputs)
    return median_inputs(derived) if sets else derived[0]


GC_TRIALS = {
    "pg-mixed": "2026-10-31-pg-mixed-w21",
    "pg-hot-row": "2026-10-31-pg-hot-row-w21",
    "cache-aside": "2026-10-31-cache-aside-w21",
    "kafka": "2026-10-31-kafka-w21",
}
"""Calibration-only trials (results/trials/) whose Node.js service reported its garbage
collection (W21). The kept runs' services did not, so a development check takes the GC per
request a trial measured at the top of its staircase (``trial_gc``); a calibration from now on
reads its own."""


def trial_gc(exp_id: str, results: Path = RESULTS) -> dict[str, Any] | None:
    """The GC per request of the experiment's trial: each kind's collections and pause over
    every top pass of its staircase, per request (None without a trial or a reported GC)."""
    name = GC_TRIALS.get(exp_id)
    cal = read_json(results / "trials" / name / "calibration.json") if name else None
    if not cal:
        return None
    tops = [r["high"]["service"] for r in cal.get("replicates") or []]
    tops = [t for t in tops if (t.get("gc") or {}).get("byKind")]
    requests = sum(t["requests"] for t in tops)
    if not requests:
        return None
    kinds: dict[str, dict[str, float]] = {}
    for t in tops:
        for k, v in t["gc"]["byKind"].items():
            acc = kinds.setdefault(k, {"collections": 0.0, "pauseMs": 0.0})
            acc["collections"] += v["collections"]
            acc["pauseMs"] += v["pauseMs"]
    return {
        "trial": name,
        "rateRps": cal["staircaseRps"][-1],
        "requests": requests,
        "perRequest": {
            k: {"collections": v["collections"] / requests, "pauseMs": v["pauseMs"] / requests}
            for k, v in kinds.items()
        },
    }


def with_trial_gc(calibration: Mapping[str, Any], gc: Mapping[str, Any]) -> dict[str, Any]:
    """A copy of a calibration whose top passes' service stats carry the trial's GC per request
    scaled to their own requests (marked with the trial's name), where they report none."""

    def give(entry: Mapping[str, Any]) -> dict[str, Any]:
        svc = entry["service"]
        if svc.get("gc"):
            return dict(entry)
        n = svc["requests"]
        by_kind = {
            k: {"collections": v["collections"] * n, "pauseMs": v["pauseMs"] * n}
            for k, v in gc["perRequest"].items()
        }
        source = f"per request as trial {gc['trial']} measured at {gc['rateRps']:g} req/s"
        return {**entry, "service": {**svc, "gc": {"byKind": by_kind, "source": source}}}

    out = dict(calibration)
    out["replicates"] = [
        {**r, "high": give(r["high"])} for r in calibration.get("replicates") or []
    ]
    out["passes"] = {**calibration["passes"], "high": give(calibration["passes"]["high"])}
    return out


def dev_check(exp_id: str, d: Path, label: str, results: Path = RESULTS) -> dict[str, Any] | None:
    """One kept run checked with today's model: its inputs derived again, its measured ladder
    and the knee simulated, and the comparison with what it measured, recorded as ``label``'s
    (the folder it is written to, ``dev_label``). None for a run whose calibration
    today's code cannot read (run 1 had one pass, not two). A Node.js service that did not
    report its garbage collection gets its trial's (``trial_gc``)."""
    exp = EXPERIMENTS[exp_id]
    cal, meas = read_json(d / "calibration.json"), read_json(d / "measured.json")
    if not (cal and meas) or not {"low", "high"} <= set(cal.get("passes", {})):
        return None
    if exp.runtime == "nodejs" and (gc := trial_gc(exp_id, results)) is not None:
        cal = with_trial_gc(cal, gc)
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
        "engineVersion": label,
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


def resimulate(
    results: Path = RESULTS, only: str | None = None, label: str | None = None
) -> list[Path]:
    """A development check of every kept run (``dev_check``), written under results/dev/, in
    the engine's folder or the one ``label`` names (``dev_label``). One process per run; no
    Docker."""
    engine = dev_label(label)
    runs = [(e, d) for e, d in result_dirs(results) if only in (None, e)]
    log(f"checking {len(runs)} kept runs with engine {engine} (in-sample)")
    with ProcessPoolExecutor(max_workers=min(len(runs) or 1, os.cpu_count() or 2)) as pool:
        checks = list(
            pool.map(
                dev_check,
                [e for e, _ in runs],
                [d for _, d in runs],
                [engine] * len(runs),
                [results] * len(runs),
            )
        )
    written = []
    for (exp_id, d), check in zip(runs, checks, strict=True):
        if check is None:
            log(f"{exp_id} ({d.name}): skipped, no two-pass calibration")
            continue
        path = dev_path(results, engine, exp_id, check)
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
# fitting the predicted range (run.py range-fit): in-sample runs only
# ---------------------------------------------------------------------------------------------

RANGE_MIN_PROTOCOL = 5
"""The range is fitted on calibrations from protocol v5 on: earlier protocols derived the inputs
from passes of another kind (docs/validation.md, protocol history)."""
RANGE_MIN_SIGMA = 0.001
"""An input whose value never moved between calibrations (a fixed default such as Kafka's fetch
overhead in the kept runs, or a share at its cap) was not seen varying: it takes the fallback
variation, not none."""
RANGE_LEVELS = (0.5, 0.8, 0.9)
"""Nominal coverages the fit's check reports, besides RANGE_COVERAGE itself."""
RANGE_FOLDS = {
    "inSample": "fitted on every run, then checked on each",
    "leaveRunOut": "each run checked with a fit that left out its calibration and its error",
    "leaveExperimentOut": (
        "each run checked with a fit that left out every calibration and error of its experiment"
    ),
}


def protocol_number(cal: Mapping[str, Any] | None) -> int:
    """A calibration's protocol version as a number (5 for "v5"; 1 before the field existed)."""
    m = re.fullmatch(r"v(\d+)", str((cal or {}).get("protocol") or ""))
    if m:
        return int(m.group(1))
    m = re.search(r"\(v(\d+)\)", (cal or {}).get("method") or "")
    return int(m.group(1)) if m else 1


def conditions(env: Mapping[str, Any] | None) -> tuple[Any, ...]:
    """What makes two calibrations comparable: the machine, its OS build and power mode."""
    e = env or {}
    return (e.get("cpu"), e.get("os"), e.get("powerMode"))


def disturbed_steps(rows: Sequence[Mapping[str, Any]]) -> list[float]:
    """The load steps of a comparison that missed the objective below a higher step that met it:
    such a run was disturbed (docs/validation.md, protocol step 5)."""
    rows = sorted(rows, key=lambda r: r["offeredRps"])
    met = [meets({k: r["measured"].get(k) for k in ("p95", "successRate")}) for r in rows]
    return [r["offeredRps"] for i, r in enumerate(rows) if not met[i] and any(met[i + 1 :])]


def range_sources(results: Path = RESULTS) -> list[dict[str, Any]]:
    """The calibrations the range is fitted on: for each in-sample experiment, its kept runs from
    protocol v5 on and its calibration-only trials (results/trials/), each under the same
    conditions (``conditions``) as the experiment's latest kept run. Held-out experiments have
    none (they are sealed). A trial folder with a ``DISCARDED`` file is left out: its calibration
    was taken under conditions it cannot stand for (W26's cache-aside trial of 1 Nov ran with the
    CPUs at about half their usual clock)."""
    trials = [
        (d, cal)
        for d in sorted((results / "trials").glob("*"))
        if d.is_dir()
        and not (d / "DISCARDED").exists()
        and (cal := read_json(d / "calibration.json"))
    ]
    out = []
    for exp_id, exp in EXPERIMENTS.items():
        kept = [d for e, d in result_dirs(results) if e == exp_id]
        if exp.held_out is not None or not kept:
            continue
        latest = conditions(read_json(kept[-1] / "environment.json"))
        found = [("kept", d, read_json(d / "calibration.json")) for d in kept]
        found += [("trial", d, cal) for d, cal in trials if cal.get("experiment") == exp_id]
        for kind, d, cal in found:
            if not cal or protocol_number(cal) < RANGE_MIN_PROTOCOL:
                continue
            if conditions(read_json(d / "environment.json")) != latest:
                continue
            out.append(
                {
                    "experiment": exp_id,
                    "kind": kind,
                    "dir": d,
                    "source": d.relative_to(results).as_posix(),
                    "calibratedAt": cal.get("at"),
                    "calibration": cal,
                }
            )
    return out


def today_inputs(exp_id: str, d: Path, results: Path = RESULTS) -> dict[str, Any]:
    """A calibration's inputs derived again with today's run.py, as ``dev_check`` does (a
    Node.js service that did not report its garbage collection gets its trial's)."""
    exp = EXPERIMENTS[exp_id]
    cal = read_json(d / "calibration.json")
    if exp.runtime == "nodejs" and (gc := trial_gc(exp_id, results)) is not None:
        cal = with_trial_gc(cal, gc)
    return rederive_inputs(exp, cal)


def variation_fit(rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """Each input's run-to-run variation over calibrations: the log standard deviation of its
    scale (``input_scale``) around its experiment's mean, pooled over the experiments (an
    experiment's n calibrations count n - 1 times); the median of those for inputs never
    measured or never seen moving (``fallbackSigma``); and one common correlation between
    inputs, the mean product of
    two inputs' standardised deviations in the same calibration, at least 0. ``rows``: one per
    calibration, ``{"experiment", "scales": {input: scale}}``."""
    by_exp: dict[str, list[Mapping[str, float]]] = {}
    for r in rows:
        by_exp.setdefault(r["experiment"], []).append(r["scales"])
    dev: dict[str, list[tuple[str, int, float]]] = {}  # input -> (experiment, row, deviation)
    inputs: dict[str, Any] = {}
    for key in RANGE_INPUTS:
        ss, dof, exps, n = 0.0, 0, 0, 0
        for e, scales in by_exp.items():
            logs = [(i, math.log(s[key])) for i, s in enumerate(scales) if s.get(key, 0) > 0]
            if len(logs) < 2:
                continue
            mean = statistics.fmean(x for _, x in logs)
            dev[key] = dev.get(key, []) + [(e, i, x - mean) for i, x in logs]
            ss += sum((x - mean) ** 2 for _, x in logs)
            dof += len(logs) - 1
            exps += 1
            n += len(logs)
        if dof and math.sqrt(ss / dof) >= RANGE_MIN_SIGMA:
            inputs[key] = {
                "sigma": round(math.sqrt(ss / dof), 4),
                "calibrations": n,
                "experiments": exps,
            }
    sigmas = [v["sigma"] for v in inputs.values()]
    fallback = round(statistics.median(sigmas), 4) if sigmas else 0.0
    # standardised deviations, each experiment's scaled up by n / (n - 1) for its own mean
    z: dict[tuple[str, int], dict[str, float]] = {}
    for key, devs in dev.items():
        if key not in inputs:
            continue
        sigma = inputs[key]["sigma"]
        for e, i, d in devs:
            n = sum(1 for e2, _, _ in devs if e2 == e)
            if sigma > 0:
                z.setdefault((e, i), {})[key] = d / sigma * math.sqrt(n / (n - 1))
    products = [
        a * b
        for vals in (list(zs.values()) for zs in z.values())
        for j, a in enumerate(vals)
        for b in vals[j + 1 :]
    ]
    rho = max(0.0, statistics.fmean(products)) if products else 0.0
    return {
        "inputs": inputs,
        "fallbackSigma": fallback,
        "correlation": round(min(rho, 1.0), 4),
    }


def model_sigma(errors: Sequence[float], cal_sigmas: Sequence[float]) -> float:
    """The model's own error, by maximum likelihood: each run's ln(measured / predicted knee) a
    normal with mean 0 and variance (its calibration spread)^2 + s^2. 0 when the calibration
    spread alone explains the errors. A grid of 0.0005 up to 2 (a factor of e^2)."""
    if not errors:
        return 0.0

    def nll(s: float) -> float:
        return sum(
            math.log(max(c * c + s * s, 1e-12)) + e * e / max(c * c + s * s, 1e-12)
            for e, c in zip(errors, cal_sigmas, strict=True)
        )

    return min((i * 0.0005 for i in range(4001)), key=nll)


def fold_fit(
    scales: Sequence[Mapping[str, Any]],
    runs: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """A range fit from some calibrations (``variation_fit``) and some runs' errors
    (``model_sigma``, each run's calibration spread from that variation)."""
    fit = variation_fit(scales)
    cal = [calibration_sigma(r["sensitivity"], fit) for r in runs]
    fit["model"] = {
        "sigma": round(model_sigma([r["error"] for r in runs], cal), 4),
        "runs": len(runs),
        "experiments": len({r["experiment"] for r in runs}),
    }
    return fit


def range_check(
    scales: Sequence[Mapping[str, Any]], runs: Sequence[Mapping[str, Any]]
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Whether the range holds each run's measured knee, at each nominal coverage of
    RANGE_LEVELS, fitted three ways (RANGE_FOLDS). Never with a run's own error in its fit
    outside "inSample". Returns the counts per fold and level, and each run's ranges at
    RANGE_COVERAGE."""
    levels = sorted({*RANGE_LEVELS, RANGE_COVERAGE})
    counts: dict[str, dict[str, list[int]]] = {
        f: {f"{c:g}": [0, 0] for c in levels} for f in RANGE_FOLDS
    }
    detail: list[dict[str, Any]] = [{} for _ in runs]
    full = fold_fit(scales, runs)
    for fold in RANGE_FOLDS:
        for i, run in enumerate(runs):
            if fold == "inSample":
                fit = full
            elif fold == "leaveRunOut":
                fit = fold_fit(
                    [s for s in scales if s["source"] != run["source"]],
                    [r for j, r in enumerate(runs) if j != i],
                )
            else:
                fit = fold_fit(
                    [s for s in scales if s["experiment"] != run["experiment"]],
                    [r for r in runs if r["experiment"] != run["experiment"]],
                )
            for c in levels:
                rng = knee_range_of(run["knee"], run["sensitivity"], fit, c)
                holds = in_range(rng, run["measuredKnee"])
                counts[fold][f"{c:g}"][1] += 1
                counts[fold][f"{c:g}"][0] += int(bool(holds))
                if c == RANGE_COVERAGE and rng is not None:
                    detail[i][fold] = {
                        "lowRps": rng["lowRps"],
                        "highRps": rng["highRps"],
                        "calibrationSigma": rng["calibrationSigma"],
                        "modelSigma": rng["modelSigma"],
                        "holds": holds,
                    }
    return counts, detail


RANGE_FIT_HOW = (
    "Fitted on the in-sample experiments only. Variation: each input's log standard deviation "
    "between calibrations of the same experiment (kept runs from protocol v5 on and "
    "calibration-only trials, under the latest kept run's conditions), pooled over experiments, "
    "and one common correlation between inputs. Model error: the log standard deviation of "
    "measured over predicted knee in the counted kept runs, by maximum likelihood after each "
    "run's calibration spread (its sensitivity to each input times that input's variation). The "
    "knees and inputs are today's run.py and engine on each run's calibration (the development "
    "checks), so the model error is in-sample: fitted on runs the model was tuned on."
)


def range_cache_path(engine: str) -> Path:
    """Where ``range_fit`` keeps the work it has finished (outside the repository), so that a
    fit cut short starts again where it stopped."""
    import tempfile

    return Path(tempfile.gettempdir()) / f"archsim-range-fit-{engine}.json"


def _cached_call(fn: Any, *args: Any) -> Any:
    return fn(*args)


def run_cached(
    tasks: Sequence[tuple[str, Any, tuple[Any, ...]]], cache: dict[str, Any], path: Path
) -> dict[str, Any]:
    """Run ``(key, function, args)`` tasks on every core, skipping keys already in ``cache``;
    each result is written to ``path`` as it arrives, with a progress line every tenth."""
    from concurrent.futures import as_completed

    todo = [t for t in tasks if t[0] not in cache]
    if not todo:
        return cache
    with ProcessPoolExecutor(max_workers=min(len(todo), os.cpu_count() or 2)) as pool:
        futures = {pool.submit(_cached_call, fn, *args): key for key, fn, args in todo}
        for n, f in enumerate(as_completed(futures), 1):
            cache[futures[f]] = f.result()
            write_json(path, cache)
            if n % 10 == 0 or n == len(todo):
                log(f"range fit: {n} of {len(todo)} done")
    return cache


def range_fit(
    results: Path = RESULTS, out: Path = RANGE_FIT, reuse: str | None = None
) -> dict[str, Any]:
    """Fit the predicted range (``knee_range_of``) and check its coverage, and write ``out``.
    Inputs and knees come from the development checks (results/dev/<engine>/, ``run.py
    resimulate``) of this engine, or of ``reuse``, a label whose run.py derived the inputs and
    whose engine simulated exactly as today's (only the label changed since); missing ones are
    derived here. Each counted kept run's sensitivity to its inputs is simulated (one knee
    search per input, and one unperturbed). Finished work is kept in ``range_cache_path``."""
    engine = engine_label()
    label = reuse or engine
    cache_path = range_cache_path(engine)
    cache: dict[str, Any] = read_json(cache_path) or {}
    sources = range_sources(results)
    checks: list[dict[str, Any] | None] = []
    for s in sources:
        path = dev_path(results, label, s["experiment"], {**s, "results": s["source"]})
        c = read_json(path) if s["kind"] == "kept" else None
        checks.append(c if c and c.get("engineVersion") == label else None)
    todo = [i for i, c in enumerate(checks) if c is None]
    log(
        f"range fit, engine {engine}: {len(sources)} calibrations, {len(sources) - len(todo)} "
        f"with engine {label}'s development checks, {len(todo)} to derive (or in the cache)"
    )

    def derive_tasks(idx: Sequence[int]) -> list[tuple[str, Any, tuple[Any, ...]]]:
        return [
            (
                f"inputs|{sources[i]['source']}",
                today_inputs,
                (sources[i]["experiment"], sources[i]["dir"], results),
            )
            for i in idx
        ]

    # the counted kept runs need their inputs and knee before their sensitivity
    counted = []
    for i, s in enumerate(sources):
        cmp_ = read_json(s["dir"] / "comparison.json")
        if s["kind"] == "kept" and cmp_ and not disturbed_steps(cmp_["rows"]):
            counted.append((i, cmp_))
    run_cached(derive_tasks([i for i, _ in counted if checks[i] is None]), cache, cache_path)
    inputs: list[dict[str, Any] | None] = [
        c["inputs"] if c else cache.get(f"inputs|{s['source']}")
        for s, c in zip(sources, checks, strict=True)
    ]
    knee_tasks = [
        (
            f"knee|{sources[i]['source']}",
            predicted_knee,
            (EXPERIMENTS[sources[i]["experiment"]], inputs[i]),
        )
        for i, _ in counted
        if checks[i] is None
    ]
    run_cached(knee_tasks, cache, cache_path)
    runs: list[dict[str, Any]] = []
    for i, cmp_ in counted:
        s, c = sources[i], checks[i]
        runs.append(
            {
                "experiment": s["experiment"],
                "source": s["source"],
                "calibratedAt": s["calibratedAt"],
                "measuredKnee": cmp_["measuredKnee"],
                "inputs": inputs[i],
                "knee": (c["knee"] if c else cache[f"knee|{s['source']}"])["kneeRps"],
                "before": cmp_.get("kneeRange"),
            }
        )
    # the other calibrations' inputs and every counted run's sensitivity, together
    jobs = {
        r["source"]: sensitivity_jobs(EXPERIMENTS[r["experiment"]], r["inputs"], r["knee"])
        for r in runs
    }
    tasks = derive_tasks([i for i, inp in enumerate(inputs) if inp is None])
    tasks += [
        (f"sens|{src}|{j}", knee_near, job) for src, js in jobs.items() for j, job in enumerate(js)
    ]
    log(
        f"range fit: {len(runs)} counted runs; {sum(len(js) for js in jobs.values())} knee "
        f"searches for their sensitivity ({sum(1 for t in tasks if t[0] in cache)} of "
        f"{len(tasks)} tasks already done)"
    )
    run_cached(tasks, cache, cache_path)
    every: list[dict[str, Any]] = [
        inp or cache[f"inputs|{s['source']}"] for s, inp in zip(sources, inputs, strict=True)
    ]
    scales = [
        {
            "experiment": s["experiment"],
            "source": s["source"],
            "calibratedAt": s["calibratedAt"],
            "scales": {
                k: round(v, 6)
                for k in range_keys(inp)
                if (v := input_scale(inp[k]["value"])) is not None
            },
        }
        for s, inp in zip(sources, every, strict=True)
    ]
    for r in runs:
        knees = [cache[f"sens|{r['source']}|{j}"] for j in range(len(jobs[r["source"]]))]
        r["sensitivity"] = sensitivity_of(r["inputs"], knees)
        mk = r["measuredKnee"].get("kneeRps")
        r["error"] = math.log(mk / r["knee"]) if mk and r["knee"] else None
    fitted = [r for r in runs if r["error"] is not None]
    fit = fold_fit(scales, fitted)
    counts, detail = range_check(scales, fitted)
    before = [r["before"] for r in fitted if r["before"]]
    doc = {
        "at": now(),
        "engineVersion": engine,
        "inputsAndKneesFrom": label,
        "commit": git("rev-parse", "HEAD"),
        "provenance": provenance(),
        "how": RANGE_FIT_HOW,
        "nominalCoverage": RANGE_COVERAGE,
        "step": RANGE_STEP,
        "sensitivitySimulatedS": list(RANGE_SIM_S),
        "runs": len(fitted),
        "experiments": len({r["experiment"] for r in fitted}),
        **fit,
        "check": {
            "folds": RANGE_FOLDS,
            "counts": counts,
            # the range the runs were predicted with (protocol v5: the replicate sets' knees)
            "before": {
                "inRange": sum(1 for b in before if b.get("containsMeasured")),
                "withRange": len(before),
                "nominalCoverage": before[-1].get("nominalCoverage") if before else None,
            },
        },
        "calibrations": scales,
        "checked": [
            {
                k: r[k]
                for k in (
                    "experiment",
                    "source",
                    "calibratedAt",
                    "measuredKnee",
                    "knee",
                    "sensitivity",
                )
            }
            | {
                "error": round(r["error"], 4),
                "calibrationSigma": round(calibration_sigma(r["sensitivity"], fit), 4),
                "ranges": d,
            }
            for r, d in zip(fitted, detail, strict=True)
        ],
    }
    write_json(out, doc)
    return doc


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
# contention check (W27): how much slower CPU work runs when its hyperthread sibling, the rest of
# the machine or the process itself is busy
# ---------------------------------------------------------------------------------------------

PROBE_IMAGE = "node:24-alpine"
"""The probe's runtime: the benchmark service's base image, so the probe's work is V8's."""
PROBE_S = 12
PROBE_LEAD_MS = 5000
"""Every container of a condition starts its window at the same moment, this long after launch."""
PROBE_PREFIX = "archsim-bench-probe-"
PROBE_JS = r"""
const S = Number(process.env.PROBE_S), DUTY = Number(process.env.DUTY), AT = Number(process.env.START_AT);
const PERIOD = 10;
const item = { id: 12345, name: "item-12345", price: 19.99, tags: ["a", "b", "c"], at: "2026-11-01T00:00:00Z", nested: { x: 1, y: [1, 2, 3] } };
let sink = 0, units = 0;
const unit = () => { for (let i = 0; i < 20; i++) { const s = JSON.stringify(item); sink += JSON.parse(s).id + s.length; } };
for (let i = 0; i < 20000; i++) unit();
setTimeout(() => {
  const t0 = performance.now(), c0 = process.cpuUsage(), end = t0 + S * 1000;
  units = 0;
  const burst = () => {
    const start = performance.now();
    const stop = DUTY >= 1 ? end : Math.min(end, start + PERIOD * DUTY);
    do { for (let k = 0; k < 10; k++) unit(); units += 10; } while (performance.now() < stop);
    const now = performance.now();
    if (now >= end) {
      const c = process.cpuUsage(c0);
      console.log(JSON.stringify({ units, cpuMs: (c.user + c.system) / 1000, wallMs: now - t0, lateMs: Math.round(Date.now() - (now - t0) - AT), sink: sink % 7 }));
      return;
    }
    setTimeout(burst, Math.max(0, start + PERIOD - now));
  };
  burst();
}, Math.max(0, AT - Date.now()));
"""
"""A fixed unit of V8 work (20 JSON round trips of a small record, like a request's), done for
PROBE_S seconds from START_AT, DUTY of every 10 ms (1 = all the time); it prints the units done
and its CPU time, so CPU per unit is the probe's cost and units per wall second its speed."""

# Probe conditions: (name, the probe's duty on CPU 2, {cpu: burner duty}). CPU 3 is the probe's
# hyperthread sibling on the laptop; 0-1, 4-5 and 6-7 are the other physical cores.
PROBE_CONDITIONS: tuple[tuple[str, float, dict[int, float]], ...] = (
    ("alone", 1.0, {}),
    ("sibling busy", 1.0, {3: 1.0}),
    ("sibling half busy", 1.0, {3: 0.5}),
    ("1 other core busy", 1.0, {0: 1.0, 1: 1.0}),
    ("2 other cores busy", 1.0, {0: 1.0, 1: 1.0, 4: 1.0, 5: 1.0}),
    ("3 other cores busy", 1.0, {0: 1.0, 1: 1.0, 4: 1.0, 5: 1.0, 6: 1.0, 7: 1.0}),
    ("3 other cores, one thread each", 1.0, {0: 1.0, 4: 1.0, 6: 1.0}),
    ("every CPU busy", 1.0, {c: 1.0 for c in (0, 1, 3, 4, 5, 6, 7)}),
    ("own duty 90%", 0.9, {}),
    ("own duty 50%", 0.5, {}),
    ("own duty 25%", 0.25, {}),
    ("own duty 50%, 3 other cores busy", 0.5, {0: 1.0, 1: 1.0, 4: 1.0, 5: 1.0, 6: 1.0, 7: 1.0}),
)
PROBE_CPU = 2
PROBE_ROUNDS = int(os.environ.get("BENCH_PROBE_ROUNDS", "2"))
"""Each round runs every condition; the second in reverse order, so drift shows."""

HOST_COUNTERS_PS = r"""
$c = @('\Processor Information(_Total)\Actual Frequency', '\Processor Information(_Total)\% Performance Limit', '\Processor Information(_Total)\Performance Limit Flags', '\Hyper-V Hypervisor Logical Processor(*)\% Total Run Time')
while ($true) {
  $s = Get-Counter -Counter $c -SampleInterval 1 -MaxSamples 1 -ErrorAction SilentlyContinue
  $o = @{ t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
  foreach ($x in $s.CounterSamples) { $p = $x.Path.Split('\'); $o[$p[-2] + '|' + $p[-1]] = [math]::Round($x.CookedValue, 2) }
  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))
  [Console]::Out.Flush()
}
"""


class HostCounters:
    """Windows' own view of the machine while a check runs, one sample a second: each logical
    processor's busy share as the hypervisor sees it (Docker's VM and Windows itself), the cores'
    actual frequency and whether the firmware limited it (power or thermal). Off Windows, or
    where the counters are missing, there are no samples."""

    def __init__(self) -> None:
        self.samples: list[dict[str, Any]] = []
        self.proc: subprocess.Popen[str] | None = None

    def __enter__(self) -> HostCounters:
        if platform.system() == "Windows":
            self.proc = subprocess.Popen(
                ["powershell", "-NoProfile", "-Command", HOST_COUNTERS_PS],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
            )
            threading.Thread(target=self._read, daemon=True).start()
        return self

    def _read(self) -> None:
        assert self.proc is not None and self.proc.stdout is not None
        for line in self.proc.stdout:
            try:
                self.samples.append(json.loads(line))
            except ValueError:
                continue

    def __exit__(self, *exc: object) -> None:
        if self.proc is not None:
            self.proc.kill()

    def window(self, t0_ms: float, t1_ms: float) -> dict[str, Any] | None:
        """Means over the samples whose second lies inside [t0, t1] (host clock, ms)."""
        inside = [s for s in self.samples if t0_ms + 1000 <= s["t"] <= t1_ms]
        if not inside:
            return None

        def mean_of(key: str) -> float | None:
            v = [s[key] for s in inside if isinstance(s.get(key), int | float)]
            return round(statistics.fmean(v), 2) if v else None

        lps = sorted(
            {k for s in inside for k in s if k.startswith("hv lp ") or "(hv lp " in k},
            key=lambda k: int(re.sub(r"\D", "", k.split("|")[0]) or 0),
        )
        busy = [mean_of(k) for k in lps if "_total" not in k]
        flags = {
            int(s.get("processor information(_total)|performance limit flags") or 0) for s in inside
        }
        return {
            "samples": len(inside),
            "frequencyMHz": mean_of("processor information(_total)|actual frequency"),
            "performanceLimitPct": mean_of("processor information(_total)|% performance limit"),
            "performanceLimitFlags": sorted(flags),
            "logicalProcessorBusyPct": busy,
            "busyLogicalProcessors": round(sum(b or 0 for b in busy) / 100.0, 2),
        }


def host_ms() -> float:
    return time.time() * 1000.0


def vm_clock_offset_ms() -> float:
    """Docker's VM clock minus this host's (ms), read through a short-lived container."""
    t0 = host_ms()
    out = _run("docker", "run", "--rm", "--entrypoint", "node", PROBE_IMAGE, "-e",
               "console.log(Date.now())")  # fmt: skip
    t1 = host_ms()
    try:
        vm = float(out.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return 0.0
    # the container's start dominates: its clock was read near the end of the call
    return vm - (t0 + 0.75 * (t1 - t0))


def probe_launch(
    name: str, cpu: int, duty: float, seconds: float, start_at: float, nice: bool = False
) -> None:
    """Start one probe container pinned to ``cpu`` (``nice``: at the lowest priority, so it only
    takes what the CPU's other work leaves idle)."""
    cmd = ["node", "-e", PROBE_JS]
    if nice:
        cmd = ["nice", "-n", "19", *cmd]
    subprocess.run(
        ["docker", "run", "-d", "--name", f"{PROBE_PREFIX}{name}", "--cpuset-cpus", str(cpu),
         "-e", f"PROBE_S={seconds:g}", "-e", f"DUTY={duty:g}", "-e", f"START_AT={start_at:.0f}",
         "--entrypoint", cmd[0], PROBE_IMAGE, *cmd[1:]],
        check=True, capture_output=True, text=True,
    )  # fmt: skip


def probe_collect(name: str) -> dict[str, Any] | None:
    """Wait for a probe to finish and read its line; the container is removed either way."""
    full = f"{PROBE_PREFIX}{name}"
    try:
        subprocess.run(["docker", "wait", full], check=False, capture_output=True, timeout=120)
        out = subprocess.run(["docker", "logs", full], capture_output=True, text=True).stdout
        line = out.strip().splitlines()[-1] if out.strip() else ""
        return json.loads(line) if line.startswith("{") else None
    except (subprocess.TimeoutExpired, ValueError):
        return None
    finally:
        subprocess.run(["docker", "rm", "-f", full], capture_output=True, check=False)


def probes_clear() -> None:
    """Remove every probe container a check left behind."""
    ids = _run("docker", "ps", "-aq", "--filter", f"name={PROBE_PREFIX}").split()
    if ids:
        subprocess.run(["docker", "rm", "-f", *ids], capture_output=True, check=False)


def probe_rates(r: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if not r or not r.get("units") or not r.get("cpuMs") or not r.get("wallMs"):
        return None
    return {
        "units": r["units"],
        "lateMs": r.get("lateMs"),
        "cpuUsPerUnit": round(r["cpuMs"] * 1000.0 / r["units"], 4),
        "unitsPerWallMs": round(r["units"] / r["wallMs"], 3),
        "busy": round(r["cpuMs"] / r["wallMs"], 4),
    }


def probe_condition(
    name: str, duty: float, burners: Mapping[int, float], offset_ms: float, host: HostCounters
) -> dict[str, Any]:
    """One probe condition: the probe on PROBE_CPU and a burner (the same work) on each CPU of
    ``burners`` at its duty, all over the same PROBE_S window."""
    probes_clear()
    start_vm = host_ms() + offset_ms + PROBE_LEAD_MS
    tag = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    launches = [(f"{tag}-probe", PROBE_CPU, duty)] + [
        (f"{tag}-burn{cpu}", cpu, d) for cpu, d in burners.items()
    ]
    with ThreadPoolExecutor(max_workers=len(launches)) as pool:
        list(pool.map(lambda a: probe_launch(a[0], a[1], a[2], PROBE_S, start_vm), launches))
    probe = probe_rates(probe_collect(f"{tag}-probe"))
    burn = {str(cpu): probe_rates(probe_collect(f"{tag}-burn{cpu}")) for cpu in burners}
    t0 = start_vm - offset_ms
    out = {
        "condition": name,
        "probeCpu": PROBE_CPU,
        "probeDuty": duty,
        "burners": {str(c): d for c, d in burners.items()},
        "probe": probe,
        "burnerResults": burn,
        "host": (seen := host.window(t0, t0 + PROBE_S * 1000.0)),
    }
    log(
        f"contention probe {name:34} cpu/unit {probe and probe['cpuUsPerUnit']} us, "
        f"MHz {seen and seen['frequencyMHz']}, busy LPs {seen and seen['busyLogicalProcessors']}"
    )
    return out


CONTENTION_LADDER = (1250, 2500, 3700, 4400, 5000)
"""pg-mixed rates run as benchmarked, a quarter of the event loop's core to past its capacity."""
CONTENTION_QUICK_LADDER = (1250, 2500, 3700)
"""The ladder of ``contention --service-only``: k6's CPU and the service's, short and cool."""
CONTENTION_RATES = (2500,)
"""pg-mixed rates for the conditions with background work: half the event loop's core."""
CONTENTION_S = 20
CONTENTION_REST_S = 15
# Service conditions: (name, {cpu: nice}): a burner on each CPU, at nice 19 where it shares the
# CPU with the stack (it takes only what k6 or PostgreSQL leave idle), else at normal priority.
SERVICE_CONDITIONS: tuple[tuple[str, dict[int, bool]], ...] = (
    ("as benchmarked", {}),
    ("service's sibling busy", {3: False}),
    ("k6's idle CPU taken", {0: True, 1: True, 6: True, 7: True}),
    ("PostgreSQL's idle CPU taken", {4: True, 5: True}),
    ("every idle CPU taken", {0: True, 1: True, 3: False, 4: True, 5: True, 6: True, 7: True}),
    ("as benchmarked, again", {}),
)


class K6Cpu:
    """k6's own CPU over the middle of a measured run: its container (``compose run`` names it
    archsim-bench-k6-run-...) read at 2 s and again 2 s before the end."""

    def __init__(self, seconds: float) -> None:
        self.seconds = seconds
        self.cores: float | None = None
        self.thread = threading.Thread(target=self._watch, daemon=True)

    def __enter__(self) -> K6Cpu:
        self.thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.thread.join(timeout=5)

    @staticmethod
    def _usec(cid: str) -> int | None:
        out = subprocess.run(["docker", "exec", cid, "cat", "/sys/fs/cgroup/cpu.stat"],
                             text=True, capture_output=True, check=False).stdout  # fmt: skip
        for line in out.splitlines():
            if line.startswith("usage_usec"):
                return int(line.split()[1])
        return None

    def _watch(self) -> None:
        t_end = time.time() + 10
        cid = ""
        while not cid and time.time() < t_end:
            ids = _run("docker", "ps", "-q", "--filter", "name=archsim-bench-k6-run").split()
            cid = ids[0] if ids else ""
            if not cid:
                time.sleep(0.2)
        if not cid:
            return
        time.sleep(2.0)
        t0, u0 = time.time(), self._usec(cid)
        time.sleep(max(1.0, self.seconds - 6.0))
        t1, u1 = time.time(), self._usec(cid)
        if u0 is not None and u1 is not None and t1 > t0:
            self.cores = round((u1 - u0) / 1e6 / (t1 - t0), 4)


def contention_step(
    exp: Experiment, rate: float, name: str, burners: Mapping[int, bool], host: HostCounters
) -> dict[str, Any]:
    """pg-mixed at ``rate``: 5 s warm-up, statistics reset, CONTENTION_S recorded, with burners
    running throughout; the service's and PostgreSQL's CPU per request and the host's view."""
    probes_clear()
    tag = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    for cpu, nice in burners.items():
        probe_launch(f"svc-{tag}-{cpu}", cpu, 1.0, CONTENTION_S + 30, 0, nice=nice)
    target = target_for(exp)
    try:
        k6(target, exp.mix, rate, 0, 5, "contention-warm", exp.keys, exp.skew)
        http_json(f"{API}/reset", "POST")
        cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
        t0 = host_ms()
        with K6Cpu(CONTENTION_S) as k6cpu:
            summary = k6(target, exp.mix, rate, 0, CONTENTION_S, "contention", exp.keys, exp.skew)
        t1 = host_ms()
        cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
        svc = http_json(f"{API}/stats")
    finally:
        probes_clear()
    m = k6_measured(summary, CONTENTION_S)
    requests = svc.get("requests") or 0
    pg_ms = cpu_ms(cpu0.get("postgres"), cpu1.get("postgres"))
    api_ms = cpu_ms(cpu0.get("api"), cpu1.get("api"))
    seconds = (t1 - t0) / 1000.0
    out = {
        "condition": name,
        "rateRps": rate,
        "burners": {str(c): ("nice 19" if n else "normal") for c, n in burners.items()},
        "k6": {k: m.get(k) for k in ("p50", "p95", "p99", "successRate", "throughputRps")},
        "serviceCpuMsPerRequest": svc.get("cpuMsPerRequest"),
        "serviceCores": round(api_ms / 1000.0 / seconds, 4) if api_ms else None,
        "postgresCpuMsPerRequest": round(pg_ms / requests, 4) if pg_ms and requests else None,
        "postgresCores": round(pg_ms / 1000.0 / seconds, 4) if pg_ms else None,
        "k6Cores": k6cpu.cores,
        "host": (seen := host.window(t0, t1)),
    }
    log(
        f"contention service {rate:>5} req/s {name:30} service {out['serviceCpuMsPerRequest']} ms, "
        f"postgres {out['postgresCpuMsPerRequest']} ms, p95 {m.get('p95')}, "
        f"k6 {k6cpu.cores} cores, MHz {seen and seen['frequencyMHz']}, "
        f"busy LPs {seen and seen['busyLogicalProcessors']}"
    )
    return out


def contention(results: Path = RESULTS, service: bool = True, probe: bool = True) -> Path:
    """Characterise how this machine turns CPUs into capacity: (``service``) pg-mixed's real
    service at rest, as benchmarked from a quarter of its core to past it (CONTENTION_LADDER)
    and at half of it with background work (SERVICE_CONDITIONS), with k6's own CPU; then a fixed
    unit of V8 work on one CPU, alone and with its hyperthread sibling, other cores or every CPU
    busy, and at a part of its own time (``probe``, PROBE_CONDITIONS). Without the probe, only
    the machine at rest and CONTENTION_QUICK_LADDER, about 4 minutes. Windows' counters give the
    cores' frequency and each logical processor's busy share meanwhile. Writes
    results/envcheck/<at>-contention.json. About 15 minutes of Docker."""
    siblings = cpu_siblings() or {}
    started = now()
    offset = vm_clock_offset_ms()
    probe_rounds: list[list[dict[str, Any]]] = []
    service_steps: list[dict[str, Any]] = []
    rest: dict[str, Any] | None = None
    with HostCounters() as host:
        time.sleep(3)
        try:
            if service:
                exp = EXPERIMENTS["pg-mixed"]
                stack_up("postgres")
                try:
                    settle(exp)
                    t0 = host_ms()
                    time.sleep(CONTENTION_REST_S)
                    rest = host.window(t0, host_ms())
                    log(
                        f"contention: machine at rest {rest and rest['busyLogicalProcessors']} busy LPs"
                    )
                    for rate in CONTENTION_LADDER if probe else CONTENTION_QUICK_LADDER:
                        service_steps.append(contention_step(exp, rate, "as benchmarked", {}, host))
                    for rate in CONTENTION_RATES if probe else ():
                        for name, burners in SERVICE_CONDITIONS[1:-1]:
                            service_steps.append(contention_step(exp, rate, name, burners, host))
                finally:
                    stack_down("postgres")
            # the probe last: its every-CPU conditions heat the machine (a laptop throttles after
            # about 10 minutes of it)
            for r in range(PROBE_ROUNDS if probe else 0):
                order = PROBE_CONDITIONS if r % 2 == 0 else tuple(reversed(PROBE_CONDITIONS))
                probe_rounds.append([probe_condition(n, d, b, offset, host) for n, d, b in order])
        finally:
            probes_clear()
    out = {
        "at": started,
        "environment": environment(),
        "topology": {"siblings": {str(c): s for c, s in sorted(siblings.items())}},
        "how": (
            f"probe: {PROBE_S} s of a fixed unit of V8 work (20 JSON round trips of a small "
            f"record) on CPU {PROBE_CPU}, every container of a condition over the same window; "
            "burners run the same work. Service: pg-mixed, 5 s warm-up then "
            f"{CONTENTION_S} s recorded, burners at nice 19 on CPUs the stack uses. Host: "
            "Windows' Processor Information and Hyper-V logical processor counters, 1 s samples"
        ),
        "probeRounds": probe_rounds,
        "serviceAtRest": rest,
        "service": service_steps,
    }
    path = results / "envcheck" / f"{started.replace(':', '-')}-contention.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    write_json(path, out)
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
    "benchmarks/knee-range.json",
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
    engine: dict[str, Any]
    sources: tuple[str, ...]
    if java_engine():
        sources = JAVA_ENGINE_SOURCES
        trees = [f"{p} {git('rev-parse', f'HEAD:{p}')}" for p in sources]
        # a hash over the Java engine's three source trees, and the build that ran
        engine = {
            "engineSources": hashlib.sha256("\n".join(trees).encode()).hexdigest(),
            "javaBuild": java_build(),
        }
    else:
        sources = ("packages/engine",)
        engine = {"engineTree": git("rev-parse", "HEAD:packages/engine")}
    status = git("status", "--porcelain", "--", *sources, *HARNESS)
    return {
        "commit": git("rev-parse", "HEAD"),
        **engine,
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
        engine = (
            "the Java engine is not frozen yet (ADR-0062)"
            if "engineSources" in prov
            else f"this engine (tree {str(tree)[:12]}) is not frozen in benchmarks/engines.json"
        )
        return (
            f"{sealed}: {engine}, so it may not be calibrated or measured. "
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
    if "collections" not in (stats.get("gc") or {}):
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
        choices=[
            *STACKS,
            "all",
            "smoke",
            "compare",
            "resources",
            "resimulate",
            "range-fit",
            "envcheck",
            "contention",
        ],
        help="a stack to run, all of them (held-out experiments only with a frozen engine), "
        "'smoke' EXPERIMENT: start its stack, send 20 req/s for 10 s and check every request "
        "succeeded and was counted (keeps nothing), 'compare': recompute every comparison.json, "
        "'resources': predict CPU and memory for results whose predictions have none, then "
        "compare, 'resimulate': check today's model against every kept run (in-sample, written "
        "under results/dev/), 'range-fit': fit the predicted range of the knee from the in-sample "
        "runs and check its coverage (writes benchmarks/knee-range.json; run resimulate first to "
        "reuse its inputs and knees), 'envcheck': characterise this machine (CPU siblings, "
        "steal, CPU per request across rates; about 8 minutes of Docker), or 'contention': how "
        "much slower CPU work runs with its hyperthread sibling, other cores or itself busy (a "
        "probe, then pg-mixed's service; about 15 minutes of Docker)",
    )
    ap.add_argument("experiment", nargs="?", help="with smoke: the experiment to smoke-test")
    ap.add_argument("--keep", action="store_true", help="leave the stack running afterwards")
    ap.add_argument(
        "--probe-only", action="store_true", help="with contention: the probe part only"
    )
    ap.add_argument(
        "--service-only",
        action="store_true",
        help="with contention: the machine at rest and the service at three rates only",
    )
    ap.add_argument("--only", choices=list(EXPERIMENTS), help="run one experiment of the stack")
    ap.add_argument(
        "--reuse-dev",
        metavar="LABEL",
        help="with range-fit: take the kept runs' inputs and knees from this engine label's "
        "development checks (only when nothing but the label changed since)",
    )
    ap.add_argument(
        "--label",
        help="with resimulate: write the checks under results/dev/LABEL/, a label that starts "
        "with the engine's (0.16.0-port, say) and names no other engine; the engine's own by "
        "default. It writes over that folder's checks of the same calibrations, committed or "
        "not",
    )
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
    try:
        java_engine()  # a BENCH_ENGINE that names neither engine, before anything starts
    except RuntimeError as exc:
        ap.error(str(exc))
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
        try:
            dev_label(args.label)
        except ValueError as exc:
            ap.error(str(exc))
        for path in resimulate(only=args.only, label=args.label):
            log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
        return 0
    if args.stack == "range-fit":
        out = range_fit_path()
        fit = range_fit(out=out, reuse=args.reuse_dev)
        for fold, by in fit["check"]["counts"].items():
            hits, n = by[f"{RANGE_COVERAGE:g}"]
            log(f"range fit: {fold}: {hits} of {n} runs inside the {RANGE_COVERAGE:.0%} range")
        log(f"wrote {out.relative_to(HERE.parent).as_posix()}")
        return 0
    if args.stack == "envcheck":
        log(f"wrote {envcheck().relative_to(HERE.parent).as_posix()}")
        return 0
    if args.stack == "contention":
        path = contention(service=not args.probe_only, probe=not args.service_only)
        log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
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
