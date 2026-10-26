"""Write the web app's engine fixtures: the component catalog (exactly as GET /api/v1/components
returns it) and the engine's analysis of the starter fixture (as POST /api/v1/analyze returns
it), so web tests use the engine's real output instead of re-implementing its rules. A Python
test fails if a committed fixture drifts from the engine.

Usage: uv run python scripts/export_web_fixtures.py
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


def main() -> None:
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    for path, data in ((FIXTURE, catalog()), (ANALYSIS, analysis())):
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
