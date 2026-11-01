"""W26 trials: protocol v6 calibrations of the four in-sample stacks, calibration only.

    uv run python benchmarks/results/trials/W26-trial.py [--only cache-aside ...] [--date 2026-11-01]

Development data for accuracy plan W26 (a staircase that climbs to 65% of the service's CPU, and
the CPU curve above it), never a benchmark run: nothing is predicted or measured, and run.py and
report.py never read results/trials/. Each experiment's calibration is ``run.calibrate`` as a
protocol v6 run calls it, written to ``<date>-<experiment>-w26/calibration.json`` with
``environment.json`` (the machine, the cpusets, the commit, the host's CPU load and the other jobs
running when each stack started).

Takes the benchmark lock (C:/Users/abarg/.archsim-bench-lock) once it is free, the host has
been under QUIET_LOAD % CPU for QUIET_S and its CPU boosts (``cpu_boost``: on 1 Nov the laptop
ran below its base clock and the service's CPU per request read twice the kept runs'), holds it
for every stack of the trial, and releases it when the last stack is down; it stops before a
stack if the CPU no longer boosts. Only the in-sample experiments can run here.
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
BENCH = HERE.parents[1]
sys.path.insert(0, str(BENCH))

import run  # noqa: E402

LOCK = Path.home() / ".archsim-bench-lock"
OWNER = "w26-cost-curve (worktree w24-resources-b4b0f2) protocol v6 calibration trial"
IN_SAMPLE = ("cache-aside", "pg-mixed", "pg-hot-row", "kafka")
QUIET_LOAD = 35.0
QUIET_S = 60
MIN_BOOST = 120.0
"""The busiest CPU's clock while one thread spins must be at least this % of base (cpu_boost)."""


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


BOOST_PS = r"""
$job = Start-Job { $t = [Diagnostics.Stopwatch]::StartNew(); $x = 0
  while ($t.ElapsedMilliseconds -lt 6000) { $x++ } }
Start-Sleep -Milliseconds 800
$s = (Get-Counter '\Processor Information(*)\% Processor Performance',
  '\Processor Information(*)\% Processor Time' -SampleInterval 2 -MaxSamples 2).CounterSamples |
  Where-Object { $_.InstanceName -notlike '*_Total' }
$s | Group-Object InstanceName | ForEach-Object {
  $p = ($_.Group | Where-Object Path -like '*performance*' | Measure-Object CookedValue -Average).Average
  $b = ($_.Group | Where-Object Path -like '*processor time*' | Measure-Object CookedValue -Average).Average
  "$b $p" }
Receive-Job $job -Wait | Out-Null
"""


def cpu_boost() -> float:
    """The clock of the busiest logical CPU while one thread spins, as a percentage of the base
    clock (Windows' % Processor Performance). On 1 Nov the laptop ran at 74-77% (throttled) and
    the service's CPU per request read twice the kept runs'; boosting it runs at 146-168%."""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command", BOOST_PS],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    rows = []
    for line in out.splitlines():
        parts = line.replace(",", ".").split()
        if len(parts) == 2:
            try:
                rows.append((float(parts[0]), float(parts[1])))
            except ValueError:
                continue
    return max(rows)[1] if rows else 0.0


def other_jobs() -> list[str]:
    """Other Python and Node.js processes on the host (command lines, shortened)."""
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
    return sorted({ln.strip()[:200] for ln in out.splitlines() if ln.strip() and "W26" not in ln})


def holder() -> str:
    try:
        return (LOCK / "owner.txt").read_text(encoding="utf-8").strip()
    except OSError:
        return "free" if not LOCK.exists() else "being taken"


def quiet() -> list[float]:
    """Host CPU readings over QUIET_S, or [] as soon as one is above QUIET_LOAD."""
    loads: list[float] = []
    end = time.time() + QUIET_S
    while time.time() < end:
        load = host_load()
        loads.append(load)
        if load > QUIET_LOAD or LOCK.exists():
            return []
        time.sleep(5)
    return loads


def wait_and_lock() -> dict[str, Any]:
    """Take the lock once it is free, the host has been quiet for QUIET_S and its CPU boosts
    (MIN_BOOST)."""
    last_log = 0.0
    boost = 0.0
    while True:
        if not LOCK.exists() and (loads := quiet()) and (boost := cpu_boost()) >= MIN_BOOST:
            try:
                LOCK.mkdir()
            except FileExistsError:
                continue
            (LOCK / "owner.txt").write_text(f"{OWNER} {time.ctime()}\n", encoding="utf-8")
            run.log(
                f"lock taken; host CPU {statistics.mean(loads):.0f}% over {QUIET_S} s, "
                f"clock {boost:.0f}% of base"
            )
            return {"hostLoad": loads, "boostPct": boost, "otherJobs": other_jobs()}
        if time.time() - last_log > 300:
            run.log(f"waiting: lock {holder()}, CPU clock {boost:.0f}% of base")
            last_log = time.time()
        time.sleep(60 if boost else 15)


def release() -> None:
    if LOCK.exists() and OWNER in holder():
        for f in LOCK.iterdir():
            f.unlink()
        LOCK.rmdir()
        run.log("lock released")


def save(folder: Path, name: str, data: Any) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{name}.json").write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", choices=IN_SAMPLE, help="these experiments only")
    ap.add_argument("--date", default=time.strftime("%Y-%m-%d"), help="the folders' date")
    a = ap.parse_args()
    wanted = a.only or list(IN_SAMPLE)
    plan = {
        stack: [e for e in exps if e in wanted]
        for stack, exps in run.STACKS.items()
        if any(e in wanted for e in exps)
    }
    plan = dict(sorted(plan.items(), key=lambda kv: kv[0] != "cache"))  # cache-aside first
    prov = run.provenance()  # the code imported now is what runs, however long the wait
    started = wait_and_lock()
    env = run.environment()
    siblings = run.cpu_siblings()
    try:
        for stack, exp_ids in plan.items():
            at_start = {
                "hostLoad": [host_load() for _ in range(3)],
                "boostPct": cpu_boost(),
                "otherJobs": other_jobs(),
            }
            if at_start["boostPct"] < MIN_BOOST:
                run.log(f"the CPU stopped boosting ({at_start['boostPct']:.0f}%): stopping here")
                return 1
            run.stack_up(stack)
            try:
                for exp_id in exp_ids:
                    exp = run.EXPERIMENTS[exp_id]
                    folder = HERE / f"{a.date}-{exp_id}-w26"
                    sets = run.cpusets(stack)
                    environment = {
                        **env,
                        "images": {**env["images"], "service": run.exp_image(exp)},
                        "cpusets": sets,
                        **({"topology": run.topology(sets, siblings)} if siblings else {}),
                        "provenance": prov,
                        "lockTaken": started,
                        "atStackStart": at_start,
                        "beforeCalibration": {"hostLoad": host_load()},
                    }
                    save(folder, "environment", environment)
                    t0 = time.time()
                    cal = run.calibrate(exp)
                    cal["trial"] = {
                        "seconds": round(time.time() - t0, 1),
                        "by": OWNER,
                        "boostPctAfter": cpu_boost(),
                    }
                    save(folder, "calibration", cal)
                    run.log(f"{exp_id}: staircase {cal['staircaseRps']} written to {folder.name}")
            finally:
                run.stack_down(stack)
    finally:
        release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
