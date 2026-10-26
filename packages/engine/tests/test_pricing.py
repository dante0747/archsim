"""Price snapshots: the format, snapping auto sizes to the cheapest covering instance type, and
cost lines that cite their SKU and snapshot date. The snapshot used here is a fixture with
made-up prices for a fictional provider; ArchSim ships no provider snapshot yet."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from archsim_engine import analyze_document, create_default_registry, load_document, run_simulation
from archsim_engine.migrations import migrate_document
from archsim_engine.pricing import (
    GENERIC,
    PricingError,
    available,
    choose_machine,
    load_snapshot,
    parse_snapshot,
)
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "pricing"
SNAPSHOT_FILE = FIXTURES / "example-cloud-test-1-2026-10-01.json"
PRICING = {"provider": "example-cloud", "region": "test-1"}


@pytest.fixture(autouse=True)
def _prices_dir(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARCHSIM_PRICES_DIR", str(FIXTURES))


def starter(pricing: dict | None = None) -> dict:
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    if pricing is not None:
        d["pricing"] = pricing
    return d


def test_snapshots_are_validated_and_found() -> None:
    data = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    snap = parse_snapshot(data)
    assert snap.label == "example-cloud test-1 (2026-10-01)" and snap.has_shapes()
    assert {
        "provider": "example-cloud",
        "region": "test-1",
        "date": "2026-10-01",
    }.items() <= available()[0].items()
    assert load_snapshot("example-cloud", "test-1").snapshot_date == "2026-10-01"
    assert load_snapshot("generic") is GENERIC and not GENERIC.has_shapes()
    for broken, where in (
        ({**data, "format": "prices/2"}, "format"),
        ({**data, "sources": []}, "sources"),
        (
            {
                **data,
                "compute": [{"sku": "x", "family": "f", "vcpu": 0, "memoryGib": 1, "hourly": {}}],
            },
            "vcpu",
        ),
    ):
        with pytest.raises(PricingError, match=where):
            parse_snapshot(broken)
    with pytest.raises(PricingError, match="no price snapshot for aws us-east-1"):
        load_snapshot("aws", "us-east-1")


def test_cover_picks_the_cheapest_type_that_fits() -> None:
    snap = load_snapshot("example-cloud", "test-1")
    # 4 vCPU and 0.5 GiB: the compute-optimised 4x8 is cheaper than the general 4x16
    assert snap.cover("compute", 4, 0.5).sku == "cpu.4x8"  # type: ignore[union-attr]
    # memory-heavy: the memory family wins
    assert snap.cover("compute", 2, 12).sku == "mem.2x16"  # type: ignore[union-attr]
    # families restrict the list that has them
    assert snap.cover("compute", 4, 0.5, families=("gp",)).sku == "gp.4x16"  # type: ignore[union-attr]
    assert snap.cover("database", 2, 4, engine="postgresql", families=("gp",)).sku == "db.gp.2x8"  # type: ignore[union-attr]
    # nothing that large
    assert snap.cover("compute", 64, 1) is None
    # an engine with no managed type runs on compute instances
    m = choose_machine(snap, "database", {"technology": "cassandra"}, 2, 8)
    assert m is not None and m.self_managed and m.sku is not None and m.sku.sku == "gp.2x8"
    # not billed per machine
    assert choose_machine(snap, "service", {"platform": "kubernetes"}, 2, 2) is None
    assert choose_machine(snap, "traffic_source", {}, 1, 1) is None


def test_auto_sizes_snap_to_real_shapes_and_costs_cite_them() -> None:
    plain = analyze_document(starter(), REGISTRY)
    priced = analyze_document(starter(PRICING), REGISTRY)
    snap = load_snapshot("example-cloud", "test-1")
    by_sku = {s.sku: s for items in snap.skus.values() for s in items}
    for nid, entry in priced["sizing"]["nodes"].items():
        if entry.get("skipped") or not entry["specs"]:
            continue
        sku = by_sku[entry["sku"]]
        before = plain["sizing"]["nodes"][nid]["auto"]
        # a real shape that covers what the part needs
        assert entry["auto"]["cpuCores"] in (None, sku.vcpu)
        assert entry["auto"]["memoryGib"] in (None, sku.memory_gib)
        assert (before["cpuCores"] or 0) <= sku.vcpu and (
            before["memoryGib"] or 0
        ) <= sku.memory_gib
        assert "snapped to " + sku.sku in " ".join(entry["why"].values())
    lines = {c["id"]: c for c in priced["cost"]["byNode"]}
    db = lines["db"]
    assert (
        db["priceSource"] == "snapshot"
        and db["sku"].startswith("db.")
        and db["snapshotDate"] == "2026-10-01"
    )
    storage = priced["sizing"]["nodes"]["db"]["specs"]["storageGib"]["value"]
    expected = by_sku[db["sku"]].hourly["on_demand"] + storage * 0.1 / 730
    assert db["unitPrice"] == pytest.approx(expected, abs=1e-4)
    assert priced["cost"]["rates"]["kind"] == "snapshot"
    assert priced["cost"]["pricing"]["provider"] == "example-cloud"
    assert any("made-up prices" in n for n in priced["cost"]["notes"])
    # the generic default keeps computed sizes, no SKUs, illustrative rates
    assert all("sku" not in c for c in plain["cost"]["byNode"])
    assert plain["cost"]["rates"]["kind"] == "illustrative"


def test_a_run_simulates_the_snapped_shapes_deterministically() -> None:
    doc = starter(PRICING)
    doc["workload"].update(durationS=10, warmupS=2)
    doc["scenarios"] = []
    a, b = run_simulation(copy.deepcopy(doc)), run_simulation(copy.deepcopy(doc))
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]
    api = next(n for n in a["input"]["architecture"]["nodes"] if n["id"] == "api")
    sku = a["sizing"]["nodes"]["api"]["sku"]
    shape = load_snapshot("example-cloud", "test-1").cover(
        "compute", api["config"]["cpuCores"], api["config"]["memoryGib"]
    )
    assert shape is not None and shape.sku == sku
    assert a["input"]["pricing"]["provider"] == "example-cloud"
    assert any(c.get("sku") == sku for c in a["cost"]["byNode"])


def test_a_missing_snapshot_warns_and_falls_back_to_generic(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("ARCHSIM_PRICES_DIR")
    doc = starter({"provider": "example-cloud", "region": "test-1"})
    analysis = analyze_document(doc, REGISTRY)
    assert not any(e.get("sku") for e in analysis["sizing"]["nodes"].values())
    assert any("no price snapshot" in n for n in analysis["cost"]["notes"])
    from archsim_engine import validate_document

    issues = validate_document(load_document(doc), REGISTRY).issues
    assert any(i.code == "pricing.snapshot_missing" and i.severity == "warning" for i in issues)


def test_schema_1_1_documents_migrate_to_1_2() -> None:
    raw = starter()
    raw["schemaVersion"] = "1.1"
    out, notes = migrate_document(raw)
    assert out["schemaVersion"] == "1.2" and notes == ["migrated schema 1.1 -> 1.2"]
    assert load_document(raw).pricing.provider == "generic"
    from archsim_engine import DocumentInvalidError

    with pytest.raises(DocumentInvalidError):
        load_document({**starter(), "pricing": {"provider": "Not Valid!"}})
