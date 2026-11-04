"""Write Python's ``math.erfc`` at test points for the Java engine, or check the committed file.

Java has no error function, so archsim-engine ports fdlibm's ``erfc`` (``numeric.Erf``), which the
instant estimate's p95 needs. ``ErfTests`` checks it against Python 3.12's ``math.erfc`` at the
points this script writes: the edges of each interval of the approximation and random points over
each, as exact hex floats.

Usage:
    uv run python scripts/export_erfc_points.py           # write the points
    uv run python scripts/export_erfc_points.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library (glibc rather than the Windows CRT) may differ in the last bit of ``exp``,
``log`` or ``erfc``. It is a manual step for that reason, not a CI one; the Java tests compare
with tolerances.
"""

from __future__ import annotations

import argparse
import math
import random
import sys
from pathlib import Path

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/numeric/erfc.csv"
)
SEED = 20261104
EDGES = [
    0.0,
    -0.0,
    2**-57,
    -(2**-57),
    1e-300,
    0.24,
    0.25,
    0.26,
    0.84375,
    -0.84375,
    0.84374,
    1.0,
    1.25,
    -1.25,
    1.2499,
    2.857142857142857,
    2.8571,
    2.858,
    -2.858,
    6.0,
    -6.0,
    -5.99,
    27.9,
    28.0,
    30.0,
    -30.0,
    0.5,
    -1.3,
    3.7,
]
"""The intervals' edges (|x| at 2**-56, 0.25, 0.84375, 1.25, about 1/0.35, 6 and 28) and more."""
RANDOM = [(-1.0, 1.0, 120), (-1.3, 1.3, 120), (-3.0, 3.0, 150), (-7.0, 28.0, 200)]
"""Random points: (low, high, how many) for each stretch."""


def export() -> str:
    """The points file's text: one ``x,erfc(x)`` line per point, as hex floats."""
    generator = random.Random(SEED)
    points = list(EDGES)
    for low, high, count in RANDOM:
        points += [generator.uniform(low, high) for _ in range(count)]
    lines = [
        "# x,erfc(x): Python 3.12's math.erfc, both as hex floats (scripts/export_erfc_points.py)"
    ]
    lines += [f"{x.hex()},{math.erfc(x).hex()}" for x in points]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_erfc_points.py")
            return 1
        print(f"{OUT.name} matches Python's math.erfc")
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
