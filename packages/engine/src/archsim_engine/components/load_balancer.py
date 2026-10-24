"""Layer-7 load balancer distributing requests across downstream instances."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.distributions import Constant, Distribution
from archsim_engine.issues import Issue, error
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
    rejected,
)
from archsim_engine.schema import CamelModel, NodeSpec

Candidate = tuple[EdgeRuntime, RuntimeComponent, int]


class LoadBalancerConfig(CamelModel):
    technology: Literal[
        "generic", "nginx", "haproxy", "envoy", "traefik", "kong", "aws_alb", "api_gateway"
    ] = Field(
        default="generic",
        description="Product or managed service. A label for the design; behaviour comes from the settings below.",
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    policy: Literal["round_robin", "least_connections", "random"] = Field(
        default="round_robin",
        description=(
            "How a target instance is chosen. Instances are the replicas of every linked target "
            "that carries the request type."
        ),
        json_schema_extra=ui(label="Balancing policy", group="Routing", headline=True),
    )
    overhead_ms: Distribution = Field(
        default_factory=lambda: Constant(value=0.2),
        description="Processing overhead added per request by the balancer itself (ms).",
        json_schema_extra=ui(label="Overhead", unit="ms", group="Performance", assumption=True),
    )
    max_connections: int = Field(
        default=10_000,
        ge=1,
        le=1_000_000,
        description="Concurrent requests the balancer accepts; beyond this new requests are rejected.",
        json_schema_extra=ui(label="Max connections", group="Capacity"),
    )
    rate_limit_rps: float | None = Field(
        default=None,
        gt=0,
        le=1_000_000,
        description="Requests per second admitted (token bucket); excess requests are rejected (HTTP 429). Empty = no limit.",
        json_schema_extra=ui(label="Rate limit", unit="req/s", group="Rate limiting"),
    )
    rate_limit_burst: float | None = Field(
        default=None,
        gt=0,
        le=10_000_000,
        description="Bucket size: requests admitted in a burst above the rate. Empty = one second's worth.",
        json_schema_extra=ui(label="Burst", unit="req", group="Rate limiting"),
    )
    cost_per_hour: float = Field(
        default=0.025,
        ge=0,
        le=10_000,
        description="Price of the balancer per hour, in USD. Illustrative default; use your provider's pricing.",
        json_schema_extra=ui(label="Cost per hour", unit="$/h", group="Cost", assumption=True),
    )


class LoadBalancerRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: LoadBalancerConfig) -> None:
        super().__init__(ctx, node, config, config.max_connections)
        self.rng = ctx.rng(f"node:{node.id}:lb")
        self.overhead_rng = ctx.rng(f"node:{node.id}:overhead")
        self._candidates: dict[str, list[Candidate]] = {}
        self._versions: dict[str, tuple[int, ...]] = {}
        self._rr: dict[str, int] = {}
        self.bucket = (
            TokenBucket(config.rate_limit_rps, config.rate_limit_burst)
            if config.rate_limit_rps
            else None
        )

    @property
    def occupancy(self) -> Gauge:
        return self.m.inflight

    def candidates(self, op: str) -> list[Candidate]:
        """Every routable instance of every target linked for ``op``. Rebuilt when a target's
        set of instances changes (autoscaling)."""
        c = self._candidates.get(op)
        edges = [e for e in self.out_edges("out") if e.carries(op)]
        version = tuple(self.ctx.components[e.target_id].instances_version for e in edges)
        if c is None or self._versions.get(op) != version:
            c = []
            for e in edges:
                target = self.ctx.components[e.target_id]
                for i in range(target.instance_count()):
                    c.append((e, target, i))
            self._candidates[op] = c
            self._versions[op] = version
            self._rr[op] = self._rr.get(op, 0) % max(1, len(c))
        return c

    def pick(self, op: str, avoid: tuple[str, int] | None) -> Candidate:
        cands = self.candidates(op)
        n = len(cands)
        policy = self.config.policy
        if policy == "random":
            idx = self.rng.randrange(n)
            if avoid and n > 1 and (cands[idx][0].id, cands[idx][2]) == avoid:
                idx = (idx + 1) % n
            return cands[idx]
        start = self._rr[op]
        self._rr[op] = (start + 1) % n
        if policy == "least_connections":
            best = None
            best_load = None
            for k in range(n):
                cand = cands[(start + k) % n]
                if avoid and n > 1 and (cand[0].id, cand[2]) == avoid:
                    continue
                load = cand[1].instance_load(cand[2])
                if best_load is None or load < best_load:
                    best, best_load = cand, load
            assert best is not None
            return best
        cand = cands[start]
        if avoid and n > 1 and (cand[0].id, cand[2]) == avoid:
            cand = cands[(start + 1) % n]
        return cand

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: LoadBalancerConfig = self.config
        m.arrive(env.now)
        if m.inflight.value > cfg.max_connections:
            m.reject("max_connections", env.now)
            return rejected("max_connections", self.id)
        if self.bucket is not None and not self.bucket.try_take(env.now):
            m.reject("rate_limited", env.now)
            return rejected("rate_limited", self.id)
        start = env.now
        d = self.sample_time(cfg.overhead_ms, self.overhead_rng)
        if d > 0:
            yield env.timeout(d)
        m.service.record(env.now, d)
        outcome: Outcome = OK
        avoid: tuple[str, int] | None = None
        attempt = 0
        while True:
            edge, target, index = self.pick(req.op, avoid)

            def handler(r: Request, _t: RuntimeComponent = target, _i: int = index) -> Any:
                return _t.handle_on(r, _i)

            outcome = yield from self.ctx.call_once(
                edge, req, handler, edge.cfg.timeout_ms, self.id
            )
            attempt += 1
            if outcome.ok or attempt >= edge.cfg.max_attempts:
                break
            edge.retries.inc(env.now)
            avoid = (edge.id, index)
            backoff = edge.cfg.retry_backoff_ms * (2 ** (attempt - 1))
            if backoff > 0:
                yield env.timeout(backoff)
        m.hold.record(env.now, env.now - start)
        m.depart(env.now, outcome, req)
        return outcome


class LoadBalancerModel(ComponentModel):
    type = "load_balancer"
    display_name = "Load Balancer"
    category = "network"
    description = (
        "Spreads requests across the replicas of its targets (round-robin, least-connections or "
        "random). Links can filter by request type, e.g. to route reads and writes differently."
    )
    icon = "split"
    config_model = LoadBalancerConfig
    ports = (
        PortSpec("in", "in", "Requests", "Incoming requests."),
        PortSpec(
            "out",
            "out",
            "Targets",
            "Instances to balance across. A retry (link max attempts > 1) avoids the instance that just failed.",
            min_edges=1,
        ),
    )
    modeled = (
        "Instance selection per policy across all routable replicas of linked targets (follows autoscaling)",
        "Per-link timeout and retries that avoid the failed instance",
        "Connection limit (rejects beyond max connections)",
        "Optional rate limit (token bucket); excess rejected as rate_limited",
    )
    not_modeled = (
        "Health checks and ejection",
        "TLS handshakes",
        "Sticky sessions",
        "Per-client or per-route rate limits",
    )
    presets = (
        Preset(
            id="nginx",
            name="Nginx",
            technology="nginx",
            family="Load balancer",
            description="Reverse proxy / load balancer with very low per-request overhead.",
            config={
                "technology": "nginx",
                "policy": "round_robin",
                "overheadMs": {"kind": "constant", "value": 0.2},
            },
            tags=("proxy", "l7"),
        ),
        Preset(
            id="haproxy",
            name="HAProxy",
            technology="haproxy",
            family="Load balancer",
            description="High-performance TCP/HTTP load balancer, least-connections balancing.",
            config={
                "technology": "haproxy",
                "policy": "least_connections",
                "overheadMs": {"kind": "constant", "value": 0.15},
            },
            tags=("proxy", "l7"),
        ),
        Preset(
            id="envoy",
            name="Envoy",
            technology="envoy",
            family="Load balancer",
            description="Service proxy (also the data plane of most service meshes).",
            config={
                "technology": "envoy",
                "policy": "least_connections",
                "overheadMs": {"kind": "constant", "value": 0.3},
            },
            tags=("proxy", "mesh"),
        ),
        Preset(
            id="kong",
            name="Kong API gateway",
            technology="kong",
            family="API gateway",
            description="API gateway in front of services, with a global rate limit.",
            config={
                "technology": "kong",
                "overheadMs": {"kind": "lognormal", "median": 1.0, "p95": 3.0},
                "rateLimitRps": 1000,
                "rateLimitBurst": 200,
                "costPerHour": 0.05,
            },
            tags=("gateway", "rate-limit"),
        ),
        Preset(
            id="aws-alb",
            name="ALB-style load balancer",
            technology="aws_alb",
            family="Load balancer",
            description="Managed cloud application load balancer (illustrative numbers, not a vendor benchmark).",
            config={
                "technology": "aws_alb",
                "overheadMs": {"kind": "lognormal", "median": 1.5, "p95": 5.0},
                "maxConnections": 100000,
                "costPerHour": 0.0225,
            },
            tags=("managed", "l7"),
        ),
        Preset(
            id="api-gateway",
            name="API-gateway-style (managed)",
            technology="api_gateway",
            family="API gateway",
            description="Managed API gateway: higher overhead per request and an account-level rate limit.",
            config={
                "technology": "api_gateway",
                "overheadMs": {"kind": "lognormal", "median": 6.0, "p95": 20.0},
                "rateLimitRps": 10000,
                "rateLimitBurst": 5000,
                "costPerHour": 0.0,
            },
            tags=("managed", "gateway", "rate-limit"),
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.max_connections)

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        outs = view.out_edges.get(node.id, [])
        if not outs:
            return issues  # arity error reported generically
        for op in sorted(view.incoming_types.get(node.id, set())):
            if not any(op in e.config.request_types for e in outs):
                issues.append(
                    error(
                        "lb.unroutable_type",
                        f"{node.label or node.id} receives {op} requests but none of its outgoing "
                        f"links carry {op}s.",
                        node_id=node.id,
                        hint=f"Add '{op}' to the request types of one of its outgoing links.",
                    )
                )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return LoadBalancerRuntime(ctx, node, config)
