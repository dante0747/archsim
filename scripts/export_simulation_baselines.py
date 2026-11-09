"""Record the frozen engine's simulation baselines (parity/simulation/), or check them.

Stage 4 of the Java port (docs/proposals/java-25-port.md) measures the Java simulator against the
frozen engine (0.16.0) statistically: every template case (``examples/*.json``, the base design
and each scenario) with 20 seeds. This script simulates each case in full with its workload's
seed and the 19 after it, as a seed group runs them, and keeps each run's figures: the client
summary with its latencies, every part's summary, the events processed and both digests. It runs
once: after stage 5 the Python engine is deleted, and these files are the reference (ADR-0052;
``./gradlew :archsim-engine:parity`` judges the Java simulator by them).

Each run is bounded (an hour of wall-clock time and the engine's request limit), so one that
fails is recorded with its error and the rest go on; if no run finishes for 75 minutes, a process
hung, and the recording stops and says so. Each finished run is appended to
``build/simulation-baselines/runs.jsonl`` and logged with the time and the progress (also to
``progress.log`` there), so a stall shows within minutes and a stopped recording resumes where
it was. The files are written once every selected run is recorded.

Usage:
    uv run python scripts/export_simulation_baselines.py                 # record, resuming
    uv run python scripts/export_simulation_baselines.py --jobs 3        # processes (default 3)
    uv run python scripts/export_simulation_baselines.py --only queueing-lab --seeds 2 --check
"""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
import multiprocessing
import os
import sys
import time
from collections.abc import Callable, Iterable, Sequence
from concurrent.futures import FIRST_COMPLETED, Future, ProcessPoolExecutor, wait
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass
from datetime import datetime
from functools import cache
from pathlib import Path
from typing import Any, TextIO

from archsim_engine import run_simulation
from archsim_engine.engine import SimulationLimits, prepare_run
from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
OUT = ROOT / "parity" / "simulation"
WORK = ROOT / "build" / "simulation-baselines"
FORMAT = 1
GENERATOR = "scripts/export_simulation_baselines.py (archsim_engine 0.16.0)"
BASE = "base"
"""The case of a template's design as drawn; the others are its scenarios' ids."""
SEEDS = 20
JOBS = 3
LIMITS = SimulationLimits(max_wall_seconds=3600.0)
"""A run may take an hour, within the engine's request limit; one that takes longer fails."""
STALL_S = LIMITS.max_wall_seconds + 15 * 60
"""No run finishing for this long means a process hung: the recording stops."""
LATENCIES = ("latencyMs", "latencyWithFailuresMs")
PROVENANCE = ("eventsProcessed", "requestsGenerated", "inputDigest", "resultDigest")
Json = dict[str, Any]


@dataclass(frozen=True)
class Case:
    """A template's design as drawn or one of its scenarios, and its workload's seed."""

    template: str
    case: str
    seed: int


@dataclass(frozen=True)
class Run:
    """One recorded simulation of a case, with the seed it ran with."""

    template: str
    case: str
    seed: int


def cases(only: Sequence[str]) -> list[Case]:
    """Every template case, or those of the ``only`` templates, with its workload's seed."""
    found = []
    for path in sorted(EXAMPLES.glob("*.json")):
        if only and path.stem not in only:
            continue
        raw = _template(path.stem)
        for sid in [None, *(s["id"] for s in raw.get("scenarios") or [])]:
            seed = prepare_run(copy.deepcopy(raw), sid).resolved.workload.seed
            found.append(Case(path.stem, sid or BASE, seed))
    return found


def runs(selected: Sequence[Case], seeds: int) -> list[Run]:
    """Each case's runs, seed by seed: a stopped recording has every case's first seeds."""
    return [Run(c.template, c.case, c.seed + step) for step in range(seeds) for c in selected]


def figures(report: Json) -> Json:
    """What a run keeps of its report: counts, digests, the summary and each part's summary."""
    summary = report["summary"]
    return {
        **{key: report["provenance"][key] for key in PROVENANCE},
        "summary": {
            **_values(summary),
            **{key: summary[key] for key in LATENCIES if key in summary},
        },
        "nodes": {node["id"]: _values(node.get("summary") or {}) for node in report["nodes"]},
    }


