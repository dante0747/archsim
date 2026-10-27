"""ArchSim MCP server (stdio): the engine as tools for AI coding agents, no browser or API needed.

Tools mirror the web app's WebMCP tools where they overlap. Every result is compact JSON; inputs
that cannot be used come back as errors that say how to fix them (validation issues carry codes,
part ids and hints). Runs enforce the engine's limits (wall clock, requests, fan-out).

Run it from a checkout with ``uv run archsim-mcp`` (see docs/agents.md).
"""

from __future__ import annotations

import functools
import json
import os
from collections import OrderedDict
from pathlib import Path
from typing import Any, Literal

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

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
from archsim_engine.engine import SimulationLimits
from archsim_engine.slo import evaluate, verdict_text

Json = dict[str, Any]
READ = ToolAnnotations(read_only_hint=True, open_world_hint=False)
COMPUTE = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=False)
MAX_RUNS_KEPT = 20
LIMITS = SimulationLimits(
    max_requests=int(os.environ.get("ARCHSIM_MCP_MAX_REQUESTS", "2000000")),
    max_wall_seconds=float(os.environ.get("ARCHSIM_MCP_MAX_WALL_SECONDS", "120")),
)

mcp = MCPServer(
    "archsim",
    instructions=(
        "ArchSim simulates distributed-system designs (services, databases, caches, queues, load "
        "balancers) with a discrete-event engine. Start with list_templates or list_part_types, "
        "get_template for a ready-made design document, validate_design and analyze_design "
        "before simulate. Documents are ArchSim JSON (schema at archsim://schema/architecture). "
        "Numbers are estimates from the model and its assumptions, not measurements."
    ),
)
_runs: OrderedDict[str, Json] = OrderedDict()


def tool(annotations: ToolAnnotations) -> Any:
    """``mcp.tool`` whose expected failures (an unusable document, bad arguments, a missing file)
    reach the agent with their message: the SDK withholds the text of any other exception."""

    def register(fn: Any) -> Any:
        @functools.wraps(fn)
        def call(*args: Any, **kwargs: Any) -> Any:
            try:
                return fn(*args, **kwargs)
            except ToolError:
                raise
            except (ValueError, KeyError, OSError) as exc:
                raise ToolError(str(exc)) from None

        return mcp.tool(annotations=annotations)(call)

    return register


# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------


def examples_dir() -> Path | None:
    env = os.environ.get("ARCHSIM_EXAMPLES_DIR")
    if env:
        return Path(env)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "examples"
        if candidate.is_dir() and any(candidate.glob("*.json")):
            return candidate
    return None


def _templates() -> dict[str, Json]:
    d = examples_dir()
    if d is None:
        return {}
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*.json"))}


def _fix(exc: DocumentInvalidError) -> ValueError:
    lines = [
        f"{i.severity} {i.code}{f' [{i.node_id or i.edge_id}]' if (i.node_id or i.edge_id) else ''}: {i.message}"
        + (f" Fix: {i.hint}" if i.hint else "")
        for i in exc.issues
    ]
    return ValueError("The document cannot be used:\n" + "\n".join(lines))


def _doc(document: Json | str) -> Json:
    if isinstance(document, str):
        try:
            document = json.loads(document)
        except json.JSONDecodeError as exc:
            raise ValueError(f"document is not valid JSON: {exc}") from None
    if not isinstance(document, dict):
        raise ValueError("document must be an ArchSim architecture document (a JSON object)")
    return document


def _keep(run_id: str, report: Json) -> None:
    _runs[run_id] = report
    _runs.move_to_end(run_id)
    while len(_runs) > MAX_RUNS_KEPT:
        _runs.popitem(last=False)


def _confidence(c: Json | None) -> Json | None:
    """How far to trust the verdict: level, the reason, and what would raise it."""
    if not c:
        return None
    return {
        "level": c["level"],
        "reason": c["reason"],
        "howToRaise": c["howToRaise"],
        "drivers": [f"{d['label']}: {d['fieldLabel']} ({d['kind']})" for d in c["drivers"]],
    }


def _estimate(e: Json | None) -> Json | None:
    """The queueing formulas' prediction, without simulating."""
    if not e:
        return None
    return {
        k: e.get(k)
        for k in ("method", "summary", "latencyMs", "busiest", "overloaded", "saturationRps")
    }


