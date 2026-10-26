"""benchmarks/run.py and benchmarks/report.py are scripts, not a package: put their directory on
the path so the tests can import them as ``run`` and ``report``, as report.py itself does."""

from __future__ import annotations

import sys
from pathlib import Path

BENCHMARKS = Path(__file__).resolve().parents[1]
if str(BENCHMARKS) not in sys.path:
    sys.path.insert(0, str(BENCHMARKS))
