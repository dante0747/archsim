"""Summarize what changed between two price snapshots, as Markdown (the monthly refresh's PR body).

    uv run python scripts/prices/diff.py \
        archsim-model/src/main/resources/io/archsim/model/pricing/snapshots/aws-us-east-1-2026-11-02.json
    uv run python scripts/prices/diff.py NEW.json --against OLD.json

Without ``--against``, the newest older snapshot of the same provider and region in the new file's
directory is the baseline. Prints the summary; under GitHub Actions it also writes
``changed=true|false`` to ``$GITHUB_OUTPUT`` (false: same prices as the baseline, only the date
differs, so there is nothing to propose).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

LISTS = {
    "compute": "EC2 / compute",
    "databases": "Databases",
    "caches": "Caches",
    "streams": "Streams",
}
TOP = 25


def _key(s: dict[str, Any]) -> tuple[str, str]:
    return s["sku"], ",".join(s.get("engines") or [])


def _prices(snap: dict[str, Any], key: str) -> dict[tuple[str, str], float]:
    return {_key(s): float(s["hourly"]["on_demand"]) for s in snap.get(key) or []}


def _content(snap: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in snap.items() if k != "snapshotDate"}


def baseline_for(new: Path) -> Path | None:
    """The newest snapshot older than ``new`` of the same provider and region, in its directory."""
    data = json.loads(new.read_text(encoding="utf-8"))
    best: tuple[str, Path] | None = None
    for p in new.parent.glob("*.json"):
        if p.resolve() == new.resolve():
            continue
        try:
            other = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        same = (other.get("provider"), other.get("region")) == (
            data.get("provider"),
            data.get("region"),
        )
        date = str(other.get("snapshotDate") or "")
        if same and date < str(data.get("snapshotDate") or "") and (best is None or date > best[0]):
            best = (date, p)
    return best[1] if best else None


def _name(k: tuple[str, str]) -> str:
    return f"{k[0]} ({k[1]})" if k[1] else k[0]


def summarize(old: dict[str, Any] | None, new: dict[str, Any]) -> tuple[str, bool]:
    """Markdown summary of ``new`` against ``old``, and whether any price or type changed."""
    where = f"{new['provider']} {new['region']}"
    lines = [f"## Price snapshot: {where}, {new['snapshotDate']}", ""]
    counts = ", ".join(f"{label} {len(new.get(k) or []):,}" for k, label in LISTS.items())
    lines.append(
        f"Types: {counts}. On-demand list prices from {', '.join(s['name'] for s in new['sources'])}."
    )
    lines.append("")
    if old is None:
        lines.append("No earlier snapshot of this provider and region to compare with.")
        return "\n".join(lines) + "\n", True
    lines.append(f"Compared with the snapshot of {old['snapshotDate']}:")
    lines.append("")
    changed = _content(old) != _content(new)
    moves: list[tuple[float, str]] = []
    lines.append("| List | Added | Removed | Price changed |")
    lines.append("|---|---:|---:|---:|")
    for key, label in LISTS.items():
        a, b = _prices(old, key), _prices(new, key)
        added = sorted(set(b) - set(a))
        removed = sorted(set(a) - set(b))
        moved = [k for k in sorted(set(a) & set(b)) if a[k] != b[k]]
        lines.append(f"| {label} | {len(added)} | {len(removed)} | {len(moved)} |")
        for k in moved:
            pct = (b[k] - a[k]) / a[k] * 100 if a[k] else float("inf")
            moves.append((abs(pct), f"| {_name(k)} | ${a[k]:g} | ${b[k]:g} | {pct:+.1f}% |"))
        for k in added[:TOP]:
            moves.append((-1.0, f"| {_name(k)} (new) | - | ${b[k]:g} | |"))
        for k in removed[:TOP]:
            moves.append((-1.0, f"| {_name(k)} (gone) | ${a[k]:g} | - | |"))
    rate_rows = []
    old_rates, new_rates = old.get("rates") or {}, new.get("rates") or {}
    for r in sorted(set(old_rates) | set(new_rates)):
        if old_rates.get(r) != new_rates.get(r):
            rate_rows.append(f"| {r} | {old_rates.get(r, '-')} | {new_rates.get(r, '-')} |")
    lines.append("")
    if not changed:
        lines.append("**No prices changed**; only the date differs.")
        return "\n".join(lines) + "\n", False
    if rate_rows:
        lines += ["Rates:", "", "| Rate | Before | After |", "|---|---:|---:|", *rate_rows, ""]
    if moves:
        moves.sort(key=lambda m: -m[0])
        shown = moves[:TOP]
        lines += [
            f"Instance types (largest changes first{f', {TOP} of {len(moves)}' if len(moves) > TOP else ''}):",
            "",
            "| Type | Before ($/h) | After ($/h) | Change |",
            "|---|---:|---:|---:|",
            *[row for _, row in shown],
            "",
        ]
    return "\n".join(lines) + "\n", True


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("new", type=Path, help="the new snapshot")
    ap.add_argument(
        "--against", type=Path, help="the snapshot to compare with (default: the newest older one)"
    )
    args = ap.parse_args()
    new = json.loads(args.new.read_text(encoding="utf-8"))
    base = args.against or baseline_for(args.new)
    old = json.loads(base.read_text(encoding="utf-8")) if base else None
    text, changed = summarize(old, new)
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]  # dashes, on any console
    print(text, end="")
    if out := os.environ.get("GITHUB_OUTPUT"):
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"changed={'true' if changed else 'false'}\n")


if __name__ == "__main__":
    main()