def summarize(report: Json, objectives: Json) -> Json:
    """The compact result an agent needs: verdict, key numbers, findings, parts, cost, provenance."""
    s = report["summary"]
    slo = evaluate(objectives, s)
    cost = report.get("cost") or {}
    prov = report["provenance"]
    return {
        "verdict": verdict_text(slo),
        "confidence": _confidence(report.get("confidence")),
        "objectives": slo["checks"],
        "throughputRps": s.get("throughputRps"),
        "offeredRps": s.get("offeredRps"),
        "successRate": s.get("successRate"),
        "latencyMs": {k: s["latencyMs"].get(k) for k in ("p50", "p95", "p99")},
        "findings": [
            {k: f.get(k) for k in ("severity", "kind", "nodeId", "title", "suggestion")}
            for f in report.get("findings", [])[:8]
        ],
        "parts": [
            {
                "id": n["id"],
                "label": n["label"],
                "status": n["summary"].get("status"),
                "utilization": n["summary"].get("utilization"),
                "p95Ms": (n["summary"].get("latencyMs") or {}).get("p95"),
            }
            for n in report["nodes"]
        ],
        "monthlyCostUsd": cost.get("monthly") if cost.get("hasCost") else None,
        "inputs": (report.get("assumptions") or {}).get("provenanceCounts"),
        "simulationMode": (report.get("simulationMode") or {}).get("note") or "full",
        "runId": prov["resultDigest"][:12],
        "resultDigest": prov["resultDigest"],
        "engineVersion": prov["engineVersion"],
        "seed": prov["seed"],
    }


def _simulate(
    document: Json | str, scenario_id: str | None, mode: Literal["full", "sampled"] = "full"
) -> tuple[Json, Json]:
    doc = _doc(document)
    try:
        report = run_simulation(doc, scenario_id=scenario_id, limits=LIMITS, mode=mode)
    except DocumentInvalidError as exc:
        raise _fix(exc) from None
    except SimulationLimitError as exc:
        raise ValueError(
            f"The run hit a limit: {exc}. Shorten durationS or lower the load."
        ) from None
    objectives = load_document(doc).objectives.model_dump(mode="json", by_alias=True)
    return report, summarize(report, objectives)


# ---------------------------------------------------------------------------------------------
# tools
# ---------------------------------------------------------------------------------------------


@tool(READ)
def list_part_types(type: str | None = None) -> Any:
    """Without a type: every kind of part (services, databases, caches, queues, load balancers,
    clients, third-party APIs, Kubernetes clusters) with their technology presets and tiers.
    With a type: its settings (key, kind, default, options, unit, limits, meaning), ports and presets."""
    cat = default_registry().catalog()
    if type:
        c = next((x for x in cat if x["type"] == type), None)
        if c is None:
            raise ValueError(
                f"unknown part type '{type}'; known: {', '.join(x['type'] for x in cat)}"
            )
        return {
            "type": c["type"],
            "name": c["displayName"],
            "description": c["description"],
            "ports": [
                {k: p[k] for k in ("name", "direction", "requestTypes", "description")}
                for p in c["ports"]
            ],
            "settings": [
                {
                    k: f.get(k)
                    for k in (
                        "key",
                        "label",
                        "kind",
                        "default",
                        "options",
                        "unit",
                        "min",
                        "max",
                        "nullable",
                        "description",
                    )
                }
                for f in c["fields"]
            ],
            "presets": [
                {k: p.get(k) for k in ("id", "name", "technology", "tier", "description")}
                for p in c["presets"]
            ],
        }
    return [
        {
            "type": c["type"],
            "name": c["displayName"],
            "category": c["category"],
            "description": c["description"],
            "presets": [
                {k: p.get(k) for k in ("id", "name", "technology", "tier")} for p in c["presets"]
            ],
        }
        for c in cat
    ]


@tool(READ)
def list_templates() -> list[Json]:
    """Ready-made designs: id, name, description and scenario count."""
    return [
        {
            "id": tid,
            "name": d.get("metadata", {}).get("name"),
            "description": d.get("metadata", {}).get("description"),
            "scenarios": len(d.get("scenarios", [])),
        }
        for tid, d in _templates().items()
    ]


