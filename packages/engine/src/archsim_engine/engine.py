"""Simulation entry point.

``run_simulation(document, scenario_id=...)`` resolves the scenario, validates the model, builds
runtime components, generates arrivals and runs SimPy in chunks so callers can observe progress,
cancel, and enforce wall-clock limits. It returns a JSON-serialisable report.
"""

from __future__ import annotations

import time
from array import array
from bisect import bisect_left
from collections.abc import Callable, Generator
from dataclasses import dataclass
from itertools import accumulate
from typing import Any

import simpy

from archsim_engine.components import create_default_registry
from archsim_engine.components.traffic_source import TrafficSourceConfig, TrafficSourceRuntime
from archsim_engine.issues import DocumentInvalidError
from archsim_engine.metrics import Timeline
from archsim_engine.registry import ComponentRegistry
from archsim_engine.report import build_report
from archsim_engine.runtime import EdgeRuntime, Request, SimContext
from archsim_engine.scenarios import load_document, resolve_scenario
from archsim_engine.schema import ArchitectureDocument, ConstantPattern, Workload
from archsim_engine.validation import ValidationResult, validate_document

ProgressFn = Callable[[float], None]
CancelFn = Callable[[], bool]

_DEFAULT_REGISTRY: ComponentRegistry | None = None

ASYNC_MIN_DRAIN_S = 60.0  # automatic drain when the model has message queues


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


def prepare_run(
    document: dict[str, Any] | ArchitectureDocument,
    scenario_id: str | None = None,
    registry: ComponentRegistry | None = None,
    limits: SimulationLimits | None = None,
) -> PreparedRun:
    """Load, resolve and validate. Raises ``DocumentInvalidError`` or ``SimulationLimitError``."""
    registry = registry or default_registry()
    limits = limits or SimulationLimits()
    doc = load_document(document)
    resolved, scenario = resolve_scenario(doc, scenario_id, registry)
    result = validate_document(resolved, registry)
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
    source: TrafficSourceRuntime,
    tl: Timeline,
    keys: ZipfSampler | None,
) -> Generator[simpy.Event, Any, None]:
    peak = wl.peak_rate()
    if peak <= 0:
        return
    arr_rng = ctx.rng("arrivals")
    thin_rng = ctx.rng("arrivals:thinning")
    mix_rng = ctx.rng("mix")
    duration = wl.duration_s * 1000.0
    poisson = wl.arrival_process == "poisson"
    steady = isinstance(wl.pattern, ConstantPattern)
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
        env.process(source.submit(req))


def run_simulation(
    document: dict[str, Any] | ArchitectureDocument,
    *,
    scenario_id: str | None = None,
    registry: ComponentRegistry | None = None,
    limits: SimulationLimits | None = None,
    progress: ProgressFn | None = None,
    should_cancel: CancelFn | None = None,
) -> dict[str, Any]:
    """Run one simulation and return its report (see ``docs/simulation-model.md``)."""
    wall0 = time.perf_counter()
    registry = registry or default_registry()
    limits = limits or SimulationLimits()
    prep = prepare_run(document, scenario_id, registry, limits)
    resolved = prep.resolved
    configs = prep.validation.configs
    wl = resolved.workload

    source_node = next(
        n for n in resolved.architecture.nodes if registry.get(n.type).is_traffic_source
    )
    source_cfg = configs[source_node.id]
    assert isinstance(source_cfg, TrafficSourceConfig)
    drain_s = wl.drain_s if wl.drain_s is not None else auto_drain_s(source_cfg)
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
    source = ctx.components[source_node.id]
    assert isinstance(source, TrafficSourceRuntime)
    keys = ZipfSampler(wl.key_count, wl.key_skew, ctx.rng("keys")) if needs_keys else None
    env.process(_arrivals(env, wl, ctx, source, tl, keys))

    step = end_ms / max(1, limits.progress_chunks)
    t = 0.0
    while t < end_ms:
        t = min(t + step, end_ms)
        env.run(until=t)
        if progress is not None:
            progress(t / end_ms)
        if should_cancel is not None and should_cancel():
            raise SimulationCancelledError("simulation cancelled")
        if time.perf_counter() - wall0 > limits.max_wall_seconds:
            raise SimulationLimitError(
                f"Simulation exceeded the wall-clock limit of {limits.max_wall_seconds:.0f} s "
                f"at simulated time {t / 1000:.1f} s. Reduce RPS, duration or model size."
            )
        if t >= duration_ms and ctx.idle():
            break  # every request has finished; only stale timers remain
    t_final = t
    for comp in ctx.components.values():
        comp.finalize(t_final)

    report = build_report(
        prep=prep,
        registry=registry,
        ctx=ctx,
        source=source,
        tl=tl,
        t_final=t_final,
        drain_s=drain_s,
        wall_seconds=time.perf_counter() - wall0,
    )
    if progress is not None:
        progress(1.0)
    return report
