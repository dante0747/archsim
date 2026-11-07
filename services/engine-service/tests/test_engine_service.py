"""The engine service answers its one call, the simulation a calibration replays, as the engine
does, and refuses what it cannot read."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from starlette.testclient import TestClient

from archsim_engine import (
    ENGINE_VERSION,
    SimulationLimitError,
    SimulationLimits,
    default_registry,
    run_simulation,
)
from archsim_engine_service.app import create_app

EXAMPLES = Path(__file__).resolve().parents[3] / "examples"


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(create_app())


def starter() -> dict[str, Any]:
    """The starter example, run for 8 s (2 s warm-up) to keep the tests quick."""
    document: dict[str, Any] = json.loads(
        (EXAMPLES / "cache-aside-starter.json").read_text("utf-8")
    )
    document["workload"]["durationS"] = 8
    document["workload"]["warmupS"] = 2
    return document


def simulate_body(**more: Any) -> dict[str, Any]:
    return {"document": starter(), "maxRequests": 2_000_000, "maxWallSeconds": 60.0, **more}


def test_health_names_the_engine_version(client: TestClient) -> None:
    response = client.get("/health")

    assert response.json() == {"status": "ok", "engineVersion": ENGINE_VERSION}


def test_unknown_path_is_not_found(client: TestClient) -> None:
    assert client.post("/nothing").status_code == 404


def test_calibrate_is_gone_since_the_java_api_calibrates(client: TestClient) -> None:
    assert client.post("/calibrate", json={}).status_code == 404


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
    response = client.post("/simulate", content=body)

    assert response.status_code == 400
    assert response.json()["message"].startswith(message)


def test_simulate_answers_the_engines_report(client: TestClient) -> None:
    limits = SimulationLimits(max_requests=2_000_000, max_wall_seconds=60.0)
    expected = run_simulation(starter(), registry=default_registry(), limits=limits)

    response = client.post("/simulate", json=simulate_body())

    assert response.status_code == 200, response.text
    report = response.json()
    # the provenance alone differs from run to run: it holds the run's wall-clock time
    assert {k: v for k, v in report.items() if k != "provenance"} == {
        k: v for k, v in expected.items() if k != "provenance"
    }
    assert report["provenance"]["resultDigest"] == expected["provenance"]["resultDigest"]


def test_simulate_document_that_is_not_an_object_is_a_bad_request(client: TestClient) -> None:
    response = client.post("/simulate", json=simulate_body(document=1))

    assert response.status_code == 400
    assert response.json()["message"] == "document must be a JSON object"


@pytest.mark.parametrize(
    "limits",
    [
        {"maxRequests": None},
        {"maxRequests": 1.5},
        {"maxRequests": True},
        {"maxWallSeconds": None},
        {"maxWallSeconds": "60"},
    ],
)
def test_simulate_without_its_limits_is_a_bad_request(
    client: TestClient, limits: dict[str, Any]
) -> None:
    response = client.post("/simulate", json=simulate_body(**limits))

    assert response.status_code == 400


def test_simulate_over_its_limits_is_refused_in_the_engines_words(client: TestClient) -> None:
    limits = SimulationLimits(max_requests=10, max_wall_seconds=60.0)
    with pytest.raises(SimulationLimitError) as engine:
        run_simulation(starter(), registry=default_registry(), limits=limits)

    response = client.post("/simulate", json=simulate_body(maxRequests=10))

    assert response.status_code == 422
    assert response.json() == {"message": str(engine.value), "issues": [], "kind": "limit"}


def test_simulate_document_it_cannot_read_is_invalid_with_its_issues(client: TestClient) -> None:
    response = client.post("/simulate", json=simulate_body(document={"kind": "nope"}))

    assert response.status_code == 422
    assert response.json()["kind"] == "invalid"
    assert response.json()["issues"]
