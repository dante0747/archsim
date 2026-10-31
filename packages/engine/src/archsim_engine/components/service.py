"""Stateless application service (e.g. API servers) with replicas, worker pools and queues.

Three runtimes share one config:

* static replicas (VM / Docker / Kubernetes without autoscaling) - the original model;
* autoscaling replicas (HPA-like): capacity follows utilization with an evaluation interval and a
  pod start-up delay;
* serverless functions: one request per instance, cold starts, a concurrency limit and idle
  reclaim.

The runtime technology changes behaviour (see ``archsim_engine.technology``): Node.js runs each
process on one event loop (CPU work queues for it, I/O does not hold it); JVM and .NET instances
start slow while the JIT warms up; garbage-collected runtimes pause for stop-the-world
collections; serverless cold starts depend on the runtime and scale-out is rate limited.
"""

from __future__ import annotations

import math
from collections import deque
from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field, model_validator

from archsim_engine.components.load_curve import (
    LoadPoint,
    RateMeter,
    capacity_rps,
    curve_of,
    describe,
    load_factor,
    rate_filling,
)
from archsim_engine.components.resources import (
    CPU_CONTENTION,
    RESOURCES,
    Cores,
    busy_cores_series,
    core_utilization,
    cpu_field,
    instance_type_field,
    memory_field,
)
from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.explain import formula, num
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge, MeanAccumulator
from archsim_engine.ratelimit import TokenBucket
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    EdgeRuntime,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    WorkerPool,
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import SERVERLESS_TRAITS, SERVICE_PROFILES, Profile, resolve

AUTOSCALE = {"autoscaling": True}
SERVERLESS = {"platform": "serverless"}
BEHAVIOUR = "Technology behaviour"


def _count_series(g: Gauge, t_end: float | None) -> list[float | None]:
    """Mean level per bucket, averaged over the part of each bucket that was simulated (so the
    last, partial bucket does not look like a drop)."""
    b = g.tl.bucket_ms
    out: list[float | None] = []
    for i, a in enumerate(g.area):
        covered = b if t_end is None else min(b, t_end - i * b)
        out.append(a / covered if covered > 0 else None)
    return out


# Documentation behind preset numbers (see Preset.sources).
_SPRING = "https://docs.spring.io/spring-boot/appendix/application-properties/index.html"
_NODE_LOOP = "https://nodejs.org/en/learn/asynchronous-work/dont-block-the-event-loop"
_HPA = "https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/"
_LAMBDA_MEMORY = "https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html"
_LAMBDA_CONCURRENCY = "https://docs.aws.amazon.com/lambda/latest/dg/lambda-concurrency.html"