def simulate(run: Run) -> Json:
    """One run's row: its figures, or the error it failed with, and the time it took."""
    row: Json = {"template": run.template, "case": run.case, "seed": run.seed}
    started = time.perf_counter()
    try:
        report = run_simulation(
            copy.deepcopy(_template(run.template)),
            scenario_id=None if run.case == BASE else run.case,
            limits=LIMITS,
            seed=run.seed,
        )
        row["figures"] = figures(report)
    except Exception as exc:  # a failed run is recorded with its error, and the others go on
        row["error"] = f"{type(exc).__name__}: {exc}"
    row["wallSeconds"] = round(time.perf_counter() - started, 1)
    return row


def recorded(log: Path) -> dict[Run, Json]:
    """The runs a recording has finished, from its log (a line cut off as it stopped is not)."""
    rows: dict[Run, Json] = {}
    if not log.exists():
        return rows
    for line in log.read_text(encoding="utf-8").splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        rows[Run(row["template"], row["case"], row["seed"])] = row
    return rows


def template_file(template: str, rows: Iterable[Json]) -> bytes:
    """A template's file: each case's runs by seed, as canonical JSON."""
    by_case: dict[str, list[Json]] = {}
    for row in sorted(rows, key=lambda r: (r["case"], r["seed"])):
        outcome = row["figures"] if "figures" in row else {"error": row["error"]}
        by_case.setdefault(row["case"], []).append({"seed": row["seed"], **outcome})
    return _canonical(
        {
            "format": FORMAT,
            "generator": GENERATOR,
            "engineVersion": ENGINE_VERSION,
            "reportVersion": REPORT_VERSION,
            "template": template,
            "cases": by_case,
        }
    )


def record(selected: list[Run], jobs: int, work: Path, out: Path) -> int:
    """Simulates the selected runs not yet recorded, then writes their templates' files."""
    work.mkdir(parents=True, exist_ok=True)
    log = work / "runs.jsonl"
    done = recorded(log)
    todo = [run for run in selected if run not in done]
    with (
        (work / "progress.log").open("a", encoding="utf-8") as progress,
        log.open("a", encoding="utf-8") as rows,
    ):

        def say(line: str) -> None:
            stamped = f"{datetime.now():%Y-%m-%d %H:%M:%S} {line}"
            print(stamped, flush=True)
            progress.write(stamped + "\n")
            progress.flush()

        if log.stat().st_size and not log.read_text(encoding="utf-8").endswith("\n"):
            rows.write("\n")  # after a line cut off as a recording stopped
        say(
            f"{len(selected) - len(todo)} of {len(selected)} runs recorded; {len(todo)} to go, {jobs} at a time"
        )
        if not _simulate_all(todo, jobs, rows, say, len(selected) - len(todo), len(selected)):
            return 2
    finished = recorded(log)
    by_template: dict[str, list[Json]] = {}
    for run in selected:
        by_template.setdefault(run.template, []).append(finished[run])
    write({name: template_file(name, rows) for name, rows in by_template.items()}, out)
    return 0


def write(files: dict[str, bytes], out: Path) -> None:
    """Writes the templates' files and updates the manifest; other templates' files stay."""
    manifest_path = out / "manifest.json"
    entries: dict[str, Json] = {}
    if manifest_path.exists():
        entries = {
            e["path"]: e for e in json.loads(manifest_path.read_text(encoding="utf-8"))["files"]
        }
    out.mkdir(parents=True, exist_ok=True)
    for name, data in sorted(files.items()):
        path = f"{name}.json.gz"
        (out / path).write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
        cases_ = json.loads(data)["cases"].values()
        entries[path] = {
            "path": path,
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "runs": sum(len(c) for c in cases_),
            "failed": sum(1 for c in cases_ for r in c if "error" in r),
        }
        print(f"wrote {out / path}")
    manifest = {
        "format": FORMAT,
        "engineVersion": ENGINE_VERSION,
        "reportVersion": REPORT_VERSION,
        "generator": GENERATOR,
        "note": "sha256 and bytes are of each file's uncompressed canonical JSON",
        "files": sorted(entries.values(), key=lambda e: e["path"]),
    }
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )


