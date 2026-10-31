"""Calibration from telemetry: fitting, the empirical distribution, the trace pattern, provenance,
Prometheus (from a recorded fixture, no live server) and OpenTelemetry traces."""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

import pytest

from archsim_engine import create_default_registry, load_document, run_simulation
from archsim_engine.calibration import (
    Histogram,
    RecordedPrometheus,
    RecordingPrometheus,
    calibrate_otlp,
    calibrate_prometheus,
    fit_histogram,
    fit_samples,
    load_mapping,
    load_mapping_file,
    parse_range,
    replay_summary,
)
from archsim_engine.calibration.fit import lognormal_quantile
from archsim_engine.cli import main as cli
from archsim_engine.distributions import Empirical
from archsim_engine.migrations import migrate_document
from archsim_engine.provenance import input_provenance, provenance_counts
from archsim_engine.schema import TracePattern, Workload
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "calibration"
STARTER = EXAMPLES / "cache-aside-starter.json"


def starter() -> dict:
    return json.loads(STARTER.read_text(encoding="utf-8"))


def lognormal_cdf(x: float, median: float, p95: float) -> float:
    sigma = math.log(p95 / median) / 1.6448536269514722
    return (
        0.5 * (1 + math.erf((math.log(x) - math.log(median)) / (sigma * math.sqrt(2))))
        if x > 0
        else 0.0
    )


# -- fitting ------------------------------------------------------------------------------------


def test_histogram_quantiles_and_lognormal_fit_recover_known_parameters() -> None:
    bounds = [0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12.5, 15, 20, 25, 30, 40, 50, 75, 100, 250]
    buckets = {b: 1000 * lognormal_cdf(b, 6, 18) for b in bounds}
    buckets[math.inf] = 1000.0
    h = Histogram.from_buckets(buckets)
    fit = fit_histogram(h)
    assert fit.distribution["kind"] == "lognormal"
    assert fit.distribution["median"] == pytest.approx(6, rel=0.03)
    assert fit.distribution["p95"] == pytest.approx(18, rel=0.05)  # inside a 15-20 ms bucket
    assert fit.error < 0.05
    # histogram_quantile semantics: the +Inf bucket answers with the highest finite bound
    tail = Histogram.from_buckets({1.0: 10.0, math.inf: 100.0})
    assert tail.quantile(0.99) == 1.0


def test_sample_fit_and_empirical_tables() -> None:
    rng = random.Random(3)
    samples = [
        rng.lognormvariate(math.log(4), math.log(14 / 4) / 1.6448536269514722)
        for _ in range(20_000)
    ]
    fit = fit_samples(samples)
    assert fit.distribution["median"] == pytest.approx(4, rel=0.03)
    assert fit.distribution["p95"] == pytest.approx(14, rel=0.05)
    emp = fit_samples(samples, "empirical")
    table = Empirical(quantiles=[tuple(p) for p in emp.distribution["quantiles"]])
    assert table.quantile(0.5) == pytest.approx(4, rel=0.05)
    assert emp.error < fit.error + 0.05
    assert lognormal_quantile(4, 14, 0.95) == pytest.approx(14)


def test_empirical_distribution_validates_samples_and_means() -> None:
    d = Empirical(quantiles=[(0.0, 1.0), (0.5, 2.0), (1.0, 4.0)])
    assert d.expected() == pytest.approx(0.5 * 1.5 + 0.5 * 3.0)
    assert d.quantile(0.75) == pytest.approx(3.0)
    a = [d.sample(random.Random(9)) for _ in range(5)]
    b = [d.sample(random.Random(9)) for _ in range(5)]
    assert a == b and all(1.0 <= x <= 4.0 for x in a)
    for bad in (
        [(0.1, 1.0), (1.0, 2.0)],
        [(0.0, 2.0), (1.0, 1.0)],
        [(0.0, 1.0), (0.0, 2.0), (1.0, 3.0)],
    ):
        with pytest.raises(ValueError):
            Empirical(quantiles=bad)


def test_trace_pattern_rates_arrivals_and_a_run() -> None:
    t = TracePattern(points=[(0.0, 100.0), (10.0, 300.0)])
    assert t.rate(5) == pytest.approx(200)
    assert t.rate(20) == 300
    assert t.area(20) == pytest.approx(2000 + 3000)
    wl = Workload(rps=200, durationS=20, warmupS=2, pattern=t)
    assert wl.peak_rate() == 300
    assert wl.expected_arrivals() == pytest.approx(5000)
    doc = starter()
    doc["workload"].update(
        durationS=20, warmupS=2, pattern={"kind": "trace", "points": [[0, 100], [10, 300]]}
    )
    doc["scenarios"] = []
    report = run_simulation(doc)
    assert report["provenance"]["requestsGenerated"] == pytest.approx(5000, rel=0.05)
    with pytest.raises(ValueError):
        TracePattern(points=[(1.0, 10.0), (2.0, 10.0)])


