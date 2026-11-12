"""The frozen Python engine's web app fixtures: its component catalog and its analysis of the
starter fixture. Kept only for that engine's test_web_catalog_fixture_matches_registry, which
compares them with the committed fixtures as parsed JSON, until stage 5 of the Java port deletes
the engine. `./gradlew :archsim-engine:generate` writes the fixtures, from the Java engine.
"""

from __future__ import annotations

import json
from pathlib import Path

from archsim_engine.analyze import analyze_document
from archsim_engine.components import create_default_registry

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "apps" / "web" / "src" / "test" / "fixtures"
FIXTURE = FIXTURES / "catalog.json"
STARTER = FIXTURES / "starter.json"
ANALYSIS = FIXTURES / "analysis-starter.json"


def catalog() -> dict:
    return create_default_registry(include_plugins=False).catalog_document()


def analysis() -> dict:
    registry = create_default_registry(include_plugins=False)
    return analyze_document(json.loads(STARTER.read_text(encoding="utf-8")), registry)
