"""ArchSim accuracy benchmarks: calibrate at low load, predict with ArchSim, measure, compare.

    uv run python benchmarks/run.py postgres      # pg-mixed and pg-hot-row
    uv run python benchmarks/run.py cache         # cache-aside
    uv run python benchmarks/run.py kafka         # kafka
    uv run python benchmarks/run.py all
    uv run python benchmarks/run.py compare       # recompute every comparison.json (no Docker)
    uv run python benchmarks/report.py            # rewrite docs/validation.md from the results

Protocol (docs/validation.md): for each experiment the runner
1. starts the stack (benchmarks/compose.yaml) and settles it: VACUUM ANALYZE and a checkpoint
   after the data load (run 3's first calibration pass met the post-load autovacuum), then a
   discarded 30 s warm-up at the calibration rate;
2. calibrates in two passes of the experiment's traffic, 50 req/s and then a quarter of the most
   pessimistic capacity the first pass implies, and reads what the service measured (CPU per
   request on the event loop, the time of each PostgreSQL, Redis and Kafka call, the database's
   CPU per query, Redis's own command time); every model input is derived from these numbers by
   a formula recorded in calibration.json;
3. predicts: builds the ArchSim design from the calibration and simulates it, finding the knee
   (the highest load with p95 <= 50 ms and >= 99% success) and every step of the load ladder
   (0.25x to 1.5x the predicted knee); predictions.json is written before step 4 starts;
4. measures: runs k6 at each step three times (5 s warm-up, 20 s measured, WARMUP_S and
   DURATION_S) and records p50, p95, p99, throughput and errors;
5. compares: comparison.json with errors per step and the knee error.

Only the Python standard library, Docker and the engine are needed.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import statistics
import subprocess
import sys
import time
import urllib.request
from collections.abc import Iterable, Mapping
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from datetime import UTC, datetime
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
    )
}
STACKS = {
    "postgres": ["pg-mixed", "pg-hot-row"],
    "cache": ["cache-aside"],
    "kafka": ["kafka"],
}


# ---------------------------------------------------------------------------------------------
# docker and HTTP helpers
# ---------------------------------------------------------------------------------------------


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def compose(*args: str, check: bool = True, capture: bool = False) -> str:
    env = {**os.environ, "BENCH_REDIS_URL": os.environ.get("BENCH_REDIS_URL", "")}
    proc = subprocess.run(
        ["docker", "compose", "-f", str(HERE / "compose.yaml"), *args],
        cwd=HERE,
        env=env,
        check=check,
        text=True,
        capture_output=capture,
    )
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


K6_CPUS = {"postgres": "0,1,3,6,7", "cache": "0,3,6,7", "kafka": "6,7"}
"""k6's cpuset per stack: every CPU the stack's other containers leave free (compose.yaml's
default for k6 is only used when the runner is bypassed)."""
USER_K6_CPUS = os.environ.get("BENCH_CPUS_K6")
STACK_SERVICES = {
    "postgres": ["api", "postgres"],
    "cache": ["api", "postgres", "redis"],
    "kafka": ["producer", "consumer", "postgres", "kafka"],
}


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


def cpu_count(cpuset: str) -> int:
    """How many CPUs a cpuset ("0,1,3" or "0-3") names."""
    n = 0
    for part in cpuset.split(","):
        lo, _, hi = part.strip().partition("-")
        n += int(hi) - int(lo) + 1 if hi else 1
    return n


def stack_up(stack: str) -> None:
    os.environ["BENCH_CPUS_K6"] = cpusets(stack)["k6"]
    if stack == "cache":
        os.environ["BENCH_REDIS_URL"] = "redis://redis:6379"
    else:
        os.environ["BENCH_REDIS_URL"] = ""
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
    return "http://producer:8080" if exp.stack == "kafka" else "http://api:8080"


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
    summary = k6(target, mix, rate, 0, CALIBRATION_S, f"cal-{exp.id}-{label}", exp.keys, exp.skew)
    time.sleep(2)  # let the consumer finish the last messages
    cpu1 = {s: cpu_usec(s) for s in services_for(exp)}
    entry: dict[str, Any] = {
        "mix": mix,
        "rateRps": rate,
        "k6": k6_measured(summary, CALIBRATION_S),
        "service": http_json(f"{API}/stats"),
        "containerCpuMs": {s: cpu_ms(cpu0[s], cpu1[s]) for s in cpu0},
    }
    if exp.stack == "kafka":
        entry["consumer"] = http_json(f"{CONSUMER}/stats")
    if exp.stack == "cache":
        entry["redis"] = redis_cmdstats()
    return entry


def capacity_bound(exp: Experiment, low: dict[str, Any]) -> float:
    """The most pessimistic capacity the low pass implies (req/s): the service's CPU at its
    low-load cost per request, the connection pool, the hot row's lock, the consumers."""
    svc = low["service"]
    t = svc["timings"]
    bounds = [1000 / svc["cpuMsPerRequest"]]
    for name in ("db_read", "db_write"):
        if name in t:
            bounds.append(PG_POOL * 1000 / t[name]["mean"])
    if exp.id == "pg-hot-row":
        bounds.append(1000 / t["db_write"]["mean"])  # one row: one write at a time
    if exp.stack == "kafka":
        cons = low["consumer"]
        bounds.append(KAFKA_CONSUMERS * 1000 / cons["timings"]["consume"]["mean"])
    return min(bounds)


