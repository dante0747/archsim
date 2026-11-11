"""benchmarks/run.py and benchmarks/report.py are scripts, not a package: put their directory on
the path so the tests can import them as ``run`` and ``report``, as report.py itself does. And the
Java engine's versions, as its source declares them."""

from __future__ import annotations

import re
import sys
from collections.abc import Callable
from pathlib import Path

import pytest

BENCHMARKS = Path(__file__).resolve().parents[1]
if str(BENCHMARKS) not in sys.path:
    sys.path.insert(0, str(BENCHMARKS))

ENGINE_VERSIONS = (
    BENCHMARKS.parent / "archsim-engine/src/main/java/io/archsim/engine/version/EngineVersions.java"
)


@pytest.fixture
def java_engine_constant() -> Callable[[str], str]:
    """A constant of the Java engine's EngineVersions (``ENGINE``, ``REPORT``) by its name."""
    source = ENGINE_VERSIONS.read_text(encoding="utf-8")

    def constant(name: str) -> str:
        (value,) = re.findall(rf'String {name} = "([^"]+)";', source)
        return value

    return constant
