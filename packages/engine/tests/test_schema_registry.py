"""Schema round-trips, migrations, metric primitives and the component extension interface."""

from __future__ import annotations

import json
import random
from collections.abc import Generator
from typing import Any

import pytest
import simpy
from pydantic import Field, ValidationError

from archsim_engine import (
    ArchitectureDocument,
    ComponentModel,
    MigrationError,
    PortSpec,
    create_default_registry,
    document_json_schema,
    load_document,
    migrate_document,
    prepare_run,
    run_simulation,
    ui,
    validate_document,
)
from archsim_engine.distributions import Constant, Exponential, LogNormal, Uniform
from archsim_engine.metrics import Gauge, LatencyHistogram, Timeline
from archsim_engine.runtime import OK, Outcome, Request, RuntimeComponent, SimContext
from archsim_engine.schema import CamelModel, NodeSpec, Workload
from engine_builders import EXAMPLES, doc, edge, load_example, node


@pytest.mark.parametrize("path", sorted(EXAMPLES.glob("*.json")), ids=lambda p: p.name)
def test_every_example_is_valid_and_round_trips(path: Any) -> None:
    raw = json.loads(path.read_text(encoding="utf-8"))
    parsed = load_document(raw)
    result = validate_document(parsed, create_default_registry())
    assert result.valid, [i.message for i in result.errors]
    again = ArchitectureDocument.model_validate(parsed.to_json_dict())
    assert again == parsed
    for sc in parsed.scenarios:  # every scenario resolves and validates
        prepare_run(raw, sc.id)


def test_layout_records_link_sides_without_affecting_the_model() -> None:
    plain = load_example("cache-aside-starter.json")
    raw = json.loads(json.dumps(plain))
    first_edge = raw["architecture"]["edges"][0]["id"]
    raw["layout"]["edges"] = {first_edge: {"sourceSide": "bottom", "targetSide": "top"}}
    parsed = load_document(raw)
    assert parsed.layout.edges[first_edge].source_side == "bottom"
    assert parsed.to_json_dict()["layout"]["edges"][first_edge] == {
        "sourceSide": "bottom",
        "targetSide": "top",
    }
    assert prepare_run(raw).resolved.architecture == prepare_run(plain).resolved.architecture
    raw["layout"]["edges"] = {first_edge: {"sourceSide": "diagonal"}}
    with pytest.raises(ValidationError):
        ArchitectureDocument.model_validate(raw)


def test_json_schema_is_exported() -> None:
    schema = document_json_schema()
    assert schema["properties"]["schemaVersion"]["const"] == "1.0"
    assert "workload" in schema["properties"]


def test_distributions() -> None:
    rng = random.Random(1)
    assert Constant(value=3).sample(rng) == 3
    ln = LogNormal(median=10, p95=40)
    samples = sorted(ln.sample(rng) for _ in range(20000))
    assert samples[10000] == pytest.approx(10, rel=0.05)
    assert samples[19000] == pytest.approx(40, rel=0.08)
    assert Exponential(mean=5).expected() == 5
    assert Uniform(min=1, max=3).expected() == 2
    with pytest.raises(ValidationError):
        LogNormal(median=10, p95=5)
    with pytest.raises(ValidationError):
        Uniform(min=5, max=1)


def test_workload_rates() -> None:
    w = Workload(
        rps=100,
        durationS=60,
        warmupS=5,
        pattern={"kind": "burst", "burstRps": 300, "startS": 10, "durationS": 5},
    )
    assert w.rate_at(5) == 100 and w.rate_at(12) == 300
    assert w.expected_arrivals() == 100 * 55 + 300 * 5
    with pytest.raises(ValidationError):
        Workload(durationS=10, warmupS=10)


def test_migration_chain_and_errors() -> None:
    table = {"0.9": ("1.0", lambda d: {**d, "migrated": True})}
    out, notes = migrate_document({"schemaVersion": "0.9"}, migrations=table)
    assert out["schemaVersion"] == "1.0" and out["migrated"] and notes
    with pytest.raises(MigrationError, match="newer"):
        migrate_document({"schemaVersion": "2.0"})
    with pytest.raises(MigrationError, match="no migration path"):
        migrate_document({"schemaVersion": "0.1"})
    with pytest.raises(MigrationError, match="missing"):
        migrate_document({})


