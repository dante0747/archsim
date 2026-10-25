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

from archsim_engine.components.resources import (
    CPU_CONTENTION,
    RESOURCES,
    Cores,
    core_utilization,
    cpu_field,
    memory_field,
)
from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.explain import formula, num
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge
from archsim_engine.ratelimit import TokenBucket
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    OK,
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
        CPU_CONTENTION + " Service time is treated as CPU work. Serverless: one request per "
        "instance, so only values below 1 matter.",
    )
    memory_gib: float | None = memory_field(
        "replica",
        "When set, every request a replica holds (queued, running or waiting on downstream calls) "
        "uses memory; a request that would exceed it is refused (out of memory).",
    )
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
        description="Utilization the autoscaler aims for (share of busy workers).",
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
        self.blocking = config.blocks()
        self.gc_cfg = config.gc()
        self.warm_cfg = config.warmup()
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
        if cfg.cpu_cores is not None and service_ms:
            out["cpu"] = cfg.cpu_cores * replicas * 1000.0 / service_ms
        if self.mem_slots is not None and self.mem_slots < cfg.workers_per_replica and hold_ms:
            out["memory"] = self.mem_slots * replicas * 1000.0 / hold_ms
        return out

    def extra_summary(self) -> dict[str, Any]:
        cfg: ServiceConfig = self.config
        out: dict[str, Any] = {}
        if self.core_busy is not None:
            n = self.cores[0].n if self.cores else 1
            out["cpuCoreUtilization"] = core_utilization(self.core_busy, n * self.replicas_mean())
        if self.mem_slots is not None:
            peak_mb = cfg.memory_baseline_mb + self.peak_load * cfg.memory_per_request_mb
            out["memoryPeakGiB"] = round(min(peak_mb / 1024.0, cfg.memory_gib or 0.0), 3)
        if self.gc_cfg is not None:
            out["gcPauses"] = self.m.extra["gcPauses"].window if "gcPauses" in self.m.extra else 0
        return out

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {"inflightMean": list(self.m.inflight.mean_series())}
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
        if cfg.event_loop() and svc:
            loops = self.per_replica * self.replicas_mean()
            out.append(
                formula(
                    "event_loop",
                    "Event-loop capacity",
                    "capacity = replicas × processes × 1000 ÷ CPU time per request",
                    f"{num(self.replicas_mean())} × {self.per_replica} × 1000 ÷ {num(svc, 2)} ms",
                    loops * 1000.0 / svc,
                    "req/s",
                    note="Only the CPU part of a request occupies the loop; time waiting on downstream calls does not count.",
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
        m.cpu.add(1, start)
        yield from self._cpu(req, index, st)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        span(req, self.id, "service", start, env.now, None)
        self._after_request(index)
        outcome: Outcome = OK
        deps = self.deps_for(req.op)
        if self.blocking:
            for e in deps:
                outcome = yield from self.ctx.call(e, req, self.id)
                if not outcome.ok:
                    break
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
        else:
            pool.release(tok)
            m.hold.record(env.now, env.now - start)
            for e in deps:
                outcome = yield from self.ctx.call(e, req, self.id)
                if not outcome.ok:
                    break
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

    def __init__(self, ctx: SimContext, node: NodeSpec, config: ServiceConfig) -> None:
        initial = min(max(config.replicas, config.min_replicas), config.max_replicas)
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
        self._cap_seen = 0.0
        self._t_end: float | None = None
        ctx.env.process(self._autoscaler())

    # -- routing over routable replicas -------------------------------------------------
    def instance_count(self) -> int:
        return len(self.routable)

    def instance_load(self, index: int) -> int:
        return self.load[self.routable[index % len(self.routable)]]

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        i = self.rr % len(self.routable)
        self.rr = i + 1
        return self._serve(req, self.routable[i])

    def handle_on(self, req: Request, index: int) -> Generator[simpy.Event, Any, Outcome]:
        return self._serve(req, self.routable[index % len(self.routable)])

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
        cfg: ServiceConfig = self.config
        if cfg.startup_s > 0:
            yield self.env.timeout(cfg.startup_s * 1000.0)
        now = self.env.now
        self.starting -= 1
        self.pools.append(self._new_pool())
        self.cores.append(self._new_cores())
        self._add_replica_state(now)
        self.routable.append(len(self.pools) - 1)
        self.capacity.add(self.per_replica, now)
        self.active.add(1, now)
        self.instances_version += 1

    def _retire_replica(self, now: float) -> None:
        if len(self.routable) <= 1:
            return
        self.routable.pop()  # newest first; it keeps draining requests it already accepted
        self.capacity.add(-self.per_replica, now)
        self.active.add(-1, now)
        self.instances_version += 1

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.capacity.finalize(t_end)
        self.active.finalize(t_end)
        self._t_end = t_end

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        return {**super().extra_timeseries(), "replicas": _count_series(self.active, self._t_end)}

    def replicas_mean(self) -> float:
        return self.active.window_mean()

    def extra_summary(self) -> dict[str, Any]:
        return {
            **super().extra_summary(),
            "replicasMean": round(self.active.window_mean(), 3),
            "replicasPeak": max(self.active.maxes) if self.active.maxes else len(self.routable),
            "replicasEnd": len(self.routable),
        }


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
        w = self.warm_cfg
        if w is not None and born > 0 and env.now - born < w[1]:
            st *= 1.0 + (w[0] - 1.0) * (1.0 - (env.now - born) / w[1])
        m.cpu.add(1, env.now)
        # one request per instance: only a fractional vCPU (below 1) slows the CPU work
        cpu_ms = st / cfg.cpu_cores if cfg.cpu_cores is not None and cfg.cpu_cores < 1 else st
        if cpu_ms > 0:
            yield env.timeout(cpu_ms)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        outcome: Outcome = OK
        for e in self.deps_for(req.op):
            outcome = yield from self.ctx.call(e, req, self.id)
            if not outcome.ok:
                break
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
        "CPU cores (when set): service time needs a core; with fewer cores than workers, work queues for a core",
        "Memory (when set): requests held by a replica use memory; past the limit new requests are refused",
    )
    not_modeled = (
        "OOM kills and restarts (memory is a refusal limit instead); concurrent GC CPU overhead",
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
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        if config.platform == "serverless":
            return ServerlessRuntime(ctx, node, config)
        if config.autoscaling:
            return AutoscalingServiceRuntime(ctx, node, config)
        return ServiceRuntime(ctx, node, config)