class ServiceConfig(CamelModel):
    technology: Literal[
        "generic", "java", "nodejs", "go", "python", "dotnet", "rust", "php", "ruby"
    ] = Field(
        default="generic",
        description=(
            "Runtime or framework. It sets the concurrency model (event loop for Node.js), GC "
            "pauses, JIT warm-up and serverless cold starts, shown under Technology behaviour."
        ),
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    platform: Literal["vm", "docker", "kubernetes", "serverless"] = Field(
        default="vm",
        description=(
            "Where it runs. VM and Docker behave the same (fixed replicas unless autoscaling is on); "
            "Kubernetes usually pairs with autoscaling (HPA); serverless runs one request per "
            "instance with cold starts and a concurrency limit."
        ),
        json_schema_extra=ui(label="Platform", group="Technology"),
    )
    replicas: int = Field(
        default=2,
        ge=1,
        le=500,
        description="Number of identical instances. Each has its own workers and queue. With autoscaling: the starting count.",
        json_schema_extra=ui(label="Replicas", group="Capacity", headline=True),
    )
    workers_per_replica: int = Field(
        default=4,
        ge=1,
        le=4096,
        description=(
            "Requests one replica processes concurrently (threads/worker processes). Event-loop "
            "runtimes (Node.js) use the processes per replica instead."
        ),
        json_schema_extra=ui(label="Workers per replica", group="Capacity", headline=True),
    )
    queue_capacity: int = Field(
        default=50,
        ge=0,
        le=100_000,
        description="Requests that may wait for a free worker per replica; more are rejected (HTTP 503).",
        json_schema_extra=ui(label="Queue limit per replica", group="Capacity"),
    )
    queue_timeout_ms: float | None = Field(
        default=1000.0,
        gt=0,
        le=600_000,
        description="Requests waiting longer than this for a worker are rejected (ms). Empty = no limit.",
        json_schema_extra=ui(label="Queue timeout", unit="ms", group="Capacity"),
    )
    service_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=8.0, p95=25.0),
        description=(
            "Handler time per request on a worker, excluding downstream calls (ms). "
            "This is an assumption unless calibrated from telemetry."
        ),
        json_schema_extra=ui(
            label="Service time", unit="ms", group="Performance", assumption=True, headline=True
        ),
    )
    service_time_by_load: list[LoadPoint] = Field(
        default_factory=list,
        # left out of the JSON when empty, so documents and reports read as before
        exclude_if=lambda v: not v,
        description=(
            "How the service time changes with load: points of (req/s one replica receives, "
            "factor). At that rate the service time is the one above times the factor; between "
            "points it is interpolated in log(req/s), and outside them it stays at the nearest "
            "point's factor (never extrapolated). The rate is the replica's arrivals over the last "
            "second. Calibration fits it from CPU per request at several loads. Empty = the same "
            "service time at every load."
        ),
        json_schema_extra=ui(label="Service time by load", group="Performance", kind="loadCurve"),
    )
    blocking_downstream: bool = Field(
        default=True,
        description=(
            "On: a worker stays occupied while waiting for downstream calls (thread-per-request). "
            "Off: the worker is released during downstream I/O (async/event-loop servers)."
        ),
        json_schema_extra=ui(label="Workers block on downstream calls", group="Performance"),
    )
    # -- resources ------------------------------------------------------------------------
    cpu_cores: float | None = cpu_field(
        "replica",
        CPU_CONTENTION + " The CPU share of the service time is CPU work. Serverless: one "
        "request per instance, so only values below 1 matter.",
    )
    cpu_share: float = Field(
        default=1.0,
        ge=0,
        le=1,
        description=(
            "Share of service time that needs a CPU core; the rest waits (I/O inside the "
            "handler) on the worker without one. Only used when CPU is set. Calibration fits it "
            "from CPU per request (CPU seconds ÷ requests)."
        ),
        json_schema_extra=ui(label="CPU share of service time", group=RESOURCES, step=0.05),
    )
    memory_gib: float | None = memory_field(
        "replica",
        "When set, every request a replica holds (queued, running or waiting on downstream calls) "
        "uses memory; a request that would exceed it is refused (out of memory).",
    )
    instance_type: str | None = instance_type_field("replica on a VM or Docker")
    memory_per_request_mb: float = Field(
        default=2.0,
        gt=0,
        le=65_536,
        description="Memory one in-flight request uses (MiB). Only used when memory is set.",
        json_schema_extra=ui(
            label="Memory per request", unit="MiB", group=RESOURCES, assumption=True
        ),
    )
    memory_baseline_mb: float = Field(
        default=256.0,
        ge=0,
        le=1_048_576,
        description="Memory a replica uses before any request (runtime, heap, caches) (MiB). Only used when memory is set.",
        json_schema_extra=ui(label="Memory at idle", unit="MiB", group=RESOURCES, assumption=True),
    )
    memory_per_rps_mb: float = Field(
        default=0.0,
        ge=0,
        le=65_536,
        description=(
            "Memory that grows with throughput, per request per second a replica serves (MiB). "
            "Garbage-collected runtimes keep what requests allocate until the next collection, "
            "so their heap grows with the request rate rather than with requests held. It counts "
            "in the memory a replica uses and is sized for, not in the out-of-memory limit (a "
            "collector reclaims it under pressure). 0: not modelled. Only used when memory is set."
        ),
        json_schema_extra=ui(label="Memory per req/s", unit="MiB", group=RESOURCES, step=0.01),
    )
    # -- autoscaling (HPA-like) -----------------------------------------------------------
    autoscaling: bool = Field(
        default=False,
        description=(
            "Add and remove replicas to keep utilization near the target (like a Kubernetes "
            "Horizontal Pod Autoscaler). Ignored on the serverless platform."
        ),
        json_schema_extra=ui(label="Autoscaling", group="Autoscaling"),
    )
    min_replicas: int = Field(
        default=1,
        ge=1,
        le=500,
        description="Fewest replicas autoscaling keeps running.",
        json_schema_extra=ui(label="Min replicas", group="Autoscaling", visible_when=AUTOSCALE),
    )
    max_replicas: int = Field(
        default=10,
        ge=1,
        le=500,
        description="Most replicas autoscaling may run.",
        json_schema_extra=ui(label="Max replicas", group="Autoscaling", visible_when=AUTOSCALE),
    )
    target_utilization: float = Field(
        default=0.6,
        ge=0.1,
        le=0.95,
        description=(
            "Utilization the autoscaler aims for: the share of busy workers, or of the CPU size when "
            "the replica has one (whichever is higher), like the Kubernetes HPA's CPU target."
        ),
        json_schema_extra=ui(
            label="Target utilization", group="Autoscaling", visible_when=AUTOSCALE, step=0.05
        ),
    )
    scale_interval_s: float = Field(
        default=15.0,
        gt=0,
        le=600,
        description="How often the autoscaler re-evaluates (s). Scale-down waits for 3 consecutive low readings.",
        json_schema_extra=ui(
            label="Evaluation interval", unit="s", group="Autoscaling", visible_when=AUTOSCALE
        ),
    )
    startup_s: float = Field(
        default=20.0,
        ge=0,
        le=600,
        description="Time from the scale-up decision until a new replica takes traffic (pod scheduling, image pull, start-up, readiness).",
        json_schema_extra=ui(
            label="Replica start-up time",
            unit="s",
            group="Autoscaling",
            visible_when=AUTOSCALE,
            assumption=True,
        ),
    )
    # -- serverless -------------------------------------------------------------------------
    concurrency_limit: int = Field(
        default=1000,
        ge=1,
        le=100_000,
        description="Most instances (= concurrent requests) the function may run; beyond it requests are throttled.",
        json_schema_extra=ui(
            label="Concurrency limit", group="Serverless", visible_when=SERVERLESS
        ),
    )
    cold_start_ms: Distribution | None = Field(
        default=None,
        description=(
            "Extra time for a request that has to start a new instance (ms). Empty = the "
            "runtime's typical cold start (Java about 1.2 s, Node.js and Python about 250 ms, Go "
            "about 100 ms; illustrative)."
        ),
        json_schema_extra=ui(
            label="Cold start",
            unit="ms",
            group="Serverless",
            visible_when=SERVERLESS,
            assumption=True,
        ),
    )
    cluster: str | None = Field(
        default=None,
        description=(
            "The Kubernetes cluster (a cluster part in this design) whose nodes run this service's "
            "pods. Empty = the only cluster in the design, if there is exactly one."
        ),
        json_schema_extra=ui(
            label="Cluster",
            group="Technology",
            kind="nodeRef",
            ref_type="kubernetes_cluster",
            visible_when={"platform": "kubernetes"},
        ),
    )
    scale_out_rate: int = Field(
        default=1000,
        ge=1,
        le=100_000,
        description="New instances the platform may start per 10 seconds (AWS Lambda: 1,000); requests that would need more are throttled.",
        json_schema_extra=ui(
            label="Scale-out rate per 10 s", group="Serverless", visible_when=SERVERLESS
        ),
    )
    # -- technology behaviour ---------------------------------------------------------------
    runtime_model: Literal["auto", "thread_pool", "event_loop"] = Field(
        default="auto",
        description=(
            "thread_pool: each worker handles one request at a time. event_loop: each process "
            "runs every request's CPU work on one thread, one piece at a time, and waits for "
            "I/O without blocking (Node.js). Auto follows the technology."
        ),
        json_schema_extra=ui(label="Concurrency model", group=BEHAVIOUR),
    )
    processes_per_replica: int = Field(
        default=1,
        ge=1,
        le=256,
        description="Event-loop processes per replica (Node.js cluster mode, PM2, uvicorn workers).",
        json_schema_extra=ui(label="Event-loop processes per replica", group=BEHAVIOUR),
    )
    gc_pause_ms: float | None = Field(
        default=None,
        ge=0,
        le=10_000,
        description=(
            "Median stop-the-world pause per garbage collection (ms); every request on the "
            "replica stalls meanwhile. Empty = runtime default (Java about 12, .NET 6, Node.js "
            "2, Go 0.1, Rust 0; illustrative)."
        ),
        json_schema_extra=ui(label="GC pause", unit="ms", group=BEHAVIOUR, assumption=True),
    )
    gc_every_requests: int | None = Field(
        default=None,
        ge=1,
        le=10_000_000,
        description="Requests a replica serves between collections (allocation pressure). Empty = runtime default.",
        json_schema_extra=ui(label="Requests between GCs", group=BEHAVIOUR, assumption=True),
    )
    warmup_factor: float | None = Field(
        default=None,
        ge=1,
        le=20,
        description=(
            "How much slower a freshly started instance runs before the JIT has optimised it "
            "(applies to replicas started during the run and to serverless instances). Empty = "
            "runtime default (Java 2.5x, .NET 1.5x, others 1x; illustrative)."
        ),
        json_schema_extra=ui(
            label="JIT warm-up slowdown", group=BEHAVIOUR, assumption=True, step=0.1
        ),
    )
    warmup_s: float | None = Field(
        default=None,
        ge=0,
        le=3600,
        description="Time for a new instance to reach full speed (s), fading linearly. Empty = runtime default.",
        json_schema_extra=ui(label="JIT warm-up time", unit="s", group=BEHAVIOUR, assumption=True),
    )
    keep_warm_s: float = Field(
        default=300.0,
        ge=0,
        le=7200,
        description="Idle time after which an instance is reclaimed (s); the next request needs a cold start.",
        json_schema_extra=ui(
            label="Keep warm", unit="s", group="Serverless", visible_when=SERVERLESS
        ),
    )
    cost_per_hour: float = Field(
        default=0.10,
        ge=0,
        le=10_000,
        description=(
            "Price of one replica (or serverless instance) per hour, in USD. Illustrative "
            "default; use your provider's pricing."
        ),
        json_schema_extra=ui(
            label="Cost per replica-hour", unit="$/h", group="Cost", assumption=True
        ),
    )

    @model_validator(mode="after")
    def _check(self) -> ServiceConfig:
        if self.min_replicas > self.max_replicas:
            raise ValueError("min replicas must not exceed max replicas")
        return self

    def profile(self) -> Profile:
        return SERVICE_PROFILES.get(self.technology, SERVICE_PROFILES["generic"])

    def setting(self, key: str, fallback: Any = None) -> Any:
        return resolve(getattr(self, key, None), self.profile(), key, fallback)

    def event_loop(self) -> bool:
        return self.setting("runtime_model", "thread_pool") == "event_loop"

    def slots_per_replica(self) -> int:
        """Concurrent handlers per replica: event loops, or workers."""
        return self.processes_per_replica if self.event_loop() else self.workers_per_replica

    def blocks(self) -> bool:
        """Whether a handler stays occupied during downstream calls."""
        return False if self.event_loop() else self.blocking_downstream

    def cold_start(self) -> Distribution:
        if self.cold_start_ms is not None:
            return self.cold_start_ms
        v = self.profile().values.get("cold_start_ms", {"median": 300, "p95": 900})
        return LogNormal(median=v["median"], p95=v["p95"])

    def gc(self) -> tuple[float, int] | None:
        pause = float(self.setting("gc_pause_ms", 0.0) or 0.0)
        every = int(self.setting("gc_every_requests", 0) or 0)
        return (pause, every) if pause > 0 and every > 0 else None

    def warmup(self) -> tuple[float, float] | None:
        f = float(self.setting("warmup_factor", 1.0) or 1.0)
        w = float(self.setting("warmup_s", 0.0) or 0.0)
        return (f, w * 1000.0) if f > 1.0 and w > 0 else None

    def memory_slots(self) -> int | None:
        """Requests one replica can hold within its memory, or None when memory is not set."""
        if self.memory_gib is None:
            return None
        free = self.memory_gib * 1024.0 - self.memory_baseline_mb
        return max(0, math.floor(free / self.memory_per_request_mb + 1e-9))

    def load_curve(self) -> list[tuple[float, float]]:
        """``serviceTimeByLoad`` as sorted (req/s, factor) points; empty: no change with load."""
        return curve_of(self.service_time_by_load)

    def cpu_fraction(self) -> float:
        """The share of service time that needs a core: ``cpuShare`` when CPU is set, else all
        of it (without a CPU size nothing waits for a core and the share changes nothing)."""
        return self.cpu_share if self.cpu_cores is not None else 1.0


