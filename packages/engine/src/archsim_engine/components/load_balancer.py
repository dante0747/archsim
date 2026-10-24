"""Layer-7 load balancer distributing requests across downstream instances."""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.distributions import Constant, Distribution
from archsim_engine.issues import Issue, error
from archsim_engine.metrics import Gauge
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, ui
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


class LoadBalancerRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: LoadBalancerConfig) -> None:
        super().__init__(ctx, node, config, config.max_connections)
        self.rng = ctx.rng(f"node:{node.id}:lb")
        self.overhead_rng = ctx.rng(f"node:{node.id}:overhead")
        self._candidates: dict[str, list[Candidate]] = {}
        self._rr: dict[str, int] = {}

    @property
    def occupancy(self) -> Gauge:
        return self.m.inflight

    def candidates(self, op: str) -> list[Candidate]:
        c = self._candidates.get(op)
        if c is None:
            c = []
            for e in self.out_edges("out"):
                if not e.carries(op):
                    continue
                target = self.ctx.components[e.target_id]
                for i in range(target.instance_count()):
                    c.append((e, target, i))
            self._candidates[op] = c
            self._rr[op] = 0
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
        start = env.now
        d = cfg.overhead_ms.sample(self.overhead_rng)
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
        "Instance selection per policy across all replicas of linked targets",
        "Per-link timeout and retries that avoid the failed instance",
        "Connection limit (rejects beyond max connections)",
    )
    not_modeled = ("Health checks and ejection", "TLS handshakes", "Sticky sessions")

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
