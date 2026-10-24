from __future__ import annotations

from typing import Any

import pytest

from engine_builders import load_example


@pytest.fixture
def starter() -> dict[str, Any]:
    return load_example("cache-aside-starter.json")
