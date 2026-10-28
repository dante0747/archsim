"""Auto sizes against what the simulation then does, on every template (scripts/sizing_audit.py).

Each template's design as drawn is simulated (compressed to SECONDS) and checked: CPU at 25-75% of
each auto-sized part's vCPU at the p95 (or a stated reason: the smallest type, capped by its
workers, a bigger type for its memory, its load held back upstream, or the run's bottleneck by its
workers); no auto-sized part the bottleneck because of its CPU or memory; memory peak ≤ need ≤
instance; a re-run of the input reproduces the types and cost; a higher request rate never gets a
cheaper or smaller type; per-request cost lines within 10% of a hand calculation; and the monthly
cost at most 1.15 times the reference below.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import sizing_audit as audit  # noqa: E402

TEMPLATES = sorted(p.stem for p in audit.EXAMPLES.glob("*.json"))
SECONDS = 15.0

REFERENCE: dict[str, float] = {
    "auth-token-introspection": 501,  # db: 1.44 cores busy at the peak, 4 vCPU (2 would run 72%)
    "black-friday-kubernetes": 825,  # orders DB: 2.06 cores at the 5x ramp's peak, 4 vCPU
    "cache-aside-starter": 334,  # by hand below
    "cdn-video-streaming": 1821,
    "cqrs-kafka-read-model": 3011,
    "flash-sale-order-flow": 1391,
    "iot-cassandra-quorum": 768,  # ring: Cassandra on full cores (c6g.medium), not a 1-GiB t4g
    "kafka-order-pipeline": 1610,  # db: 3.13 cores at the peak, 8 vCPU (4 would run 78%)
    "kubernetes-pods-pending": 232,
    "kubernetes-shared-pool": 845,
    "llm-rag-assistant": 189276,  # nearly all third-party LLM calls at the base load
    "microservices-call-chain": 1089,
    "mongodb-hot-shard": 1254,
    "mysql-replica-lag": 1289,
    "nats-live-telemetry": 2242,
    "notification-fanout-quotas": 283532,  # nearly all SMS and email calls at the base load
    "public-api-slow-dependency": 1246,
    "queueing-lab": 73,
    "retry-storm-ttl-cache": 993,  # db: 10 connections x 50% CPU, 8 vCPU at most (was 32)
    "right-size-catalog-api": 672,
    "search-opensearch-catalog": 2538,
    "serverless-burst": 2007,  # by hand in test_cost_managed.py
    "serverless-image-pipeline": 19365,
    "social-feed-hot-key": 1817,  # cache: its keys +25% need a cache.r6g.xlarge
    "sqlserver-saas-blocking": 1078,
}
"""Monthly cost ($) of the instance types picked by hand for each template's stated peak, at the
60% CPU target: the cheapest type that covers each part's CPU and memory, a t4g type where its CPU
at the base load stays under the baseline; per-request lines at the run's base load (a
SECONDS-long run). Recorded on 2026-10-28 (engine 0.13.0, the AWS snapshot of that date), where
the hand pick matched auto-sizing on every template; the bound catches a change that makes
auto-sizing dearer.

The starter by hand: load balancer t4g.micro $0.0084/h, 2 API servers c6g.xlarge $0.136/h, Redis
cache.t4g.micro $0.016/h, PostgreSQL db.m6g.large $0.159/h plus 10 GiB at $0.115/GiB-month:
730 x 0.4554 + 1.15 = $333.6.

What the run measured is lower on six templates (``sizing_audit.py --reference``: the cheapest
types their simulated p95 CPU allows): auth-token -$72, black-friday -$283 (its checkout
saturates by design, so the database gets less than the peak), iot-cassandra -$336, kafka -$341,
llm-rag -$24, social-feed -$150. That is what right-sizing from the run is for; sizing for the
design's stated peak is what auto-sizing promises."""


@pytest.mark.parametrize("name", TEMPLATES)
def test_auto_sizes_hold_up_in_the_simulation(name: str) -> None:
    audit.REFERENCE.clear()
    audit.REFERENCE.update(REFERENCE)
    result, _ = audit.audit_template(name, SECONDS)
    checks = {c.check for c in result.checks}
    assert {"rerun", "cost"} <= checks, checks  # every template; the rest per auto-sized part
    failed = [f"{c.check} {c.part}: {c.detail}" for c in result.failed]
    assert not failed, "\n".join(failed)


def test_every_template_has_a_reference() -> None:
    assert sorted(REFERENCE) == TEMPLATES
