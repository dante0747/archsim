"""Write Python's case folding for the Java engine, or check the committed file.

The design review sorts its findings' titles as the Python engine does, folded with
``str.casefold()``. Java has no case folding, and its case mappings follow the Unicode version of
the JDK it runs on, newer than the frozen engine's Python 3.12 (Unicode 15.0). So the Java reads
the folding from data: every code point whose ``casefold()`` is not itself, with what it folds to,
as this script writes it to archsim-engine's resources for ``PythonStrings``.

Usage:
    uv run python scripts/export_casefold.py           # write the table
    uv run python scripts/export_casefold.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import sys
import unicodedata
from pathlib import Path

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/main/resources/io/archsim/engine/text/casefold.tsv"
)
SURROGATES = range(0xD800, 0xE000)


def export() -> str:
    """The table's text: a header, then one line per code point that folds to something else."""
    lines = [
        f"# str.casefold() of Python {sys.version_info.major}.{sys.version_info.minor} "
        f"(Unicode {unicodedata.unidata_version}): code point, then what it folds to, in hex;",
        "# every code point not listed folds to itself. Written by scripts/export_casefold.py.",
    ]
    for code_point in range(sys.maxunicode + 1):
        if code_point in SURROGATES:
            continue
        folded = chr(code_point).casefold()
        if folded != chr(code_point):
            lines.append(f"{code_point:04X}\t{' '.join(f'{ord(c):04X}' for c in folded)}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_casefold.py")
            return 1
        print(f"{OUT.name} matches Python's casefold")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
