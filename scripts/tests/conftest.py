"""The scripts' tests import a script as a module (``check_commits``) from ``scripts/``, the
folder a script runs from."""

from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