# -- schema and provenance ------------------------------------------------------------------------


def test_schema_1_0_documents_migrate_and_newer_ones_are_refused() -> None:
    raw = {**starter(), "schemaVersion": "1.0"}  # the starter as a 1.0 document
    migrated, notes = migrate_document(raw)
    assert migrated["schemaVersion"] == "1.6"
    assert notes == [
        "migrated schema 1.0 -> 1.1",
        "migrated schema 1.1 -> 1.2",
        "migrated schema 1.2 -> 1.3",
        "migrated schema 1.3 -> 1.4",
        "migrated schema 1.4 -> 1.5",
        "migrated schema 1.5 -> 1.6",
    ]
    doc = load_document(raw)
    assert doc.provenance == {} and doc.calibration is None
    newer = {**raw, "schemaVersion": "1.7"}
    from archsim_engine import DocumentInvalidError

    with pytest.raises(DocumentInvalidError):
        load_document(newer)


def test_inputs_are_illustrative_assumed_or_measured() -> None:
    raw = starter()
    nodes = {n["id"]: n for n in raw["architecture"]["nodes"]}
    nodes["api"]["config"]["serviceTimeMs"] = {"kind": "lognormal", "median": 9, "p95": 30}
    raw["provenance"] = {
        "nodes/db/readServiceTimeMs": {
            "kind": "measured",
            "source": "Prometheus http://prom:9090",
            "query": "q",
            "timeRange": "a/b",
            "fitError": 0.04,
        }
    }
    doc = load_document(raw)
    entries = {(e["id"], e["field"]): e["provenance"] for e in input_provenance(doc, REGISTRY)}
    assert entries[("api", "serviceTimeMs")]["kind"] == "assumed"
    assert entries[("db", "readServiceTimeMs")] == raw["provenance"]["nodes/db/readServiceTimeMs"]
    assert entries[("cache", "itemSizeKb")]["kind"] == "illustrative"
    # a round trip keeps provenance
    again = load_document(doc.to_json_dict())
    assert again.provenance["nodes/db/readServiceTimeMs"].fit_error == 0.04
    report = run_simulation(raw)
    counts = report["assumptions"]["provenanceCounts"]
    assert counts["measured"] == 1 and counts["assumed"] >= 1 and counts["illustrative"] >= 1
    assert counts == provenance_counts(report["assumptions"]["userInputs"])


# -- Prometheus -----------------------------------------------------------------------------------


def calibrated_starter() -> dict:
    mapping = load_mapping_file(FIXTURES / "starter-mapping.yaml")
    client = RecordedPrometheus.load(FIXTURES / "starter-prometheus.json")
    return calibrate_prometheus(starter(), mapping, client, REGISTRY).to_dict()


def test_starter_calibrates_from_the_recorded_fixture_and_replays_within_15_percent() -> None:
    result = calibrated_starter()
    doc = result["document"]
    truth = json.loads((FIXTURES / "starter-prometheus.json").read_text(encoding="utf-8"))["truth"]
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    st = nodes["api"]["config"]["serviceTimeMs"]
    assert st["median"] == pytest.approx(truth["api"]["median"], rel=0.05)
    assert st["p95"] == pytest.approx(truth["api"]["p95"], rel=0.05)
    assert nodes["cache"]["config"]["hitRatio"] == pytest.approx(truth["hitRatio"], abs=0.001)
    assert doc["workload"]["pattern"]["kind"] == "trace"
    # every changed input is measured, with its query, range and fit error
    assert len(result["changes"]) == 13
    for change in result["changes"]:
        prov = doc["provenance"][change["path"]]
        assert (
            prov["kind"] == "measured"
            and prov["query"]
            and prov["timeRange"].startswith("2026-10-01")
        )
    assert doc["provenance"]["nodes/api/serviceTimeMs"]["fitError"] < 0.1
    record = doc["calibration"]
    e2e = next(c for c in record["replay"] if c["scope"] == "end_to_end" and c["metric"] == "p95")
    assert abs(e2e["error"]) < 0.15
    assert "within" in (replay_summary(record) or "")
    load_document(doc)  # still a valid document
    # reports carry the provenance and the calibration
    report = run_simulation(doc)
    assert report["assumptions"]["provenanceCounts"]["measured"] == 13
    assert report["assumptions"]["calibration"]["source"] == record["source"]