def test_gauge_integrates_exactly() -> None:
    tl = Timeline(end_ms=1000, window_start=200, window_end=800)
    g = Gauge(tl)
    g.add(2, 100)  # level 2 from 100..500
    g.add(-1, 500)  # level 1 from 500..900
    g.add(-1, 900)
    g.finalize(1000)
    assert sum(g.area) == pytest.approx(2 * 400 + 1 * 400)
    assert g.window_mean() == pytest.approx((2 * 300 + 1 * 300) / 600)
    assert g.peak_window == 2


def test_histogram_percentiles() -> None:
    h = LatencyHistogram()
    for v in range(1, 1001):
        h.record(float(v))
    assert h.percentile(50) == pytest.approx(500, rel=0.02)
    assert h.percentile(99) == pytest.approx(990, rel=0.02)
    assert h.mean() == pytest.approx(500.5)
    assert LatencyHistogram().percentile(50) is None


def test_catalog_describes_fields_for_the_ui() -> None:
    cat = {c["type"]: c for c in create_default_registry().catalog()}
    assert {"traffic_source", "load_balancer", "service", "cache", "database"} <= set(cat)
    svc = {f["key"]: f for f in cat["service"]["fields"]}
    assert svc["replicas"]["kind"] == "integer" and svc["replicas"]["min"] == 1
    assert svc["serviceTimeMs"]["kind"] == "distribution" and svc["serviceTimeMs"]["assumption"]
    assert svc["queueTimeoutMs"]["nullable"] and svc["queueTimeoutMs"]["unit"] == "ms"
    cache = {f["key"]: f for f in cat["cache"]["fields"]}
    assert cache["hitModel"]["options"] == ["fixed_ratio", "ttl_keyspace"]
    assert cache["hitRatio"]["visibleWhen"] == {"hitModel": "fixed_ratio"}
    miss = next(p for p in cat["cache"]["ports"] if p["name"] == "miss")
    assert miss["requestTypes"] == ["read"] and miss["maxEdges"] == 1


# --- extension interface ------------------------------------------------------------------------


class DelayConfig(CamelModel):
    delay_ms: float = Field(default=5.0, ge=0, json_schema_extra=ui(unit="ms", assumption=True))


class DelayRuntime(RuntimeComponent):
    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        self.m.arrive(self.env.now)
        yield self.env.timeout(self.config.delay_ms)
        self.m.depart(self.env.now, OK)
        return OK


class FixedDelayModel(ComponentModel):
    """A minimal third-party component: adds a fixed delay, unlimited concurrency."""

    type = "fixed_delay"
    display_name = "Fixed delay"
    category = "network"
    description = "Adds a constant delay."
    config_model = DelayConfig
    ports = (PortSpec("in", "in", "In"),)

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return DelayRuntime(ctx, node, config, None)


def test_custom_component_can_be_registered_and_simulated() -> None:
    registry = create_default_registry(include_plugins=False)
    registry.register(FixedDelayModel())
    assert any(c["type"] == "fixed_delay" for c in registry.catalog())
    d = doc(
        [node("clients", "traffic_source"), node("d", "fixed_delay", delayMs=25)],
        [edge("c-d", "clients", "d")],
        rps=50,
        durationS=10,
        warmupS=1,
    )
    r = run_simulation(d, registry=registry)
    assert r["summary"]["successRate"] == 1.0
    assert r["summary"]["latencyMs"]["p50"] == pytest.approx(25, rel=0.03)
    with pytest.raises(ValueError, match="already registered"):
        registry.register(FixedDelayModel())


def test_starter_document_exports_and_reimports_identically() -> None:
    raw = load_example("cache-aside-starter.json")
    first = load_document(raw).to_json_dict()
    second = load_document(json.loads(json.dumps(first))).to_json_dict()
    assert first == second


def test_web_catalog_fixture_matches_registry() -> None:
    """The web unit tests use a copy of the catalog; regenerate it with
    `uv run python scripts/export_web_fixtures.py` when component metadata changes."""
    import importlib.util

    script = EXAMPLES.parent / "scripts" / "export_web_fixtures.py"
    spec = importlib.util.spec_from_file_location("export_web_fixtures", script)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    committed = json.loads(module.FIXTURE.read_text(encoding="utf-8"))
    assert committed == json.loads(json.dumps(module.catalog()))
    # the engine's analysis of the starter, which web tests use instead of re-implementing rules
    analysis = json.loads(module.ANALYSIS.read_text(encoding="utf-8"))
    assert analysis == json.loads(json.dumps(module.analysis()))
