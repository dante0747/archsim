"""k6 at the second held-out set's rates, on a spent stack that gives it the same CPUs (ADR-0090).

python-wide's ladder is expected to pass 4,000 req/s and cache-miss's knee to lie above 3,000,
with k6 on CPUs 6 and 7 alone, where it has been checked only up to 4,400 req/s (ADR-0086).
This sends cache-ttl's own traffic (a spent experiment, k6 on the same CPUs, a stack that holds
about 5,000 req/s) at those rates, with the virtual users protocol v7 gives each rate, and reads
what the harness reads of a measured window: k6's busy cores from its container's cgroup
(K6Cpu), and from its summary the requests sent, dropped and failed and the latency (K6Measured).
Nothing here calibrates or measures a held-out experiment.

Run from the repository's root with cache-ttl's stack up
(`./gradlew :archsim-benchmarks:run --args="smoke cache-ttl --keep"`):

    uv run --no-project python benchmarks/results/trials/2026-11-21-k6-check/k6_check.py
"""
import datetime
import json
import math
import os
import subprocess
import time
from pathlib import Path

HERE = Path(__file__).parent
COMPOSE = ["docker", "compose", "-f", "benchmarks/compose.yaml"]
SUMMARIES = Path("benchmarks/results/.k6")
RATES = [3000, 4000, 5000]
REPEATS = 2
WARMUP_S = 5  # Protocol.WARMUP_S
DURATION_S = 20  # Protocol.DURATION_S
TIMEOUT_MS = 2000  # Protocol.TIMEOUT_MS
MARGIN_S = 2.0  # K6Cpu.MARGIN_S
TRAFFIC = {"TARGET": "http://api-ttl:8080", "MIX": "cached:1", "KEYS": "1000000", "SKEW": "1.1"}
K6_CPUS = "6,7"


def virtual_users(rate):
    """Traffic.virtualUsers: a quarter of a second of arrivals, or the whole timeout's up to 1,000."""
    cover = math.ceil(rate * 0.25)
    whole_timeout = math.ceil(rate * TIMEOUT_MS / 1000)
    return min(max(cover, min(whole_timeout, 1000)) + 50, 3000)


def k6_container():
    for _ in range(75):
        found = subprocess.run(["docker", "ps", "-q", "--filter", "name=archsim-bench-k6-run"],
                               capture_output=True, text=True).stdout.split()
        if found:
            return found[0]
        time.sleep(0.2)
    return None


def usage_usec(container):
    done = subprocess.run(["docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat"],
                          capture_output=True, text=True)
    for line in done.stdout.splitlines():
        if line.startswith("usage_usec "):
            return int(line.split()[1])
    return None


def nearest(samples, at):
    return min(samples, key=lambda sample: abs(sample[0] - at))


def run(rate, repeat):
    name = f"k6check-{rate}-{repeat}"
    environment = dict(TRAFFIC, RATE=str(rate), WARMUP_S=str(WARMUP_S), DURATION_S=str(DURATION_S),
                       TIMEOUT_MS=str(TIMEOUT_MS), VUS=str(virtual_users(rate)), OUT=name)
    arguments = [part for key, value in environment.items() for part in ("-e", f"{key}={value}")]
    process = subprocess.Popen(COMPOSE + ["--profile", "load", "run", "--rm", *arguments, "k6", "run", "-q",
                                          "/scripts/load.js"],
                               env=dict(os.environ, BENCH_CPUS_K6=K6_CPUS),
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    container = k6_container()
    samples = []
    while process.poll() is None and container:
        usage = usage_usec(container)
        if usage is not None:
            samples.append((time.monotonic(), usage))
        time.sleep(1.0)
    process.wait()
    # the measured window is the run's last DURATION_S seconds; read it as K6Cpu does, 2 s in
    # and 2 s before its end
    cores = None
    if len(samples) >= 3:
        end = samples[-1][0]
        first, last = nearest(samples, end - DURATION_S + MARGIN_S), nearest(samples, end - MARGIN_S)
        if last[0] > first[0]:
            cores = round((last[1] - first[1]) / 1e6 / (last[0] - first[0]), 4)
    metrics = json.loads((SUMMARIES / f"{name}.json").read_text(encoding="utf-8"))

    def values(metric):
        return metrics.get(metric, {}).get("values", {})

    sent = int(values("http_reqs{scenario:measure}").get("count", 0))
    dropped = int(values("dropped_iterations{scenario:measure}").get("count", 0))
    failed = int(values("http_req_failed{scenario:measure}").get("passes", 0))
    latency = values("http_req_duration{scenario:measure,expected_response:true}")
    return {"rateRps": rate, "repeat": repeat, "virtualUsers": virtual_users(rate), "k6Cores": cores,
            "sent": sent, "dropped": dropped,
            "droppedShare": round(dropped / (sent + dropped), 4) if sent + dropped else None,
            "failed": failed, "p50Ms": latency.get("med"), "p95Ms": latency.get("p(95)"),
            "p99Ms": latency.get("p(99)"), "samples": len(samples)}


def main():
    at = datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    steps = []
    for rate in RATES:
        for repeat in range(1, REPEATS + 1):
            step = run(rate, repeat)
            steps.append(step)
            print(json.dumps(step))
            time.sleep(5)
    record = {"at": at, "stack": "cache-ttl", "traffic": TRAFFIC, "k6Cpus": K6_CPUS, "warmupS": WARMUP_S,
              "durationS": DURATION_S, "steps": steps}
    (HERE / "k6-check.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")


main()
