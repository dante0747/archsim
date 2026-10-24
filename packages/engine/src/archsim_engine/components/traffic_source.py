"""Traffic source: the clients. Generates requests from the workload and measures end-to-end
latency, success, errors and client timeouts."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any

import simpy
from pydantic import Field

from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import BucketedHistograms, Counter, LatencyHistogram, Timeline
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, ui
from archsim_engine.runtime import OK, Outcome, Request, RuntimeComponent, SimContext
from archsim_engine.schema import CamelModel, NodeSpec


class TrafficSourceConfig(CamelModel):
    timeout_ms: float = Field(
        default=3000.0,
        gt=0,
        le=600_000,
        description="How long a client waits for a response per attempt before giving up (ms).",
        json_schema_extra=ui(
            label="Client timeout", unit="ms", group="Client behaviour", headline=True
        ),
    )
    max_attempts: int = Field(
        default=1,
        ge=1,
        le=5,
        description="Total attempts per user request including retries (1 = no retries). Retries add load.",
        json_schema_extra=ui(label="Max attempts", group="Client behaviour"),
    )
    retry_backoff_ms: float = Field(
        default=100.0,
        ge=0,
        le=60_000,
        description="Delay before the first retry (ms); doubles for each further retry.",
        json_schema_extra=ui(label="Retry backoff", unit="ms", group="Client behaviour"),
    )


class Cohort:
    __slots__ = ("arrived", "errors", "succeeded", "timeouts")

    def __init__(self) -> None:
        self.arrived = 0
        self.succeeded = 0
        self.errors = 0
        self.timeouts = 0

    @property
    def outstanding(self) -> int:
        return self.arrived - self.succeeded - self.errors - self.timeouts

    def to_dict(self) -> dict[str, int]:
        return {
            "arrived": self.arrived,
            "succeeded": self.succeeded,
            "failed": self.errors,
            "timedOut": self.timeouts,
            "outstanding": self.outstanding,
        }


class ClientMetrics:
    """End-to-end metrics as experienced by clients."""

    def __init__(self, tl: Timeline) -> None:
        self.tl = tl
        self.offered = Counter(tl)
        self.succeeded = Counter(tl)
        self.errors = Counter(tl)
        self.timeouts = Counter(tl)
        self.attempts = Counter(tl)
        self.latency = LatencyHistogram()
        self.latency_by_op = {"read": LatencyHistogram(), "write": LatencyHistogram()}
        self.latency_series = BucketedHistograms(tl)
        self.cohorts = {"warmup": Cohort(), "measured": Cohort()}
        self.failures: dict[tuple[str, str, str | None, str | None], int] = {}

    def cohort(self, req: Request) -> Cohort:
        return self.cohorts["measured" if req.measured else "warmup"]

    def record(self, req: Request, outcome: Outcome, now: float) -> None:
        c = self.cohort(req)
        if outcome.ok:
            c.succeeded += 1
            self.succeeded.inc(now)
            lat = now - req.created_at
            self.latency_series.record(now, lat)
            if req.measured:
                self.latency.record(lat)
                self.latency_by_op[req.op].record(lat)
            return
        if outcome.reason == "client_timeout":
            c.timeouts += 1
            self.timeouts.inc(now)
        else:
            c.errors += 1
            self.errors.inc(now)
        if req.measured:
            key = (outcome.status, outcome.reason or "unknown", outcome.node_id, outcome.edge_id)
            self.failures[key] = self.failures.get(key, 0) + 1


class TrafficSourceRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: TrafficSourceConfig) -> None:
        super().__init__(ctx, node, config, None)
        self.client = ClientMetrics(ctx.tl)

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:  # pragma: no cover
        raise RuntimeError("a traffic source cannot be the target of a call")
        yield

    def submit(self, req: Request) -> Generator[simpy.Event, Any, None]:
        """Process for one user request, including client-side retries."""
        env = self.env
        ctx = self.ctx
        cfg: TrafficSourceConfig = self.config
        self.m.arrive(env.now)
        self.client.offered.inc(env.now)
        self.client.cohort(req).arrived += 1
        edge = self.out_edges("out")[0]
        handler = ctx.handler_for(edge)
        outcome: Outcome = OK
        for attempt in range(cfg.max_attempts):
            self.client.attempts.inc(env.now)
            # each attempt is its own request object so abandoned work can be attributed
            att = Request(req.id, req.op, req.key, req.created_at, req.measured)
            outcome = yield from ctx.call_once(
                edge, att, handler, cfg.timeout_ms, self.id, timeout_reason="client_timeout"
            )
            if outcome.reason == "client_timeout":
                att.abandoned = True
            if outcome.ok:
                break
            if attempt + 1 < cfg.max_attempts:
                edge.retries.inc(env.now)
                backoff = cfg.retry_backoff_ms * (2**attempt)
                if backoff > 0:
                    yield env.timeout(backoff)
        self.client.record(req, outcome, env.now)
        self.m.depart(env.now, outcome)


class TrafficSourceModel(ComponentModel):
    type = "traffic_source"
    display_name = "Clients / Traffic"
    category = "traffic"
    description = (
        "Generates the workload (RPS, read/write mix, traffic pattern) and measures what users "
        "experience: end-to-end latency, errors and client timeouts."
    )
    icon = "users"
    config_model = TrafficSourceConfig
    ports = (
        PortSpec(
            "out",
            "out",
            "Requests",
            "Sends every generated request to one entry point (usually a load balancer).",
            min_edges=1,
            max_edges=1,
        ),
    )
    is_traffic_source = True
    modeled = (
        "Open-loop arrivals: clients do not slow down when the system is slow",
        "Per-attempt client timeout and bounded retries with exponential backoff",
    )
    not_modeled = ("Closed-loop users with think time", "Client-side connection pools")

    def emitted_types(
        self, port: str, incoming: set[str], config: Any, view: GraphView
    ) -> set[str]:
        ratio = view.workload.read_ratio
        types = set()
        if ratio > 0:
            types.add("read")
        if ratio < 1:
            types.add("write")
        return types

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        generated = self.emitted_types("out", set(), config, view)
        for e in view.out_edges.get(node.id, []):
            missing = generated - set(e.config.request_types)
            if missing:
                kinds = " and ".join(sorted(missing))
                issues.append(
                    error(
                        "source.unrouted_types",
                        f"The workload generates {kinds} requests but link '{e.id}' from "
                        f"{node.label or node.id} does not carry them.",
                        node_id=node.id,
                        edge_id=e.id,
                        hint=f"Add {kinds} to the link's request types, or change the read ratio.",
                    )
                )
            if e.config.timeout_ms is not None or e.config.max_attempts != 1:
                issues.append(
                    warning(
                        "source.edge_retry_ignored",
                        f"Timeout/retry settings on link '{e.id}' are ignored; the traffic source's "
                        "own client timeout and attempts apply.",
                        edge_id=e.id,
                        hint="Configure client timeout and attempts on the traffic source node.",
                    )
                )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return TrafficSourceRuntime(ctx, node, config)
