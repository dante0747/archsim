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
    analyze_document,
    default_registry,
    load_document,
    resolve_scenario,
    validate_document,
)
from archsim_engine_service.app import create_app

EXAMPLES = Path(__file__).resolve().parents[3] / "examples"


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
