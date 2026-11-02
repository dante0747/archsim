"""The command line: export the golden files, or check the committed ones."""

from __future__ import annotations

import argparse
import os
import sys
import time

from golden.build import build_all
from golden.compare import check
from golden.files import job_names
from golden.store import write


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Golden files for the Java port: the inputs and outputs of every "
        "deterministic engine function (parity/README.md)."
    )
    parser.add_argument("--check", action="store_true", help="compare with the committed files")
    parser.add_argument("--only", help="one template id (or e.g. catalog/pricing)")
    parser.add_argument(
        "--jobs", type=int, default=min(8, os.cpu_count() or 1), help="worker processes"
    )
    args = parser.parse_args(argv)
    names = job_names(args.only)
    t0 = time.perf_counter()
    results = build_all(names, args.jobs)
    slowest = sorted(results.items(), key=lambda kv: -kv[1][2])[:3]
    print(
        f"exported {len(results)} golden files in {time.perf_counter() - t0:.1f} s "
        f"(slowest: {', '.join(f'{n} {r[2]:.1f} s' for n, r in slowest)})",
        file=sys.stderr,
    )
    if args.check:
        return check(results, full=args.only is None)
    write(results, full=args.only is None)
    return 0
