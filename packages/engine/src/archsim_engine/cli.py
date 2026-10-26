"""Command-line interface: validate documents, run simulations, print the catalog/schema.

Examples::

    archsim validate examples/cache-aside-starter.json
    archsim run examples/cache-aside-starter.json --scenario three-api-replicas --out report.json
    archsim compare examples/cache-aside-starter.json --a baseline --b one-api-replica
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine import (
    DocumentInvalidError,
    SimulationLimitError,
    analyze_document,
    compare_reports,
    default_registry,
    document_json_schema,
    load_document,
    run_simulation,
    validate_document,
)


def _load(path: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads(Path(path).read_text(encoding="utf-8"))
    return data


def _print_issues(issues: list[Any]) -> None:
    for i in issues:
        where = f" [{i.node_id or i.edge_id}]" if (i.node_id or i.edge_id) else ""
        print(f"  {i.severity.upper():7} {i.code}{where}: {i.message}")
        if i.hint:
            print(f"          hint: {i.hint}")


def _summary_line(r: dict[str, Any]) -> str:
    s = r["summary"]
    lat = s["latencyMs"]

    def f(v: Any, spec: str) -> str:
        return "n/a" if v is None else format(v, spec)

    return (
        f"offered {f(s['offeredRps'], ',.1f')} req/s | throughput {f(s['throughputRps'], ',.1f')} req/s | "
        f"success {f(s['successRate'], '.2%')} | p50 {f(lat['p50'], ',.1f')} ms | "
        f"p95 {f(lat['p95'], ',.1f')} ms | p99 {f(lat['p99'], ',.1f')} ms"
    )


def cmd_validate(args: argparse.Namespace) -> int:
    try:
        doc = load_document(_load(args.file))
    except DocumentInvalidError as exc:
        print("INVALID")
        _print_issues(exc.issues)
        return 1
    result = validate_document(doc, default_registry())
    print("VALID" if result.valid else "INVALID")
    _print_issues(result.issues)
    return 0 if result.valid else 1


def cmd_run(args: argparse.Namespace) -> int:
    raw = _load(args.file)
    if args.seed is not None:
        raw.setdefault("workload", {})["seed"] = args.seed
    try:
        report = run_simulation(raw, scenario_id=args.scenario)
    except DocumentInvalidError as exc:
        print("Cannot simulate: document is invalid", file=sys.stderr)
        _print_issues(exc.issues)
        return 1
    except SimulationLimitError as exc:
        print(f"Cannot simulate: {exc}", file=sys.stderr)
        return 1
    print(_summary_line(report))
    for f in report["findings"]:
        print(f"  [{f['severity']}] {f['title']}: {f['detail']}")
    p = report["provenance"]
    print(
        f"  seed={p['seed']} inputDigest={p['inputDigest'][:12]} resultDigest={p['resultDigest'][:12]}"
    )
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"report written to {args.out}")
    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    raw = _load(args.file)
    ra = run_simulation(raw, scenario_id=args.a)
    rb = run_simulation(raw, scenario_id=args.b)
    cmp = compare_reports(ra, rb, args.a or "base", args.b or "base")
    print(f"A ({args.a or 'base'}): {_summary_line(ra)}")
    print(f"B ({args.b or 'base'}): {_summary_line(rb)}")
    for line in cmp["explanations"]:
        print(f"  - {line}")
    for w in cmp["warnings"]:
        print(f"  ! {w}")
    return 0


def cmd_analyze(args: argparse.Namespace) -> int:
    try:
        result = analyze_document(_load(args.file), default_registry(), args.scenario)
    except DocumentInvalidError as exc:
        print("Cannot analyze: document is invalid", file=sys.stderr)
        _print_issues(exc.issues)
        return 1
    print(json.dumps(result, indent=2))
    return 0


def cmd_catalog(args: argparse.Namespace) -> int:
    print(json.dumps(default_registry().catalog(), indent=2))
    return 0


def cmd_schema(args: argparse.Namespace) -> int:
    print(json.dumps(document_json_schema(), indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="archsim", description="ArchSim architecture simulator")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("validate", help="validate an architecture document")
    p.add_argument("file")
    p.set_defaults(fn=cmd_validate)
    p = sub.add_parser("run", help="simulate an architecture document")
    p.add_argument("file")
    p.add_argument("--scenario", help="scenario id to apply")
    p.add_argument("--seed", type=int, help="override the workload seed")
    p.add_argument("--out", help="write the full JSON report to this file")
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("compare", help="simulate two scenarios and compare them")
    p.add_argument("file")
    p.add_argument("--a", help="scenario id for A (default: base document)")
    p.add_argument("--b", help="scenario id for B (default: base document)")
    p.set_defaults(fn=cmd_compare)
    p = sub.add_parser(
        "analyze", help="print the auto sizes, cost estimate and design review as JSON"
    )
    p.add_argument("file")
    p.add_argument("--scenario", help="scenario id to apply")
    p.set_defaults(fn=cmd_analyze)
    p = sub.add_parser("catalog", help="print the component catalog as JSON")
    p.set_defaults(fn=cmd_catalog)
    p = sub.add_parser("schema", help="print the document JSON Schema")
    p.set_defaults(fn=cmd_schema)
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
