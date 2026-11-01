"""W26 trials: protocol v6 calibrations of the four in-sample stacks, calibration only.

    uv run python benchmarks/results/trials/W26-trial.py [--only cache-aside ...] [--date 2026-11-01]

Development data for accuracy plan W26 (a staircase that climbs to 65% of the service's CPU, and
the CPU curve above it), never a benchmark run: nothing is predicted or measured, and run.py and
report.py never read results/trials/. Each experiment's calibration is ``run.calibrate`` as a
protocol v6 run calls it, written to ``<date>-<experiment>-w26/calibration.json`` with
``environment.json`` (the machine, the cpusets, the commit, the host's CPU load and the other jobs
running when each stack started).

Takes the benchmark lock (C:/Users/abarg/.archsim-bench-lock) once it is free and the host has
been under QUIET_LOAD % CPU for QUIET_S, holds it for every stack of the trial, and releases it
when the last stack is down. Only the in-sample experiments can run here.
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
    """Take the lock once it is free and the host has been quiet for QUIET_S."""
    last_log = 0.0
    while True:
        if not LOCK.exists() and (loads := quiet()):
            try:
                LOCK.mkdir()
            except FileExistsError:
                continue
            (LOCK / "owner.txt").write_text(f"{OWNER} {time.ctime()}\n", encoding="utf-8")
            run.log(f"lock taken; host CPU {statistics.mean(loads):.0f}% over {QUIET_S} s")
            return {"hostLoad": loads, "otherJobs": other_jobs()}
        if time.time() - last_log > 300:
            run.log(f"waiting for the lock and a quiet host ({holder()})")
            last_log = time.time()
        time.sleep(15)


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
            at_start = {"hostLoad": [host_load() for _ in range(3)], "otherJobs": other_jobs()}
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
                    cal["trial"] = {"seconds": round(time.time() - t0, 1), "by": OWNER}
                    save(folder, "calibration", cal)
                    run.log(f"{exp_id}: staircase {cal['staircaseRps']} written to {folder.name}")
            finally:
                run.stack_down(stack)
    finally:
        release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