@tool(READ)
def get_template(id: str) -> Json:
    """The full document of a template (edit it and pass it to the other tools)."""
    t = _templates()
    if id not in t:
        raise ValueError(f"unknown template '{id}'; templates: {', '.join(t)}")
    return t[id]


@tool(COMPUTE)
def validate_design(document: Json | str) -> Json:
    """Check a document: errors block a run, warnings do not. Each issue says how to fix it."""
    doc = _doc(document)
    try:
        result = validate_document(load_document(doc), default_registry())
    except DocumentInvalidError as exc:
        return {"valid": False, "issues": [i.to_dict() for i in exc.issues]}
    return {"valid": result.valid, "issues": [i.to_dict() for i in result.issues]}


@tool(COMPUTE)
def analyze_design(document: Json | str, scenario_id: str | None = None) -> Json:
    """Without simulating: the engine's auto sizes (CPU, memory, storage per part), monthly cost
    estimate, design review (capacity, timeouts, retries, single points of failure), technology
    tiers, where every input came from, an instant latency and utilization estimate from queueing
    formulas, and how much confidence the inputs deserve (high, medium or low, with why)."""
    try:
        a = analyze_document(_doc(document), default_registry(), scenario_id)
    except DocumentInvalidError as exc:
        raise _fix(exc) from None
    return {
        "peakRps": a["peakRps"],
        "sizes": {
            nid: {**e.get("auto", {}), "why": e.get("why")}
            for nid, e in a["sizing"]["nodes"].items()
            if not e.get("skipped")
        },
        "monthlyCostUsd": a["cost"].get("monthly") if a["cost"].get("hasCost") else None,
        "review": [
            {k: f.get(k) for k in ("severity", "title", "detail", "suggestion", "nodeIds")}
            for f in a["review"]
        ],
        "technologies": a.get("technologies"),
        "inputs": a.get("provenanceCounts"),
        "estimate": _estimate(a.get("estimate")),
        "confidence": _confidence(a.get("confidence")),
    }


@tool(COMPUTE)
def simulate(
    document: Json | str,
    scenario_id: str | None = None,
    mode: Literal["full", "sampled"] = "full",
) -> Json:
    """Run the discrete-event simulation of the design (or one of its scenarios). Returns the
    verdict against the document's objectives, throughput, success rate, latency, findings (the
    bottleneck and what to do), per-part status, monthly cost, run id and result digest (the
    same document, scenario and seed always give the same digest). mode "sampled" answers a long
    steady run from a steady sample (much faster; falls back to full when the load changes)."""
    report, summary = _simulate(document, scenario_id, mode)
    _keep(summary["runId"], report)
    return summary


@tool(READ)
def get_results(run_id: str, detail: Literal["summary", "full"] = "summary") -> Json:
    """A run from this session by id: the summary again, or the full report (large)."""
    report = _runs.get(run_id)
    if report is None:
        raise ValueError(f"no run '{run_id}' in this session (kept: {', '.join(_runs) or 'none'})")
    if detail == "full":
        return report
    objectives = (report.get("input") or {}).get("objectives") or {}
    return summarize(report, objectives)


@tool(COMPUTE)
def compare(document: Json | str, a: str | None = None, b: str | None = None) -> Json:
    """Simulate two scenarios of the document (None = the design as drawn) and explain the
    differences: KPIs with deltas and verdicts, and the input changes that caused them."""
    ra, sa = _simulate(document, a)
    rb, sb = _simulate(document, b)
    _keep(sa["runId"], ra)
    _keep(sb["runId"], rb)
    cmp = compare_reports(ra, rb, a or "base", b or "base")
    return {
        "a": sa,
        "b": sb,
        "kpis": cmp.get("kpis"),
        "explanations": cmp.get("explanations"),
        "warnings": cmp.get("warnings"),
    }


@tool(COMPUTE)
def breaking_point(
    document: Json | str,
    scenario_id: str | None = None,
    from_rps: float | None = None,
    to_rps: float | None = None,
    steps: int = 8,
) -> Json:
    """Find the highest load the design holds within its objectives: short constant-load runs from
    from_rps to to_rps (default half to three times the baseline), the knee where it stops keeping
    up, and the bottleneck there."""
    from archsim_engine.sweep import breaking_point as sweep

    try:
        out = sweep(
            _doc(document),
            default_registry(),
            scenario_id=scenario_id,
            from_rps=from_rps,
            to_rps=to_rps,
            steps=steps,
            limits=LIMITS,
            workers=min(steps, os.cpu_count() or 1),
        )
    except DocumentInvalidError as exc:
        raise _fix(exc) from None
    return out