def test_cpu_and_memory_are_fitted_and_replayed() -> None:
    """CPU per request sets the CPU share (services and databases), the working set sets the
    memory at idle and per req/s (and per request held: its floor, since the fit was against the
    rate alone), a database size gauge in bytes sets the working set in GiB, and the replay
    compares the simulated CPU and memory with what was observed."""
    result = calibrated_starter()
    doc = result["document"]
    truth = json.loads((FIXTURES / "starter-prometheus.json").read_text(encoding="utf-8"))["truth"]
    cfg = {n["id"]: n["config"] for n in doc["architecture"]["nodes"]}
    # the API's share comes from its fitted service time, whose p95 is 3% high: within 2%
    assert cfg["api"]["cpuShare"] == pytest.approx(truth["apiCpuShare"], rel=0.02)
    assert cfg["db"]["cpuShare"] == pytest.approx(truth["dbCpuShare"], rel=0.02)
    assert cfg["api"]["memoryBaselineMb"] == pytest.approx(truth["apiMemoryBaselineMb"], rel=1e-3)
    assert cfg["api"]["memoryPerRpsMb"] == pytest.approx(truth["apiMemoryPerRpsMb"], rel=1e-3)
    assert cfg["api"]["memoryPerRequestMb"] == 0.01
    assert cfg["db"]["workingSetGib"] == pytest.approx(truth["dbWorkingSetGib"])
    prov = doc["provenance"]
    for key in ("cpuShare", "memoryBaselineMb", "memoryPerRpsMb", "memoryPerRequestMb"):
        assert prov[f"nodes/api/{key}"]["kind"] == "measured"
        assert prov[f"nodes/api/{key}"]["fitError"] < 0.01
    assert "ms of CPU per request" in prov["nodes/api/cpuShare"]["note"]
    assert "fitted against the request rate" in prov["nodes/api/memoryPerRpsMb"]["note"]
    checks = {(c["id"], c["metric"]): c for c in doc["calibration"]["replay"]}
    for key in (("api", "cpuCores"), ("db", "cpuCores"), ("api", "memoryMiB")):
        assert checks[key]["simulated"] is not None and abs(checks[key]["error"]) < 0.1, key
    # the inputs count as measured: confidence weighs them like any measured input
    report = run_simulation(doc)
    user = {(e["id"], e["field"]): e for e in report["assumptions"]["userInputs"]}
    assert user[("api", "cpuShare")]["provenance"]["kind"] == "measured"
    assert user[("api", "memoryPerRpsMb")]["provenance"]["kind"] == "measured"
    assert user[("db", "workingSetGib")]["provenance"]["kind"] == "measured"


def test_cpu_and_memory_sources_need_the_right_shape_and_part() -> None:
    from archsim_engine.calibration import MappingError, load_mapping

    with pytest.raises(MappingError, match="cpuPerRequest needs"):
        load_mapping({"nodes": {"api": {"cpuPerRequest": {"gauge": "x"}}}})
    with pytest.raises(MappingError, match="go under 'cpuPerRequest'"):
        load_mapping({"nodes": {"api": {"cpuShare": {"cpu": "a", "requests": "b"}}}})
    with pytest.raises(MappingError, match="needs 'requests'"):
        load_mapping({"nodes": {"api": {"memoryWorkingSet": {"workingSet": "w"}}}})
    with pytest.raises(MappingError, match="a node entry is one of"):
        load_mapping({"nodes": {"api": {"serviceTimeMs": {"buckets": "x"}}}})
    # a cache has no CPU share, and a unit only converts memory and size settings
    mapping = load_mapping(
        {
            "nodes": {
                "cache": {"cpuPerRequest": {"cpu": "c", "requests": "r"}},
                "api": {"workersPerReplica": {"gauge": "w", "unit": "bytes"}},
            }
        }
    )
    flat = {
        "status": "success",
        "data": {"resultType": "matrix", "result": [{"metric": {}, "values": [[1, "4"]]}]},
    }
    client = RecordedPrometheus({"c": flat, "r": flat, "w": flat})
    out = calibrate_prometheus(
        starter(),
        mapping,
        client,
        REGISTRY,
        parse_range("2026-10-01T00:00:00Z/2026-10-01T00:02:00Z"),
    )
    assert not out.changes
    assert any("does not have: skipped" in n for n in out.notes)
    assert any("only for memory and size settings" in n for n in out.notes)


def test_replay_is_deterministic() -> None:
    a = calibrated_starter()["document"]["calibration"]
    b = calibrated_starter()["document"]["calibration"]
    assert a["replayDigest"] == b["replayDigest"]
    assert a["replay"] == b["replay"]