def check(selected: list[Run], out: Path) -> int:
    """Simulates the selected runs again; 1 if any differs from the committed files."""
    committed: dict[str, Json] = {}
    differing = 0
    for run in selected:
        if run.template not in committed:
            path = out / f"{run.template}.json.gz"
            if not path.exists():
                print(f"{path} is missing: record the baselines first")
                return 1
            committed[run.template] = json.loads(gzip.decompress(path.read_bytes()))
        row = simulate(run)
        fresh = {
            "seed": run.seed,
            **(row["figures"] if "figures" in row else {"error": row["error"]}),
        }
        kept = next(
            (
                r
                for r in committed[run.template]["cases"].get(run.case, [])
                if r["seed"] == run.seed
            ),
            None,
        )
        same = kept == fresh
        differing += not same
        print(
            f"{run.template}/{run.case} seed {run.seed}: {'same' if same else 'DIFFERS'}",
            flush=True,
        )
    print(f"{len(selected) - differing} of {len(selected)} runs match the committed baselines")
    return 1 if differing else 0


def _simulate_all(
    todo: list[Run], jobs: int, rows: TextIO, say: Callable[[str], None], before: int, total: int
) -> bool:
    started = time.monotonic()
    finished = 0
    pool = ProcessPoolExecutor(max_workers=jobs, mp_context=multiprocessing.get_context("spawn"))
    pending: set[Future[Json]] = {pool.submit(simulate, run) for run in todo}
    while pending:
        done, pending = wait(pending, timeout=STALL_S, return_when=FIRST_COMPLETED)
        if not done:
            say(
                f"STALLED: no run finished in {STALL_S / 60:.0f} min; stopping (run again to resume)"
            )
            for process in multiprocessing.active_children():
                process.kill()  # a spawned worker outlives its parent, and a resume would race it
            os._exit(2)  # a hung process would keep a graceful shutdown waiting
        for future in done:
            try:
                row = future.result()
            except BrokenProcessPool as exc:
                say(f"STOPPED: a simulating process died ({exc}); run again to resume")
                pool.shutdown(wait=False, cancel_futures=True)
                return False
            rows.write(json.dumps(row, sort_keys=True) + "\n")
            rows.flush()
            finished += 1
            left = (time.monotonic() - started) / finished * (len(todo) - finished)
            outcome = (
                f"{row['wallSeconds']:.1f} s" if "figures" in row else f"FAILED ({row['error']})"
            )
            say(
                f"[{before + finished:>4}/{total}] {row['template']}/{row['case']} seed {row['seed']}: "
                f"{outcome}, about {_duration(left)} left"
            )
    pool.shutdown()
    return True


def _values(table: Json) -> Json:
    """A summary's numbers, texts and flags; not its lists, its tables or its missing values."""
    return {k: v for k, v in table.items() if v is not None and not isinstance(v, (dict, list))}


@cache
def _template(name: str) -> Json:
    data: Json = json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))
    return data


def _canonical(value: Any) -> bytes:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode()


def _duration(seconds: float) -> str:
    minutes = round(seconds / 60)
    return f"{minutes // 60}h{minutes % 60:02d}m" if minutes >= 60 else f"{minutes}m"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument(
        "--only", nargs="*", default=[], metavar="TEMPLATE", help="these templates only"
    )
    parser.add_argument("--seeds", type=int, default=SEEDS, help=f"runs per case (default {SEEDS})")
    parser.add_argument("--jobs", type=int, default=JOBS, help=f"processes (default {JOBS})")
    parser.add_argument(
        "--check", action="store_true", help="simulate again; exit 1 if a run differs"
    )
    parser.add_argument("--work", type=Path, default=WORK, help="where the runs are logged")
    parser.add_argument("--out", type=Path, default=OUT, help="where the files are written")
    args = parser.parse_args(argv)
    selected = runs(cases(args.only), args.seeds)
    if args.check:
        return check(selected, args.out)
    return record(selected, args.jobs, args.work, args.out)


if __name__ == "__main__":
    sys.exit(main())
