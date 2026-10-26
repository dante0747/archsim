"""Write docs/assumptions.md: every number ArchSim uses without the user typing it, each marked
documented (with its link) or illustrative, read from the engine code that uses it.

Usage: uv run python scripts/gen_assumptions.py
A test (packages/engine/tests/test_assumptions.py) fails when the committed file is out of date.
"""

from __future__ import annotations

from pathlib import Path

from archsim_engine.assumptions import render

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "docs" / "assumptions.md"


def main() -> None:
    TARGET.write_text(render(), encoding="utf-8", newline="\n")
    print(f"wrote {TARGET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
