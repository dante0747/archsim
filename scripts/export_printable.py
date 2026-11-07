"""Write which code points Python's ``repr()`` escapes, for the Java model, or check the committed file.

The frozen engine quotes texts in its messages with ``repr()``, which escapes every character
``str.isprintable()`` rejects: the Unicode categories Cc, Cf, Cs, Co, Cn, Zl, Zp and Zs, the space
excepted. Java's ``Character`` follows the Unicode version of the JDK it runs on, newer than the
frozen engine's Python 3.12 (Unicode 15.0), so a character assigned since then would print in Java
and be escaped in Python. So the Java reads the escaped characters from data: the ranges of code
points ``isprintable()`` rejects, as this script writes them to archsim-model's resources for
``PythonText``.

Usage:
    uv run python scripts/export_printable.py           # write the table
    uv run python scripts/export_printable.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import sys
import unicodedata
from pathlib import Path

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-model/src/main/resources/io/archsim/model/text/nonprintable.tsv"
)
PYTHON = (3, 12)
"""The frozen engine's Python, whose Unicode version the table records."""


def ranges() -> list[tuple[int, int]]:
    """The runs of code points ``isprintable()`` rejects, first and last inclusive."""
    runs: list[tuple[int, int]] = []
    start = None
    for code_point in range(sys.maxunicode + 1):
        printable = chr(code_point).isprintable()
        if not printable and start is None:
            start = code_point
        elif printable and start is not None:
            runs.append((start, code_point - 1))
            start = None
    if start is not None:
        runs.append((start, sys.maxunicode))
    return runs


def export() -> str:
    """The table's text: a header, then one line per run of code points ``repr()`` escapes."""
    lines = [
        f"# str.isprintable() of Python {sys.version_info.major}.{sys.version_info.minor} "
        f"(Unicode {unicodedata.unidata_version}): the first and last code point, in hex, of each",
        "# run it rejects, which repr() escapes; every other code point prints as it is.",
        "# Written by scripts/export_printable.py.",
    ]
    lines.extend(f"{first:04X}\t{last:04X}" for first, last in ranges())
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    if sys.version_info[:2] != PYTHON:
        print(f"run this with Python {PYTHON[0]}.{PYTHON[1]}, the frozen engine's (uv run)")
        return 1
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_printable.py")
            return 1
        print(f"{OUT.name} matches Python's isprintable")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
