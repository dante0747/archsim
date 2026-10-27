"""Client groups: the traffic sources. Each generates its share of the workload and measures
end-to-end latency, success, errors and client timeouts.

``traffic_source`` is the generic client group. ``mobile_app``, ``web_client`` and
``api_consumer`` are the same model with their own defaults (timeouts, retries, share of traffic,
network latency on their link) and a technology label. A design may have several client groups;
the workload is split between them by their traffic share.
"""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, ClassVar, Literal

import simpy
from pydantic import Field

from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import (
    BucketedHistograms,
    Counter,
    LatencyHistogram,
    LatencyWithFailures,
    Timeline,
)
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, ui
from archsim_engine.runtime import OK, Outcome, Request, RuntimeComponent, SimContext, span
from archsim_engine.schema import CamelModel, NodeSpec


def _timeout(default: float) -> Any:
    return Field(
        default=default,
        gt=0,
        le=600_000,
        description="How long a client waits for a response per attempt before giving up (ms).",
        json_schema_extra=ui(
            label="Client timeout", unit="ms", group="Client behaviour", headline=True
        ),
    )


def _attempts(default: int) -> Any:
    return Field(
        default=default,
        ge=1,
        le=5,
        description="Total attempts per user request including retries (1 = no retries). Retries add load.",
        json_schema_extra=ui(label="Max attempts", group="Client behaviour"),
    )


def _backoff(default: float) -> Any:
    return Field(
        default=default,
        ge=0,
        le=60_000,
        description="Delay before the first retry (ms); doubles for each further retry.",
        json_schema_extra=ui(label="Retry backoff", unit="ms", group="Client behaviour"),
    )


def _share(default: float) -> Any:
    return Field(
        default=default,
        gt=0,
        le=100,
        description=(
            "Share of the workload this client group sends (%). With several client groups the "
            "shares are relative: each sends its share divided by the sum of all shares, so "
            "60 / 30 / 10 means 60%, 30% and 10%. A single client group sends everything."
        ),
        json_schema_extra=ui(label="Traffic share", unit="%", group="Traffic"),
    )


def _technology(default: str) -> Any:
    return Field(
        default=default,
        description="What the client is built with. A label and logo for the design; behaviour comes from the settings.",
        json_schema_extra=ui(label="Technology", group="Technology"),
    )


class TrafficSourceConfig(CamelModel):
    timeout_ms: float = _timeout(3000.0)
    max_attempts: int = _attempts(1)
    retry_backoff_ms: float = _backoff(100.0)
    traffic_share: float = _share(100.0)


class MobileAppConfig(TrafficSourceConfig):
    technology: Literal["mobile", "ios", "android", "react_native", "flutter"] = _technology(
        "mobile"
    )
    timeout_ms: float = _timeout(10_000.0)
    max_attempts: int = _attempts(2)
    retry_backoff_ms: float = _backoff(500.0)
    traffic_share: float = _share(50.0)


class WebClientConfig(TrafficSourceConfig):
    technology: Literal["browser", "react", "nextjs", "vue", "angular", "svelte"] = _technology(
        "browser"
    )
    timeout_ms: float = _timeout(15_000.0)
    max_attempts: int = _attempts(1)
    retry_backoff_ms: float = _backoff(100.0)
    traffic_share: float = _share(35.0)


class ApiConsumerConfig(TrafficSourceConfig):
    technology: Literal["api_client", "rest", "graphql"] = _technology("api_client")
    timeout_ms: float = _timeout(5000.0)
    max_attempts: int = _attempts(3)
    retry_backoff_ms: float = _backoff(200.0)
    traffic_share: float = _share(15.0)


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
        # Failing fast must not look fast: failed requests count as a full client timeout.
        self.latency_with_failures = LatencyWithFailures()
        self.latency_by_op = {"read": LatencyHistogram(), "write": LatencyHistogram()}
        self.latency_series = BucketedHistograms(tl)
        self.cohorts = {"warmup": Cohort(), "measured": Cohort()}
        self.failures: dict[tuple[str, str, str | None, str | None], int] = {}

    def cohort(self, req: Request) -> Cohort:
        return self.cohorts["measured" if req.measured else "warmup"]

    def record(self, req: Request, outcome: Outcome, now: float, timeout_ms: float) -> None:
        c = self.cohort(req)
        lat = now - req.created_at
        if outcome.ok:
            c.succeeded += 1
            self.succeeded.inc(now)
            self.latency_series.record(now, lat)
            if req.measured:
                self.latency.record(lat)
                self.latency_with_failures.success(lat)
                self.latency_by_op[req.op].record(lat)
            return
        if outcome.reason == "client_timeout":
            c.timeouts += 1
            self.timeouts.inc(now)
        else:
            c.errors += 1
            self.errors.inc(now)
        if req.measured:
            self.latency_with_failures.failure(lat, timeout_ms)
            key = (outcome.status, outcome.reason or "unknown", outcome.node_id, outcome.edge_id)
            self.failures[key] = self.failures.get(key, 0) + 1


