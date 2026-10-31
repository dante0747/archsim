"""W23 trial on the Kafka stack: calibration-rate and diagnostic passes, never a benchmark run.

    uv run python benchmarks/results/trials/2026-10-31-kafka-w23/trial.py --raw <dir>

Takes the benchmark lock (C:/Users/abarg/.archsim-bench-lock) as soon as it is free, records the
host's load and the other jobs running, and runs three variants back to back:

1. The stack as benchmarked: two passes at 569 req/s (the top of the kept runs' calibration
   staircases), then 1,050, 1,575 and 1,890 req/s (the latest run's measured steps).
2. The broker's healthcheck made cheap once the broker is ready (it starts a JVM every 3 s
   otherwise) and the broker's JMX on: rest, then 525, 1,050, 1,575 and 1,890 req/s.
3. As 2 with four producer connections (KAFKA_PRODUCERS=4) instead of one: 1,050 to 1,890.

Each pass is a calibration pass (10 s warm-up, 30 s recorded; the instrumented service reports
kafkajs's fetch cycle and the producer's sends in flight) plus the broker JVM's CPU by thread,
the broker's GC log and, in 2 and 3, its request times (JMX), and the host's CPU load before it.
Writes diagnostics.json next to this file; the consumer's raw events go to --raw (kept out of
the repo). Nothing here is read by run.py or report.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
BENCH = HERE.parents[2]
sys.path.insert(0, str(BENCH))

import run  # noqa: E402

OVERRIDE = HERE / "override.yaml"
LOCK = Path.home() / ".archsim-bench-lock"
TOP_RATE = 569
DIAG_RATES = (1050, 1575, 1890)
AFTER_FREE_S = 120
KNEE_RATES: list[float] = [1890, 2100, 2400, 2700, 3000, 3300]
_base_compose = run.compose
_use_override = False


def compose(*args: str, check: bool = True, capture: bool = False) -> str:
    """run.compose, with this trial's override file when it is on."""
    if not _use_override:
        return _base_compose(*args, check=check, capture=capture)
    env = {**os.environ, "BENCH_REDIS_URL": os.environ.get("BENCH_REDIS_URL", "")}
    cmd = ["docker", "compose", "-f", str(BENCH / "compose.yaml"), "-f", str(OVERRIDE), *args]
    proc = subprocess.run(cmd, cwd=BENCH, env=env, check=False, text=True, capture_output=capture)
    if check and proc.returncode != 0:
        print((proc.stdout or "")[-4000:], (proc.stderr or "")[-4000:], sep="\n", flush=True)
        raise subprocess.CalledProcessError(proc.returncode, cmd, proc.stdout, proc.stderr)
    return proc.stdout if capture else ""


run.compose = compose

BROKER_SH = r"""
pid=""
for d in /proc/[0-9]*; do
  if tr '\0' ' ' < $d/cmdline 2>/dev/null | grep -q 'kafka.Kafka'; then pid=${d#/proc/}; break; fi
done
echo "pid $pid"
sed 's/.*) //' /proc/$pid/stat | cut -d' ' -f12,13 | sed 's/^/proc /'
for t in /proc/$pid/task/*; do
  echo "thread $(sed 's/.*) //' $t/stat | cut -d' ' -f12,13) $(cat $t/comm)"
done
"""


def broker_threads() -> dict[str, Any]:
    """The broker JVM's CPU (clock ticks of 10 ms), whole and by thread name (truncated to 15
    characters by Linux; numbered threads grouped)."""
    out = compose("exec", "-T", "kafka", "sh", "-c", BROKER_SH, capture=True)
    proc = 0
    groups: dict[str, int] = {}
    for line in out.splitlines():
        parts = line.split(" ", 3)
        if parts[0] == "proc" and len(parts) >= 3:
            proc = int(parts[1]) + int(parts[2])
        elif parts[0] == "thread" and len(parts) == 4:
            name = re.sub(r"[#\-]?\d+$", "", parts[3].strip()) or parts[3]
            groups[name] = groups.get(name, 0) + int(parts[1]) + int(parts[2])
    return {"jvmTicks": proc, "threadTicks": groups}


