"""Third-party API (payments, email, SMS, identity, LLM inference, object storage...).

A dependency you do not operate: each call takes a sampled latency, fails with a given
probability, and may be rate limited or capped in concurrency by the provider. There is no queue
on our side: calls above the limits fail immediately, as a provider returning 429 would.

Provider failures are the dependency's answers, not our capacity: they are recorded as failed
calls (node counters ``upstreamErrors``, ``rateLimited``, ``throttled``) and returned to the caller
with reasons ``upstream_error`` / ``rate_limited`` / ``throttled``.
"""

from __future__ import annotations

from collections.abc import Generator
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.ratelimit import TokenBucket
from archsim_engine.registry import ComponentModel, PortSpec, Preset, ui
from archsim_engine.runtime import (
    OK,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec


class ExternalApiConfig(CamelModel):
    technology: Literal[
        "payments", "email", "sms", "identity", "llm", "object_storage", "maps", "generic"
    ] = Field(
        default="generic",
        description=(
            "Kind of provider. A label only; presets set latency, error rate, limits and price to "
            "illustrative defaults."
        ),
        json_schema_extra=ui(label="Kind of service", group="Technology"),
    )
    latency_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=120.0, p95=500.0),
        description="Time the provider takes to answer one call, excluding network to it (ms).",
        json_schema_extra=ui(
            label="Response time", unit="ms", group="Performance", assumption=True, headline=True
        ),
    )
    error_rate: float = Field(
        default=0.001,
        ge=0,
        le=1,
        description="Share of calls the provider fails (5xx / errors), after its response time.",
        json_schema_extra=ui(
            label="Error rate", group="Reliability", assumption=True, headline=True, step=0.001
        ),
    )
    rate_limit_rps: float | None = Field(
        default=None,
        gt=0,
        le=1_000_000,
        description=(
            "Calls per second the provider accepts (bursts up to one second's worth); more are "
            "rejected as rate limited. Empty = no limit."
        ),
        json_schema_extra=ui(
            label="Rate limit", unit="req/s", group="Limits", assumption=True, headline=True
        ),
    )
    concurrency_limit: int | None = Field(
        default=None,
        ge=1,
        le=100_000,
        description="Calls that may be in flight at once; more are rejected as throttled. Empty = no limit.",
        json_schema_extra=ui(label="Concurrency limit", group="Limits", assumption=True),
    )
    cost_per_million_requests: float = Field(
        default=0.0,
        ge=0,
        le=10_000_000,
        description="Price per million calls, used for cost estimates only. Illustrative default.",
        json_schema_extra=ui(
            label="Cost per million calls", unit="$", group="Cost", assumption=True
        ),
    )


class ExternalApiRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: ExternalApiConfig) -> None:
        super().__init__(ctx, node, config, config.concurrency_limit)
        self.rng = ctx.rng(f"node:{node.id}:latency")
        self.err_rng = ctx.rng(f"node:{node.id}:errors")
        self.bucket = TokenBucket(config.rate_limit_rps) if config.rate_limit_rps else None
        self.active = 0

    def _fail(self, req: Request, reason: str, counter: str) -> Outcome:
        now = self.env.now
        self.m.count(counter, now)
        outcome = rejected(reason, self.id)
        self.m.depart(now, outcome, req)
        return outcome

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: ExternalApiConfig = self.config
        m.arrive(env.now)
        if self.bucket is not None and not self.bucket.try_take(env.now):
            return self._fail(req, "rate_limited", "rateLimited")
        limit = cfg.concurrency_limit
        if limit is not None and self.active >= limit:
            return self._fail(req, "throttled", "throttled")
        self.active += 1
        start = env.now
        m.busy.add(1, start)
        t0 = env.now
        d = self.sample_time(cfg.latency_ms, self.rng)
        if d > 0:
            yield env.timeout(d)
        span(req, self.id, "external", t0, env.now, "third-party call")
        m.busy.add(-1, env.now)
        self.active -= 1
        m.service.record(env.now, d)
        m.hold.record(env.now, env.now - start)
        if cfg.error_rate > 0 and self.err_rng.random() < cfg.error_rate:
            return self._fail(req, "upstream_error", "upstreamErrors")
        m.count("reads" if req.op == "read" else "writes", env.now)
        m.depart(env.now, OK, req)
        return OK


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


