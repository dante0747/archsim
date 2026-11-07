"""The engine service's HTTP app: the simulation a calibration replays, until stage 4 of the Java
port moves the simulator to Java (ADR-0045). JSON in and out.

A call the engine refuses answers ``422`` with ``{"message", "issues", "kind"}``: ``invalid``
with the engine's words and issues for a document it cannot read, or ``limit`` with the engine's
words for a run over its limits. The Java API turns that into its own error body.
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
    default_registry,
    run_simulation,
)


class BadRequest(Exception):
    """The request is not what an endpoint takes: not JSON, or missing a field."""


class OverLimit(Exception):
    """A run over its limits; the message is the engine's."""


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
            return refused(str(exc), [issue.to_dict() for issue in exc.issues], "invalid")
        except OverLimit as exc:
            return refused(str(exc), [], "limit")
        return JSONResponse(result)

    return handle


def refused(message: str, issues: list[dict[str, Any]], kind: str) -> Response:
    return JSONResponse({"message": message, "issues": issues, "kind": kind}, status_code=422)


def limits_of(body: dict[str, Any]) -> SimulationLimits:
    """The run's limits: ``maxRequests`` and ``maxWallSeconds``, its workspace's. Both are
    required, so the service never runs a simulation without bounds."""
    max_requests = whole_number(body, "maxRequests")
    if max_requests is None:
        raise BadRequest("maxRequests must be a whole number")
    return SimulationLimits(
        max_requests=max_requests, max_wall_seconds=number(body, "maxWallSeconds")
    )


def simulate(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``POST /simulate``: the replay of a calibration the Java API runs (ADR-0045).
    ``run_simulation`` of ``document`` within ``maxRequests`` and ``maxWallSeconds``, as the
    engine's calibrations ran it; the report as the engine wrote it."""
    limits = limits_of(body)
    document = document_of(body)
    try:
        report: dict[str, Any] = run_simulation(document, registry=registry, limits=limits)
    except SimulationLimitError as exc:
        raise OverLimit(str(exc)) from None
    return report


async def health(request: Request) -> Response:
    return JSONResponse({"status": "ok", "engineVersion": ENGINE_VERSION})


def create_app() -> Starlette:
    """The service, with the engine's standard component registry."""
    app = Starlette(
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/simulate", endpoint(simulate), methods=["POST"]),
        ]
    )
    app.state.registry = default_registry()
    return app
