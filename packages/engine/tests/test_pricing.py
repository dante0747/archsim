"""Price snapshots: the format, snapping auto sizes to the cheapest covering instance type, and
cost lines that cite their SKU and snapshot date. Most tests use a fixture with made-up prices
for a fictional provider (so they do not change when AWS's prices do); the last ones check the
AWS us-east-1 snapshot ArchSim ships, the templates' pins and the generic standard shapes."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path

import pytest

from archsim_engine import analyze_document, create_default_registry, load_document, run_simulation
from archsim_engine.migrations import migrate_document
from archsim_engine.pricing import (
    DIR,
    GENERIC,
    PricingError,
    available,
    choose_machine,
    default_pricing,
    load_snapshot,
    machine_kind,
    parse_snapshot,
)
from archsim_engine.sizing import GIB_PER_VCPU, standard_shape
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "pricing"
SNAPSHOT_FILE = FIXTURES / "example-cloud-test-1-2026-10-01.json"
PRICING = {"provider": "example-cloud", "region": "test-1"}


@pytest.fixture(autouse=True)
def _prices_dir(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARCHSIM_PRICES_DIR", str(FIXTURES))


def starter(pricing: dict | None = None) -> dict:
    """The starter template priced with ``pricing`` (generic rates without it)."""
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d.pop("pricing", None)
    if pricing is not None:
        d["pricing"] = pricing
    return d


def test_snapshots_are_validated_and_found() -> None:
    data = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    snap = parse_snapshot(data)
    assert snap.label == "example-cloud test-1 (2026-10-01)" and snap.has_shapes()
    listed = [a for a in available() if a["provider"] == "example-cloud"]
    assert {
        "provider": "example-cloud",
        "region": "test-1",
        "date": "2026-10-01",
    }.items() <= listed[0].items()
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
    with pytest.raises(PricingError, match="no price snapshot for aws eu-north-9"):
        load_snapshot("aws", "eu-north-9")


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
    assert out["schemaVersion"] == "1.3" and notes == [
        "migrated schema 1.1 -> 1.2",
        "migrated schema 1.2 -> 1.3",
    ]
    assert load_document(raw).pricing.provider == "generic"
    from archsim_engine import DocumentInvalidError

    with pytest.raises(DocumentInvalidError):
        load_document({**starter(), "pricing": {"provider": "Not Valid!"}})


# -- review fixes: preset prices, managed rates, notes, pinned snapshots --------------------------


def _preset(type_: str, preset_id: str) -> dict:
    return dict(next(p for p in REGISTRY.get(type_).presets if p.id == preset_id).config)


def _lines(cost: dict) -> dict[str, dict]:
    return {c["id"]: c for c in cost["byNode"]}


def _with_presets(pricing: dict | None = None) -> dict:
    """The starter with its API on the Java/Spring preset and its database on the MySQL one:
    both carry an illustrative ``costPerHour`` (0.17 and 0.33)."""
    d = starter(pricing)
    nodes = {n["id"]: n for n in d["architecture"]["nodes"]}
    nodes["api"]["config"] = {**nodes["api"]["config"], **_preset("service", "java-spring-docker")}
    nodes["db"]["config"] = {**nodes["db"]["config"], **_preset("database", "mysql")}
    return d


def test_preset_prices_are_illustrative_and_a_snapshot_reprices_them() -> None:
    plain = analyze_document(_with_presets(), REGISTRY)
    lines = _lines(plain["cost"])
    # generic rates: the preset's price is used, as before, but it is not "set" by the user
    assert (lines["api"]["priceSource"], lines["api"]["unitPrice"]) == ("preset", 0.17)
    assert (lines["db"]["priceSource"], lines["db"]["unitPrice"]) == ("preset", 0.33)
    priced = analyze_document(_with_presets(PRICING), REGISTRY)
    lines = _lines(priced["cost"])
    for nid in ("api", "db"):
        sizing = priced["sizing"]["nodes"][nid]
        # the cost line and the sizing panel agree: the snapshot's SKU and its price
        assert lines[nid]["priceSource"] == "snapshot"
        assert lines[nid]["sku"] == sizing["sku"] and lines[nid]["snapshotDate"] == "2026-10-01"
        assert lines[nid]["unitPrice"] == sizing["pricePerHour"]
    assert lines["api"]["unitPrice"] != 0.17 and lines["db"]["unitPrice"] != 0.33
    # a price the user typed stays theirs, with or without a snapshot
    doc = _with_presets(PRICING)
    next(n for n in doc["architecture"]["nodes"] if n["id"] == "api")["config"]["costPerHour"] = (
        0.42
    )
    assert _lines(analyze_document(doc, REGISTRY)["cost"])["api"]["priceSource"] == "set"
    # so does a preset's value whose provenance says a person chose it
    doc = _with_presets(PRICING)
    doc["provenance"] = {"nodes/api/costPerHour": {"kind": "assumed", "note": "our contract"}}
    line = _lines(analyze_document(doc, REGISTRY)["cost"])["api"]
    assert (line["priceSource"], line["unitPrice"]) == ("set", 0.17)


def test_a_user_price_wins_over_a_snapshots_managed_rates() -> None:
    from archsim_engine.cost import estimate_cost

    def nodes(alb: dict, queue: dict) -> list[dict]:
        return [
            {"id": "alb", "type": "load_balancer", "config": {"technology": "aws_alb", **alb}},
            {"id": "q", "type": "message_queue", "config": {"technology": "sqs", **queue}},
        ]

    own = _lines(
        estimate_cost(nodes({"costPerHour": 0.5}, {"costPerHour": 0.3}), REGISTRY, None, PRICING)
    )
    assert (own["alb"]["priceSource"], own["alb"]["unitPrice"]) == ("set", 0.5)
    assert (own["q"]["priceSource"], own["q"]["unitPrice"]) == ("set", 0.3)
    # the presets' illustrative prices are repriced at the snapshot's rates
    presets = _lines(
        estimate_cost(
            nodes(_preset("load_balancer", "aws-alb"), _preset("message_queue", "sqs")),
            REGISTRY,
            None,
            PRICING,
        )
    )
    assert (presets["alb"]["priceSource"], presets["alb"]["unitPrice"]) == ("snapshot", 0.03)
    assert (presets["q"]["priceSource"], presets["q"]["unit"]) == ("snapshot", "million")


def test_api_gateways_are_not_priced_at_the_load_balancer_hourly_rate() -> None:
    from archsim_engine.cost import estimate_cost

    gw = {"id": "gw", "type": "load_balancer", "config": _preset("load_balancer", "api-gateway")}
    line = _lines(estimate_cost([gw], REGISTRY, None, PRICING))["gw"]
    assert line["priceSource"] == "preset" and line["unitPrice"] == 0.0
    assert "billed per request" in line["basis"]
    # even with a size set, a managed gateway is not snapped to an instance type
    gw["config"]["cpuCores"] = 2
    assert "sku" not in _lines(estimate_cost([gw], REGISTRY, None, PRICING))["gw"]


def test_lines_at_generic_rates_keep_their_note_next_to_snapshot_lines() -> None:
    from archsim_engine.cost import estimate_cost

    nodes = [
        {"id": "vm", "type": "service", "config": {"cpuCores": 2, "memoryGib": 4}},
        # on Kubernetes but the design has no cluster part: priced from its size
        {
            "id": "pod",
            "type": "service",
            "config": {"platform": "kubernetes", "cpuCores": 1, "memoryGib": 2},
        },
    ]
    cost = estimate_cost(nodes, REGISTRY, None, PRICING)
    lines = _lines(cost)
    assert lines["vm"]["priceSource"] == "snapshot" and lines["pod"]["priceSource"] == "size"
    assert any("illustrative on-demand rates" in n for n in cost["notes"])


def _newer_snapshot(tmp_path: Path) -> Path:
    """A newer snapshot of the same provider and region whose cheapest database type is bigger,
    so an unpinned design snaps (and simulates) differently."""
    data = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    data["snapshotDate"] = "2026-11-01"
    for sku in data["databases"]:
        if sku["sku"] == "db.gp.4x16":
            sku["hourly"]["on_demand"] = 0.1
    d = tmp_path / "newer"
    d.mkdir()
    (d / "example-cloud-test-1-2026-11-01.json").write_text(json.dumps(data), encoding="utf-8")
    return d


def _short(doc: dict) -> dict:
    doc["workload"].update(durationS=10, warmupS=2)
    doc["scenarios"] = []
    return doc


def test_a_pinned_snapshot_gives_the_same_run_when_a_newer_one_is_installed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    pinned_doc = _short(starter({**PRICING, "snapshotDate": "2026-10-01"}))
    before = run_simulation(copy.deepcopy(pinned_doc))
    unpinned_before = run_simulation(_short(starter(PRICING)))
    sha = load_snapshot("example-cloud", "test-1").sha256
    # the run records the snapshot it used, pinned or not
    for r in (before, unpinned_before):
        assert r["input"]["pricing"]["snapshotDate"] == "2026-10-01"
        assert r["input"]["pricing"]["snapshotSha256"] == sha == r["cost"]["pricing"]["sha256"]
    monkeypatch.setenv(
        "ARCHSIM_PRICES_DIR", os.pathsep.join([str(FIXTURES), str(_newer_snapshot(tmp_path))])
    )
    after = run_simulation(copy.deepcopy(pinned_doc))
    assert after["provenance"]["resultDigest"] == before["provenance"]["resultDigest"]
    assert after["provenance"]["inputDigest"] == before["provenance"]["inputDigest"]
    assert after["cost"] == before["cost"]
    # unpinned, the newer snapshot is used (and named), so the run differs
    unpinned = run_simulation(_short(starter(PRICING)))
    assert unpinned["input"]["pricing"]["snapshotDate"] == "2026-11-01"
    assert unpinned["sizing"]["nodes"]["db"]["sku"] == "db.gp.4x16"
    assert unpinned["provenance"]["resultDigest"] != before["provenance"]["resultDigest"]
    # a run's input is a pinned document: re-running it reproduces that run
    rerun = {**copy.deepcopy(unpinned_before["input"]), "kind": "archsim.architecture"}
    again = run_simulation(rerun)
    assert again["provenance"]["resultDigest"] == unpinned_before["provenance"]["resultDigest"]


def test_a_missing_pinned_snapshot_warns_and_is_never_swapped_for_another() -> None:
    from archsim_engine import validate_document

    for pin in (
        {"snapshotDate": "2026-09-01"},
        {"snapshotDate": "2026-10-01", "snapshotSha256": "0" * 64},
    ):
        doc = starter({**PRICING, **pin})
        issues = validate_document(load_document(doc), REGISTRY).issues
        missing = [i for i in issues if i.code == "pricing.snapshot_missing"]
        assert missing and missing[0].severity == "warning"
        assert "2026-10-01 (" in missing[0].message  # what this machine has
        analysis = analyze_document(doc, REGISTRY)
        # generic rates, and said so; never the 2026-10-01 snapshot in place of the pinned one
        assert analysis["cost"]["pricing"]["illustrative"]
        assert not any(e.get("sku") for e in analysis["sizing"]["nodes"].values())
        assert any("no price snapshot" in n for n in analysis["cost"]["notes"])
    with pytest.raises(PricingError, match="of 2026-09-01"):
        load_snapshot("example-cloud", "test-1", "2026-09-01")


def test_unknown_instance_families_are_reported() -> None:
    from archsim_engine import validate_document

    doc = starter({**PRICING, "families": ["gp", "m7gg"]})
    issues = validate_document(load_document(doc), REGISTRY).issues
    unknown = [i for i in issues if i.code == "pricing.unknown_family"]
    assert len(unknown) == 1 and unknown[0].message.startswith("m7gg:")


def test_available_lists_every_snapshot_with_its_hash(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(
        "ARCHSIM_PRICES_DIR", os.pathsep.join([str(FIXTURES), str(_newer_snapshot(tmp_path))])
    )

    def ours(listed: list[dict]) -> list[dict]:
        return [a for a in listed if a["provider"] == "example-cloud"]

    assert [(a["date"], a["newest"]) for a in ours(available())] == [("2026-11-01", True)]
    every = ours(available(every_date=True))
    assert [(a["date"], a["newest"]) for a in every] == [
        ("2026-11-01", True),
        ("2026-10-01", False),
    ]
    assert all(len(a["sha256"]) == 64 for a in every)
    # the hash is of the content, not of the file's formatting or line endings
    data = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    assert parse_snapshot(data).sha256 == every[1]["sha256"]
    assert load_snapshot("example-cloud", "test-1").snapshot_date == "2026-11-01"


# -- the AWS snapshot ArchSim ships, templates and new documents, generic shapes -------------------


def _shipped_aws() -> list[Path]:
    return sorted(DIR.glob("aws-us-east-1-*.json"))


def test_the_shipped_aws_snapshots_are_clean() -> None:
    files = _shipped_aws()
    assert files, "the engine ships an AWS us-east-1 price snapshot"
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        snap = parse_snapshot(data)
        assert (snap.provider, snap.region) == ("aws", "us-east-1")
        assert path.name == f"aws-us-east-1-{snap.snapshot_date}.json"
        for category in ("compute", "database", "cache", "stream"):
            items = snap.skus[category]
            assert items, f"{path.name} has no {category} types"
            # one price per type and engine (Outposts or Local Zone rows would add a second)
            keys = [(s.sku, e) for s in items for e in (s.engines or ("",))]
            assert len(keys) == len(set(keys)), f"{path.name}: duplicate {category} types"
            assert all((s.price("on_demand") or 0) > 0 for s in items)
            # burstable types (the t families) are marked, and only they are
            for s in items:
                name = s.sku if category == "compute" else s.sku.split(".", 1)[1]
                assert s.burstable == (name[0] == "t" and name[1].isdigit()), s.sku
                # a baseline only on burstable types; in the newest, the t4g ones all have theirs
                assert s.baseline_cpu is None or s.burstable, s.sku
                if path == files[-1] and name.startswith("t4g.") and category != "stream":
                    assert s.baseline_cpu is not None and 0 < s.baseline_cpu <= 1, s.sku
        # older snapshots have fewer rates: every rate a snapshot has is a price
        assert all(v is None or v > 0 for v in snap.rates.values()), snap.rates
    # the newest has every rate the engine knows (the older ones stay as they were: stored runs
    # pin their content hash)
    newest = parse_snapshot(json.loads(files[-1].read_text(encoding="utf-8")))
    assert all(v is not None and v > 0 for v in newest.rates.values()), newest.rates


def test_templates_and_new_documents_pin_a_shipped_aws_snapshot() -> None:
    shipped = {json.loads(p.read_text(encoding="utf-8"))["snapshotDate"] for p in _shipped_aws()}
    assert default_pricing() == {
        "provider": "aws",
        "region": "us-east-1",
        "snapshotDate": max(shipped),
    }
    for path in sorted(EXAMPLES.glob("*.json")):
        pricing = json.loads(path.read_text(encoding="utf-8")).get("pricing") or {}
        assert (pricing.get("provider"), pricing.get("region")) == ("aws", "us-east-1"), path.name
        assert pricing.get("snapshotDate") in shipped, path.name
        assert load_snapshot("aws", "us-east-1", pricing["snapshotDate"]).has_shapes()


def test_the_starter_runs_on_real_aws_types_and_says_so() -> None:
    doc = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    analysis = analyze_document(doc, REGISTRY)
    snap = load_snapshot("aws", "us-east-1", doc["pricing"]["snapshotDate"])
    by_sku = {(s.sku, c): s for c, items in snap.skus.items() for s in items}
    expected = {"lb": "compute", "api": "compute", "cache": "cache", "db": "database"}
    for nid, category in expected.items():
        sku = by_sku[(analysis["sizing"]["nodes"][nid]["sku"], category)]
        assert not sku.burstable, f"{nid} snapped to a burstable type"
    lines = _lines(analysis["cost"])
    assert {lines[nid]["priceSource"] for nid in expected} == {"snapshot"}
    assert analysis["cost"]["rates"]["kind"] == "snapshot"
    assert analysis["cost"]["pricing"]["provider"] == "aws"
    assert any("AWS Price List" in n for n in analysis["cost"]["notes"])


def test_burstable_types_are_picked_only_when_their_family_is_named() -> None:
    data = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    data["compute"].append(
        {
            "sku": "burst.2x4",
            "family": "burst",
            "vcpu": 2,
            "memoryGib": 4,
            "hourly": {"on_demand": 0.001},
            "burstable": True,
        }
    )
    snap = parse_snapshot(data)
    assert snap.cover("compute", 2, 4).sku != "burst.2x4"  # type: ignore[union-attr]
    assert snap.cover("compute", 2, 4, families=("burst",)).sku == "burst.2x4"  # type: ignore[union-attr]


def test_without_a_snapshot_machines_get_standard_shapes() -> None:
    # the report's example: 4 vCPU and 0.5 GiB becomes 4 vCPU and 8 GiB (the cheapest ratio)
    assert standard_shape(4, 0.5) == (4.0, 8.0)
    assert standard_shape(1, 32) == (4.0, 32.0)  # memory-heavy: 8 GiB per vCPU
    assert standard_shape(2, 8) == (2.0, 8.0)  # already standard
    # a value set on the part is kept: 3 vCPU set, so memory follows it
    assert standard_shape(3, 1, set_cpu=3) == (3.0, 6.0)
    assert standard_shape(1, 16, set_memory=16) == (2.0, 16.0)
    for path in sorted(EXAMPLES.glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc.pop("pricing", None)
        analysis = analyze_document(doc, REGISTRY)
        nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
        for nid, entry in analysis["sizing"]["nodes"].items():
            cpu, mem = entry["auto"]["cpuCores"], entry["auto"]["memoryGib"]
            n = nodes[nid]
            cfg = {**REGISTRY.get(n["type"]).default_config(), **(n.get("config") or {})}
            if entry.get("skipped") or cpu is None or mem is None:
                continue
            if machine_kind(n["type"], cfg) is None:
                continue  # pods and functions: not machines of their own
            if any(
                isinstance((n.get("config") or {}).get(k), int | float)
                for k in ("cpuCores", "memoryGib")
            ):
                continue  # a spec set on the part: the shape follows it (checked below)
            assert mem / cpu in GIB_PER_VCPU, f"{path.name} {nid}: {cpu} vCPU, {mem} GiB"
    api = analyze_document(starter(), REGISTRY)["sizing"]["nodes"]["api"]
    assert (api["auto"]["cpuCores"], api["auto"]["memoryGib"]) == (4.0, 8.0)
    assert "standard shape" in api["why"]["memoryGib"]
