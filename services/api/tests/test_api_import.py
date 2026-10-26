"""POST /import: docker-compose, Kubernetes and Terraform to a design, with the import report."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from api_helpers import starter

API = "/api/v1"
FIXTURES = (
    Path(__file__).resolve().parents[3] / "packages" / "engine" / "tests" / "fixtures" / "import"
)


def test_import_reports_guesses_and_round_trips_an_export(client: TestClient) -> None:
    text = (FIXTURES / "voting-compose.yml").read_text(encoding="utf-8")
    res = client.post(f"{API}/import", json={"format": "compose", "text": text, "name": "Voting"})
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["document"]["metadata"]["name"] == "Voting"
    assert out["report"]["guessed"] and out["report"]["mapped"]
    assert out["document"]["provenance"]["nodes/vote/replicas"]["kind"] == "assumed"

    exported = client.post(f"{API}/export", json={"document": starter(), "format": "k8s"}).json()
    back = client.post(f"{API}/import", json={"format": "k8s", "text": exported["text"]}).json()
    ids = {n["id"] for n in back["document"]["architecture"]["nodes"]}
    assert ids == {n["id"] for n in starter()["architecture"]["nodes"]}
    assert back["report"]["guessed"] == []


def test_import_says_what_it_expected(client: TestClient) -> None:
    res = client.post(f"{API}/import", json={"format": "terraform", "text": "not json"})
    assert res.status_code == 422 and "terraform show -json" in res.json()["detail"]["message"]
    res = client.post(f"{API}/import", json={"format": "compose", "text": "[1, 2]"})
    assert res.status_code == 422 and "services" in res.json()["detail"]["message"]
