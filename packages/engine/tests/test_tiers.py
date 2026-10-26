"""Technology tiers (core / experimental), where each default comes from, and docs/assumptions.md."""

from __future__ import annotations

from pathlib import Path

from archsim_engine import create_default_registry, run_simulation
from archsim_engine.analyze import analyze_document
from archsim_engine.assumptions import constants, render
from archsim_engine.technology import TIER_NOTE
from engine_builders import REPO_ROOT, doc, edge, node

REGISTRY = create_default_registry(include_plugins=False)

# The core set the roadmap starts from (docs/prompts/how-to-win.md, Phase 1); the generic part of
# each kind is core too. Change it only with the owner, from validation data.
CORE = {
    "service": {"generic", "nodejs", "java", "go"},
    "database": {"postgresql", "generic"},
    "cache": {"redis", "generic"},
    "message_queue": {"kafka", "sqs", "generic"},
    "load_balancer": {"nginx", "aws_alb", "generic"},
    "kubernetes_cluster": {"kubernetes"},
}


def test_every_technology_has_a_tier_and_the_core_set_is_the_roadmaps() -> None:
    seen = {}
    for model in REGISTRY.models():
        if not model.technology_profiles:
            continue
        assert {p.tier for p in model.technology_profiles.values()} <= {"core", "experimental"}
        seen[model.type] = {t for t, p in model.technology_profiles.items() if p.tier == "core"}
    assert seen == CORE
    # experimental technologies stay available
    assert REGISTRY.get("database").technology_profiles["mongodb"].tier == "experimental"


def test_documented_values_name_real_settings_and_link_https() -> None:
    for model in REGISTRY.models():
        for tech, profile in model.technology_profiles.items():
            for trait in profile.traits:
                for key in trait.covers:
                    assert key in profile.values, (model.type, tech, trait.id, key)
                    assert trait.source and trait.source.startswith("https://")
        for preset in model.presets:
            for key, url in preset.sources.items():
                assert key in preset.config, (preset.id, key)
                assert url.startswith("https://")


def test_catalog_lists_tiers_sources_and_the_badge_text() -> None:
    cat = REGISTRY.catalog_document()
    assert cat["tierNote"] == TIER_NOTE
    comps = {c["type"]: c for c in cat["components"]}
    db = comps["database"]
    assert db["technologyTiers"]["postgresql"] == "core"
    assert db["technologyTiers"]["cassandra"] == "experimental"
    # PostgreSQL's single WAL replay worker is documented, MongoDB's 16 is illustrative
    assert db["technologySources"]["replicaApplyWorkers"]["postgresql"].startswith("https://")
    assert "mongodb" not in db["technologySources"]["replicaApplyWorkers"]
    presets = {p["id"]: p for p in comps["service"]["presets"]}
    assert presets["go-k8s-hpa"]["tier"] == "core"
    assert presets["python-k8s-hpa"]["tier"] == "experimental"
    assert set(presets["serverless-fn"]["sources"]) == {"cpuCores", "memoryGib", "concurrencyLimit"}
    # parts without technology profiles have no tier
    assert all("tier" not in p for p in comps["external_api"]["presets"])
    assert comps["external_api"]["technologyTiers"] == {}


def _design(db_tech: str) -> dict:
    return doc(
        [
            node("clients", "traffic_source"),
            node("api", "service", technology="go"),
            node("db", "database", label="Orders DB", technology=db_tech),
            node("ext", "external_api", technology="payments"),
        ],
        [edge("e1", "clients", "api"), edge("e2", "api", "db"), edge("e3", "api", "ext")],
        rps=20,
        durationS=5,
        warmupS=0,
    )


def test_reports_and_analysis_list_each_parts_tier() -> None:
    report = run_simulation(_design("cassandra"))
    techs = {t["id"]: (t["technology"], t["tier"]) for t in report["assumptions"]["technologies"]}
    assert techs == {"api": ("go", "core"), "db": ("cassandra", "experimental")}
    notes = [n for n in report["assumptions"]["modelNotes"] if n.startswith("Experimental")]
    assert notes == [
        "Experimental technologies (Orders DB): behaviour based on documentation and "
        "illustrative numbers; not yet validated against measurements."
    ]
    analysis = analyze_document(_design("cassandra"), REGISTRY)
    assert analysis["technologies"] == report["assumptions"]["technologies"]

    core = run_simulation(_design("postgresql"))
    assert not any(n.startswith("Experimental") for n in core["assumptions"]["modelNotes"])
    # tiers are metadata: they never change what is simulated
    assert {t["tier"] for t in core["assumptions"]["technologies"]} == {"core"}


def test_assumptions_doc_is_generated_and_up_to_date() -> None:
    """Regenerate with `uv run python scripts/gen_assumptions.py`."""
    committed = Path(REPO_ROOT, "docs", "assumptions.md").read_text(encoding="utf-8")
    assert committed == render(REGISTRY)


def test_assumptions_doc_marks_each_value() -> None:
    text = render(REGISTRY)
    # a documented profile value and a documented preset number, each with its link
    kafka_acks = "https://kafka.apache.org/documentation/#producerconfigs_acks"
    assert f"documented: [kafka.apache.org]({kafka_acks})" in text
    lambda_limit = "https://docs.aws.amazon.com/lambda/latest/dg/lambda-concurrency.html"
    assert f"documented: [aws.amazon.com]({lambda_limit})" in text
    # Java's GC pause is an illustrative number
    java_gc = next(line for line in text.splitlines() if "(`gcPauseMs`) | 12 ms" in line)
    assert java_gc.endswith("| illustrative |")
    names = [c.code for c in constants()]
    assert "sizing.TARGET_CPU" in names and "cost.PRICE_PER_VCPU_HOUR" in names
    assert all(f"`{n}`" in text for n in names)
