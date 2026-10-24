"""Stateless application service (e.g. API servers) with replicas, worker pools and queues.

Three runtimes share one config:

* static replicas (VM / Docker / Kubernetes without autoscaling) - the original model;
* autoscaling replicas (HPA-like): capacity follows utilization with an evaluation interval and a
  pod start-up delay;
* serverless functions: one request per instance, cold starts, a concurrency limit and idle
  reclaim.
"""

from __future__ import annotations

import math
from collections import deque
from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field, model_validator

from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge
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
)
from archsim_engine.schema import CamelModel, NodeSpec

AUTOSCALE = {"autoscaling": True}
SERVERLESS = {"platform": "serverless"}


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
        description="Runtime or framework. A label for the design; behaviour comes from the settings below.",
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
        description="Requests one replica processes concurrently (threads/worker processes).",
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
    cold_start_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=300.0, p95=900.0),
        description="Extra time for a request that has to start a new instance (ms).",
        json_schema_extra=ui(
            label="Cold start",
            unit="ms",
            group="Serverless",
            visible_when=SERVERLESS,
            assumption=True,
        ),
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


class ServiceRuntime(RuntimeComponent):
    """Fixed number of replicas; each replica is a worker pool with its own queue."""

    def __init__(
        self, ctx: SimContext, node: NodeSpec, config: ServiceConfig, initial: int | None = None
    ) -> None:
        n = initial if initial is not None else config.replicas
        super().__init__(ctx, node, config, n * config.workers_per_replica)
        self.m.has_cpu_split = True
        self.pools = [self._new_pool() for _ in range(n)]
        self.load = [0] * n
        self.rr = 0
        self.rng = ctx.rng(f"node:{node.id}:service")
        self._deps: dict[str, list[EdgeRuntime]] = {}

    def _new_pool(self) -> WorkerPool:
        cfg: ServiceConfig = self.config
        return WorkerPool(
            self.ctx.env, cfg.workers_per_replica, cfg.queue_capacity, cfg.queue_timeout_ms, self.m
        )

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
        self.load[index] += 1
        pool = self.pools[index]
        tok, reason = yield from pool.acquire()
        if tok is None:
            self.load[index] -= 1
            m.reject(reason or "rejected", env.now)
            return rejected(reason or "rejected", self.id)
        start = env.now
        st = self.sample_time(cfg.service_time_ms, self.rng)
        m.cpu.add(1, start)
        if st > 0:
            yield env.timeout(st)
        m.cpu.add(-1, env.now)
        m.service.record(env.now, st)
        outcome: Outcome = OK
        deps = self.deps_for(req.op)
        if cfg.blocking_downstream:
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
        self.capacity.add(initial * config.workers_per_replica, 0.0)
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
        self.load.append(0)
        self.routable.append(len(self.pools) - 1)
        self.capacity.add(cfg.workers_per_replica, now)
        self.active.add(1, now)
        self.instances_version += 1

    def _retire_replica(self, now: float) -> None:
        if len(self.routable) <= 1:
            return
        self.routable.pop()  # newest first; it keeps draining requests it already accepted
        self.capacity.add(-self.config.workers_per_replica, now)
        self.active.add(-1, now)
        self.instances_version += 1

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.capacity.finalize(t_end)
        self.active.finalize(t_end)
        self._t_end = t_end

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        return {"replicas": _count_series(self.active, self._t_end)}

    def extra_summary(self) -> dict[str, Any]:
        return {
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
        self.idle: deque[float] = deque()  # release times of warm idle instances, oldest first
        self.alive = 0
        self.instances = Gauge(ctx.tl)
        self.keep_warm_ms = config.keep_warm_s * 1000.0
        self._deps: dict[str, list[EdgeRuntime]] = {}
        self._t_end: float | None = None
        self.cold_starts = 0
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
        while self.idle and now - self.idle[0] > self.keep_warm_ms:
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
        if self.idle:
            self.idle.pop()  # most recently used instance is the warmest
        elif self.alive < cfg.concurrency_limit:
            self.alive += 1
            self.instances.add(1, now)
            cold = True
        else:
            m.reject("throttled", now)
            return rejected("throttled", self.id)
        start = now
        m.busy.add(1, start)
        if cold:
            self.cold_starts += 1
            m.count("coldStarts", now)
            c = cfg.cold_start_ms.sample(self.cold_rng)
            if c > 0:
                yield env.timeout(c)
        st = self.sample_time(cfg.service_time_ms, self.rng)
        m.cpu.add(1, env.now)
        if st > 0:
            yield env.timeout(st)
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
        self.idle.append(end)
        m.depart(end, outcome, req)
        return outcome

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        self.instances.finalize(t_end)
        self._t_end = t_end

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        return {"replicas": _count_series(self.instances, self._t_end)}

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
        "Sequential downstream calls; blocking (thread-per-request) or non-blocking I/O",
        "Autoscaling (when on): utilization-driven replica count with evaluation interval, start-up delay and scale-down stabilization",
        "Serverless (platform): one request per instance, cold starts, concurrency limit (throttling), idle reclaim",
    )
    not_modeled = (
        "CPU contention between workers, garbage collection pauses",
        "Parallel fan-out calls (dependencies are called one after another)",
        "Node/cluster capacity limits for new pods, readiness probe failures",
        "Serverless memory/CPU sizing and provisioned concurrency",
    )
    presets = (
        Preset(
            id="generic-vm",
            name="Generic service (VM)",
            technology="generic",
            family="VM",
            description="Fixed replicas on virtual machines, thread-per-request workers.",
            config={"technology": "generic", "platform": "vm"},
            tags=("vm", "static"),
        ),
        Preset(
            id="java-spring-docker",
            name="Java/Spring on Docker",
            technology="java",
            family="Container",
            description="Spring Boot containers with a large blocking thread pool (Tomcat-style).",
            config={
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
            description="Single-threaded event loop per container: one handler at a time, I/O does not block it.",
            config={
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
                "technology": "nodejs",
                "platform": "serverless",
                "concurrencyLimit": 1000,
                "coldStartMs": _lognormal(300, 900),
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
            return int(config.max_replicas * config.workers_per_replica)
        return int(config.replicas * config.workers_per_replica)

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
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        if config.platform == "serverless":
            return ServerlessRuntime(ctx, node, config)
        if config.autoscaling:
            return AutoscalingServiceRuntime(ctx, node, config)
        return ServiceRuntime(ctx, node, config)
