"""Layer-7 load balancer distributing requests across downstream instances.

The technology adds its real defaults (see ``archsim_engine.technology``): an upstream timeout
after which the proxy answers 504 (nginx 60 s, Envoy 15 s, API Gateway 29 s...) and, for Envoy,
circuit breakers that answer 503 at once when a target already has 1,024 requests in flight.
"""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.components.resources import INFO_ONLY, cpu_field, memory_field
from archsim_engine.distributions import Constant, Distribution
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
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import LB_PROFILES, Profile, resolve

BEHAVIOUR = "Technology behaviour"

Candidate = tuple[EdgeRuntime, RuntimeComponent, int]


class LoadBalancerConfig(CamelModel):
    technology: Literal[
        "generic", "nginx", "haproxy", "envoy", "traefik", "kong", "aws_alb", "api_gateway"
    ] = Field(
        default="generic",
        description=(
            "Product or managed service. It sets the upstream timeout and circuit breakers shown "
            "under Technology behaviour; presets also fill in typical numbers."
        ),
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
    upstream_timeout_ms: float | None = Field(
        default=None,
        gt=0,
        le=3_600_000,
        description=(
            "How long the balancer waits for a target's response before answering 504 itself, "
            "unless the link has a shorter timeout. Empty = the technology's default (nginx "
            "60 s, Envoy 15 s, API Gateway 29 s, ALB 60 s; none for HAProxy and generic)."
        ),
        json_schema_extra=ui(label="Upstream timeout", unit="ms", group=BEHAVIOUR),
    )
    circuit_breaker_max_requests: int | None = Field(
        default=None,
        ge=1,
        le=1_000_000,
        description=(
            "Requests in flight to one target (upstream cluster) before new ones get 503 "
            "immediately (Envoy circuit breakers, 1,024 by default). Empty = technology default."
        ),
        json_schema_extra=ui(label="Circuit breaker: max requests per target", group=BEHAVIOUR),
    )
    cpu_cores: float | None = cpu_field("instance", INFO_ONLY)
    memory_gib: float | None = memory_field("instance", INFO_ONLY)
    cost_per_hour: float = Field(
        default=0.025,
        ge=0,
        le=10_000,
        description="Price of the balancer per hour, in USD. Illustrative default; use your provider's pricing.",
        json_schema_extra=ui(label="Cost per hour", unit="$/h", group="Cost", assumption=True),
    )


def _profile(config: LoadBalancerConfig) -> Profile:
    return LB_PROFILES.get(config.technology, LB_PROFILES["generic"])


def upstream_timeout(config: LoadBalancerConfig) -> float | None:
    return resolve(config.upstream_timeout_ms, _profile(config), "upstream_timeout_ms", None)


def breaker_limit(config: LoadBalancerConfig) -> int | None:
    v = resolve(
        config.circuit_breaker_max_requests, _profile(config), "circuit_breaker_max_requests", None
    )
    return int(v) if v else None


class LoadBalancerRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: LoadBalancerConfig) -> None:
        super().__init__(ctx, node, config, config.max_connections)
        self.upstream_timeout = upstream_timeout(config)
        self.breaker = breaker_limit(config)
        self.active: dict[str, int] = {}  # requests in flight per target node
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
            tid = edge.target_id
            if self.breaker is not None and self.active.get(tid, 0) >= self.breaker:
                m.count("circuitOpen", env.now)
                span(req, self.id, "rejected", env.now, env.now, "circuit breaker overflow (503)")
                outcome = rejected("circuit_open", self.id)
                attempt += 1
                if attempt >= edge.cfg.max_attempts:
                    break
                avoid = (edge.id, index)
                continue

            def handler(r: Request, _t: RuntimeComponent = target, _i: int = index) -> Any:
                return _t.handle_on(r, _i)

            timeout = edge.cfg.timeout_ms
            reason = "dependency_timeout"
            if self.upstream_timeout is not None and (
                timeout is None or self.upstream_timeout < timeout
            ):
                timeout = self.upstream_timeout
                reason = "gateway_timeout"
            self.active[tid] = self.active.get(tid, 0) + 1
            outcome = yield from self.ctx.call_once(edge, req, handler, timeout, self.id, reason)
            self.active[tid] -= 1
            if outcome.reason == "gateway_timeout":
                m.count("upstreamTimeouts", env.now)
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

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {"inflightMean": list(self.m.inflight.mean_series())}
        if "rate_limited" in self.m.rejected:
            out["rateLimitedRps"] = list(self.m.rejected["rate_limited"].rate_series())
        if "upstreamTimeouts" in self.m.extra:
            out["upstreamTimeoutRps"] = list(self.m.extra["upstreamTimeouts"].rate_series())
        if "circuitOpen" in self.m.extra:
            out["circuitOpenRps"] = list(self.m.extra["circuitOpen"].rate_series())
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: LoadBalancerConfig = self.config
        active = {
            "circuit_breakers": self.breaker is not None,
            "account_throttle": self.bucket is not None,
        }
        return [t.to_dict() for t in _profile(cfg).traits if active.get(t.id, True)]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: LoadBalancerConfig = self.config
        out: list[dict[str, Any]] = []
        if self.upstream_timeout is not None:
            out.append(
                formula(
                    "upstream_timeout",
                    "Upstream timeout",
                    "effective timeout = min(link timeout, balancer's upstream timeout)",
                    f"upstream timeout {num(self.upstream_timeout / 1000.0)} s ({cfg.technology})",
                    self.upstream_timeout,
                    "ms",
                    note="A target slower than this gets a 504 from the balancer, while the target keeps working on the abandoned request.",
                    metric="upstreamTimeoutRps",
                )
            )
        if self.bucket is not None and cfg.rate_limit_rps:
            burst = cfg.rate_limit_burst or cfg.rate_limit_rps
            out.append(
                formula(
                    "rate_limit",
                    "Rate limit (token bucket)",
                    "admitted ≤ rate × time + burst",
                    f"{num(cfg.rate_limit_rps)} req/s, burst {num(burst)}",
                    cfg.rate_limit_rps,
                    "req/s",
                    note="Requests that find the bucket empty get 429.",
                    metric="rateLimitedRps",
                )
            )
        if self.breaker is not None:
            out.append(
                formula(
                    "circuit_breaker",
                    "Circuit breaker",
                    "503 when requests in flight to a target ≥ max requests",
                    f"max {self.breaker:,} per target",
                    float(self.breaker),
                    "requests",
                    note="By Little's law a target reaches it when arrival rate × its response time ≥ the limit.",
                )
            )
        return out


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
    technology_profiles = LB_PROFILES
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
        "Technology defaults: upstream timeout (504) and Envoy circuit breakers (503)",
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
            description=(
                "Reverse proxy with very low per-request overhead: 2 workers x 1,024 connections, "
                "two per proxied request, so 1,024 requests at once; 60 s upstream timeout."
            ),
            config={
                "cpuCores": 2,
                "memoryGib": 2,
                "technology": "nginx",
                "policy": "round_robin",
                "maxConnections": 1024,
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
                "cpuCores": 2,
                "memoryGib": 2,
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
            description="Service proxy (also the data plane of most service meshes): circuit breakers at 1,024 requests per target, 15 s route timeout.",
            config={
                "cpuCores": 2,
                "memoryGib": 2,
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
                "cpuCores": 2,
                "memoryGib": 4,
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
        up = upstream_timeout(config)
        if up is not None:
            for e in outs:
                if e.config.timeout_ms is not None and e.config.timeout_ms > up:
                    issues.append(
                        warning(
                            "lb.timeout_above_upstream",
                            f"Link '{e.id}' allows {e.config.timeout_ms:g} ms but "
                            f"{node.label or node.id} gives up after {up:g} ms (its upstream "
                            "timeout), so the longer link timeout never applies.",
                            node_id=node.id,
                            edge_id=e.id,
                            path="upstreamTimeoutMs",
                        )
                    )
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
