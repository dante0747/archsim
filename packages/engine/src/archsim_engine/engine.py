"""Simulation entry point.

``run_simulation(document, scenario_id=...)`` resolves the scenario, validates the model, builds
runtime components, generates arrivals and runs SimPy in chunks so callers can observe progress,
cancel, and enforce wall-clock limits. It returns a JSON-serialisable report.
"""

from __future__ import annotations

import math
import time
from array import array
from bisect import bisect_left
from collections.abc import Callable, Generator
from dataclasses import dataclass, field
from itertools import accumulate
from typing import Any, Literal

import simpy
from simpy.core import URGENT, EmptySchedule, StopSimulation

from archsim_engine.components import create_default_registry
from archsim_engine.components.traffic_source import TrafficSourceConfig, TrafficSourceRuntime
from archsim_engine.issues import DocumentInvalidError, error
from archsim_engine.metrics import Timeline
from archsim_engine.registry import ComponentRegistry
from archsim_engine.report import build_report
from archsim_engine.runtime import EdgeRuntime, Request, SimContext
from archsim_engine.scenarios import load_document, resolve_scenario_sized
from archsim_engine.schema import ArchitectureDocument, ConstantPattern, Workload
from archsim_engine.sizing import PartSize
from archsim_engine.validation import DEFAULT_MAX_AMPLIFICATION, ValidationResult, validate_document

ProgressFn = Callable[[float], None]
CancelFn = Callable[[], bool]

_DEFAULT_REGISTRY: ComponentRegistry | None = None

ASYNC_MIN_DRAIN_S = 60.0  # automatic drain when the model has message queues
TRACE_TARGET = 1200  # traced requests per run (sampled evenly); the report keeps ~40 of them


def default_registry() -> ComponentRegistry:
    global _DEFAULT_REGISTRY
    if _DEFAULT_REGISTRY is None:
        _DEFAULT_REGISTRY = create_default_registry()
    return _DEFAULT_REGISTRY


@dataclass(frozen=True)
class SimulationLimits:
    """Hard bounds that keep a run from consuming unbounded time or memory."""

    max_requests: int = 2_000_000
    max_wall_seconds: float = 300.0
    progress_chunks: int = 200
    max_amplification: float | None = DEFAULT_MAX_AMPLIFICATION
    """Most component invocations one client request may cause (fan-out x retries); None: no bound."""
    check_every_events: int = 4096
    """How often (in processed events) the wall clock and the cancel flag are checked."""


class SimulationCancelledError(Exception):
    pass


class SimulationLimitError(Exception):
    pass


@dataclass
class PreparedRun:
    document: ArchitectureDocument
    resolved: ArchitectureDocument
    scenario_id: str | None
    scenario_name: str | None
    overrides: list[dict[str, Any]]
    validation: ValidationResult
    sizes: dict[str, PartSize] = field(default_factory=dict)


MAX_SEED = 2**32 - 1


def with_seed(resolved: ArchitectureDocument, seed: int | None) -> ArchitectureDocument:
    """``resolved`` with its workload's seed replaced (``None``: unchanged). Raises
    ``DocumentInvalidError`` for a seed outside 0 to 2**32 - 1."""
    if seed is None or seed == resolved.workload.seed:
        return resolved
    if not 0 <= seed <= MAX_SEED:
        raise DocumentInvalidError(
            [error("workload.seed", f"Seeds go from 0 to {MAX_SEED:,}; got {seed:,}.")]
        )
    workload = resolved.workload.model_copy(update={"seed": seed})
    return resolved.model_copy(update={"workload": workload})


def prepare_run(
    document: dict[str, Any] | ArchitectureDocument,
    scenario_id: str | None = None,
    registry: ComponentRegistry | None = None,
    limits: SimulationLimits | None = None,
    seed: int | None = None,
) -> PreparedRun:
    """Load, resolve and validate. ``seed`` replaces the workload's seed after the scenario is
    applied. Raises ``DocumentInvalidError`` or ``SimulationLimitError``."""
    registry = registry or default_registry()
    limits = limits or SimulationLimits()
    doc = load_document(document)
    resolved, scenario, sizes = resolve_scenario_sized(doc, scenario_id, registry)
    resolved = with_seed(resolved, seed)
    result = validate_document(resolved, registry, max_amplification=limits.max_amplification)
    if not result.valid:
        raise DocumentInvalidError(result.issues)
    expected = resolved.workload.expected_arrivals()
    if expected > limits.max_requests:
        raise SimulationLimitError(
            f"This run would generate about {expected:,.0f} requests, above the limit of "
            f"{limits.max_requests:,}. Reduce RPS or duration."
        )
    return PreparedRun(
        document=doc,
        resolved=resolved,
        scenario_id=scenario.id if scenario else None,
        scenario_name=scenario.name if scenario else None,
        overrides=[o.model_dump(mode="json", by_alias=True) for o in scenario.overrides]
        if scenario
        else [],
        validation=result,
        sizes=sizes,
    )


