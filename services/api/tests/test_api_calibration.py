"""POST /calibrate: Prometheus from recorded responses (no live server) and OpenTelemetry."""

from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from api_helpers import starter

API = "/api/v1"

FIXTURES = (
    Path(__file__).resolve().parents[3]
    / "packages"
    / "engine"
    / "tests"
    / "fixtures"
    / "calibration"
)


def test_calibrate_from_recorded_prometheus_and_otlp(client: TestClient) -> None:
    recorded = json.loads((FIXTURES / "starter-prometheus.json").read_text(encoding="utf-8"))
    mapping = (FIXTURES / "starter-mapping.yaml").read_text(encoding="utf-8")
    res = client.post(
        f"{API}/calibrate",
        json={
            "document": starter(),
            "source": "prometheus",
            "mapping": mapping,
            "recorded": recorded,
            "range": "2026-10-20T00:00:00Z/2026-10-21T00:00:00Z",  # not the recording's window
        },
    )
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["document"]["provenance"]["nodes/api/serviceTimeMs"]["timeRange"].startswith(
        "2026-10-01T00:00:00Z"
    )
    assert any("the recording covers" in n for n in out["notes"])
    assert len(out["changes"]) == 7
    assert out["document"]["schemaVersion"] == "1.1"
    assert out["document"]["provenance"]["nodes/api/serviceTimeMs"]["kind"] == "measured"
    assert out["summary"].startswith("This model reproduces the observed p95")
    # the calibrated document is valid input for the rest of the API
    assert client.post(f"{API}/validate", json={"document": out["document"]}).json()["valid"]

    traces = json.loads((FIXTURES / "checkout-traces.otlp.json").read_text(encoding="utf-8"))
    res = client.post(
        f"{API}/calibrate",
        json={
            "document": starter(),
            "source": "otlp",
            "traces": traces,
            "mapping": "services: {checkout-api: api}\ndatabases: {orders: db}\n",
        },
    )
    assert res.status_code == 200, res.text
    assert any(c["path"] == "nodes/api/serviceTimeMs" for c in res.json()["changes"])


def test_calibrate_says_what_is_missing(client: TestClient) -> None:
    res = client.post(
        f"{API}/calibrate",
        json={"document": starter(), "source": "prometheus", "mapping": "nodes: {}"},
    )
    assert res.status_code == 422 and "URL or recorded" in res.json()["detail"]["message"]
    res = client.post(
        f"{API}/calibrate",
        json={
            "document": starter(),
            "source": "prometheus",
            "mapping": "nodes: {}",
            "prometheusUrl": "file:///etc/passwd",
        },
    )
    assert res.status_code == 422 and "http" in res.json()["detail"]["message"]
