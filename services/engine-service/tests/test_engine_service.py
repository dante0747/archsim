"""The engine service answers each call as the engine does, and refuses what it cannot read."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from starlette.responses import JSONResponse
from starlette.testclient import TestClient

from archsim_engine import (
    ENGINE_VERSION,
    SimulationLimits,
    analyze_document,
    compare_reports,
    default_registry,
    load_document,
    prepare_run,
    quick_check,
    resolve_scenario,
    summarize_seeds,
    validate_document,
)
from archsim_engine.export import export_document
from archsim_engine_service.app import create_app

EXAMPLES = Path(__file__).resolve().parents[3] / "examples"

CALIBRATION = (
    Path(__file__).resolve().parents[3]
    / "packages"
    / "engine"
    / "tests"
    / "fixtures"
    / "calibration"
)


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(create_app())


def starter() -> dict[str, Any]:
    document: dict[str, Any] = json.loads(
        (EXAMPLES / "cache-aside-starter.json").read_text("utf-8")
    )
    return document


def test_health_names_the_engine_version(client: TestClient) -> None:
    response = client.get("/health")

    assert response.json() == {"status": "ok", "engineVersion": ENGINE_VERSION}


def test_analyze_starter_answers_what_the_engine_computes(client: TestClient) -> None:
    response = client.post("/analyze", json={"document": starter()})

    assert response.status_code == 200
    assert response.json() == analyze_document(starter(), default_registry())


@pytest.mark.parametrize("scenario", [None, "three-api-replicas"])
def test_analyze_answer_is_the_bytes_fastapi_wrote(
    client: TestClient, scenario: str | None
) -> None:
    fastapi = pytest.importorskip("fastapi.encoders")
    expected = analyze_document(starter(), default_registry(), scenario)

    response = client.post("/analyze", json={"document": starter(), "scenarioId": scenario})

    assert response.content == JSONResponse(fastapi.jsonable_encoder(expected)).body


def test_analyze_scenario_answers_the_scenarios_analysis(client: TestClient) -> None:
    response = client.post(
        "/analyze", json={"document": starter(), "scenarioId": "three-api-replicas"}
    )

    assert response.json() == analyze_document(starter(), default_registry(), "three-api-replicas")


def test_analyze_unreadable_document_answers_the_engines_issues(client: TestClient) -> None:
    response = client.post("/analyze", json={"document": {"kind": "nope"}})

    assert response.status_code == 422
    assert response.json()["issues"]
    assert set(response.json()["issues"][0]) >= {"severity", "code", "message"}


@pytest.mark.parametrize(
    ("body", "message"),
    [
        (b"not json", "Request body is not JSON"),
        (b"[]", "Request body must be a JSON object"),
        (b'{"document": 1}', "document must be a JSON object"),
        (b'{"document": {}, "scenarioId": 1}', "scenarioId must be a string"),
    ],
)
def test_analyze_request_it_cannot_read_is_a_bad_request(
    client: TestClient, body: bytes, message: str
) -> None:
    response = client.post("/analyze", content=body)

    assert response.status_code == 400
    assert response.json()["message"].startswith(message)


def test_validate_scenario_validates_the_resolved_design(client: TestClient) -> None:
    response = client.post(
        "/validate", json={"document": starter(), "scenarioId": "three-api-replicas"}
    )

    registry = default_registry()
    resolved, _ = resolve_scenario(load_document(starter()), "three-api-replicas", registry)
    assert response.json() == validate_document(resolved, registry).to_dict()


def test_validate_unknown_scenario_is_invalid_with_the_reason(client: TestClient) -> None:
    response = client.post("/validate", json={"document": starter(), "scenarioId": "nope"})

    assert response.status_code == 200
    assert response.json()["valid"] is False
    assert response.json()["issues"][0]["code"] == "scenario.unknown"


def test_unknown_path_is_not_found(client: TestClient) -> None:
    assert client.post("/nothing").status_code == 404


# -- runs: prepare, compare, summarize seeds


def prepare_body(**more: Any) -> dict[str, Any]:
    return {"document": starter(), "maxRequests": 2_000_000, "maxWallSeconds": 300.0, **more}


def test_prepare_starter_answers_the_document_to_store_and_its_seed(client: TestClient) -> None:
    response = client.post("/prepare", json=prepare_body())

    prepared = prepare_run(starter(), None, default_registry(), SimulationLimits())
    assert response.status_code == 200
    assert response.json()["document"] == prepared.document.to_json_dict()
    assert response.json()["seed"] == prepared.resolved.workload.seed
    assert response.json()["scenarioName"] is None


def test_prepare_scenario_names_it(client: TestClient) -> None:
    response = client.post("/prepare", json=prepare_body(scenarioId="three-api-replicas"))

    assert response.json()["scenarioId"] == "three-api-replicas"
    assert response.json()["scenarioName"]


def test_prepare_seed_replaces_the_documents(client: TestClient) -> None:
    response = client.post("/prepare", json=prepare_body(seed=7))

    assert response.json()["seed"] == 7


def test_prepare_answers_whether_a_quick_check_can_run(client: TestClient) -> None:
    response = client.post("/prepare", json=prepare_body())

    prepared = prepare_run(starter(), None, default_registry(), SimulationLimits())
    assert response.json()["quickCheck"] == quick_check(prepared.resolved.workload)


def test_prepare_run_over_the_limits_is_refused_with_the_engines_words(
    client: TestClient,
) -> None:
    response = client.post("/prepare", json=prepare_body(maxRequests=10))

    assert response.status_code == 422
    assert response.json()["kind"] == "limit"
    assert response.json()["message"]
    assert response.json()["issues"] == []


def test_prepare_unreadable_document_is_refused_with_its_issues_and_message(
    client: TestClient,
) -> None:
    response = client.post("/prepare", json=prepare_body(document={"kind": "nope"}))

    assert response.status_code == 422
    assert response.json()["kind"] == "invalid"
    assert response.json()["issues"]
    assert response.json()["message"]


@pytest.mark.parametrize(
    ("more", "message"),
    [
        ({"maxRequests": "many"}, "maxRequests must be a whole number"),
        ({"maxWallSeconds": None}, "maxWallSeconds must be a number"),
        ({"seed": 1.5}, "seed must be a whole number"),
    ],
)
def test_prepare_limits_it_cannot_read_are_a_bad_request(
    client: TestClient, more: dict[str, Any], message: str
) -> None:
    response = client.post("/prepare", json=prepare_body(**more))

    assert response.status_code == 400
    assert response.json()["message"] == message


def report() -> dict[str, Any]:
    node = {"id": "api", "label": "API", "type": "service", "status": "ok", "summary": {}}
    return {"summary": {"latencyP95Ms": 120.0, "successRate": 0.99}, "nodes": [node]}


def test_compare_answers_what_the_engine_compares(client: TestClient) -> None:
    body = {"a": report(), "b": report(), "labelA": "before", "labelB": "after"}

    response = client.post("/compare", json=body)

    assert response.json() == compare_reports(report(), report(), "before", "after")


def test_summarize_seeds_answers_what_the_engine_summarizes(client: TestClient) -> None:
    runs = [{"seed": 42, "summary": {"latencyP95Ms": 120.0}}, {"seed": 43, "summary": None}]

    response = client.post(
        "/summarize-seeds", json={"runs": runs, "objectives": {"latencyP95Ms": 300.0}}
    )

    assert response.json() == summarize_seeds(runs, {"latencyP95Ms": 300.0})


@pytest.mark.parametrize(
    ("path", "body", "message"),
    [
        ("/compare", {"a": [], "b": {}, "labelA": "x", "labelB": "y"}, "a and b must be reports"),
        (
            "/compare",
            {"a": {}, "b": {}, "labelA": None, "labelB": "y"},
            "labelA and labelB must be strings",
        ),
        (
            "/summarize-seeds",
            {"runs": {}, "objectives": {}},
            "runs must be a list and objectives an object",
        ),
    ],
)
def test_runs_request_it_cannot_read_is_a_bad_request(
    client: TestClient, path: str, body: dict[str, Any], message: str
) -> None:
    response = client.post(path, json=body)

    assert response.status_code == 400
    assert response.json()["message"] == message


def test_export_starter_answers_the_engines_text_with_its_suffix(client: TestClient) -> None:
    response = client.post("/export", json={"document": starter(), "format": "compose"})

    assert response.status_code == 200
    assert response.json()["text"] == export_document(starter(), "compose", default_registry())
    assert response.json()["format"] == "compose"


def test_export_unknown_format_is_a_bad_request(client: TestClient) -> None:
    response = client.post("/export", json={"document": starter(), "format": "helm"})

    assert response.status_code == 400


def test_export_unreadable_document_is_invalid(client: TestClient) -> None:
    response = client.post("/export", json={"document": {"kind": "nope"}, "format": "mermaid"})

    assert response.status_code == 422
    assert response.json()["kind"] == "invalid"
    assert response.json()["issues"]


def test_import_compose_answers_a_design_and_its_report(client: TestClient) -> None:
    text = "services:\n  api:\n    image: nginx\n"

    response = client.post("/import", json={"format": "compose", "text": text, "name": "Shop"})

    assert response.status_code == 200
    assert response.json()["document"]["metadata"]["name"] == "Shop"
    assert "mapped" in response.json()["report"]


def test_import_text_of_another_format_is_unreadable(client: TestClient) -> None:
    response = client.post("/import", json={"format": "terraform", "text": "not json"})

    assert response.status_code == 422
    assert response.json()["kind"] == "unreadable"
    assert response.json()["message"].startswith("Cannot import: ")


def calibrate_body(**more: Any) -> dict[str, Any]:
    return {
        "document": starter(),
        "maxRequests": 2_000_000,
        "maxWallSeconds": 60.0,
        "outbound": "open",
        "allowedHosts": [],
        "timeoutS": 30.0,
        "maxBytes": 50 * 1024 * 1024,
        **more,
    }


def test_calibrate_recorded_prometheus_answers_the_calibrated_design(client: TestClient) -> None:
    recorded = json.loads((CALIBRATION / "starter-prometheus.json").read_text("utf-8"))
    mapping = (CALIBRATION / "starter-mapping.yaml").read_text("utf-8")

    response = client.post(
        "/calibrate",
        json=calibrate_body(source="prometheus", mapping=mapping, recorded=recorded),
    )

    assert response.status_code == 200, response.text
    assert len(response.json()["changes"]) == 13
    assert response.json()["summary"].startswith("This model reproduces the observed p95")


def test_calibrate_otlp_traces_answers_the_changes(client: TestClient) -> None:
    traces = json.loads((CALIBRATION / "checkout-traces.otlp.json").read_text("utf-8"))

    response = client.post(
        "/calibrate",
        json=calibrate_body(
            source="otlp",
            traces=traces,
            mapping="services: {checkout-api: api}\ndatabases: {orders: db}\n",
        ),
    )

    assert response.status_code == 200, response.text
    assert any(c["path"] == "nodes/api/serviceTimeMs" for c in response.json()["changes"])


@pytest.mark.parametrize(
    ("more", "message"),
    [
        ({"source": "prometheus"}, "A Prometheus calibration needs a mapping"),
        (
            {"source": "prometheus", "mapping": "nodes: {}"},
            "Give a Prometheus URL or recorded responses",
        ),
        (
            {
                "source": "prometheus",
                "mapping": "nodes: {}",
                "prometheusUrl": "http://prom:9090",
                "outbound": "off",
            },
            "This server does not call Prometheus servers (ARCHSIM_OUTBOUND): send recorded "
            "responses instead (archsim calibrate --record makes them)",
        ),
        (
            {"source": "prometheus", "mapping": "nodes: {}", "prometheusUrl": "ftp://prom"},
            "The Prometheus URL must be http(s)://host[:port]",
        ),
        (
            {
                "source": "prometheus",
                "mapping": "nodes: {}",
                "prometheusUrl": "http://prom:9090",
                "outbound": "allowlist",
                "allowedHosts": ["other:9090"],
            },
            "This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)",
        ),
        ({"source": "otlp"}, "An OpenTelemetry calibration needs the traces (OTLP JSON)"),
    ],
)
def test_calibrate_without_what_it_needs_is_unreadable_in_the_apis_words(
    client: TestClient, more: dict[str, Any], message: str
) -> None:
    response = client.post("/calibrate", json=calibrate_body(**more))

    assert response.status_code == 422
    assert response.json() == {"message": message, "issues": [], "kind": "unreadable"}


@pytest.mark.parametrize(
    "policy",
    [
        {"outbound": None},
        {"outbound": "ALLOWLIST"},
        {"outbound": "sometimes"},
        {"allowedHosts": None},
        {"allowedHosts": "prom:9090"},
        {"allowedHosts": [9090]},
    ],
)
def test_calibrate_policy_it_cannot_read_is_a_bad_request(
    client: TestClient, policy: dict[str, Any]
) -> None:
    response = client.post("/calibrate", json=calibrate_body(source="otlp", traces={}, **policy))

    assert response.status_code == 400


def test_calibrate_unknown_source_is_a_bad_request(client: TestClient) -> None:
    response = client.post("/calibrate", json=calibrate_body(source="statsd"))

    assert response.status_code == 400


def test_import_unknown_format_is_a_bad_request(client: TestClient) -> None:
    response = client.post("/import", json={"format": "helm", "text": "x"})

    assert response.status_code == 400


def test_calibrate_replay_over_its_limits_is_unreadable_in_the_apis_words(
    client: TestClient,
) -> None:
    traces = json.loads((CALIBRATION / "checkout-traces.otlp.json").read_text("utf-8"))

    response = client.post(
        "/calibrate",
        json=calibrate_body(
            source="otlp",
            traces=traces,
            mapping="services: {checkout-api: api}\ndatabases: {orders: db}\n",
            maxRequests=10,
        ),
    )

    assert response.status_code == 422
    assert response.json()["kind"] == "unreadable"
    assert response.json()["message"].startswith("Cannot calibrate: the replay run hit a limit (")


def test_calibrate_document_it_cannot_read_is_invalid_with_its_issues(client: TestClient) -> None:
    response = client.post(
        "/calibrate", json=calibrate_body(document={"kind": "nope"}, source="otlp", traces={})
    )

    assert response.status_code == 422
    assert response.json()["kind"] == "invalid"
    assert response.json()["issues"]


def test_calibrate_mapping_of_another_type_is_unreadable(client: TestClient) -> None:
    recorded = json.loads((CALIBRATION / "starter-prometheus.json").read_text("utf-8"))

    response = client.post(
        "/calibrate", json=calibrate_body(source="prometheus", mapping=5, recorded=recorded)
    )

    assert response.status_code == 422
    assert response.json()["kind"] == "unreadable"
    assert response.json()["message"].startswith("Cannot calibrate: ")
