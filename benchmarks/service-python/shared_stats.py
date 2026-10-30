"""Request statistics shared by gunicorn's worker processes (the python-mixed benchmark service).

Each sync worker is a process of its own, and /stats is answered by whichever worker accepts it,
so the numbers live in one shared memory file (/dev/shm) that every worker maps: a slot per
worker with its request count, its garbage collections and its timing samples (a fixed array per
timing name). /stats adds the slots up; /reset zeroes them. CPU is the container's own (cgroup
``cpu.stat``), so it counts every worker and gunicorn's master, as ``docker stats`` would.

gunicorn's master creates the file before it forks the workers (``create``, from
gunicorn_conf.py); each worker maps it and claims a slot when it imports the app.
"""

from __future__ import annotations

import gc
import math
import mmap
import os
import platform
import time
from pathlib import Path
from typing import Any

PATH = Path(os.environ.get("STATS_PATH", "/dev/shm/archsim-bench-stats"))
NAMES = ("handler", "own", "db_read", "db_write", "db_write_async")
INDEX = {name: i for i, name in enumerate(NAMES)}
CAPACITY = 32_768
"""Samples kept per worker and timing name (a 30 s pass at 1,000 req/s over 10 workers is 3,000)."""
SLOTS = 20
"""Worker slots: twice gunicorn's 10 workers, so a restarted worker finds a free one."""
HEADER = 2
"""int64s before the slots: the container's CPU (usec) and the wall clock (ns) at the reset."""
SLOT_INTS = 4 + len(NAMES)
"""Each slot's int64s: pid, requests, collections, collection ns, then a sample count per name."""
INTS = HEADER + SLOTS * SLOT_INTS
SIZE = INTS * 8 + SLOTS * len(NAMES) * CAPACITY * 8


def cgroup_cpu_usec() -> int | None:
    """CPU the container has used (cgroup v2, else v1), in microseconds; None when unreadable."""
    try:
        for line in Path("/sys/fs/cgroup/cpu.stat").read_text().splitlines():
            if line.startswith("usage_usec "):
                return int(line.split()[1])
    except OSError:
        pass
    try:
        return int(Path("/sys/fs/cgroup/cpuacct/cpuacct.usage").read_text()) // 1000
    except (OSError, ValueError):
        return None


def alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def quantile(values: list[float], q: float) -> float | None:
    """The same quantile as server.js: the ceil(q * n)-th smallest value."""
    if not values:
        return None
    return values[min(len(values) - 1, max(0, math.ceil(q * len(values)) - 1))]


def slot_marker(slot: int) -> Path:
    return PATH.with_name(f"{PATH.name}.slot{slot}")


def create() -> None:
    """A zeroed statistics file of the right size and no slot taken (gunicorn's master, before
    any worker)."""
    with PATH.open("wb") as f:
        f.truncate(SIZE)
    for s in range(SLOTS):
        slot_marker(s).unlink(missing_ok=True)
    Shared().reset()


class Shared:
    """The statistics file, mapped into this process."""

    def __init__(self) -> None:
        self.fd = os.open(PATH, os.O_RDWR)
        view = memoryview(mmap.mmap(self.fd, SIZE))
        self.ints = view[: INTS * 8].cast("q")
        self.floats = view[INTS * 8 :].cast("d")
        self.slot = -1
        self.gc_started = 0

    @staticmethod
    def base(slot: int) -> int:
        return HEADER + slot * SLOT_INTS

    def claim(self) -> None:
        """Take the first free slot for this process (creating its marker file exclusively, so
        two workers starting at once never share one) and time its garbage collections from now
        on. A worker that dies keeps its slot, so the requests it served stay counted."""
        for s in range(SLOTS):
            try:
                os.close(os.open(slot_marker(s), os.O_CREAT | os.O_EXCL | os.O_WRONLY))
            except FileExistsError:
                continue
            self.ints[self.base(s)] = os.getpid()
            self.slot = s
            gc.callbacks.append(self.on_gc)
            return
        raise RuntimeError(f"no free statistics slot among {SLOTS}")

    def on_gc(self, phase: str, info: dict[str, Any]) -> None:
        if phase == "start":
            self.gc_started = time.perf_counter_ns()
            return
        b = self.base(self.slot)
        self.ints[b + 2] += 1
        self.ints[b + 3] += time.perf_counter_ns() - self.gc_started

    def record(self, name: str, ms: float) -> None:
        k = INDEX[name]
        i = self.base(self.slot) + 4 + k
        n = self.ints[i]
        if n < CAPACITY:
            self.floats[(self.slot * len(NAMES) + k) * CAPACITY + n] = ms
            self.ints[i] = n + 1

    def request(self, handler_ms: float, own_ms: float) -> None:
        self.ints[self.base(self.slot) + 1] += 1
        self.record("handler", handler_ms)
        self.record("own", own_ms)

    def reset(self) -> None:
        """Zero every worker's numbers (their slots stay theirs)."""
        for s in range(SLOTS):
            b = self.base(s)
            for i in range(b + 1, b + SLOT_INTS):
                self.ints[i] = 0
        cpu = cgroup_cpu_usec()
        self.ints[0] = -1 if cpu is None else cpu
        self.ints[1] = time.time_ns()

    def snapshot(self) -> dict[str, Any]:
        slots = [self.base(s) for s in range(SLOTS)]
        requests = sum(self.ints[b + 1] for b in slots)
        cpu, cpu0 = cgroup_cpu_usec(), self.ints[0]
        cpu_ms = None if cpu is None or cpu0 < 0 else (cpu - cpu0) / 1000
        timings: dict[str, Any] = {}
        for k, name in enumerate(NAMES):
            values: list[float] = []
            for s, b in enumerate(slots):
                start = (s * len(NAMES) + k) * CAPACITY
                values += self.floats[start : start + self.ints[b + 4 + k]].tolist()
            if not values:
                continue
            values.sort()
            timings[name] = {
                "count": len(values),
                "mean": sum(values) / len(values),
                "median": quantile(values, 0.5),
                "p95": quantile(values, 0.95),
                "p99": quantile(values, 0.99),
            }
        return {
            "role": "api",
            "runtime": f"python {platform.python_version()}, gunicorn sync workers",
            "workers": sum(1 for b in slots if alive(self.ints[b])),
            "requests": requests,
            "wallMs": (time.time_ns() - self.ints[1]) / 1e6,
            "cpuMs": cpu_ms,
            "cpuMsPerRequest": cpu_ms / requests if cpu_ms is not None and requests else None,
            "cpuSource": "cgroup",
            "gc": {
                "collections": sum(self.ints[b + 2] for b in slots),
                "pauseMs": sum(self.ints[b + 3] for b in slots) / 1e6,
            },
            "counters": {},
            "timings": timings,
        }