@tool(COMPUTE)
def export(document: Json | str, format: Literal["compose", "k8s", "mermaid"]) -> str:
    """Scaffolding generated from the design: docker-compose, Kubernetes manifests or a Mermaid
    diagram (resources from the parts' specs or the engine's auto sizes). Review before use."""
    from archsim_engine.export import export_document

    try:
        return export_document(_doc(document), format, default_registry())
    except DocumentInvalidError as exc:
        raise _fix(exc) from None


@tool(COMPUTE)
def calibrate(
    document: Json | str,
    source: Literal["prometheus", "otlp"],
    mapping_path: str | None = None,
    recorded_path: str | None = None,
    traces_path: str | None = None,
) -> Json:
    """Set the design's inputs from telemetry files (no network): Prometheus responses recorded
    with `archsim calibrate prometheus --record` plus a mapping file, or an OTLP JSON trace
    export (optional mapping of service and database names to parts). Returns the calibrated
    document, what changed (with fit errors) and the replay check against what was observed."""
    from archsim_engine.calibration import (
        PrometheusError,
        RecordedPrometheus,
        calibrate_otlp,
        calibrate_prometheus,
        load_mapping_file,
        load_otlp_mapping,
        replay_summary,
    )

    doc = _doc(document)
    try:
        if source == "prometheus":
            if not mapping_path or not recorded_path:
                raise ValueError(
                    "Prometheus calibration here needs mapping_path and recorded_path (files)"
                )
            outcome = calibrate_prometheus(
                doc,
                load_mapping_file(mapping_path),
                RecordedPrometheus.load(recorded_path),
                default_registry(),
                limits=LIMITS,
            )
        else:
            if not traces_path:
                raise ValueError("OTLP calibration needs traces_path (an OTLP JSON file)")
            mapping = (
                load_otlp_mapping(Path(mapping_path).read_text(encoding="utf-8"))
                if mapping_path
                else None
            )
            outcome = calibrate_otlp(doc, traces_path, default_registry(), mapping, limits=LIMITS)
    except DocumentInvalidError as exc:
        raise _fix(exc) from None
    except SimulationLimitError as exc:
        raise ValueError(
            f"The replay run hit a limit: {exc}. Shorten the design's durationS or lower the load."
        ) from None
    except PrometheusError as exc:
        raise ValueError(f"Cannot calibrate: {exc}") from None
    result = outcome.to_dict()
    result["summary"] = replay_summary(outcome.document.get("calibration"))
    return result


@tool(COMPUTE)
def import_design(
    format: Literal["compose", "k8s", "terraform"], text: str, name: str | None = None
) -> Json:
    """A design from a docker-compose file, Kubernetes manifests or a Terraform plan (the JSON of
    `terraform show -json`), with the import report: what was mapped, what was guessed (marked
    assumed), what was ignored, links to confirm, and validation issues to fix before a run."""
    from archsim_engine.importers import import_design as run_import

    try:
        doc, report = run_import(format, text, default_registry(), name)
    except DocumentInvalidError as exc:
        raise _fix(exc) from None
    return {"document": doc, "report": report.to_dict()}


# ---------------------------------------------------------------------------------------------
# resources
# ---------------------------------------------------------------------------------------------


@mcp.resource("archsim://schema/architecture", mime_type="application/json")
def schema() -> str:
    """JSON Schema of ArchSim architecture documents."""
    return json.dumps(document_json_schema(), indent=1)


@mcp.resource("archsim://templates/{template_id}", mime_type="application/json")
def template(template_id: str) -> str:
    """A ready-made design document."""
    return json.dumps(get_template(template_id), indent=1)


@mcp.resource("archsim://docs/simulation-model", mime_type="text/markdown")
def simulation_model() -> str:
    """What the simulation models and what it does not (docs/simulation-model.md)."""
    d = examples_dir()
    path = d.parent / "docs" / "simulation-model.md" if d else None
    if path is None or not path.exists():
        return "docs/simulation-model.md is not available in this installation."
    return path.read_text(encoding="utf-8")


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
