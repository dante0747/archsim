"""The table of characters Python's ``repr()`` escapes, which the Java model reads: the committed
file is a fresh export, and the export is what ``str.isprintable()`` says."""

from __future__ import annotations

import sys

import export_printable
import pytest

pytestmark = pytest.mark.skipif(
    sys.version_info[:2] != export_printable.PYTHON,
    reason="the table records the frozen engine's Python 3.12, whose Unicode version it holds",
)


def test_committed_table_is_a_fresh_export() -> None:
    assert export_printable.OUT.read_text(encoding="utf-8") == export_printable.export()


def test_ranges_hold_every_code_point_isprintable_rejects_and_no_other() -> None:
    rejected = {cp for first, last in export_printable.ranges() for cp in range(first, last + 1)}

    assert rejected == {cp for cp in range(sys.maxunicode + 1) if not chr(cp).isprintable()}