def gc_log_size() -> tuple[str, int]:
    path = compose(
        "exec", "-T", "kafka", "sh", "-c", "ls -t /opt/kafka/logs/*gc*.log* 2>/dev/null | head -1",
        capture=True,
    ).strip()  # fmt: skip
    if not path:
        return "", 0
    size = compose("exec", "-T", "kafka", "sh", "-c", f"wc -c < {path}", capture=True).strip()
    return path, int(size or 0)


def gc_log_since(path: str, offset: int) -> list[str]:
    if not path:
        return []
    text = compose("exec", "-T", "kafka", "sh", "-c", f"tail -c +{offset + 1} {path}", capture=True)
    return [ln for ln in text.splitlines() if ln.strip()]


PAUSE = re.compile(r"Pause (Young|Remark|Cleanup|Full)[^\n]*?(\d+\.\d+)ms")


def gc_pauses(lines: list[str]) -> dict[str, Any]:
    pauses = [float(m.group(2)) for ln in lines if (m := PAUSE.search(ln))]
    pauses.sort()
    return {
        "count": len(pauses),
        "totalMs": round(sum(pauses), 2),
        "medianMs": pauses[len(pauses) // 2] if pauses else None,
        "maxMs": pauses[-1] if pauses else None,
    }


def delta(after: dict[str, Any], before: dict[str, Any]) -> dict[str, Any]:
    names = set(after["threadTicks"]) | set(before["threadTicks"])
    by = {n: (after["threadTicks"].get(n, 0) - before["threadTicks"].get(n, 0)) * 10 for n in names}
    return {
        "jvmCpuMs": (after["jvmTicks"] - before["jvmTicks"]) * 10,
        "threadCpuMs": dict(sorted(((n, v) for n, v in by.items() if v), key=lambda x: -x[1])),
    }


JMX_REQUESTS = ("Produce", "Fetch", "OffsetCommit")
JMX_TIMES = (
    "TotalTimeMs",
    "RequestQueueTimeMs",
    "LocalTimeMs",
    "RemoteTimeMs",
    "ResponseQueueTimeMs",
    "ResponseSendTimeMs",
)
JMX_OTHER = (
    "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent",
    "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent",
)


def jmx() -> dict[str, float]:
    """The broker's request metrics over JMX (override variants only: KAFKA_JMX_PORT 9999):
    each request type's time in the broker by stage (cumulative counts and means; percentiles
    from a sample biased to the last minutes), and how idle its handler and network threads
    are. One JmxTool JVM, run outside the measured window."""
    names = [
        f"kafka.network:type=RequestMetrics,name={t},request={r}"
        for r in JMX_REQUESTS
        for t in JMX_TIMES
    ]
    args: list[str] = []
    for n in (*names, *JMX_OTHER):
        args += ["--object-name", n]
    out = compose(
        "exec", "-T", "kafka", "/opt/kafka/bin/kafka-run-class.sh",
        "org.apache.kafka.tools.JmxTool",
        "--jmx-url", "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi",
        "--one-time", "true", *args,
        capture=True, check=False,
    )  # fmt: skip
    lines = [ln for ln in out.splitlines() if ln.startswith('"') or ln[:1].isdigit()]
    if len(lines) < 2:
        return {}
    head = [h.strip('"') for h in lines[0].split('",')]
    vals = lines[1].split(",")
    res: dict[str, float] = {}
    for h, v in zip(head, vals, strict=False):
        try:
            res[h.replace("kafka.network:type=RequestMetrics,", "")] = float(v.strip('"'))
        except ValueError:
            continue
    return res


def jmx_delta(after: dict[str, float], before: dict[str, float]) -> dict[str, Any]:
    """Each request type's mean time per stage over the pass, from cumulative counts and means."""
    out: dict[str, Any] = {}
    for r in JMX_REQUESTS:
        row: dict[str, Any] = {}
        for t in JMX_TIMES:
            k = f"name={t},request={r}"
            n1, n0 = after.get(f"{k}:Count", 0.0), before.get(f"{k}:Count", 0.0)
            m1, m0 = after.get(f"{k}:Mean", 0.0), before.get(f"{k}:Mean", 0.0)
            if n1 > n0:
                row[t] = round((n1 * m1 - n0 * m0) / (n1 - n0), 4)
                row["count"] = int(n1 - n0)
            for q in ("50thPercentile", "95thPercentile", "99thPercentile"):
                if f"{k}:{q}" in after and t == "TotalTimeMs":
                    row[f"total{q[:2]}"] = after[f"{k}:{q}"]
        out[r] = row
    out["idle"] = {k: v for k, v in after.items() if "Idle" in k}
    return out


def diag_pass(exp: run.Experiment, rate: float, label: str, raw: Path) -> dict[str, Any]:
    """A calibration pass at ``rate`` with the broker's CPU by thread, its GC log, its request
    times (JMX, override variants) and the consumer's raw events."""
    j0 = jmx() if _use_override else {}
    b0 = broker_threads()
    path, off = gc_log_size()
    entry = run.calibration_pass(exp, exp.mix, rate, label)
    b1 = broker_threads()
    lines = gc_log_since(path, off)
    if _use_override:
        entry["brokerRequests"] = jmx_delta(jmx(), j0)
    events = run.http_json(f"{run.CONSUMER}/events")["events"]
    raw.mkdir(parents=True, exist_ok=True)
    (raw / f"{label}.json").write_text(json.dumps(events), encoding="utf-8")
    entry["broker"] = {**delta(b1, b0), "gcLog": lines, "gc": gc_pauses(lines)}
    k = entry["k6"]
    log_pass(label, rate, k, entry)
    return entry


def log_pass(label: str, rate: float, k: dict[str, Any], entry: dict[str, Any]) -> None:
    t = entry["service"]["timings"]
    c = entry["consumer"]["timings"]
    run.log(
        f"{label}: {rate:g} req/s p50 {k['p50'] or 0:.2f} p95 {k['p95'] or 0:.2f} ms; send p95 "
        f"{t['kafka_send']['p95']:.2f} ms, in flight mean {t['kafka_inflight']['mean']:.2f}; "
        f"age p95 {(c.get('end_to_end:measure') or {}).get('p95')} ms; cycle mean "
        f"{(c.get('cycle') or {}).get('mean', 0):.2f} ms; broker GC {entry['broker']['gc']}; "
        f"produce in broker {(entry.get('brokerRequests') or {}).get('Produce')}"
    )


def rest(exp: run.Experiment, label: str) -> dict[str, Any]:
    b0 = broker_threads()
    path, off = gc_log_size()
    idle = run.idle_pass(exp)
    b1 = broker_threads()
    lines = gc_log_since(path, off)
    idle["broker"] = {**delta(b1, b0), "gc": gc_pauses(lines), "gcLog": lines}
    run.log(
        f"{label}: at rest {idle['containerCpuMsPerSecond']}, broker JVM {idle['broker']['jvmCpuMs']} ms"
    )
    return idle


def host_load() -> float:
    """The host's CPU load (%), as Windows reports it."""
    out = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage"
            " -Average).Average",
        ],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    try:
        return float(out)
    except ValueError:
        return 100.0


