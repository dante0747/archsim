"""The scripts' tests import the script packages (``golden``) as the scripts do: from
``scripts/``, the folder a script runs from."""

from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