def test_a_recording_keeps_its_range_and_replays_from_it(tmp_path: Path) -> None:
    mapping = load_mapping_file(FIXTURES / "starter-mapping.yaml")
    source = RecordedPrometheus.load(FIXTURES / "starter-prometheus.json")
    assert source.recorded_range is not None
    assert source.recorded_range.start == "2026-10-01T00:00:00Z"
    # re-record: the new recording saves the window its queries covered
    source = RecordedPrometheus(source.responses, source.label)  # as if live: no range of its own
    recording = RecordingPrometheus(source)
    calibrate_prometheus(starter(), mapping, recording, REGISTRY, replay=False)
    path = tmp_path / "rec.json"
    recording.save(path)
    again = RecordedPrometheus.load(path)
    assert again.recorded_range is not None
    assert (again.recorded_range.start, again.recorded_range.end) == (
        "2026-10-01T00:00:00Z",
        "2026-10-01T00:02:00Z",
    )
    # a different range is noted and not used: the data came from the recording's window
    other = parse_range("2026-10-20T00:00:00Z/2026-10-21T00:00:00Z")
    out = calibrate_prometheus(starter(), mapping, again, REGISTRY, other, replay=False)
    assert any("the recording covers 2026-10-01T00:00:00Z" in n for n in out.notes)
    prov = out.document["provenance"]["nodes/api/serviceTimeMs"]
    assert prov["timeRange"] == "2026-10-01T00:00:00Z/2026-10-01T00:02:00Z"


def test_missing_parts_and_settings_are_reported_not_guessed() -> None:
    mapping = load_mapping(
        "nodes:\n  nope: {serviceTimeMs: {histogram: q}}\n  api: {notASetting: {gauge: g}}\n"
        "range: {start: '2026-10-01T00:00:00Z', end: '2026-10-01T00:02:00Z'}\n"
    )
    out = calibrate_prometheus(starter(), mapping, RecordedPrometheus({}), REGISTRY, replay=False)
    assert not out.changes
    assert any("nope" in n for n in out.notes) and any("notASetting" in n for n in out.notes)


def test_cli_calibrates_from_a_recording(tmp_path: Path) -> None:
    out = tmp_path / "calibrated.json"
    code = cli(
        [
            "calibrate",
            "prometheus",
            str(STARTER),
            "--map",
            str(FIXTURES / "starter-mapping.yaml"),
            "--recorded",
            str(FIXTURES / "starter-prometheus.json"),
            "--no-replay",
            "-o",
            str(out),
        ]
    )
    assert code == 0
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["schemaVersion"] == "1.6" and doc["calibration"]["inputs"] == 13


# -- OpenTelemetry --------------------------------------------------------------------------------


def test_otlp_traces_give_own_service_time_database_times_and_the_call_graph() -> None:
    mapping = {"services": {"checkout-api": "api"}, "databases": {"orders": "db"}}
    out = calibrate_otlp(starter(), FIXTURES / "checkout-traces.otlp.json", REGISTRY, mapping)
    doc = out.document
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    own = nodes["api"]["config"]["serviceTimeMs"]
    # own time excludes the database and inventory calls (generated with median 6, p95 18)
    assert own["median"] == pytest.approx(6, rel=0.15)
    assert own["p95"] == pytest.approx(18, rel=0.2)
    reads = nodes["db"]["config"]["readServiceTimeMs"]
    assert reads["median"] == pytest.approx(4, rel=0.15)
    assert doc["provenance"]["nodes/api/serviceTimeMs"]["source"].startswith("OpenTelemetry traces")
    assert any("inventory" in n for n in out.notes)
    replay = doc["calibration"]["replay"]
    assert replay and all(c["scope"] == "component" and c["id"] == "api" for c in replay)
    load_document(doc)
    # without a CPU-time attribute in the mapping, the CPU share is left alone
    assert "cpuShare" not in nodes["api"]["config"]


def test_otlp_spans_that_record_cpu_time_give_the_cpu_share() -> None:
    from archsim_engine.calibration import load_otlp_mapping

    mapping = load_otlp_mapping((FIXTURES / "otlp-mapping.yaml").read_text(encoding="utf-8"))
    assert mapping is not None and mapping["cpuTime"] == {
        "attribute": "app.cpu_time_ms",
        "unit": "ms",
    }
    out = calibrate_otlp(starter(), FIXTURES / "checkout-traces.otlp.json", REGISTRY, mapping)
    api = next(n for n in out.document["architecture"]["nodes"] if n["id"] == "api")
    assert api["config"]["cpuShare"] == pytest.approx(0.7, abs=0.001)  # generated at 70%
    prov = out.document["provenance"]["nodes/api/cpuShare"]
    assert prov["kind"] == "measured" and "400 spans" in prov["note"]
    # an attribute no span carries: noted, nothing set
    other = {**mapping, "cpuTime": {"attribute": "not.there"}}
    out = calibrate_otlp(starter(), FIXTURES / "checkout-traces.otlp.json", REGISTRY, other)
    assert not any(c.path.endswith("cpuShare") for c in out.changes)
    assert any("not.there" in n for n in out.notes)
    for bad in (
        {"cpuTime": {"attribute": "x", "unit": "minutes"}},
        {"cpuTime": "x"},
        {"other": {}},
    ):
        with pytest.raises(ValueError):
            load_otlp_mapping(bad)