def other_jobs() -> list[str]:
    """Other Python and Node.js processes on the host (their command lines, shortened): what
    else was running during the trial."""
    out = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            "Get-CimInstance Win32_Process -Filter \"Name='python.exe' or Name='node.exe'\" | "
            "ForEach-Object { $_.CommandLine }",
        ],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    return sorted(
        {ln.strip()[:200] for ln in out.splitlines() if ln.strip() and "kafka-w23" not in ln}
    )


def holder() -> str:
    try:
        return (LOCK / "owner.txt").read_text(encoding="utf-8").strip()
    except OSError:
        return "free" if not LOCK.exists() else "being taken"


def wait_and_lock(after: str | None = None) -> dict[str, Any]:
    """Take the lock as soon as it is free (the main session's rule of 31 Oct: quiet means no
    lock held and no heavy job of this session's own), recording the host's load and the other
    jobs running then. With ``after``, only once a holder whose note contains it has held the
    lock and the lock has then stayed free for AFTER_FREE_S (a holder that releases it between
    its stacks takes it straight back; the main session's lock order)."""
    last_log = 0.0
    seen = after is None
    free_since: float | None = None
    while True:
        held = holder()
        if after and after in held:
            seen = True
        if LOCK.exists():
            free_since = None
        elif free_since is None:
            free_since = time.time()
        ready = free_since is not None and (
            after is None or time.time() - free_since >= AFTER_FREE_S
        )
        if not seen or not ready:
            if time.time() - last_log > 300:
                run.log(f"waiting for the lock ({held}; after {after}: seen {seen})")
                last_log = time.time()
            time.sleep(10)
            continue
        try:
            LOCK.mkdir()
        except FileExistsError:
            continue
        (LOCK / "owner.txt").write_text(
            f"w23-kafka (worktree w23-kafka-230dab) Kafka trial {time.ctime()}\n",
            encoding="utf-8",
        )
        load = [host_load() for _ in range(3)]
        run.log(f"lock taken; host CPU {statistics.mean(load):.0f}%")
        return {"hostLoad": load, "otherJobs": other_jobs()}


