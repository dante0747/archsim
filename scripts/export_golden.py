"""Write the golden files of the Java port (parity/golden/), or check the committed ones.

The exporter is the package ``scripts/golden``; parity/README.md describes the files.

Usage:
    uv run python scripts/export_golden.py                 # write parity/golden/
    uv run python scripts/export_golden.py --check         # exit 1 if a fresh export differs
    uv run python scripts/export_golden.py --only queueing-lab [--check]
    uv run python scripts/export_golden.py --jobs 1        # one process (default: up to 8)
"""

from __future__ import annotations

import sys

from golden.cli import main

if __name__ == "__main__":
    sys.exit(main())