class ServiceRuntime(RuntimeComponent):
    """Fixed number of replicas; each replica is a worker pool with its own queue."""

    def __init__(
        self, ctx: SimContext, node: NodeSpec, config: ServiceConfig, initial: int | None = None
    ) -> None:
        n = initial if initial is not None else config.replicas
        self.per_replica = config.slots_per_replica()
        super().__init__(ctx, node, config, n * self.per_replica)
        self.m.has_cpu_split = True
        self.core_busy = Gauge(ctx.tl) if config.cpu_cores is not None else None
        self.mem_slots = config.memory_slots()
        self.peak_load = 0
        self.cpu_frac = config.cpu_fraction()
        self.blocking = config.blocks()
        self.gc_cfg = config.gc()
        self.warm_cfg = config.warmup()
        self.curve = config.load_curve()
        # each replica's arrivals over the last second, when the service time follows load
        self.meters = [RateMeter(0.0) for _ in range(n)] if self.curve else []
        self.factors = MeanAccumulator(ctx.tl) if self.curve else None
        self.pools = [self._new_pool() for _ in range(n)]
        self.cores = [self._new_cores() for _ in range(n)]
        self.load = [0] * n
        self.born = [0.0] * n  # replicas present at the start are warm
        self.served = [0] * n
        self.pause = [[0.0, -1.0, -1.0] for _ in range(n)]  # [paused_until, start, end]
        self.gc_ms = [0.0] * ctx.tl.n
        self.warming = Gauge(ctx.tl)
        self.rr = 0
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.gc_rng = ctx.rng(f"node:{node.id}:gc")
        self._deps: dict[str, list[EdgeRuntime]] = {}

    def _new_pool(self) -> WorkerPool:
        cfg: ServiceConfig = self.config
        return WorkerPool(
            self.ctx.env, self.per_replica, cfg.queue_capacity, cfg.queue_timeout_ms, self.m
        )

    def _new_cores(self) -> Cores:
        cfg: ServiceConfig = self.config
        return Cores(self.ctx.env, cfg.cpu_cores, self.per_replica, self.core_busy)

    def _add_replica_state(self, now: float) -> None:
        self.load.append(0)
        self.born.append(now)
        self.served.append(0)
        self.pause.append([0.0, -1.0, -1.0])
        if self.curve:
            self.meters.append(RateMeter(now))
        if self.warm_cfg is not None:
            self.warming.add(1, now)
            self.env.process(self._warm_done(self.warm_cfg[1]))

    def _warm_done(self, after_ms: float) -> Generator[simpy.Event, Any, None]:
        yield self.env.timeout(after_ms)
        self.warming.add(-1, self.env.now)

    def _warm_factor(self, index: int, now: float) -> float:
        w = self.warm_cfg
        born = self.born[index]
        if w is None or born <= 0:
            return 1.0
        f, dur = w
        left = 1.0 - (now - born) / dur
        return 1.0 + (f - 1.0) * left if left > 0 else 1.0

    def _load_factor(self, index: int, now: float) -> float:
        """The service-time factor at replica ``index``'s rate over the last second."""
        f = load_factor(self.curve, self.meters[index].rps(now))
        if self.factors is not None:
            self.factors.record(now, f)
        return f

    def _cpu(self, req: Request, index: int, work: float) -> Generator[simpy.Event, Any, None]:
        """CPU work on replica ``index``, stalled by stop-the-world GC pauses."""
        env = self.env
        p = self.pause[index]
        if p[0] > env.now:
            t0 = env.now
            yield env.timeout(p[0] - env.now)
            span(req, self.id, "gc", t0, env.now, "stop-the-world garbage collection")
        t0 = env.now
        yield from self.cores[index].run(work)
        if t0 < p[1] < env.now:
            # a collection started while this work ran: it lost the pause's duration
            extra = p[2] - p[1]
            t1 = env.now
            yield env.timeout(extra)
            span(req, self.id, "gc", t1, env.now, "stop-the-world garbage collection")

    def _after_request(self, index: int) -> None:
        g = self.gc_cfg
        if g is None:
            return
        self.served[index] += 1
        if self.served[index] % g[1]:
            return
        now = self.env.now
        median = g[0]
        pause = LogNormal(median=median, p95=median * 2.5).sample(self.gc_rng)
        p = self.pause[index]
        start = max(now, p[0])
        p[0], p[1], p[2] = start + pause, start, start + pause
        self.m.count("gcPauses", now)
        self.gc_ms[self.ctx.tl.index(now)] += pause / max(1, self.instance_count())

    def deps_for(self, op: str) -> list[EdgeRuntime]:
        d = self._deps.get(op)
        if d is None:
            d = sorted(
                (e for e in self.out_edges("out") if e.carries(op)),
                key=lambda e: (e.cfg.order, e.id),
            )
            self._deps[op] = d
        return d

    def instance_count(self) -> int:
        return len(self.pools)

    def replicas_mean(self) -> float:
        return float(len(self.pools))

    def replicas_series(self) -> list[float]:
        """Replicas taking traffic in each time bucket."""
        return [float(len(self.pools))] * self.ctx.tl.n

    def peak_rps_per_replica(self) -> float:
        """The highest rate of requests one replica handled (completed or failed downstream) in
        a time bucket of the measurement window: what throughput-driven memory follows."""
        tl = self.ctx.tl
        b = tl.bucket_ms
        done = self.m.completed.buckets
        failed = self.m.failed_downstream.buckets
        replicas = self.replicas_series()
        peak = 0.0
        for i in range(tl.n):
            mid = (i + 0.5) * b
            if not tl.window_start <= mid < tl.window_end or replicas[i] <= 0:
                continue
            peak = max(peak, (done[i] + failed[i]) * 1000.0 / b / replicas[i])
        return peak

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.warming.finalize(t_end)
        if self.core_busy is not None:
            self.core_busy.finalize(t_end)

    def resource_capacity(
        self, hold_ms: float | None, service_ms: float | None
    ) -> dict[str, float]:
        cfg: ServiceConfig = self.config
        out: dict[str, float] = {}
        replicas = self.replicas_mean()
        if cfg.cpu_cores is not None and service_ms and cfg.cpu_share > 0:
            # the rate at which a replica's cores fill, at the factor of that rate
            per_replica = (
                cfg.cpu_cores * 1000.0 / (self._base_service_ms(service_ms) * cfg.cpu_share)
            )
            out["cpu"] = capacity_rps(self.curve, per_replica) * replicas
        if self.mem_slots is not None and self.mem_slots < cfg.workers_per_replica and hold_ms:
            out["memory"] = self.mem_slots * replicas * 1000.0 / hold_ms
        return out

    def slot_capacity(self, slots: float, hold_ms: float, service_ms: float | None) -> float:
        if not self.curve or not service_ms:
            return super().slot_capacity(slots, hold_ms, service_ms)
        # the rate where a replica's handlers fill, its own work at that rate's factor (the
        # rest of the time a request holds one, its downstream calls, does not follow the curve)
        replicas = max(1.0, self.replicas_mean())
        per_replica = rate_filling(
            self.curve,
            self._base_service_ms(service_ms),
            slots / replicas * 1000.0,
            max(0.0, hold_ms - service_ms),
        )
        return per_replica * replicas

    def _base_service_ms(self, service_ms: float) -> float:
        """The mean service time at factor 1: the observed mean over the mean load factor the
        window's requests were served at (the observed mean itself without a load curve)."""
        mean_f = self.factors.window_mean() if self.factors is not None else None
        return service_ms / mean_f if mean_f else service_ms

    def extra_summary(self) -> dict[str, Any]:
        cfg: ServiceConfig = self.config
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            n = self.cores[0].n if self.cores else 1
            out["cpuCoreUtilization"] = core_utilization(self.core_busy, n * self.replicas_mean())
        if self.mem_slots is not None:
            peak_mb = cfg.memory_baseline_mb + self.peak_load * cfg.memory_per_request_mb
            if cfg.memory_per_rps_mb > 0:
                peak_mb += cfg.memory_per_rps_mb * self.peak_rps_per_replica()
            out["memoryPeakGiB"] = round(min(peak_mb / 1024.0, cfg.memory_gib or 0.0), 3)
        if self.gc_cfg is not None:
            out["gcPauses"] = self.m.extra["gcPauses"].window if "gcPauses" in self.m.extra else 0
        return out

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {"inflightMean": list(self.m.inflight.mean_series())}
        if self.core_busy is not None and self.cores:
            out["cpuBusyCores"] = busy_cores_series(self.core_busy, self.cores[0])
        bucket_s = self.ctx.tl.bucket_ms / 1000.0
        if self.gc_cfg is not None:
            out["gcPauseMs"] = [v / bucket_s for v in self.gc_ms]
        if self.warm_cfg is not None:
            out["warmingReplicas"] = list(self.warming.mean_series())
        if self.config.event_loop():
            out["eventLoopLagMs"] = self.m.queue_wait.series()
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: ServiceConfig = self.config
        active = {
            "event_loop": cfg.event_loop(),
            "gc_pauses": self.gc_cfg is not None,
            "v8_gc": self.gc_cfg is not None,
            "jit_warmup": self.warm_cfg is not None,
            "tiered_compilation": self.warm_cfg is not None,
            "thread_per_request": not cfg.event_loop(),
        }
        return [t.to_dict() for t in cfg.profile().traits if active.get(t.id, True)]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: ServiceConfig = self.config
        out: list[dict[str, Any]] = []
        svc = summary.get("meanServiceMs")
        if svc and self.curve:
            base = self._base_service_ms(svc)
            lam = (summary.get("arrivalRps") or 0.0) / max(1.0, self.replicas_mean())
            f = load_factor(self.curve, lam)
            out.append(
                formula(
                    "load_curve",
                    "Service time at this load",
                    "service time = service time at factor 1 × factor(req/s per replica)",
                    f"{num(base, 3)} ms × {num(f, 3)} at {num(lam, 1)} req/s per replica",
                    base * f,
                    "ms",
                    note=(
                        f"The factor follows each replica's arrivals over the last second: "
                        f"{describe(self.curve)}, interpolated in log(req/s) and flat outside the "
                        "points."
                    ),
                )
            )
        if cfg.event_loop() and svc:
            loops = self.per_replica * self.replicas_mean()
            per_loop = capacity_rps(self.curve, 1000.0 / self._base_service_ms(svc))
            at = (
                f" (at the factor of that rate, ×{num(load_factor(self.curve, per_loop), 3)})"
                if self.curve
                else ""
            )
            out.append(
                formula(
                    "event_loop",
                    "Event-loop capacity",
                    "capacity = replicas × processes × 1000 ÷ CPU time per request" + at,
                    f"{num(self.replicas_mean())} × {self.per_replica} × 1000 ÷ "
                    f"{num(1000.0 / per_loop, 2)} ms",
                    loops * per_loop,
                    "req/s",
                    note="Only the CPU part of a request occupies the loop; time waiting on downstream calls does not count."
                    + (
                        f" The whole service time holds the loop; {cfg.cpu_share:.0%} of it (the CPU share) counts as CPU use."
                        if cfg.cpu_cores is not None and cfg.cpu_share < 1
                        else ""
                    ),
                    metric="eventLoopLagMs",
                )
            )
        if self.gc_cfg is not None:
            pause, every = self.gc_cfg
            lam = summary.get("arrivalRps") or 0.0
            per_replica = lam / max(1.0, self.replicas_mean())
            share = per_replica / every * pause * 1.2 / 1000.0
            out.append(
                formula(
                    "gc_share",
                    "Time lost to GC pauses",
                    "paused share ≈ (requests/s per replica ÷ requests per GC) × mean pause ÷ 1000",
                    f"({num(per_replica, 2)} ÷ {every:,}) × {num(pause * 1.2, 2)} ms ÷ 1000",
                    share,
                    "",
                    note="During a pause every request on that replica stalls, which shows up in the latency tail more than in the mean.",
                    metric="gcPauseMs",
                )
            )
        if self.warm_cfg is not None:
            f, dur = self.warm_cfg
            out.append(
                formula(
                    "warmup",
                    "JIT warm-up",
                    "slowdown(t) = 1 + (factor − 1) × max(0, 1 − age ÷ warm-up time)",
                    f"factor {num(f, 2)}, warm-up {num(dur / 1000.0)} s",
                    f,
                    "x",
                    note="Applies to replicas started during the run (autoscaling) and to new serverless instances; it makes scale-out slower to help.",
                    metric="warmingReplicas",
                )
            )
        return out

    def instance_load(self, index: int) -> int:
        return self.load[index]

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        # Called without a load balancer: spread across replicas round-robin.
        i = self.rr
        self.rr = (i + 1) % len(self.pools)
        return self.handle_on(req, i)

    def handle_on(self, req: Request, index: int) -> Generator[simpy.Event, Any, Outcome]:
        return self._serve(req, index)

    def _serve(self, req: Request, index: int) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: ServiceConfig = self.config
        m.arrive(env.now)
        if self.curve:
            self.meters[index].arrive(env.now)
        if self.mem_slots is not None and self.load[index] >= self.mem_slots:
            m.reject("out_of_memory", env.now)
            span(req, self.id, "rejected", env.now, env.now, "out of memory")
            return rejected("out_of_memory", self.id)
        self.load[index] += 1
        if self.load[index] > self.peak_load:
            self.peak_load = self.load[index]
        pool = self.pools[index]
        tok, reason = yield from pool.acquire(req, self.id)
        if tok is None:
            self.load[index] -= 1
            m.reject(reason or "rejected", env.now)
            span(req, self.id, "rejected", env.now, env.now, reason)
            return rejected(reason or "rejected", self.id)
        start = env.now
        st = self.sample_time(cfg.service_time_ms, self.rng) * self._warm_factor(index, start)
        if self.curve:
            st *= self._load_factor(index, start)
        m.cpu.add(1, start)
        cpu = st * self.cpu_frac
        yield from self._cpu(req, index, cpu)
        if st > cpu:  # the rest of the handler time waits (I/O) on the worker without a core
            yield env.timeout(st - cpu)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        span(req, self.id, "service", start, env.now, None)
        self._after_request(index)
        deps = self.deps_for(req.op)
        if self.blocking:
            outcome = yield from self.ctx.call_dependencies(deps, req, self.id)
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
        else:
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
            outcome = yield from self.ctx.call_dependencies(deps, req, self.id)
        self.load[index] -= 1
        m.depart(env.now, outcome, req)
        return outcome


