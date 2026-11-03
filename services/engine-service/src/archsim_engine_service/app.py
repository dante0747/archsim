"""The engine service's HTTP app: one ``POST`` endpoint per engine call, JSON in and out.

A call the engine refuses answers ``422`` with ``{"message", "issues", "limit"}``: the engine's
words and issues for a document it cannot read (``limit`` false), or for a run over its limits
(``limit`` true, no issues). The Java API turns that into its own error body. Answers are written
as FastAPI wrote them, so the Java API can pass them on unchanged.
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from typing import Any

from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from archsim_engine import (
    ENGINE_VERSION,
    ComponentRegistry,
    DocumentInvalidError,
    SimulationLimitError,
    SimulationLimits,
    compare_reports,
    default_registry,
    load_document,
    prepare_run,
    quick_check,
    resolve_scenario,
    summarize_seeds,
    validate_document,
)
from archsim_engine import analyze_document as engine_analyze


class BadRequest(Exception):
    """The request is not what an endpoint takes: not JSON, or missing a field."""


async def read_json(request: Request) -> dict[str, Any]:
    """The request's JSON object. The service is internal: the Java API has already refused
    a body over its ``ARCHSIM_MAX_BODY_BYTES``."""
    body = await request.body()
    try:
        value = json.loads(body)
    except ValueError as exc:
        raise BadRequest(f"Request body is not JSON: {exc}") from None
    if not isinstance(value, dict):
        raise BadRequest("Request body must be a JSON object")
    return value


def document_of(body: dict[str, Any]) -> dict[str, Any]:
    document = body.get("document")
    if not isinstance(document, dict):
        raise BadRequest("document must be a JSON object")
    return document


def whole_number(body: dict[str, Any], name: str) -> int | None:
    value = body.get(name)
    if value is not None and (isinstance(value, bool) or not isinstance(value, int)):
        raise BadRequest(f"{name} must be a whole number")
    return value


def number(body: dict[str, Any], name: str) -> float:
    value = body.get(name)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BadRequest(f"{name} must be a number")
    return float(value)


def scenario_of(body: dict[str, Any]) -> str | None:
    scenario = body.get("scenarioId")
    if scenario is not None and not isinstance(scenario, str):
        raise BadRequest("scenarioId must be a string")
    return scenario


Handler = Callable[[Request], Awaitable[Response]]


def endpoint(call: Callable[[dict[str, Any], ComponentRegistry], Any]) -> Handler:
    """An endpoint that runs ``call`` on the request's JSON with the standard registry."""

    async def handle(request: Request) -> Response:
        try:
            body = await read_json(request)
            # the engine computes for a while: in a worker thread, as FastAPI ran sync routes,
            # so other requests (and /health) are answered meanwhile
            result = await run_in_threadpool(call, body, request.app.state.registry)
        except BadRequest as exc:
            return JSONResponse({"message": str(exc)}, status_code=400)
        except DocumentInvalidError as exc:
            return refused(str(exc), [issue.to_dict() for issue in exc.issues], limit=False)
        except SimulationLimitError as exc:
            return refused(str(exc), [], limit=True)
        return JSONResponse(result)

    return handle


def refused(message: str, issues: list[dict[str, Any]], *, limit: bool) -> Response:
    return JSONResponse({"message": message, "issues": issues, "limit": limit}, status_code=422)


def analyze(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``analyze_document``: the auto sizes, cost and review of a document or one of its scenarios."""
    return engine_analyze(document_of(body), registry, scenario_of(body))


def validate(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``validate_document`` of a document or one of its scenarios, as ``POST /validate`` answered
    it: a scenario is resolved first (auto sizes, pinned prices, overrides), and a document that
    cannot be read or resolved is invalid with the reasons, not refused."""
    scenario_id = scenario_of(body)
    try:
        doc = load_document(document_of(body))
        resolved, _ = resolve_scenario(doc, scenario_id, registry)
    except DocumentInvalidError as exc:
        return {"valid": False, "issues": [issue.to_dict() for issue in exc.issues]}
    return validate_document(resolved if scenario_id else doc, registry).to_dict()


def prepare(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``prepare_run`` of a run about to be queued, within ``maxRequests`` and ``maxWallSeconds``,
    with ``seed`` replacing the workload's: the document to store with the run, its scenario,
    the seed it runs with, and whether a quick check can answer it."""
    prepared = prepare_run(
        document_of(body), scenario_of(body), registry, limits_of(body), whole_number(body, "seed")
    )
    return {
        "scenarioId": prepared.scenario_id,
        "scenarioName": prepared.scenario_name,
        "seed": prepared.resolved.workload.seed,
        "document": prepared.document.to_json_dict(),
        "quickCheck": quick_check(prepared.resolved.workload),
    }


def limits_of(body: dict[str, Any]) -> SimulationLimits:
    """The run's limits: ``maxRequests`` and ``maxWallSeconds``, its workspace's."""
    max_requests = whole_number(body, "maxRequests")
    if max_requests is None:
        raise BadRequest("maxRequests must be a whole number")
    return SimulationLimits(
        max_requests=max_requests, max_wall_seconds=number(body, "maxWallSeconds")
    )


def compare(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``compare_reports`` of two finished runs' reports, named ``labelA`` and ``labelB``."""
    a, b = body.get("a"), body.get("b")
    label_a, label_b = body.get("labelA"), body.get("labelB")
    if not isinstance(a, dict) or not isinstance(b, dict):
        raise BadRequest("a and b must be reports")
    if not isinstance(label_a, str) or not isinstance(label_b, str):
        raise BadRequest("labelA and labelB must be strings")
    return compare_reports(a, b, label_a, label_b)


def seeds(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``summarize_seeds`` of a seed group's runs against the objectives they ran with."""
    runs = body.get("runs")
    objectives = body.get("objectives")
    if not isinstance(runs, list) or not isinstance(objectives, dict):
        raise BadRequest("runs must be a list and objectives an object")
    return summarize_seeds(runs, objectives)


async def health(request: Request) -> Response:
    return JSONResponse({"status": "ok", "engineVersion": ENGINE_VERSION})


def create_app() -> Starlette:
    """The service, with the engine's standard component registry."""
    app = Starlette(
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/analyze", endpoint(analyze), methods=["POST"]),
            Route("/validate", endpoint(validate), methods=["POST"]),
            Route("/prepare", endpoint(prepare), methods=["POST"]),
            Route("/compare", endpoint(compare), methods=["POST"]),
            Route("/summarize-seeds", endpoint(seeds), methods=["POST"]),
        ]
    )
    app.state.registry = default_registry()
    return app
