"""Command-line interface: validate documents, run simulations, print the catalog/schema.

Examples::

    archsim validate examples/cache-aside-starter.json
    archsim run examples/cache-aside-starter.json --scenario three-api-replicas --out report.json
    archsim compare examples/cache-aside-starter.json --a baseline --b one-api-replica
    archsim calibrate prometheus doc.json --map mapping.yaml --url http://localhost:9090 \
        --range 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z -o calibrated.json
    archsim calibrate otlp doc.json --traces traces.json -o calibrated.json
    archsim import compose docker-compose.yml -o design.json --report import-report.json
    archsim export k8s design.json -o manifests.yaml
    archsim check archsim/design.json --baseline-ref origin/main --fail-on objectives \
        --fail-on regression --format markdown --out check.md
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


def _write_calibration(outcome: Any, out: str | None) -> int:
    from archsim_engine.calibration import replay_summary

    for c in outcome.changes:
        fit = f" (fit error {c.fit_error:.0%})" if c.fit_error is not None else ""
        print(f"  set {c.label}{fit}", file=sys.stderr)
    for n in outcome.notes:
        print(f"  note: {n}", file=sys.stderr)
    for c in (outcome.document.get("calibration") or {}).get("replay", []):
        err = f"{c['error']:+.0%}" if c.get("error") is not None else "n/a"
        sim = f"{c['simulated']:,.2f}" if c.get("simulated") is not None else "n/a"
        print(
            f"  replay {c['label']} {c['metric']}: observed {c['observed']:,.2f}, simulated {sim} ({err})",
            file=sys.stderr,
        )
    line = replay_summary(outcome.document.get("calibration"))
    if line:
        print(line, file=sys.stderr)
    text = json.dumps(outcome.document, indent=2)
    if out:
        Path(out).write_text(text + "\n", encoding="utf-8")
        print(f"wrote {out}", file=sys.stderr)
    else:
        print(text)
    return 0


def cmd_calibrate_prometheus(args: argparse.Namespace) -> int:
    from archsim_engine.calibration import (
        HttpPrometheus,
        PrometheusError,
        RecordedPrometheus,
        RecordingPrometheus,
        calibrate_prometheus,
        load_mapping,
        parse_range,
    )

    mapping = load_mapping(Path(args.map))
    client: Any = (
        RecordedPrometheus.load(args.recorded) if args.recorded else HttpPrometheus(args.url)
    )
    if args.record:
        client = RecordingPrometheus(client)
    rng = parse_range(args.range, args.step) if args.range else None
    try:
        outcome = calibrate_prometheus(
            _load(args.file), mapping, client, default_registry(), rng, replay=not args.no_replay
        )
    except DocumentInvalidError as exc:
        print("Cannot calibrate: document is invalid", file=sys.stderr)
        _print_issues(exc.issues)
        return 1
    except (PrometheusError, ValueError) as exc:
        print(f"Cannot calibrate: {exc}", file=sys.stderr)
        return 1
    if args.record:
        client.save(args.record)
        print(f"recorded the responses to {args.record}", file=sys.stderr)
    return _write_calibration(outcome, args.out)


def cmd_calibrate_otlp(args: argparse.Namespace) -> int:
    from archsim_engine.calibration import calibrate_otlp

    mapping = None
    if args.map:
        import yaml

        mapping = yaml.safe_load(Path(args.map).read_text(encoding="utf-8"))
    try:
        outcome = calibrate_otlp(_load(args.file), args.traces, default_registry(), mapping)
    except DocumentInvalidError as exc:
        print("Cannot calibrate: document is invalid", file=sys.stderr)
        _print_issues(exc.issues)
        return 1
    except (ValueError, KeyError) as exc:
        print(f"Cannot calibrate: {exc}", file=sys.stderr)
        return 1
    return _write_calibration(outcome, args.out)


def cmd_import(args: argparse.Namespace) -> int:
    from archsim_engine.importers import import_design

    text = sys.stdin.read() if args.file == "-" else Path(args.file).read_text(encoding="utf-8")
    try:
        doc, report = import_design(args.format, text, default_registry(), args.name)
    except ValueError as exc:
        print(f"cannot import: {exc}", file=sys.stderr)
        return 2
    r = report.to_dict()
    for key in ("mapped", "guessed", "ignored"):
        for line in r[key]:
            print(f"  {key}: {line}", file=sys.stderr)
    for s in r["suggestions"]:
        print(f"  connect? {s['source']} -> {s['target']} ({s['reason']})", file=sys.stderr)
    errors = [i for i in r["issues"] if i["severity"] == "error"]
    print(
        f"{len(doc['architecture']['nodes'])} parts, {len(doc['architecture']['edges'])} links; "
        f"{len(r['guessed'])} guesses to check, {len(errors)} errors to fix before a run",
        file=sys.stderr,
    )
    if args.report:
        Path(args.report).write_text(json.dumps(r, indent=2) + "\n", encoding="utf-8")
    text = json.dumps(doc, indent=2)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        print(f"wrote {args.out}", file=sys.stderr)
    else:
        print(text)
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    from archsim_engine.export import export_document

    try:
        text = export_document(_load(args.file), args.format, default_registry())
    except DocumentInvalidError as exc:
        _print_issues(exc.issues)
        return 1
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"wrote {args.out}", file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


def _at_ref(ref: str, path: str) -> dict[str, Any] | None:
    """The JSON of ``path`` as it is at git ``ref`` (None when the file does not exist there)."""
    import subprocess

    file = Path(path).resolve()
    top = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=file.parent,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    rel = file.relative_to(Path(top).resolve()).as_posix()
    shown = subprocess.run(
        ["git", "show", f"{ref}:{rel}"], cwd=top, capture_output=True, text=True, check=False
    )
    if shown.returncode != 0:
        return None
    data: dict[str, Any] = json.loads(shown.stdout)
    return data


def cmd_check(args: argparse.Namespace) -> int:
    from archsim_engine.check import Thresholds, run_check, to_markdown

    try:
        doc = _load(args.file)
        baseline = None
        if args.baseline:
            baseline = _load(args.baseline)
        elif args.baseline_ref:
            baseline = _at_ref(args.baseline_ref, args.file)
            if baseline is None:
                print(
                    f"{args.file} does not exist at {args.baseline_ref}: no baseline",
                    file=sys.stderr,
                )
        result = run_check(
            doc,
            default_registry(),
            scenario_id=args.scenario,
            baseline=baseline,
            thresholds=Thresholds(
                p95_increase=args.max_p95_increase,
                success_drop=args.max_success_drop,
                cost_increase=args.max_cost_increase,
                knee_drop=args.max_knee_drop,
            ),
            knee=args.knee,
            fail_on=tuple(args.fail_on or ["objectives"]),
        )
    except DocumentInvalidError as exc:
        _print_issues(exc.issues)
        return 2
    except SimulationLimitError as exc:
        print(f"cannot check: {exc}", file=sys.stderr)
        return 2
    except (OSError, ValueError) as exc:  # unreadable file, bad JSON, git failure
        print(f"cannot check: {exc}", file=sys.stderr)
        return 2
    text = (
        to_markdown(result, args.report_url)
        if args.format == "markdown"
        else json.dumps(result, indent=2) + "\n"
    )
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(("PASS: " if result["pass"] else "FAIL: ") + result["headline"], file=sys.stderr)
    code: int = result["exitCode"]
    return code


def _ref(value: str) -> str:
    if value.startswith("-"):
        raise argparse.ArgumentTypeError("a git ref cannot start with '-'")
    return value


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
        "analyze",
        help="print the auto sizes, cost estimate, design review and technology tiers as JSON",
    )
    p.add_argument("file")
    p.add_argument("--scenario", help="scenario id to apply")
    p.set_defaults(fn=cmd_analyze)
    cal = sub.add_parser(
        "calibrate", help="set a design's inputs from telemetry (docs/calibration.md)"
    )
    cal_sub = cal.add_subparsers(dest="source", required=True)
    p = cal_sub.add_parser("prometheus", help="from Prometheus metrics, with a mapping file")
    p.add_argument("file", help="the design to calibrate")
    p.add_argument(
        "--map", required=True, help="mapping file (YAML or JSON): which query sets which input"
    )
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--url", help="Prometheus base URL, e.g. http://localhost:9090")
    src.add_argument(
        "--recorded", help="replay responses recorded earlier (--record) instead of querying"
    )
    p.add_argument("--range", help="START/END in ISO 8601 (default: the mapping's range)")
    p.add_argument("--step", type=float, default=60, help="seconds between points (default 60)")
    p.add_argument(
        "--record", help="also save every response to this file (for offline replays and tests)"
    )
    p.add_argument("--no-replay", action="store_true", help="skip the replay check")
    p.add_argument("-o", "--out", help="write the calibrated design here (default: stdout)")
    p.set_defaults(fn=cmd_calibrate_prometheus)
    p = cal_sub.add_parser("otlp", help="from an OpenTelemetry trace export (OTLP JSON)")
    p.add_argument("file", help="the design to calibrate")
    p.add_argument("--traces", required=True, help="OTLP JSON file (resourceSpans)")
    p.add_argument(
        "--map", help="YAML: services: {service.name: part id}, databases: {db: part id}"
    )
    p.add_argument("-o", "--out", help="write the calibrated design here (default: stdout)")
    p.set_defaults(fn=cmd_calibrate_otlp)
    p = sub.add_parser(
        "import", help="a design from docker-compose, Kubernetes manifests or a Terraform plan"
    )
    p.add_argument("format", choices=["compose", "k8s", "terraform"])
    p.add_argument(
        "file", help="the file ('-' for stdin); Terraform: the output of terraform show -json"
    )
    p.add_argument("--name", help="the design's name (default: from the file)")
    p.add_argument("-o", "--out", help="write the design here (default: stdout)")
    p.add_argument("--report", help="write the import report (JSON) here")
    p.set_defaults(fn=cmd_import)
    p = sub.add_parser("export", help="docker-compose, Kubernetes or Mermaid from a design")
    p.add_argument("format", choices=["compose", "k8s", "mermaid"])
    p.add_argument("file", help="the design (JSON)")
    p.add_argument("-o", "--out", help="write here (default: stdout)")
    p.set_defaults(fn=cmd_export)
    p = sub.add_parser(
        "check",
        help="an SLO gate for CI: objectives and regressions; exit 0 pass, 1 fail, 2 invalid input",
    )
    p.add_argument("file", help="the design (JSON)")
    p.add_argument("--scenario", help="check one of its scenarios instead")
    base = p.add_mutually_exclusive_group()
    base.add_argument("--baseline", help="another design file to compare with")
    base.add_argument(
        "--baseline-ref", type=_ref, help="compare with this file at a git ref (e.g. origin/main)"
    )
    p.add_argument(
        "--fail-on",
        action="append",
        choices=["objectives", "regression"],
        help="what fails the check (repeatable; default: objectives)",
    )
    p.add_argument("--max-p95-increase", type=float, default=0.10, help="relative (default 0.10)")
    p.add_argument("--max-success-drop", type=float, default=0.001, help="absolute (default 0.001)")
    p.add_argument("--max-cost-increase", type=float, default=0.10, help="relative (default 0.10)")
    p.add_argument("--max-knee-drop", type=float, default=0.10, help="relative (default 0.10)")
    p.add_argument("--knee", action="store_true", help="also compare breaking points (slower)")
    p.add_argument("--format", choices=["markdown", "json"], default="markdown")
    p.add_argument("-o", "--out", help="write the result here (default: stdout)")
    p.add_argument("--json-out", help="also write the JSON result here")
    p.add_argument("--report-url", help="link to the full report in the Markdown")
    p.set_defaults(fn=cmd_check)
    p = sub.add_parser("catalog", help="print the component catalog as JSON")
    p.set_defaults(fn=cmd_catalog)
    p = sub.add_parser("schema", help="print the document JSON Schema")
    p.set_defaults(fn=cmd_schema)
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
