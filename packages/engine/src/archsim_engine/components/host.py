"""CPU threads that slow each other down (schema 1.7): hyperthreads and a shared machine.

A vCPU is usually a hardware thread, not a core: two threads share each physical core
(hyperthreads), and a machine's clock falls as more of its threads are busy (turbo and power
limits). So N busy vCPUs do less than N times the work of one. Two settings model it, both
empty by default (every vCPU a full core at a fixed speed, as before schema 1.7):

* ``architecture.host`` (``HostSpec``): the one machine every part with a CPU size runs on, for
  example docker compose on one VM, or a benchmark on a laptop. The parts' CPU work, the
  machine's own background and the CPU the clients' traffic costs on it outside the design's
  parts (a load generator on the same machine) all draw on its physical cores.
* ``siblingSpeed`` on a service, database or cache (``private_machine``): that part's own vCPUs
  are hyperthreads, two per physical core (a cloud instance; Graviton and other cores without
  hyperthreads have none). Ignored on a part that runs on ``architecture.host``.

**Speed of a thread.** With ``busy`` threads wanting the CPU (the one in question included) on a
machine of ``P`` physical cores with ``T`` threads each, a thread runs at
``clock x sharing x turns`` of its speed alone on an idle machine:

* ``clock = 1 - (1 - allCoreClock) x (min(busy, P T) - 1) / (P T - 1)``: the clock falls in a
  straight line from one busy thread to all of them (power rises with every busy thread);
* ``sharing = 1 - (1 - siblingSpeed) x min(1, 2 (busy - P) / busy)`` when ``busy > P`` and
  ``T = 2``: the scheduler puts threads on idle cores first, so only past ``P`` busy threads do
  they share a core, and a thread sharing its core runs at ``siblingSpeed``;
* ``turns = min(1, P T / busy)``: past ``P x T`` busy threads they take turns.

In the simulation a piece of CPU work takes ``work / speed`` at the number of busy threads when
it starts (its part's executors on that machine, plus the background and the clients' load at
that moment); it occupies its vCPU for that long, so the CPU time per request a part reports
rises as the machine fills, as a container's CPU time does. The analytic copies (capacity,
the instant estimate, sizing, the review) take the mean busy threads at the load they reason
about: ``busy = base / speed(busy) + other`` (``solve``), where ``base`` is the threads the
parts' CPU work needs at full speed.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from archsim_engine.metrics import Gauge, MeanAccumulator, Timeline


@dataclass(frozen=True)
class Machine:
    """The CPU of one machine, as the speed formula needs it."""

    cores: float
    threads_per_core: int = 2
    sibling_speed: float = 1.0
    all_core_clock: float = 1.0
    other_busy_threads: float = 0.0
    client_cpu_ms: float = 0.0

    @property
    def threads(self) -> float:
        return self.cores * self.threads_per_core

    def speed(self, busy: float) -> float:
        """Mean speed of a busy thread when ``busy`` threads want the CPU (it included)."""
        p = max(self.cores, 1e-9)
        if busy <= 1.0:
            return 1.0
        clock = 1.0
        n = self.threads
        if n > 1.0 and self.all_core_clock < 1.0:
            clock = 1.0 - (1.0 - self.all_core_clock) * (min(n, busy) - 1.0) / (n - 1.0)
        sharing = 1.0
        if self.threads_per_core > 1 and busy > p and self.sibling_speed < 1.0:
            sharing = 1.0 - (1.0 - self.sibling_speed) * min(1.0, 2.0 * (busy - p) / busy)
        turns = min(1.0, self.threads / busy)
        return clock * sharing * turns

    def other(self, client_rps: float) -> float:
        """Threads busy outside the design's parts: the background and the clients' traffic."""
        return self.other_busy_threads + max(0.0, client_rps) * self.client_cpu_ms / 1000.0

    def solve(self, base_busy: float, client_rps: float = 0.0) -> tuple[float, float]:
        """(busy threads, their speed) when the parts' CPU work needs ``base_busy`` threads at
        full speed: the ``busy`` with ``busy = base_busy / speed(busy) + other``. Past what the
        machine can do the busy threads grow without bound; they are capped at 64 times its
        threads (speed then is the machine's share for that many)."""
        other = self.other(client_rps)
        lo = max(0.0, base_busy) + other
        if base_busy <= 0:
            return lo, self.speed(lo)
        hi = 64.0 * max(self.threads, 1.0) + lo

        def gap(d: float) -> float:
            return base_busy / self.speed(d) + other - d

        if gap(hi) > 0:
            return hi, self.speed(hi)
        for _ in range(80):
            mid = 0.5 * (lo + hi)
            if gap(mid) > 0:
                lo = mid
            else:
                hi = mid
        return hi, self.speed(hi)

    def scale_filling(
        self, base_busy: float, own_base: float, own_threads: float, client_rps: float = 0.0
    ) -> float:
        """The multiple ``k`` of a load at which one part's threads fill: the part needs
        ``own_base`` threads at full speed and has ``own_threads``; the whole machine's parts
        need ``base_busy`` (the part's included) and the clients send ``client_rps``, and all of
        it grows in proportion. ``inf`` without CPU work."""
        if own_base <= 0 or own_threads <= 0:
            return math.inf

        def fills(k: float) -> bool:
            _, s = self.solve(k * base_busy, k * client_rps)
            return k * own_base / s >= own_threads

        lo, hi = 0.0, own_threads / own_base  # at full speed it fills at hi
        if fills(lo + 1e-12):
            return 0.0
        while not fills(hi):  # pragma: no cover - speed never exceeds 1
            hi *= 2.0
        for _ in range(80):
            mid = 0.5 * (lo + hi)
            if fills(mid):
                hi = mid
            else:
                lo = mid
        return hi


def host_machine(host: Any) -> Machine | None:
    """The ``Machine`` of an ``architecture.host`` (a ``HostSpec`` or its JSON), or None."""
    if host is None:
        return None
    get = host.get if isinstance(host, Mapping) else lambda k, d=None: getattr(host, k, d)
    cores = get("physicalCores") if isinstance(host, Mapping) else get("physical_cores")

    def val(camel: str, snake: str, default: float) -> float:
        v = get(camel) if isinstance(host, Mapping) else get(snake)
        return float(v) if isinstance(v, int | float) and not isinstance(v, bool) else default

    if not isinstance(cores, int | float) or isinstance(cores, bool) or cores <= 0:
        return None
    return Machine(
        cores=float(cores),
        threads_per_core=int(val("threadsPerCore", "threads_per_core", 2)),
        sibling_speed=val("siblingSpeed", "sibling_speed", 0.6),
        all_core_clock=val("allCoreClock", "all_core_clock", 1.0),
        other_busy_threads=val("otherBusyThreads", "other_busy_threads", 0.0),
        client_cpu_ms=val("clientCpuMs", "client_cpu_ms", 0.0),
    )


def private_machine(vcpu: float | None, sibling_speed: float | None) -> Machine | None:
    """A part's own vCPUs as hyperthreads, two per physical core (``siblingSpeed``), or None
    when it has no CPU size, fewer than 2 vCPU or no sibling speed."""
    if vcpu is None or sibling_speed is None or vcpu < 2 or sibling_speed >= 1.0:
        return None
    return Machine(cores=vcpu / 2.0, threads_per_core=2, sibling_speed=sibling_speed)


def full_load_threads(m: Machine) -> float:
    """What all of a machine's threads do together when every one is busy, in threads alone."""
    return m.threads * m.speed(m.threads)


class HostRuntime:
    """One machine's threads during a simulation: shared by the parts on ``architecture.host``
    (``shared``), or private to one part's instance (``siblingSpeed``). ``start`` counts a
    thread in and returns how long its work takes; ``stop`` counts it out."""

    __slots__ = (
        "busy",
        "busy_gauge",
        "env",
        "machine",
        "rate_at",
        "shared",
        "speeds",
        "tl",
        "work",
    )

    def __init__(
        self,
        env: Any,
        machine: Machine,
        tl: Timeline | None = None,
        rate_at: Any = None,
    ) -> None:
        self.env = env
        self.machine = machine
        self.shared = tl is not None
        self.tl = tl
        self.busy = 0
        self.work = 0.0  # CPU work started inside the window, at full speed (ms)
        self.rate_at = rate_at  # the clients' offered rate (req/s) at a time in seconds
        self.busy_gauge = Gauge(tl) if tl is not None else None
        self.speeds = MeanAccumulator(tl) if tl is not None else None

    def client_rps(self, now_ms: float) -> float:
        return float(self.rate_at(now_ms / 1000.0)) if self.rate_at is not None else 0.0

    def start(self, work_ms: float) -> float:
        """Count a thread in for ``work_ms`` of CPU work and return how long it takes."""
        now = self.env.now
        self.busy += 1
        if self.busy_gauge is not None:
            self.busy_gauge.add(1, now)
        m = self.machine
        s = m.speed(self.busy + m.other(self.client_rps(now)))
        if self.speeds is not None:
            self.speeds.record(now, s)
        if self.tl is not None and self.tl.window_start <= now < self.tl.window_end:
            self.work += work_ms
        return work_ms / s

    def window_base(self) -> float:
        """The parts' CPU work over the window, in threads at full speed."""
        tl = self.tl
        if tl is None or tl.window_end <= tl.window_start:
            return 0.0
        return self.work / (tl.window_end - tl.window_start)

    def window_client_rps(self) -> float:
        """The clients' mean offered rate over the window (req/s)."""
        tl = self.tl
        if tl is None or self.rate_at is None:
            return 0.0
        t0, t1 = tl.window_start / 1000.0, tl.window_end / 1000.0
        steps = 50
        return sum(self.rate_at(t0 + (i + 0.5) * (t1 - t0) / steps) for i in range(steps)) / steps

    def stop(self) -> None:
        self.busy -= 1
        if self.busy_gauge is not None:
            self.busy_gauge.add(-1, self.env.now)

    def finalize(self, t_end: float) -> None:
        if self.busy_gauge is not None:
            self.busy_gauge.finalize(t_end)

    def summary(self) -> dict[str, Any]:
        """The machine over the measurement window: the parts' busy threads, the background and
        clients' threads, and the mean speed CPU work ran at."""
        m = self.machine
        parts = self.busy_gauge.window_mean() if self.busy_gauge is not None else None
        other = m.other(self.window_client_rps())
        mean_speed = self.speeds.window_mean() if self.speeds is not None else None
        return {
            "physicalCores": m.cores,
            "threads": m.threads,
            "partsBusyThreads": round(parts, 4) if parts is not None else None,
            "otherBusyThreads": round(other, 4),
            "busyThreads": round((parts or 0.0) + other, 4),
            "meanSpeed": round(mean_speed, 4) if mean_speed is not None else None,
        }


def machine_capacity(
    machine: HostRuntime | None, full_speed: float, own_threads: float, observed_rps: float
) -> float:
    """A part's CPU ceiling (req/s) on its machine, from ``full_speed``: the ceiling were every
    one of its ``own_threads`` vCPUs to run at full speed. Its own hyperthreads: every vCPU
    busy, each at the speed of a full machine. The design's host: the load multiple at which
    its threads fill while every part's load and the clients' grow in proportion to what the
    window saw (``observed_rps``, the part's arrival rate); unloaded, the part alone on it."""
    if machine is None or full_speed <= 0 or not math.isfinite(full_speed) or own_threads <= 0:
        return full_speed
    m = machine.machine
    if not machine.shared:
        return full_speed * m.speed(m.threads)
    own_ms = own_threads * 1000.0 / full_speed  # CPU work per request at full speed
    if observed_rps > 0:
        own_base = observed_rps * own_ms / 1000.0
        base = max(machine.window_base(), own_base)
        k = m.scale_filling(base, own_base, own_threads, machine.window_client_rps())
        return k * observed_rps
    k = m.scale_filling(own_threads, own_threads, own_threads)
    return k * full_speed


# -- the static analyses (the instant estimate, sizing, the review) ----------------------------


def _json_busy(
    node_type: str, cfg: Mapping[str, Any], read: float, write: float, need_cores: bool = True
) -> tuple[float, float, float] | None:
    """(busy threads at full speed over all instances, busy threads of one instance, its vCPU)
    of a part with a CPU size at ``read`` + ``write`` req/s, from its JSON config; None for a
    part without a CPU size or CPU model."""
    from archsim_engine.components.load_curve import curve_of, load_factor
    from archsim_engine.load import mean_ms, num

    cores = num(cfg.get("cpuCores"))
    if cores is None or cores <= 0:
        if need_cores:
            return None
        cores = 1.0  # sizing: the part gets a CPU size, so it runs on the host
    total = read + write
    if node_type == "service":
        if cfg.get("platform") == "serverless":
            return None
        share = num(cfg.get("cpuShare"))
        share = 1.0 if share is None else share
        reps = max(1.0, num(cfg.get("replicas")) or 1.0)
        if cfg.get("autoscaling") is True:
            reps = max(reps, num(cfg.get("maxReplicas")) or 1.0)
        ms = mean_ms(cfg.get("serviceTimeMs")) * share
        ms *= load_factor(curve_of(cfg.get("serviceTimeByLoad")), total / reps)
        busy = total * ms / 1000.0
        return busy, busy / reps, cores
    if node_type == "database":
        share = num(cfg.get("cpuShare"))
        share = 0.5 if share is None else share
        shards = max(1.0, num(cfg.get("shards")) or 1.0)
        copies = 1.0 + max(0.0, num(cfg.get("readReplicas")) or 0.0)
        r_ms = mean_ms(cfg.get("readServiceTimeMs"))
        w_ms = mean_ms(cfg.get("writeServiceTimeMs"))
        busy = (read * r_ms + write * w_ms) * share / 1000.0
        primary = ((read / copies) * r_ms + write * w_ms) * share / 1000.0 / shards
        return busy, primary, cores
    if node_type == "cache":
        shards = max(1.0, num(cfg.get("shards")) or 1.0)
        busy = total * mean_ms(cfg.get("serviceTimeMs")) / 1000.0
        return busy, busy / shards, cores
    return None


def machine_speeds(
    host: Any,
    parts: Mapping[str, tuple[str, Mapping[str, Any], float, float]],
    client_rps: float,
    sizing: bool = False,
) -> dict[str, float]:
    """Each part's thread speed at a load (``parts``: id -> (type, JSON config, read req/s,
    write req/s)): every part with a CPU size at the design host's speed for all their busy
    threads, the background and the clients' traffic; without a host, a part with
    ``siblingSpeed`` at its own hyperthreads' speed for its busiest instance. Parts at full
    speed are left out. ``sizing``: a part without a CPU size counts too (it is about to get
    one), and a part's own hyperthreads are left to the CPU size sizing picks."""
    machine = host_machine(host)
    busy = {
        nid: b
        for nid, (t, cfg, read, write) in parts.items()
        if (b := _json_busy(t, cfg, read, write, need_cores=not sizing)) is not None
    }
    out: dict[str, float] = {}
    if machine is not None:
        _, speed = machine.solve(sum(b[0] for b in busy.values()), client_rps)
        if speed < 1.0:
            out = dict.fromkeys(busy, speed)
        return out
    if sizing:
        return out
    from archsim_engine.load import num

    for nid, (_, one, cores) in busy.items():
        own = private_machine(cores, num(parts[nid][1].get("siblingSpeed")))
        if own is not None:
            _, speed = own.solve(one)
            if speed < 1.0:
                out[nid] = speed
    return out