def calibrate(exp: Experiment) -> dict[str, Any]:
    """Two passes of the experiment's traffic: 50 req/s, then at most a quarter of the most
    pessimistic capacity the first pass implies (still low load). CPU per request is the slope
    between them (service demand: the fixed CPU a process burns at any rate cancels out);
    timings come from the second pass. Auxiliary mixes (writes without the WAL flush) run at the
    second pass's rate."""
    main_mix = exp.calibration[0]
    settled = settle(exp)
    discarded: list[dict[str, Any]] = []
    low = steady_pass(exp, main_mix, CALIBRATION_RPS, "low", discarded)
    bound = capacity_bound(exp, low)
    rate2 = max(CALIBRATION_RPS + 25, round(CALIBRATION_UTILIZATION * bound))
    passes: dict[str, Any] = {
        "low": low,
        "high": steady_pass(exp, main_mix, rate2, "high", discarded),
    }
    for mix in exp.calibration[1:]:
        passes[mix] = steady_pass(exp, mix, rate2, mix.split(":")[0], discarded)
    inputs = model_inputs(exp, passes)
    anchor(exp, inputs, passes["high"])
    return {
        "experiment": exp.id,
        "at": now(),
        "method": "two-pass slope, anchored at the calibration rate, settled first (v4)",
        "settle": settled,
        "capacityBoundRps": round(bound, 1),
        "passes": passes,
        "discardedPasses": discarded,
        "inputs": inputs,
    }


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
    """Every model input with the measured numbers and the formula it came from."""
    low, high = passes["low"], passes["high"]
    svc, svc_low = high["service"], low["service"]
    own = timing(svc, "own")
    handler = timing(svc, "handler")
    demand = slope(svc_low["cpuMs"], svc["cpuMs"], svc_low["requests"], svc["requests"])
    if demand is None or demand <= 0:
        demand = svc["cpuMsPerRequest"]
    out: dict[str, Any] = {
        "serviceTimeMs": {
            "value": lognormal_with_mean(demand, own),
            "how": (
                f"mean = Node's CPU per extra request between the passes: ({svc['cpuMs']:.0f} - "
                f"{svc_low['cpuMs']:.0f} ms) / ({svc['requests']} - {svc_low['requests']} requests) = "
                f"{demand:.4f} ms (at {low['rateRps']:g} req/s alone: {svc_low['cpuMsPerRequest']:.4f} ms); "
                f"shape (p95/median {own['p95']:.4f}/{own['median']:.4f} ms) from the handler's own time"
            ),
        },
        "clientLinkLatencyMs": {
            "value": round(max(0.0, (high["k6"]["p50"] - handler["median"]) / 2), 4),
            "how": f"(k6 median {high['k6']['p50']:.3f} ms - handler median {handler['median']:.3f} ms) / 2, one way",
        },
        "measuredLowLoad": {"rateRps": high["rateRps"], **high["k6"]},
    }

    def db_share(q_low: float, q_high: float, mean_ms: float) -> None:
        pg = slope(
            low["containerCpuMs"].get("postgres"),
            high["containerCpuMs"].get("postgres"),
            q_low,
            q_high,
        )
        if pg is None or pg <= 0 or mean_ms <= 0:
            return
        out["dbCpuShare"] = {
            "value": round(min(1.0, max(0.02, pg / mean_ms)), 4),
            "how": f"PostgreSQL CPU per extra query between the passes ({pg:.4f} ms) / mean query time ({mean_ms:.3f} ms)",
        }

    if exp.stack == "kafka":
        cons, cons_low = high["consumer"], low["consumer"]
        insert = timing(cons, "db_insert")
        consume_own = timing(cons, "consume_own")
        send = timing(svc, "kafka_send")
        sync = timing(passes["write:1"]["service"], "db_write")
        async_ = timing(passes["write_async:1"]["service"], "db_write_async")
        flush_med = max(0.01, sync["median"] - async_["median"])
        out["publishTimeMs"] = {
            "value": lognormal(send["median"], send["p95"]),
            "how": f"producer.send with acks=all on one broker: median {send['median']:.3f}, p95 {send['p95']:.3f} ms",
        }
        out["consumerTimeMs"] = {
            "value": lognormal(consume_own["median"], consume_own["p95"]),
            "how": f"consumer's own time per message (handling minus the INSERT): median {consume_own['median']:.3f}, p95 {consume_own['p95']:.3f} ms",
        }
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
        db_share(cons_low["consumed"], cons["consumed"], insert["mean"])
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
            a = timing(passes["write_async:1"]["service"], "db_write_async")
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
        db_share(q_low, q_high, mean_q)
    if exp.stack == "cache":
        rs = high["redis"]
        get = rs.get("get", {"calls": 0, "usec": 0})
        set_ = rs.get("set", {"calls": 0, "usec": 0})
        calls = get["calls"] + set_["calls"]
        server_ms = (get["usec"] + set_["usec"]) / max(calls, 1) / 1000
        app_get = timing(svc, "redis_get")
        out["cacheServiceTimeMs"] = {
            "value": {"kind": "exponential", "mean": round(max(server_ms, 0.001), 5)},
            "how": f"Redis INFO commandstats: {get['usec'] + set_['usec']:.0f} us over {calls:.0f} GET/SET calls",
        }
        out["cacheLinkLatencyMs"] = {
            "value": round(max(0.0, (app_get["median"] - server_ms) / 2), 4),
            "how": f"(GET round trip median {app_get['median']:.3f} ms - Redis's own time {server_ms:.4f} ms) / 2, one way",
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


def design(
    exp: Experiment,
    inputs: dict[str, Any],
    rps: float,
    warm: int = WARMUP_S,
    duration: int = DURATION_S,
) -> dict[str, Any]:
    v = {k: x["value"] for k, x in inputs.items() if isinstance(x, dict) and "value" in x}
    nodes: list[dict[str, Any]] = [
        {
            "id": "clients",
            "type": "traffic_source",
            "label": "k6",
            "config": {"timeoutMs": TIMEOUT_MS, "maxAttempts": 1},
        },
        {
            "id": "api",
            "type": "service",
            "label": "Node.js service",
            "config": {
                "technology": "nodejs",
                "platform": "docker",
                "replicas": 1,
                "processesPerReplica": 1,
                "queueCapacity": 100_000,
                "queueTimeoutMs": None,
                "serviceTimeMs": v["serviceTimeMs"],
                "gcPauseMs": 0,  # measured CPU per request already includes GC
                "warmupFactor": 1,
                "cpuCores": 1,
            },
        },
    ]
    db = {
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
    edges = [_edge("e-clients", "clients", "api", v["clientLinkLatencyMs"])]
    if exp.stack == "postgres":
        nodes.append(db)
        edges.append(_edge("e-db", "api", "db", 0.0))
    elif exp.stack == "cache":
        nodes += [
            {
                "id": "cache",
                "type": "cache",
                "label": "Redis",
                "config": {
                    "technology": "redis",
                    "hitModel": "ttl_keyspace",
                    "ttlSeconds": CACHE_TTL_S,
                    "populateOnMiss": True,
                    "serviceTimeMs": v["cacheServiceTimeMs"],
                    "queueCapacity": 100_000,
                    "cpuCores": 1,
                },
            },
            db,
        ]
        edges += [
            _edge("e-cache", "api", "cache", v["cacheLinkLatencyMs"], types=["read"]),
            _edge("e-miss", "cache", "db", 0.0, port="miss", types=["read"]),
        ]
    else:  # kafka
        nodes += [
            {
                "id": "kafka",
                "type": "message_queue",
                "label": "Kafka",
                "config": {
                    "technology": "kafka",
                    "partitions": KAFKA_PARTITIONS,
                    "consumers": KAFKA_CONSUMERS,
                    "brokers": 1,
                    "maxDepth": None,
                    "acks": "leader",  # one broker: acks=all is the leader's write
                    "lingerMs": 0,  # kafkajs sends each message at once
                    "publishTimeMs": v["publishTimeMs"],
                    "consumerTimeMs": v["consumerTimeMs"],
                    "maxDeliveries": 1,
                },
            },
            db,
        ]
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


def predict(exp: Experiment, calibration: dict[str, Any]) -> dict[str, Any]:
    from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION

    inputs = calibration["inputs"]
    log(f"predicting {exp.id}: searching the knee")
    knee = predicted_knee(exp, inputs)
    k = knee["kneeRps"]
    steps = sorted({max(10, round(k * f)) for f in STEP_FRACTIONS})
    log(f"predicted knee {k:g} req/s; steps {steps}")
    with ProcessPoolExecutor(max_workers=min(len(steps), os.cpu_count() or 2)) as pool:
        results = list(pool.map(simulate, [design(exp, inputs, s) for s in steps]))
    return {
        "experiment": exp.id,
        "at": now(),
        "engineVersion": ENGINE_VERSION,
        "reportVersion": REPORT_VERSION,
        "objective": {"p95Ms": OBJECTIVE_P95_MS, "minSuccess": MIN_SUCCESS},
        "knee": knee,
        "steps": results,
        "design": design(exp, inputs, k),
    }


# ---------------------------------------------------------------------------------------------
# 3. measurement
# ---------------------------------------------------------------------------------------------


def measure_once(exp: Experiment, rps: float, run: int) -> dict[str, Any]:
    target = target_for(exp)
    log(f"measuring {exp.id} at {rps:g} req/s (run {run})")
    if exp.stack == "cache":
        compose("exec", "-T", "redis", "redis-cli", "FLUSHALL", capture=True)
    http_json(f"{API}/reset", "POST")
    cons0 = http_json(f"{CONSUMER}/stats") if exp.stack == "kafka" else None
    cpu0 = {s: cpu_usec(s) for s in services_for(exp)}
    t0 = time.time()
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
    row["containerCpuShare"] = {
        s: round((after - before) / 1e6 / elapsed, 3)
        for s, after in cpu1.items()
        if (before := cpu0.get(s)) is not None and after is not None
    }
    c = svc.get("counters", {})
    if exp.stack == "cache":
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
    return out


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


def measured_steps(meas: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Each measured step aggregated from its repeats (``aggregate``), so that results recorded
    before a fix to the aggregation are compared the way new ones are. Steps of run 1 had no
    repeats: the step is its one run."""
    return [
        {"offeredRps": s["offeredRps"], **aggregate(s.get("runs") or [s])} for s in meas["steps"]
    ]


def compare(exp: Experiment, pred: dict[str, Any], meas: dict[str, Any]) -> dict[str, Any]:
    by = {s["offeredRps"]: s for s in pred["steps"]}
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
        rows.append(row)
    mk = grid_knee(steps)
    pk = grid_knee(pred["steps"])
    knee_err = err(pk["kneeRps"], mk["kneeRps"])
    below = at_or_below_knee(rows, mk)
    p95_errs = [abs(r["error"]["p95"]) for r in below if r["error"]["p95"] is not None]
    return {
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


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def recompare(results: Path = RESULTS) -> list[Path]:
    """Recompute every comparison.json from its measured.json and predictions.json (no Docker)."""
    written = []
    for exp, d in result_dirs(results):
        if not (d / "predictions.json").exists():
            continue
        pred = json.loads((d / "predictions.json").read_text(encoding="utf-8"))
        meas = json.loads((d / "measured.json").read_text(encoding="utf-8"))
        write_json(d / "comparison.json", compare(EXPERIMENTS[exp], pred, meas))
        written.append(d / "comparison.json")
    return written


# ---------------------------------------------------------------------------------------------


def now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def environment() -> dict[str, Any]:
    def run(*cmd: str) -> str:
        return subprocess.run(cmd, text=True, capture_output=True, check=False).stdout.strip()

    cpu = platform.processor()
    if sys.platform == "win32":
        cpu = (
            run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name")
            or cpu
        )
    elif Path("/proc/cpuinfo").exists():
        cpu = next(
            (
                ln.split(":", 1)[1].strip()
                for ln in Path("/proc/cpuinfo").read_text().splitlines()
                if ln.startswith("model name")
            ),
            cpu,
        )
    return {
        "cpu": cpu,
        "os": platform.platform(),
        "docker": run(
            "docker", "version", "--format", "{{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})"
        ),
        "dockerCpus": run("docker", "info", "--format", "{{.NCPU}}"),
        "dockerMemory": run("docker", "info", "--format", "{{.MemTotal}}"),
        "images": {
            "postgres": "postgres:16-alpine",
            "redis": "redis:7-alpine",
            "kafka": "apache/kafka:3.8.0",
            "k6": "grafana/k6:1.3.0",
            "service": "node:24-alpine + pg 8.23.1, redis 4.7.1, kafkajs 2.2.4",
        },
        "python": platform.python_version(),
    }


def save(exp_id: str, name: str, data: dict[str, Any]) -> None:
    d = RESULTS / exp_id
    d.mkdir(parents=True, exist_ok=True)
    write_json(d / f"{name}.json", data)


def run_experiment(exp: Experiment) -> None:
    calibration = calibrate(exp)
    save(exp.id, "calibration", calibration)
    predictions = predict(exp, calibration)
    save(exp.id, "predictions", predictions)  # written before any measured step runs
    measured: dict[str, Any] = {"experiment": exp.id, "at": now(), "steps": []}
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


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "stack",
        choices=[*STACKS, "all", "compare"],
        help="a stack to run, all of them, or 'compare': recompute every comparison.json",
    )
    ap.add_argument("--keep", action="store_true", help="leave the stack running afterwards")
    ap.add_argument("--only", choices=list(EXPERIMENTS), help="run one experiment of the stack")
    args = ap.parse_args(argv)
    if args.stack == "compare":
        for path in recompare():
            log(f"wrote {path.relative_to(HERE.parent).as_posix()}")
        return 0
    stacks = list(STACKS) if args.stack == "all" else [args.stack]
    env = environment()
    for stack in stacks:
        stack_up(stack)
        try:
            for exp_id in STACKS[stack]:
                if args.only and exp_id != args.only:
                    continue
                save(exp_id, "environment", {**env, "cpusets": cpusets(stack)})
                run_experiment(EXPERIMENTS[exp_id])
        finally:
            if not args.keep:
                stack_down(stack)
    return 0


if __name__ == "__main__":
    sys.exit(main())