_ILLUSTRATIVE = "Numbers are illustrative assumptions, not a provider's published figures."


class ExternalApiModel(ComponentModel):
    type = "external_api"
    display_name = "External API"
    category = "external"
    description = (
        "A third-party service you call but do not operate (payments, email, SMS, identity, LLM "
        "inference, object storage). Each call takes the provider's response time, may fail, and "
        "may be rejected by the provider's rate or concurrency limit."
    )
    icon = "globe"
    config_model = ExternalApiConfig
    ports = (PortSpec("in", "in", "Calls", "Calls to the third-party service."),)
    modeled = (
        "Response time sampled from the configured distribution",
        "Random provider errors at the configured rate",
        "Provider rate limit (token bucket) and concurrency limit; excess calls fail immediately",
        "Price per million calls for cost estimates",
    )
    not_modeled = (
        "Provider-side queueing and degradation under your load",
        "Retry-After headers and provider-side backoff",
        "Regional outages (use a fault to inject one)",
    )
    presets = (
        Preset(
            "payments-api",
            "Payments API",
            "payments",
            "SaaS API",
            "Card payment provider: a few hundred ms per charge, rare errors, per-account rate limit. "
            "Usually priced per transaction amount, so no per-call price is set. " + _ILLUSTRATIVE,
            {
                "technology": "payments",
                "latencyMs": _lognormal(250, 900),
                "errorRate": 0.002,
                "rateLimitRps": 100,
                "costPerMillionRequests": 0,
            },
            ("payments", "third-party"),
        ),
        Preset(
            "email-api",
            "Email API",
            "email",
            "SaaS API",
            "Transactional email sending. " + _ILLUSTRATIVE,
            {
                "technology": "email",
                "latencyMs": _lognormal(120, 400),
                "errorRate": 0.001,
                "rateLimitRps": 50,
                "costPerMillionRequests": 1000,
            },
            ("email", "third-party"),
        ),
        Preset(
            "sms-api",
            "SMS API",
            "sms",
            "SaaS API",
            "SMS / messaging provider: slower and pricier per call. " + _ILLUSTRATIVE,
            {
                "technology": "sms",
                "latencyMs": _lognormal(300, 1200),
                "errorRate": 0.005,
                "rateLimitRps": 30,
                "costPerMillionRequests": 7500,
            },
            ("sms", "third-party"),
        ),
        Preset(
            "identity-provider",
            "Identity provider",
            "identity",
            "SaaS API",
            "OAuth/OIDC identity provider for token checks and logins. Usually priced per active "
            "user, so no per-call price is set. " + _ILLUSTRATIVE,
            {
                "technology": "identity",
                "latencyMs": _lognormal(80, 250),
                "errorRate": 0.0005,
                "rateLimitRps": 200,
                "costPerMillionRequests": 0,
            },
            ("auth", "third-party"),
        ),
        Preset(
            "llm-api",
            "LLM inference API",
            "llm",
            "SaaS API",
            "Hosted model inference: seconds per call with a heavy tail, tight rate and concurrency "
            "limits, noticeable error rate. " + _ILLUSTRATIVE,
            {
                "technology": "llm",
                "latencyMs": _lognormal(1500, 6000),
                "errorRate": 0.01,
                "rateLimitRps": 50,
                "concurrencyLimit": 100,
                "costPerMillionRequests": 2000,
            },
            ("ai", "llm", "third-party"),
        ),
        Preset(
            "object-storage",
            "Object storage (S3-compatible)",
            "object_storage",
            "SaaS API",
            "Object GETs from S3-compatible storage: tens of ms, very high limits. "
            + _ILLUSTRATIVE,
            {
                "technology": "object_storage",
                "latencyMs": _lognormal(25, 80),
                "errorRate": 0.0001,
                "rateLimitRps": 5500,
                "costPerMillionRequests": 0.4,
            },
            ("storage", "blob", "third-party"),
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.concurrency_limit) if config.concurrency_limit else None

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return ExternalApiRuntime(ctx, node, config)
