"""Write the component catalog (exactly as GET /api/v1/components returns it) to the web app's
test fixtures. A Python test fails if the committed fixture drifts from the registry.

Usage: uv run python scripts/export_web_fixtures.py
"""

from __future__ import annotations

import json
from pathlib import Path

from archsim_engine.components import create_default_registry
from archsim_engine.registry import describe_fields
from archsim_engine.schema import EdgeConfig, Workload

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "apps" / "web" / "src" / "test" / "fixtures" / "catalog.json"


def catalog() -> dict:
    registry = create_default_registry(include_plugins=False)
    return {
        "components": registry.catalog(),
        "edgeFields": describe_fields(EdgeConfig),
        "workloadFields": describe_fields(Workload),
    }


def main() -> None:
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(catalog(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {FIXTURE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
