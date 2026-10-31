"""POST /calibrate: Prometheus from recorded responses (no live server) and OpenTelemetry."""

from __future__ import annotations

import http.server
import json
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from api_helpers import make_client, starter
from archsim_api.config import Settings

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
    assert len(out["changes"]) == 13
    assert out["document"]["schemaVersion"] == "1.6"
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


def test_calibrate_reads_no_server_file_and_repeats_no_input(
    client: TestClient, tmp_path: Path
) -> None:
    secret = tmp_path / "secret.yaml"
    secret.write_text("nodes: TOPSECRET-file-content-41\n", encoding="utf-8")
    recorded = json.loads((FIXTURES / "starter-prometheus.json").read_text(encoding="utf-8"))

    def post(**body: object) -> Any:
        return client.post(
            f"{API}/calibrate", json={"document": starter(), "source": "prometheus", **body}
        )

    # a mapping string naming a file on the server is YAML text, not a path to open
    res = post(mapping=str(secret), recorded=recorded)
    assert res.status_code == 422 and "TOPSECRET" not in res.text
    assert "must be a YAML or JSON object" in res.json()["detail"]["message"]
    # validation errors name the place, never the value sent
    res = post(mapping="nodes: {api: {serviceTimeMs: {histogram: q, unit: SECRET-UNIT}}}")
    assert res.status_code == 422 and "SECRET-UNIT" not in res.text
    assert "input_value" not in res.text and "nodes.api.serviceTimeMs" in res.text
    res = post(mapping="nodes: {}", recorded={"range": {"start": "a", "end": 5, "step": -7}})
    assert res.status_code == 422 and "input_value" not in res.text and "-7" not in res.text
    # broken YAML is a 422 too (it was a 500), without an excerpt of the text
    for source, mapping in (("prometheus", "nodes: [SECRET-YAML"), ("otlp", "a: [SECRET-YAML")):
        res = client.post(
            f"{API}/calibrate",
            json={"document": starter(), "source": source, "mapping": mapping, "traces": {}},
        )
        assert res.status_code == 422 and "SECRET-YAML" not in res.text, res.text


@contextmanager
def _prometheus(raw: bytes) -> Iterator[str]:
    """A local server that answers every GET with ``raw`` bytes and closes."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.wfile.write(raw)

        def log_message(self, *args: object) -> None:
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def test_calibrate_prometheus_failures_and_the_host_allowlist(db_url: str) -> None:
    mapping = (FIXTURES / "starter-mapping.yaml").read_text(encoding="utf-8")
    cut_off = b'HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{"status": '
    body = {"document": starter(), "source": "prometheus", "mapping": mapping, "replay": False}
    with _prometheus(cut_off) as url, make_client(db_url) as c:
        res = c.post(f"{API}/calibrate", json={**body, "prometheusUrl": url})
        assert res.status_code == 422, res.text  # was a 500 (IncompleteRead)
        assert "cut off" in res.json()["detail"]["message"]
    with (
        _prometheus(cut_off) as url,
        make_client(db_url, prometheus_allowed_hosts=["prom.example:9090"]) as c,
    ):
        res = c.post(f"{API}/calibrate", json={**body, "prometheusUrl": url})
        assert res.status_code == 422 and "not allowed" in res.json()["detail"]["message"]


def test_the_allowlist_setting_reads_a_comma_separated_list(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS", "prom.internal:9090, localhost")
    assert Settings().prometheus_allowed_hosts == ["prom.internal:9090", "localhost"]
    monkeypatch.setenv("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS", '["a:1"]')
    assert Settings().prometheus_allowed_hosts == ["a:1"]
    monkeypatch.delenv("ARCHSIM_PROMETHEUS_ALLOWED_HOSTS")
    assert Settings().prometheus_allowed_hosts == []


def test_a_replay_past_the_limits_is_a_422(db_url: str) -> None:
    recorded = json.loads((FIXTURES / "starter-prometheus.json").read_text(encoding="utf-8"))
    mapping = (FIXTURES / "starter-mapping.yaml").read_text(encoding="utf-8")
    with make_client(db_url, max_requests=1000) as c:
        res = c.post(
            f"{API}/calibrate",
            json={
                "document": starter(),
                "source": "prometheus",
                "mapping": mapping,
                "recorded": recorded,
            },
        )
    assert res.status_code == 422, res.text
    assert "replay run hit a limit" in res.json()["detail"]["message"]