class AutoscalingServiceRuntime(ServiceRuntime):
    """Replicas follow utilization (HPA-like).

    Every ``scaleIntervalS`` the autoscaler measures utilization of the routable replicas over
    the last interval: desired = ceil(active x utilization / target), clamped to [min, max]. New
    replicas take traffic after ``startupS``. Scale-down needs 3 consecutive low readings; a
    removed replica stops receiving requests and drains what it already has.
    """

    def __init__(
        self,
        ctx: SimContext,
        node: NodeSpec,
        config: ServiceConfig,
        cluster_id: str | None = None,
        autoscale: bool = True,
    ) -> None:
        if autoscale:
            initial = min(max(config.replicas, config.min_replicas), config.max_replicas)
        else:
            initial = config.replicas
        super().__init__(ctx, node, config, initial)
        tl = ctx.tl
        self.routable: list[int] = list(range(initial))
        self.starting = 0
        self.capacity = Gauge(tl)  # routable worker slots
        self.active = Gauge(tl)  # routable replicas
        self.capacity.add(initial * self.per_replica, 0.0)
        self.active.add(initial, 0.0)
        self._low_readings = 0
        self._busy_seen = 0.0
        self._core_seen = 0.0
        self._pending_n = 0  # this service's pods waiting for a node
        self._cap_seen = 0.0
        self._t_end: float | None = None
        # Kubernetes: each replica is a pod placed on a node of the cluster
        self.cluster_id = cluster_id
        self.cluster: Any = None
        self.pod_node: dict[int, Any] = {}
        self.pods_pending = Gauge(tl)
        self.pods_starting = Gauge(tl)
        if autoscale:
            ctx.env.process(self._autoscaler())
        if cluster_id is not None:
            ctx.env.process(self._place_initial())

    # -- routing over routable replicas -------------------------------------------------
    def instance_count(self) -> int:
        return len(self.routable)

    def instance_load(self, index: int) -> int:
        if not self.routable:
            return 0
        return self.load[self.routable[index % len(self.routable)]]

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        if not self.routable:
            return self._no_pods(req)
        i = self.rr % len(self.routable)
        self.rr = i + 1
        return self._serve(req, self.routable[i])

    def handle_on(self, req: Request, index: int) -> Generator[simpy.Event, Any, Outcome]:
        if not self.routable:
            return self._no_pods(req)
        return self._serve(req, self.routable[index % len(self.routable)])

    def _no_pods(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        now = self.env.now
        self.m.arrive(now)
        self.m.reject("no_ready_pods", now)
        span(req, self.id, "rejected", now, now, "no ready pod (503)")
        return rejected("no_ready_pods", self.id)
        yield  # pragma: no cover - makes this a generator

    # -- pods ----------------------------------------------------------------------------------
    def _pod_request(self) -> tuple[float, float]:
        from archsim_engine.components.kubernetes import pod_request

        return pod_request(self.config, self.cluster.config)

    def _place_initial(self) -> Generator[simpy.Event, Any, None]:
        """Bind the starting pods; those that do not fit wait Pending, then pull and start."""
        env = self.env
        assert self.cluster_id is not None
        self.cluster = self.ctx.components[self.cluster_id]
        cpu, mem = self._pod_request()
        now = env.now
        for i in list(self.routable):
            ev = self.cluster.request_pod(self.id, cpu, mem)
            if ev.triggered:
                self.pod_node[i] = ev.value
                self.cluster.mark_image(ev.value, self.id)  # running before the simulation started
                continue
            self.routable.remove(i)
            self.capacity.add(-self.per_replica, now)
            self.active.add(-1, now)
            self.instances_version += 1
            self.starting += 1
            env.process(self._await_pod(i, ev))
        yield env.timeout(0)

    def _await_pod(self, index: int, ev: simpy.Event) -> Generator[simpy.Event, Any, None]:
        """A starting pod that was Pending: bind, pull, start, then take traffic."""
        env = self.env
        cfg: ServiceConfig = self.config
        self.pods_pending.add(1, env.now)
        self._pending_n += 1
        node = yield ev
        self._pending_n -= 1
        self.pods_pending.add(-1, env.now)
        if node is None:  # deleted while Pending (scaled down)
            self.starting -= 1
            return
        self.pods_starting.add(1, env.now)
        delay = self.cluster.pull_ms(node, self.id) + cfg.startup_s * 1000.0
        if delay > 0:
            yield env.timeout(delay)
        now = env.now
        self.pods_starting.add(-1, now)
        self.pod_node[index] = node
        self.starting -= 1
        self.routable.append(index)
        self.capacity.add(self.per_replica, now)
        self.active.add(1, now)
        self.instances_version += 1

    def capacity_gauge(self) -> Gauge | None:
        return self.capacity

    # -- control loop ----------------------------------------------------------------------
    @staticmethod
    def _integral(g: Gauge, now: float) -> float:
        g.finalize(now)  # brings the per-bucket integral up to ``now``; safe mid-run
        return sum(g.area)

    def _autoscaler(self) -> Generator[simpy.Event, Any, None]:
        env = self.env
        cfg: ServiceConfig = self.config
        interval = cfg.scale_interval_s * 1000.0
        while True:
            yield env.timeout(interval)
            now = env.now
            busy = self._integral(self.m.busy, now)
            cap = self._integral(self.capacity, now)
            d_busy, d_cap = busy - self._busy_seen, cap - self._cap_seen
            self._busy_seen, self._cap_seen = busy, cap
            util = d_busy / d_cap if d_cap > 0 else 0.0
            if self.core_busy is not None and self.cores:
                # with a CPU size, CPU use counts too (the HPA's CPU target): busy CPU executors
                # over the executors of the replicas that were up
                core = self._integral(self.core_busy, now)
                d_core, self._core_seen = core - self._core_seen, core
                executors = d_cap * self.cores[0].n / max(1, self.per_replica)
                if executors > 0:
                    util = max(util, d_core / executors)
            active = len(self.routable)
            desired = math.ceil(active * util / cfg.target_utilization - 1e-9) if util > 0 else 0
            desired = min(max(desired, cfg.min_replicas), cfg.max_replicas)
            planned = active + self.starting
            if desired > planned:
                self._low_readings = 0
                add = desired - planned
                self.m.count("scaleUps", now)
                for _ in range(add):
                    self.starting += 1
                    env.process(self._start_replica())
            elif self._pending_n > 0 and desired < planned and self.cluster is not None:
                # scaling down deletes the Pending pods first, after the same three low readings
                self._low_readings += 1
                if self._low_readings >= 3:
                    self._low_readings = 0
                    self.cluster.cancel_pending(self.id, min(self._pending_n, planned - desired))
            elif desired < active and self.starting == 0:
                self._low_readings += 1
                if self._low_readings >= 3:
                    self._low_readings = 0
                    self.m.count("scaleDowns", now)
                    for _ in range(active - desired):
                        self._retire_replica(now)
            else:
                self._low_readings = 0

    def _start_replica(self) -> Generator[simpy.Event, Any, None]:
        env = self.env
        cfg: ServiceConfig = self.config
        node = None
        if self.cluster is not None:
            # a new pod: Pending until a node has room, then image pull and start-up
            cpu, mem = self._pod_request()
            self.pods_pending.add(1, env.now)
            self._pending_n += 1
            node = yield self.cluster.request_pod(self.id, cpu, mem)
            self._pending_n -= 1
            self.pods_pending.add(-1, env.now)
            if node is None:  # deleted while Pending (scaled down)
                self.starting -= 1
                return
            self.pods_starting.add(1, env.now)
            delay = self.cluster.pull_ms(node, self.id) + cfg.startup_s * 1000.0
            if delay > 0:
                yield env.timeout(delay)
            self.pods_starting.add(-1, env.now)
        elif cfg.startup_s > 0:
            yield env.timeout(cfg.startup_s * 1000.0)
        now = env.now
        self.starting -= 1
        self.pools.append(self._new_pool())
        self.cores.append(self._new_cores())
        self._add_replica_state(now)
        self.routable.append(len(self.pools) - 1)
        if node is not None:
            self.pod_node[len(self.pools) - 1] = node
        self.capacity.add(self.per_replica, now)
        self.active.add(1, now)
        self.instances_version += 1

    def _retire_replica(self, now: float) -> None:
        if len(self.routable) <= 1:
            return
        index = self.routable.pop()  # newest first; it keeps draining requests it already accepted
        node = self.pod_node.pop(index, None)
        if node is not None and self.cluster is not None:
            cpu, mem = self._pod_request()
            self.cluster.release_pod(node, cpu, mem)
        self.capacity.add(-self.per_replica, now)
        self.active.add(-1, now)
        self.instances_version += 1

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.capacity.finalize(t_end)
        self.active.finalize(t_end)
        self.pods_pending.finalize(t_end)
        self.pods_starting.finalize(t_end)
        self._t_end = t_end

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out = {**super().extra_timeseries(), "replicas": _count_series(self.active, self._t_end)}
        if self.cluster_id is not None:
            out["podsReady"] = out["replicas"]
            out["podsStarting"] = _count_series(self.pods_starting, self._t_end)
            out["podsPending"] = _count_series(self.pods_pending, self._t_end)
        return out

    def replicas_mean(self) -> float:
        return self.active.window_mean()

    def replicas_series(self) -> list[float]:
        return list(self.active.mean_series())

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        out = super().explain(summary)
        if self.cluster is None:
            return out
        cpu, mem = self._pod_request()
        alloc_cpu, alloc_mem = self.cluster.alloc_cpu, self.cluster.alloc_mem
        per_node = min(math.floor(alloc_cpu / cpu + 1e-9), math.floor(alloc_mem / mem + 1e-9))
        out.append(
            formula(
                "pods_per_node",
                "Pods per node",
                "pods per node = min(⌊allocatable CPU ÷ CPU request⌋, ⌊allocatable memory ÷ memory request⌋)",
                f"min(⌊{num(alloc_cpu, 2)} ÷ {num(cpu, 2)}⌋, ⌊{num(alloc_mem, 2)} ÷ {num(mem, 2)}⌋)",
                float(per_node),
                "pods",
                note="Counts this service alone; pods of other services on the same nodes take their share.",
                source="https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/",
                metric="podsPending",
            )
        )
        return out

    def extra_summary(self) -> dict[str, Any]:
        out = {
            **super().extra_summary(),
            "replicasMean": round(self.active.window_mean(), 3),
            "replicasPeak": max(self.active.maxes) if self.active.maxes else len(self.routable),
            "replicasEnd": len(self.routable),
        }
        if self.cluster_id is not None:
            out["cluster"] = self.cluster_id
            out["podsPendingPeak"] = max(self.pods_pending.maxes) if self.pods_pending.maxes else 0
        return out


class ServerlessRuntime(RuntimeComponent):
    """Function-as-a-service: one request per instance, warm reuse, cold starts, throttling."""

    def __init__(self, ctx: SimContext, node: NodeSpec, config: ServiceConfig) -> None:
        super().__init__(ctx, node, config, config.concurrency_limit)
        self.m.has_cpu_split = True
        self.rng = ctx.rng(f"node:{node.id}:service")
        self.cold_rng = ctx.rng(f"node:{node.id}:cold")
        # warm idle instances, oldest release first: (release time, start time)
        self.idle: deque[tuple[float, float]] = deque()
        self.alive = 0
        self.instances = Gauge(ctx.tl)
        self.keep_warm_ms = config.keep_warm_s * 1000.0
        self._deps: dict[str, list[EdgeRuntime]] = {}
        self._t_end: float | None = None
        self.cold_starts = 0
        self.cold_dist = config.cold_start()
        self.warm_cfg = config.warmup()
        self.scale_bucket = TokenBucket(config.scale_out_rate / 10.0, float(config.scale_out_rate))
        # the function's arrivals over the last second, when the service time follows load
        self.curve = config.load_curve()
        self.meter = RateMeter(0.0) if self.curve else None
        ctx.env.process(self._reaper())

    def deps_for(self, op: str) -> list[EdgeRuntime]:
        d = self._deps.get(op)
        if d is None:
            d = sorted(
                (e for e in self.out_edges("out") if e.carries(op)),
                key=lambda e: (e.cfg.order, e.id),
            )
            self._deps[op] = d
        return d

    def _reclaim(self, now: float) -> None:
        n = 0
        while self.idle and now - self.idle[0][0] > self.keep_warm_ms:
            self.idle.popleft()
            n += 1
        if n:
            self.alive -= n
            self.instances.add(-n, now)

    def _reaper(self) -> Generator[simpy.Event, Any, None]:
        period = max(1000.0, min(self.keep_warm_ms / 10.0, 30_000.0))
        while True:
            yield self.env.timeout(period)
            self._reclaim(self.env.now)

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: ServiceConfig = self.config
        now = env.now
        m.arrive(now)
        if self.meter is not None:
            self.meter.arrive(now)
        self._reclaim(now)
        cold = False
        born = 0.0
        if self.idle:
            _, born = self.idle.pop()  # most recently used instance is the warmest
        elif self.alive < cfg.concurrency_limit and self.scale_bucket.try_take(now):
            self.alive += 1
            self.instances.add(1, now)
            cold = True
            born = now
        else:
            m.reject("throttled", now)
            span(req, self.id, "rejected", now, now, "throttled: concurrency or scale-out rate")
            return rejected("throttled", self.id)
        start = now
        m.busy.add(1, start)
        if cold:
            self.cold_starts += 1
            m.count("coldStarts", now)
            c = self.cold_dist.sample(self.cold_rng)
            if c > 0:
                yield env.timeout(c)
            span(req, self.id, "cold_start", start, env.now, "starting a new instance")
        st = self.sample_time(cfg.service_time_ms, self.rng)
        if self.meter is not None:
            # per instance: the warmest instance is reused first, so the function's rate is
            # spread over the instances busy now, not every one kept warm
            st *= load_factor(self.curve, self.meter.rps(env.now) / max(1, m.busy.value))
        w = self.warm_cfg
        if w is not None and born > 0 and env.now - born < w[1]:
            st *= 1.0 + (w[0] - 1.0) * (1.0 - (env.now - born) / w[1])
        m.cpu.add(1, env.now)
        # one request per instance: only a fractional vCPU (below 1) slows the CPU work, and
        # only the CPU share of the service time is CPU work
        cpu_ms = st
        if cfg.cpu_cores is not None and cfg.cpu_cores < 1:
            cpu = st * cfg.cpu_share
            cpu_ms = cpu / cfg.cpu_cores + (st - cpu)
        if cpu_ms > 0:
            yield env.timeout(cpu_ms)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        outcome = yield from self.ctx.call_dependencies(self.deps_for(req.op), req, self.id)
        end = env.now
        m.busy.add(-1, end)
        m.hold.record(end, end - start)
        self.idle.append((end, born))
        m.depart(end, outcome, req)
        return outcome

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.instances.finalize(t_end)
        self._t_end = t_end

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {
            "replicas": _count_series(self.instances, self._t_end)
        }
        if "coldStarts" in self.m.extra:
            out["coldStartRps"] = list(self.m.extra["coldStarts"].rate_series())
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: ServiceConfig = self.config
        traits = [t.to_dict() for t in SERVERLESS_TRAITS]
        if self.warm_cfg is not None:
            traits += [
                t.to_dict()
                for t in cfg.profile().traits
                if t.id in ("jit_warmup", "tiered_compilation")
            ]
        return traits

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: ServiceConfig = self.config
        lam = summary.get("arrivalRps") or 0.0
        hold = summary.get("meanHoldMs") or 0.0
        need = lam * hold / 1000.0
        return [
            formula(
                "concurrency",
                "Instances needed",
                "concurrent instances = arrival rate × time per request (Little's law)",
                f"{num(lam, 2)} req/s × {num(hold, 2)} ms ÷ 1000",
                need,
                "instances",
                note=f"Limit {cfg.concurrency_limit:,}; new instances at most {cfg.scale_out_rate:,} per 10 s, each paying a cold start of about {num(self.cold_dist.expected())} ms.",
                metric="replicas",
            )
        ]

    def extra_summary(self) -> dict[str, Any]:
        return {
            "coldStartsTotal": self.cold_starts,
            "replicasMean": round(self.instances.window_mean(), 3),
            "replicasPeak": max(self.instances.maxes) if self.instances.maxes else self.alive,
            "replicasEnd": self.alive,
        }


def _cache_aside_issues(node: NodeSpec, view: GraphView) -> list[Issue]:
    """Checks for links set to 'Reads only on a cache miss': the service needs a cache lookup
    that can answer 'miss', which a read-through cache (one with its own miss link) never does."""
    issues: list[Issue] = []
    name = node.label or node.id
    outs = view.out_edges.get(node.id, [])
    fetch = [e for e in outs if e.config.reads_on_miss and "read" in e.config.request_types]
    if not fetch:
        return issues
    lookups = [
        e
        for e in outs
        if not e.config.reads_on_miss
        and "read" in e.config.request_types
        and view.nodes[e.target].type == "cache"
    ]
    if not lookups:
        issues.append(
            error(
                "edge.reads_on_miss_no_cache",
                f"{name} sends reads to {_label(view, fetch[0].target)} only on a cache miss, but "
                f"{name} has no link that reads from a cache, so those reads never happen.",
                node_id=node.id,
                edge_id=fetch[0].id,
                hint="Link the service to a cache for reads, or turn off 'Reads only on a cache "
                "miss' on this link.",
            )
        )
        return issues
    read_through = [
        e
        for e in lookups
        if any(x.source_port == "miss" for x in view.out_edges.get(e.target, []))
        and not e.config.continue_on_failure
    ]
    if len(read_through) == len(lookups):
        cache = _label(view, read_through[0].target)
        issues.append(
            warning(
                "edge.reads_on_miss_unused",
                f"{cache} reads the data store itself on a miss (its 'miss' port is connected), so "
                f"{name}'s link to {_label(view, fetch[0].target)} set to 'Reads only on a cache "
                "miss' never gets reads.",
                node_id=node.id,
                edge_id=fetch[0].id,
                hint=f"For cache-aside, remove {cache}'s miss link; for read-through, turn off "
                "'Reads only on a cache miss'.",
            )
        )
    return issues


def _label(view: GraphView, node_id: str) -> str:
    n = view.nodes.get(node_id)
    return (n.label or n.id) if n is not None else node_id


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


class ServiceModel(ComponentModel):
    type = "service"
    display_name = "Service / API Servers"
    category = "compute"
    description = (
        "Stateless application servers. Each replica has a worker pool and a bounded queue. "
        "Outgoing links are called in order for matching request types (e.g. reads to the cache, "
        "writes to the database). Runs on VMs, containers, Kubernetes (optional autoscaling) or "
        "as a serverless function."
    )
    icon = "server"
    config_model = ServiceConfig
    technology_profiles = SERVICE_PROFILES
    ports = (
        PortSpec("in", "in", "Requests", "Incoming requests."),
        PortSpec(
            "out",
            "out",
            "Dependencies",
            "Downstream calls made while handling a request. Each link's request types decide which requests use it.",
        ),
    )
    modeled = (
        "FIFO queue per replica with a size limit and a wait timeout",
        "Worker pool per replica; service time sampled from the configured distribution",
        "Technology behaviour: event loops (Node.js), stop-the-world GC pauses, JIT warm-up of new instances, runtime-specific serverless cold starts",
        "Sequential downstream calls; blocking (thread-per-request) or non-blocking I/O",
        "Autoscaling (when on): utilization-driven replica count with evaluation interval, start-up delay and scale-down stabilization",
        "Serverless (platform): one request per instance, cold starts, concurrency limit and scale-out rate (throttling), idle reclaim",
        "CPU cores (when set): the CPU share of service time needs a core; with fewer cores than workers, work queues for a core",
        "Memory (when set): requests held by a replica use memory; past the limit new requests are refused; memory per req/s adds memory that follows throughput (reported and sized, reclaimable)",
    )
    not_modeled = (
        "OOM kills and restarts (memory is a refusal limit instead); concurrent GC CPU overhead",
        "CPU per request that changes with load (one value per request: calibration fits it where load is highest)",
        "Parallel fan-out calls (dependencies are called one after another)",
        "Node/cluster capacity limits for new pods, readiness probe failures",
        "Serverless memory-to-CPU scaling (set CPU directly) and provisioned concurrency",
    )
    presets = (
        Preset(
            id="generic-vm",
            name="Generic service (VM)",
            technology="generic",
            family="VM",
            description="Fixed replicas on virtual machines, thread-per-request workers.",
            config={"cpuCores": 2, "memoryGib": 4, "technology": "generic", "platform": "vm"},
            tags=("vm", "static"),
        ),
        Preset(
            id="java-spring-docker",
            name="Java/Spring on Docker",
            technology="java",
            family="Container",
            description="Spring Boot containers with a large blocking thread pool (Tomcat-style).",
            config={
                "cpuCores": 2,
                "memoryGib": 4,
                "memoryPerRequestMb": 1,
                "memoryBaselineMb": 768,
                "technology": "java",
                "platform": "docker",
                "replicas": 2,
                "workersPerReplica": 200,
                "queueCapacity": 100,
                "serviceTimeMs": _lognormal(12, 40),
                "blockingDownstream": True,
                "costPerHour": 0.17,
            },
            tags=("container", "jvm", "blocking"),
            sources={"workersPerReplica": _SPRING, "queueCapacity": _SPRING},
        ),
        Preset(
            id="nodejs-docker",
            name="Node.js on Docker",
            technology="nodejs",
            family="Container",
            description="One event loop per container: every request's CPU work queues for that single thread, while I/O waits don't hold it.",
            config={
                "cpuCores": 1,
                "memoryGib": 1,
                "memoryPerRequestMb": 0.5,
                "memoryBaselineMb": 128,
                "technology": "nodejs",
                "platform": "docker",
                "replicas": 4,
                "workersPerReplica": 1,
                "queueCapacity": 1000,
                "serviceTimeMs": _lognormal(4, 12),
                "blockingDownstream": False,
                "costPerHour": 0.05,
            },
            tags=("container", "event-loop", "non-blocking"),
            sources={"workersPerReplica": _NODE_LOOP},
        ),
        Preset(
            id="go-k8s-hpa",
            name="Go service on Kubernetes (HPA)",
            technology="go",
            family="Kubernetes",
            description="Go pods behind a Kubernetes Service with a Horizontal Pod Autoscaler.",
            config={
                "cpuCores": 2,
                "memoryGib": 1,
                "memoryPerRequestMb": 0.25,
                "memoryBaselineMb": 64,
                "technology": "go",
                "platform": "kubernetes",
                "replicas": 2,
                "workersPerReplica": 64,
                "queueCapacity": 200,
                "serviceTimeMs": _lognormal(5, 15),
                "autoscaling": True,
                "minReplicas": 2,
                "maxReplicas": 10,
                "targetUtilization": 0.6,
                "scaleIntervalS": 15,
                "startupS": 10,
                "costPerHour": 0.05,
            },
            tags=("kubernetes", "hpa", "autoscaling"),
            sources={"scaleIntervalS": _HPA},
        ),
        Preset(
            id="python-k8s-hpa",
            name="Python service on Kubernetes (HPA)",
            technology="python",
            family="Kubernetes",
            description="Gunicorn-style worker processes in pods, autoscaled by an HPA.",
            config={
                "cpuCores": 2,
                "memoryGib": 2,
                "memoryPerRequestMb": 4,
                "memoryBaselineMb": 512,
                "technology": "python",
                "platform": "kubernetes",
                "replicas": 2,
                "workersPerReplica": 4,
                "queueCapacity": 64,
                "serviceTimeMs": _lognormal(15, 60),
                "autoscaling": True,
                "minReplicas": 2,
                "maxReplicas": 12,
                "targetUtilization": 0.6,
                "scaleIntervalS": 15,
                "startupS": 25,
                "costPerHour": 0.05,
            },
            tags=("kubernetes", "hpa", "autoscaling"),
            sources={"scaleIntervalS": _HPA},
        ),
        Preset(
            id="serverless-fn",
            name="Serverless function (Lambda-style)",
            technology="nodejs",
            family="Serverless",
            description="Function-as-a-service: an instance per concurrent request, cold starts when scaling out.",
            config={
                "cpuCores": 1,
                "memoryGib": 1.75,
                "technology": "nodejs",
                "platform": "serverless",
                "concurrencyLimit": 1000,
                "keepWarmS": 300,
                "serviceTimeMs": _lognormal(20, 60),
                "costPerHour": 0.06,
            },
            tags=("serverless", "faas", "cold-start"),
            sources={
                "cpuCores": _LAMBDA_MEMORY,
                "memoryGib": _LAMBDA_MEMORY,
                "concurrencyLimit": _LAMBDA_CONCURRENCY,
            },
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        if config.platform == "serverless":
            return int(config.concurrency_limit)
        if config.autoscaling:
            return int(config.max_replicas * config.slots_per_replica())
        return int(config.replicas * config.slots_per_replica())

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        name = node.label or node.id
        clusters = [n.id for n in view.nodes.values() if n.type == "kubernetes_cluster"]
        if config.cluster and config.cluster not in clusters:
            issues.append(
                error(
                    "service.cluster_missing",
                    f"{name} runs on cluster '{config.cluster}', which is not a Kubernetes cluster in this design.",
                    node_id=node.id,
                    path="cluster",
                    hint="Pick one of the design's clusters, or clear the setting.",
                )
            )
        elif config.cluster and config.platform != "kubernetes":
            issues.append(
                warning(
                    "service.cluster_ignored",
                    f"{name} names a cluster but runs on {config.platform}, so the cluster is ignored.",
                    node_id=node.id,
                    path="cluster",
                )
            )
        elif config.platform == "kubernetes" and not config.cluster and len(clusters) > 1:
            issues.append(
                warning(
                    "service.cluster_ambiguous",
                    f"{name} runs on Kubernetes but the design has {len(clusters)} clusters; pick one, "
                    "or its pods are not scheduled on any of them.",
                    node_id=node.id,
                    path="cluster",
                )
            )
        if config.platform == "serverless" and config.autoscaling:
            issues.append(
                warning(
                    "service.autoscaling_ignored",
                    f"{name} runs serverless, so the autoscaling settings are ignored (serverless "
                    "scales per request up to its concurrency limit).",
                    node_id=node.id,
                    hint="Turn autoscaling off, or pick a non-serverless platform.",
                )
            )
        if (
            config.autoscaling
            and config.platform != "serverless"
            and not (config.min_replicas <= config.replicas <= config.max_replicas)
        ):
            issues.append(
                warning(
                    "service.replicas_outside_range",
                    f"{name} starts with {config.replicas} replicas, outside its autoscaling range "
                    f"{config.min_replicas}-{config.max_replicas}; it will start at the nearest bound.",
                    node_id=node.id,
                )
            )
        if config.min_replicas > config.max_replicas:  # pragma: no cover - rejected by the model
            issues.append(
                error(
                    "service.autoscaling_range",
                    f"{name}: min replicas exceed max.",
                    node_id=node.id,
                )
            )
        slots = config.memory_slots()
        if slots is not None:
            if slots < 1:
                issues.append(
                    error(
                        "service.memory_too_small",
                        f"{name} has {config.memory_gib:g} GiB per replica, less than it needs at "
                        f"idle plus one request ({config.memory_baseline_mb:g} + "
                        f"{config.memory_per_request_mb:g} MiB).",
                        node_id=node.id,
                        hint="Give it more memory, or lower the memory at idle or per request.",
                    )
                )
            elif config.platform != "serverless":
                held = config.workers_per_replica + config.queue_capacity
                if slots < config.workers_per_replica:
                    issues.append(
                        warning(
                            "service.memory_below_workers",
                            f"{name} can hold only {slots} requests per replica in "
                            f"{config.memory_gib:g} GiB, fewer than its "
                            f"{config.workers_per_replica} workers; the rest are refused as out "
                            "of memory.",
                            node_id=node.id,
                            hint="Add memory or lower the workers per replica.",
                        )
                    )
                elif slots < held and config.blocking_downstream:
                    issues.append(
                        warning(
                            "service.memory_below_queue",
                            f"{name} can hold {slots} requests per replica in "
                            f"{config.memory_gib:g} GiB but its workers and queue take up to "
                            f"{held}; under load requests are refused as out of memory.",
                            node_id=node.id,
                        )
                    )
        issues.extend(_cache_aside_issues(node, view))
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        from archsim_engine.components.kubernetes import cluster_for

        if config.platform == "serverless":
            return ServerlessRuntime(ctx, node, config)
        cluster = cluster_for(config, ctx.shared.get("clusters", []))
        if cluster is not None:
            return AutoscalingServiceRuntime(
                ctx, node, config, cluster_id=cluster, autoscale=config.autoscaling
            )
        if config.autoscaling:
            return AutoscalingServiceRuntime(ctx, node, config)
        return ServiceRuntime(ctx, node, config)
