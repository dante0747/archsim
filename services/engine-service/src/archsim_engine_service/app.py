"""The engine service's HTTP app: one ``POST`` endpoint per engine call, JSON in and out.

A call the engine refuses because the document cannot be read answers ``422`` with the engine's
issues, ``{"issues": [...]}``; the Java API turns that into its own error body. Answers are
written as FastAPI wrote them, so the Java API can pass them on unchanged.
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
    default_registry,
    load_document,
    resolve_scenario,
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
            return JSONResponse(
                {"issues": [issue.to_dict() for issue in exc.issues]}, status_code=422
            )
        return JSONResponse(result)

    return handle


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


async def health(request: Request) -> Response:
    return JSONResponse({"status": "ok", "engineVersion": ENGINE_VERSION})


def create_app() -> Starlette:
    """The service, with the engine's standard component registry."""
    app = Starlette(
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/analyze", endpoint(analyze), methods=["POST"]),
            Route("/validate", endpoint(validate), methods=["POST"]),
        ]
    )
    app.state.registry = default_registry()
    return app