class ClientGroup:
    """What one client group experienced, for the per-client breakdown of the report."""

    def __init__(self, tl: Timeline) -> None:
        self.offered = Counter(tl)
        self.attempts = Counter(tl)
        self.measured = Cohort()
        self.latency = LatencyHistogram()

    def record(self, req: Request, outcome: Outcome, now: float) -> None:
        if not req.measured:
            return
        c = self.measured
        if outcome.ok:
            c.succeeded += 1
            self.latency.record(now - req.created_at)
        elif outcome.reason == "client_timeout":
            c.timeouts += 1
        else:
            c.errors += 1


class TrafficSourceRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: TrafficSourceConfig) -> None:
        super().__init__(ctx, node, config, None)
        # End-to-end metrics are shared by every client group of the run; each group also keeps
        # its own breakdown.
        client = ctx.shared.get("client")
        if client is None:
            client = ctx.shared["client"] = ClientMetrics(ctx.tl)
        self.client: ClientMetrics = client
        self.group = ClientGroup(ctx.tl)

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
        self.group.offered.inc(env.now)
        if req.measured:
            self.group.measured.arrived += 1
        edge = self.out_edges("out")[0]
        handler = ctx.handler_for(edge)
        outcome: Outcome = OK
        for attempt in range(cfg.max_attempts):
            self.client.attempts.inc(env.now)
            self.group.attempts.inc(env.now)
            # each attempt is its own request object so abandoned work can be attributed
            att = Request(req.id, req.op, req.key, req.created_at, req.measured, trace=req.trace)
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
                    t0 = env.now
                    yield env.timeout(backoff)
                    span(att, self.id, "backoff", t0, env.now, f"retry {attempt + 2} after backoff")
        now = env.now
        self.client.record(req, outcome, now, cfg.timeout_ms)
        self.group.record(req, outcome, now)
        self.m.depart(now, outcome)
        if req.trace is not None:
            status = (
                "ok"
                if outcome.ok
                else ("timeout" if outcome.reason == "client_timeout" else "error")
            )
            ctx.traces.append(
                {
                    "id": req.id,
                    "op": req.op,
                    "client": self.id,
                    "start": req.created_at,
                    "end": now,
                    "status": status,
                    "reason": None if outcome.ok else outcome.reason,
                    "failedAt": None if outcome.ok else outcome.node_id,
                    "measured": req.measured,
                    "attempts": attempt + 1,
                    "spans": req.trace,
                }
            )


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
    modeled: ClassVar[tuple[str, ...]] = (
        "Open-loop arrivals: clients do not slow down when the system is slow",
        "Per-attempt client timeout and bounded retries with exponential backoff",
    )
    not_modeled: ClassVar[tuple[str, ...]] = (
        "Closed-loop users with think time",
        "Client-side connection pools",
    )

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


class MobileAppModel(TrafficSourceModel):
    type = "mobile_app"
    display_name = "Mobile app"
    description = (
        "Users on phones (iOS, Android, cross-platform). Sends its share of the workload over a "
        "slower, more variable mobile network, waits longer and retries once by default."
    )
    icon = "smartphone"
    config_model = MobileAppConfig
    link_defaults = {"latencyMs": {"kind": "lognormal", "median": 35.0, "p95": 120.0}}
    modeled = (
        *TrafficSourceModel.modeled,
        "Its share of the workload, split from the same arrivals as the other client groups",
        "Mobile network delay as the latency of its link (default p50 35 ms / p95 120 ms one way)",
    )
    not_modeled = (
        *TrafficSourceModel.not_modeled,
        "Offline queues, background sync and app-store release lag",
    )


class WebClientModel(TrafficSourceModel):
    type = "web_client"
    display_name = "Web browser"
    description = (
        "Users in a web browser (single-page app or server-rendered site). Sends its share of the "
        "workload over a broadband network and does not retry by default."
    )
    icon = "app-window"
    config_model = WebClientConfig
    link_defaults = {"latencyMs": {"kind": "lognormal", "median": 15.0, "p95": 45.0}}
    modeled = (
        *TrafficSourceModel.modeled,
        "Its share of the workload, split from the same arrivals as the other client groups",
        "Home or office network delay as the latency of its link (default p50 15 ms / p95 45 ms one way)",
    )
    not_modeled = (
        *TrafficSourceModel.not_modeled,
        "Static assets, browser caching and CDN hits (model a CDN as a cache in front if needed)",
    )


class ApiConsumerModel(TrafficSourceModel):
    type = "api_consumer"
    display_name = "API consumers"
    description = (
        "Partners and other systems calling your public API endpoints (REST, GraphQL). Sends its "
        "share of the workload server to server, with a short timeout and SDK-style retries."
    )
    icon = "braces"
    config_model = ApiConsumerConfig
    link_defaults = {"latencyMs": {"kind": "lognormal", "median": 10.0, "p95": 30.0}}
    modeled = (
        *TrafficSourceModel.modeled,
        "Its share of the workload, split from the same arrivals as the other client groups",
        "Server-to-server internet delay as the latency of its link (default p50 10 ms / p95 30 ms one way)",
    )
    not_modeled = (
        *TrafficSourceModel.not_modeled,
        "Per-consumer API keys and quotas (put a rate-limited gateway in front to model them)",
    )
