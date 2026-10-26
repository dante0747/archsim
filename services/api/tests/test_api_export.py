"""POST /export: the engine's docker-compose, Kubernetes and Mermaid exports."""

from __future__ import annotations

from fastapi.testclient import TestClient

from api_helpers import starter

API = "/api/v1"


def test_export_formats_and_errors(client: TestClient) -> None:
    for fmt, suffix, needle in (
        ("compose", "docker-compose.yml", "image: postgres:16"),
        ("k8s", "k8s.yaml", "kind: StatefulSet"),
        ("mermaid", "mmd", "flowchart LR"),
    ):
        res = client.post(f"{API}/export", json={"document": starter(), "format": fmt})
        assert res.status_code == 200, res.text
        out = res.json()
        assert out["format"] == fmt and out["suffix"] == suffix and needle in out["text"]
    broken = starter()
    broken["architecture"]["nodes"] = "nope"
    res = client.post(f"{API}/export", json={"document": broken, "format": "compose"})
    assert res.status_code == 422 and res.json()["detail"]["issues"]
