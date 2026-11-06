"""The engine service answers each call as the engine does, and refuses what it cannot read."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from starlette.testclient import TestClient

from archsim_engine import ENGINE_VERSION
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


def test_unknown_path_is_not_found(client: TestClient) -> None:
    assert client.post("/nothing").status_code == 404


@pytest.mark.parametrize(
    ("body", "message"),
    [
        (b"not json", "Request body is not JSON"),
        (b"[]", "Request body must be a JSON object"),
    ],
)
def test_request_it_cannot_read_is_a_bad_request(
    client: TestClient, body: bytes, message: str
) -> None:
    response = client.post("/calibrate", content=body)

    assert response.status_code == 400
    assert response.json()["message"].startswith(message)


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


def test_calibrate_document_that_is_not_an_object_is_a_bad_request(client: TestClient) -> None:
    recorded = json.loads((CALIBRATION / "starter-prometheus.json").read_text("utf-8"))
    mapping = (CALIBRATION / "starter-mapping.yaml").read_text("utf-8")
    body = calibrate_body(source="prometheus", mapping=mapping, recorded=recorded, document=1)

    response = client.post("/calibrate", json=body)

    assert response.status_code == 400
    assert response.json()["message"] == "document must be a JSON object"


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