class ZipfSampler:
    """Samples key ranks 0..n-1 with probability proportional to 1 / (rank+1)^s."""

    def __init__(self, n: int, s: float, rng: Any) -> None:
        self.cdf = array("d", accumulate(k**-s for k in range(1, n + 1)))
        self.total = self.cdf[-1]
        self.rng = rng

    def sample(self) -> int:
        return bisect_left(self.cdf, self.rng.random() * self.total)


def auto_drain_s(source_cfg: TrafficSourceConfig) -> float:
    """Time after the last arrival by which every client has given up or finished."""
    attempts = source_cfg.max_attempts
    backoff_total = source_cfg.retry_backoff_ms * (2 ** (attempts - 1) - 1)
    deadline_ms = source_cfg.timeout_ms * attempts + backoff_total
    return min(deadline_ms / 1000.0 + 1.0, 600.0)


def _arrivals(
    env: simpy.Environment,
    wl: Workload,
    ctx: SimContext,
    sources: list[TrafficSourceRuntime],
    shares: list[float],
    tl: Timeline,
    keys: ZipfSampler | None,
) -> Generator[simpy.Event, Any, None]:
    peak = wl.peak_rate()
    if peak <= 0:
        return
    arr_rng = ctx.rng("arrivals")
    thin_rng = ctx.rng("arrivals:thinning")
    mix_rng = ctx.rng("mix")
    # Several client groups split one arrival stream by share. A single group draws nothing
    # extra, so single-client runs are unchanged.
    pick_rng = ctx.rng("clients") if len(sources) > 1 else None
    cum = list(accumulate(shares))
    duration = wl.duration_s * 1000.0
    poisson = wl.arrival_process == "poisson"
    steady = isinstance(wl.pattern, ConstantPattern)
    # Every n-th request carries a trace: deterministic, draws no random numbers.
    trace_every = max(1, int(wl.expected_arrivals() // TRACE_TARGET))
    rid = 0
    t = 0.0
    while True:
        if poisson:
            # Non-homogeneous Poisson process by thinning (Lewis & Shedler).
            t += arr_rng.expovariate(peak / 1000.0)
            if t >= duration:
                return
            if not steady and thin_rng.random() * peak >= wl.rate_at(t / 1000.0):
                continue
        else:
            rate = wl.rate_at(t / 1000.0)
            if rate <= 0:
                t += 10.0
                if t >= duration:
                    return
                continue
            t += 1000.0 / rate
            if t >= duration:
                return
        yield env.timeout(t - env.now)
        rid += 1
        op = "read" if mix_rng.random() < wl.read_ratio else "write"
        key = keys.sample() if keys is not None else 0
        now = env.now
        req = Request(rid, op, key, now, tl.window_start <= now < tl.window_end)
        if rid % trace_every == 0:
            req.trace = []
        if pick_rng is None:
            src = sources[0]
        else:
            src = sources[min(bisect_left(cum, pick_rng.random() * cum[-1]), len(sources) - 1)]
        env.process(src.submit(req))


def _run_until(env: simpy.Environment, at: float, every: int, check: Callable[[], None]) -> int:
    """``env.run(until=at)`` with ``check()`` called every ``every`` processed events, so limits
    and cancellation hold inside a chunk too (one request of a heavily fanned-out design can
    process millions of events). Mirrors SimPy's own loop, stop event included, so results are
    identical. Returns the number of events processed."""
    until = simpy.Event(env)
    until._ok = True
    until._value = None
    env.schedule(until, URGENT, at - env.now)
    assert until.callbacks is not None
    until.callbacks.append(StopSimulation.callback)
    step = env.step
    left = every
    done = 0
    try:
        while True:
            step()
            left -= 1
            if not left:
                done += every
                left = every
                check()
    except (StopSimulation, EmptySchedule):
        pass
    return done + (every - left)


SAMPLE_MIN_S = 10.0
"""Sampled mode simulates at least this much steady time after the warm-up..."""
SAMPLE_MIN_REQUESTS = 50_000
"""...and at least this many arrivals, so its percentiles rest on enough requests."""
SAMPLE_MAX_SHARE = 0.5
"""A run is sampled only when the sample is at most half of it (else it is simulated whole)."""


def _secs(v: float) -> str:
    return f"{v:,.0f} s" if v >= 10 else f"{v:.1f} s"


def quick_check(wl: Workload) -> dict[str, Any]:
    """Can sampled mode (the web app's "Quick check (estimated)") answer this workload? It needs
    a constant rate, no scheduled faults, and a run long enough that the warm-up plus a steady
    sample is at most half of it. ``reason`` says why not, or what it will simulate.

    ``{eligible, reason, requestedS, simulatedS, sampleS}`` (the last two None when not
    eligible). Even an eligible run is simulated whole if its sample turns out not steady."""
    duration = wl.duration_s
    out: dict[str, Any] = {
        "eligible": False,
        "reason": "",
        "requestedS": duration,
        "simulatedS": None,
        "sampleS": None,
    }
    if not isinstance(wl.pattern, ConstantPattern):
        out["reason"] = (
            f"Quick check needs constant traffic; this run's traffic is a {wl.pattern.kind} "
            "pattern, so it is simulated whole."
        )
        return out
    if wl.faults:
        n = len(wl.faults)
        out["reason"] = (
            f"Quick check needs a run without planned failures; this one has {n} "
            f"planned failure{'s' if n != 1 else ''}, so it is simulated whole."
        )
        return out
    if wl.rps <= 0:
        out["reason"] = "There is no traffic to sample."
        return out
    sample = max(SAMPLE_MIN_S, SAMPLE_MIN_REQUESTS / wl.rps)
    total = wl.warmup_s + sample
    if total > SAMPLE_MAX_SHARE * duration:
        out["reason"] = (
            f"The run is too short to gain from a quick check: the warm-up and a steady sample "
            f"({_secs(total)}) would be more than half of its {_secs(duration)}. Runs of "
            f"{_secs(math.ceil(total / SAMPLE_MAX_SHARE))} or longer qualify."
        )
        return out
    out.update(
        eligible=True,
        simulatedS=total,
        sampleS=sample,
        reason=(
            f"Simulates the {_secs(wl.warmup_s)} warm-up and a {_secs(sample)} steady sample "
            f"instead of all {_secs(duration)}, then answers from the sample if it is steady."
        ),
    )
    return out


def sample_seconds(wl: Workload) -> float | None:
    """How long sampled mode simulates (warm-up plus a steady sample), or None when the run must
    be simulated whole: a load that changes over time, scheduled faults, or a run too short to
    gain from sampling (see ``quick_check``)."""
    qc = quick_check(wl)
    return float(qc["simulatedS"]) if qc["eligible"] else None


def stationary(report: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
    """Did the sample reach a steady state? The measured window's two halves must agree:
    throughput within 3% and at least 97% of the offered load (nothing piling up), p95 within
    15%, and no node's queue, backlog or replica count still growing."""
    ts = report["timeseries"]
    bucket = ts["bucketS"]
    w = report["window"]
    i0 = math.ceil(w["warmupS"] / bucket)
    i1 = max(i0 + 2, math.floor(w["durationS"] / bucket))

    def halves(series: list[Any] | None) -> tuple[float, float] | None:
        vals = [v for v in (series or [])[i0:i1] if v is not None]
        if len(vals) < 4:
            return None
        h = len(vals) // 2
        return sum(vals[:h]) / h, sum(vals[h:]) / (len(vals) - h)

    checks: dict[str, Any] = {}
    thr, off, p95 = (
        halves(ts["throughputRps"]),
        halves(ts["offeredRps"]),
        halves(ts["latencyP95Ms"]),
    )
    if thr is None or off is None or p95 is None:
        return False, {"reason": "too few time buckets to judge"}
    checks["throughputDrift"] = round(abs(thr[1] - thr[0]) / max(thr[0], 1e-9), 4)
    checks["keepsUp"] = round((thr[0] + thr[1]) / max(off[0] + off[1], 1e-9), 4)
    checks["p95Drift"] = round(abs(p95[1] - p95[0]) / max(p95[0], 1e-9), 4)
    growing = []
    for n in report["nodes"]:
        for key in ("queueLength", "backlog", "lag", "replicas", "nodes"):
            h = halves((n.get("timeseries") or {}).get(key))
            if h is not None and h[1] > h[0] * 1.2 + 0.5:
                growing.append(f"{n['id']}.{key}")
    checks["growing"] = growing
    ok = (
        checks["throughputDrift"] <= 0.03
        and checks["keepsUp"] >= 0.97
        and checks["p95Drift"] <= 0.15
        and not growing
    )
    return ok, checks


def run_simulation(
    document: dict[str, Any] | ArchitectureDocument,
    *,
    scenario_id: str | None = None,
    registry: ComponentRegistry | None = None,
    limits: SimulationLimits | None = None,
    progress: ProgressFn | None = None,
    should_cancel: CancelFn | None = None,
    mode: Literal["full", "sampled"] = "full",
    seed: int | None = None,
) -> dict[str, Any]:
    """Run one simulation and return its report (see ``docs/simulation-model.md``).

    ``seed`` replaces the workload's seed (after the scenario is applied): the API's seed groups
    run one document with seeds base to base + N - 1, and a rerun with the same seed reproduces
    the run exactly (the report's input, and so its ``inputDigest``, carries the seed used).

    ``mode="sampled"`` (hybrid): for a steady load (constant rate, no faults) that runs much
    longer than it needs to settle, simulate the warm-up and a steady sample (at least 10 s and
    50,000 arrivals) in full, check the sample is stationary, and answer the whole run from it:
    rates, success and percentiles of a stationary system do not change with run length. The
    report's ``simulationMode`` says which mode answered and why; a run that does not qualify
    is simulated whole, and one whose sample is not stationary is simulated whole with
    ``simulationMode.kind == "full"`` saying why."""
    fallback: dict[str, Any] | None = None
    if mode == "sampled":
        sampled, fallback = _run_sampled(
            document, scenario_id, registry, limits, progress, should_cancel, seed
        )
        if sampled is not None:
            return sampled
    report = _run_full(document, scenario_id, registry, limits, progress, should_cancel, seed)
    if fallback is not None:
        report["simulationMode"] = fallback
    return report


def _run_sampled(
    document: dict[str, Any] | ArchitectureDocument,
    scenario_id: str | None,
    registry: ComponentRegistry | None,
    limits: SimulationLimits | None,
    progress: ProgressFn | None,
    should_cancel: CancelFn | None,
    seed: int | None,
) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    """The sampled report, or None and (when a sample was simulated but was not steady) the
    ``simulationMode`` note for the full run that answers instead."""
    registry = registry or default_registry()
    prep = prepare_run(document, scenario_id, registry, limits or SimulationLimits(), seed)
    wl = prep.resolved.workload
    seconds = sample_seconds(wl)
    if seconds is None:
        return None, None
    short = prep.resolved.to_json_dict()
    short["workload"]["durationS"] = seconds
    short["scenarios"] = []
    report = _run_full(short, None, registry, limits, progress, should_cancel)
    ok, checks = stationary(report)
    if not ok:
        return None, {
            "kind": "full",
            "requestedMode": "sampled",
            "requestedDurationS": wl.duration_s,
            "simulatedDurationS": wl.duration_s,
            "checks": checks,
            "note": (
                f"A quick check was asked for, but the {seconds - wl.warmup_s:g} s sample was "
                "not steady (a queue, backlog or latency was still changing), so the whole "
                f"{wl.duration_s:g} s run was simulated."
            ),
        }
    report["simulationMode"] = {
        "kind": "sampled",
        "requestedDurationS": wl.duration_s,
        "simulatedDurationS": seconds,
        "checks": checks,
        "note": (
            f"Answered from a steady {seconds - wl.warmup_s:g} s sample after the "
            f"{wl.warmup_s:g} s warm-up: the {wl.duration_s:g} s run was not simulated whole. "
            "Rates, success and percentiles hold for a stationary system; counts and time "
            "series cover the sample."
        ),
    }
    report["provenance"]["scenarioId"] = prep.scenario_id
    report["provenance"]["scenarioName"] = prep.scenario_name
    return report, None


def _run_full(
    document: dict[str, Any] | ArchitectureDocument,
    scenario_id: str | None,
    registry: ComponentRegistry | None,
    limits: SimulationLimits | None,
    progress: ProgressFn | None,
    should_cancel: CancelFn | None,
    seed: int | None = None,
) -> dict[str, Any]:
    wall0 = time.perf_counter()
    registry = registry or default_registry()
    limits = limits or SimulationLimits()
    prep = prepare_run(document, scenario_id, registry, limits, seed)
    resolved = prep.resolved
    configs = prep.validation.configs
    wl = resolved.workload

    source_nodes = [
        n for n in resolved.architecture.nodes if registry.get(n.type).is_traffic_source
    ]
    source_cfgs: list[TrafficSourceConfig] = []
    for n in source_nodes:
        cfg = configs[n.id]
        assert isinstance(cfg, TrafficSourceConfig)
        source_cfgs.append(cfg)
    drain_s = wl.drain_s if wl.drain_s is not None else max(map(auto_drain_s, source_cfgs))
    if wl.drain_s is None and any(
        registry.get(n.type).category == "messaging" for n in resolved.architecture.nodes
    ):
        # Async backlogs outlive client deadlines; give consumers time to catch up. The run
        # still stops as soon as nothing is queued or in flight.
        drain_s = min(600.0, max(drain_s, ASYNC_MIN_DRAIN_S))
    duration_ms = wl.duration_s * 1000.0
    end_ms = duration_ms + drain_s * 1000.0
    tl = Timeline(end_ms, wl.warmup_s * 1000.0, duration_ms)

    env = simpy.Environment()
    ctx = SimContext(env, wl.seed, tl)
    ctx.shared["workload"] = wl
    # Kubernetes clusters, so services on the Kubernetes platform can find theirs at build time
    ctx.shared["clusters"] = [
        n.id for n in resolved.architecture.nodes if n.type == "kubernetes_cluster"
    ]
    ctx.set_faults(list(wl.faults))
    for e in resolved.architecture.edges:
        er = EdgeRuntime(e, tl, ctx.rng(f"edge:{e.id}:latency"))
        ctx.edges[e.id] = er
        ctx.out_edges.setdefault(e.source, []).append(er)
    needs_keys = False
    for n in resolved.architecture.nodes:
        model = registry.get(n.type)
        ctx.components[n.id] = model.build(ctx, n, configs[n.id])
        needs_keys = needs_keys or model.uses_keys(configs[n.id])
    sources: list[TrafficSourceRuntime] = []
    for n in source_nodes:
        rt = ctx.components[n.id]
        assert isinstance(rt, TrafficSourceRuntime)
        sources.append(rt)
    shares = [c.traffic_share for c in source_cfgs]
    keys = ZipfSampler(wl.key_count, wl.key_skew, ctx.rng("keys")) if needs_keys else None
    env.process(_arrivals(env, wl, ctx, sources, shares, tl, keys))

    def check() -> None:
        if should_cancel is not None and should_cancel():
            raise SimulationCancelledError("simulation cancelled")
        if time.perf_counter() - wall0 > limits.max_wall_seconds:
            raise SimulationLimitError(
                f"Simulation exceeded the wall-clock limit of {limits.max_wall_seconds:.0f} s "
                f"at simulated time {env.now / 1000:.1f} s. Reduce RPS, duration, fan-out or "
                "model size."
            )

    step = end_ms / max(1, limits.progress_chunks)
    every = max(1, limits.check_every_events)
    events = 0
    t = 0.0
    while t < end_ms:
        t = min(t + step, end_ms)
        if t > env.now:
            events += _run_until(env, t, every, check)
        if progress is not None:
            progress(t / end_ms)
        check()
        if t >= duration_ms and ctx.idle():
            break  # every request has finished; only stale timers remain
    t_final = t
    for comp in ctx.components.values():
        comp.finalize(t_final)

    report = build_report(
        prep=prep,
        registry=registry,
        ctx=ctx,
        sources=sources,
        shares=shares,
        tl=tl,
        t_final=t_final,
        drain_s=drain_s,
        wall_seconds=time.perf_counter() - wall0,
        events=events,
    )
    if progress is not None:
        progress(1.0)
    return report