def passes(exp: run.Experiment, rates: list[float], name: str, raw: Path) -> list[dict[str, Any]]:
    out = []
    for i, r in enumerate(rates):
        load = host_load()
        e = diag_pass(exp, r, f"{name}-{r:g}-{i + 1}", raw)
        e["hostLoadBefore"] = load
        out.append(e)
    return out


def save(name: str, data: Any) -> None:
    (HERE / name).write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")


def main() -> int:
    global _use_override
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--raw", type=Path, required=True, help="where the raw events go (not the repo)"
    )
    ap.add_argument(
        "--knee",
        action="store_true",
        help="only the stack with compose.yaml's fixed healthcheck, from 1,890 to 3,300 req/s",
    )
    ap.add_argument(
        "--after", help="take the lock only after a holder whose note contains this released it"
    )
    a = ap.parse_args()
    exp = run.EXPERIMENTS["kafka"]
    started = wait_and_lock(a.after)
    out: dict[str, Any] = {
        "at": run.now(),
        "environment": run.environment(),
        "atStart": started,
        "variants": {},
    }
    try:
        if a.knee:  # compose.yaml's healthcheck now stops starting JVMs once the broker is ready
            run.stack_up("kafka")
            run.settle(exp)
            out["variants"]["fixed-healthcheck"] = {
                "passes": passes(exp, KNEE_RATES, "fixed-healthcheck", a.raw)
            }
            save("knee.json", out)
            return 0
        run.stack_up("kafka")
        v: dict[str, Any] = {"rest": rest(exp, "benchmarked")}
        run.settle(exp)
        v["passes"] = passes(exp, [TOP_RATE, TOP_RATE, *DIAG_RATES], "benchmarked", a.raw)
        out["variants"]["benchmarked"] = v
        save("diagnostics.json", out)
        run.stack_down("kafka")

        _use_override = True
        os.environ["BENCH_KAFKA_PRODUCERS"] = "1"
        run.stack_up("kafka")
        v = {"rest": rest(exp, "cheap-healthcheck")}
        run.settle(exp)
        v["passes"] = passes(exp, [525, *DIAG_RATES], "cheap-healthcheck", a.raw)
        out["variants"]["cheap-healthcheck"] = v
        save("diagnostics.json", out)

        os.environ["BENCH_KAFKA_PRODUCERS"] = "4"
        compose("up", "-d", "--wait", "producer", capture=True)
        run.wait_http(f"{run.API}/health")
        time.sleep(5)
        run.settle(exp)
        v = {"passes": passes(exp, list(DIAG_RATES), "producers-4", a.raw)}
        out["variants"]["producers-4"] = v
        save("diagnostics.json", out)
    finally:
        run.stack_down("kafka")
        if LOCK.exists() and "w23-kafka" in (LOCK / "owner.txt").read_text(encoding="utf-8"):
            for f in LOCK.iterdir():
                f.unlink()
            LOCK.rmdir()
            run.log("lock released")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
