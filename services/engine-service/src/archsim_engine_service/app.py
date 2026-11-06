"""The engine service's HTTP app: one ``POST`` endpoint per engine call, JSON in and out.

A call the engine refuses answers ``422`` with ``{"message", "issues", "kind"}``: ``invalid``
with the engine's words and issues for a document it cannot read, or ``unreadable`` for text, a
mapping or telemetry that cannot be used, in the words the Python API answered. The Java API
turns that into its own error body. Answers are written as FastAPI wrote them, so the Java API can
pass them on unchanged.
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from typing import Any
from urllib.parse import urlparse

from pydantic import ValidationError
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
)
from archsim_engine.calibration import (
    HttpPrometheus,
    PrometheusError,
    RecordedPrometheus,
    calibrate_otlp,
    calibrate_prometheus,
    load_mapping,
    load_otlp_mapping,
    parse_range,
    replay_summary,
    validation_message,
)
from archsim_engine.calibration.prometheus import host_allowed

OUTBOUND_MODES = ("open", "allowlist", "off")
"""How freely a calibration may call the Prometheus server it names (``ARCHSIM_OUTBOUND``)."""


class BadRequest(Exception):
    """The request is not what an endpoint takes: not JSON, or missing a field."""


class Unreadable(Exception):
    """Text, a mapping or telemetry that cannot be used; the message says why, in the words the
    Python API answered."""


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
        except Unreadable as exc:
            return refused(str(exc), [], "unreadable")
        return JSONResponse(result)

    return handle


def refused(message: str, issues: list[dict[str, Any]], kind: str) -> Response:
    return JSONResponse({"message": message, "issues": issues, "kind": kind}, status_code=422)


def limits_of(body: dict[str, Any]) -> SimulationLimits:
    """The run's limits: ``maxRequests`` and ``maxWallSeconds``, its workspace's."""
    max_requests = whole_number(body, "maxRequests")
    if max_requests is None:
        raise BadRequest("maxRequests must be a whole number")
    return SimulationLimits(
        max_requests=max_requests, max_wall_seconds=number(body, "maxWallSeconds")
    )


def calibrate(body: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """``POST /calibrate`` as the Python API answered it. The server's outbound policy comes with
    the request: ``outbound`` (``open``, ``allowlist`` or ``off``, the workspace's),
    ``allowedHosts``, ``timeoutS`` and ``maxBytes``; the replay's limits are ``maxRequests`` and
    ``maxWallSeconds``. A policy it cannot read is refused: the service never calls out on a
    guess."""
    limits = limits_of(body)
    policy = policy_of(body)
    source = body.get("source")
    if source not in ("prometheus", "otlp"):
        raise BadRequest("source must be prometheus or otlp")
    try:
        if source == "prometheus":
            outcome = _calibrate_prometheus(body, registry, limits, policy)
        else:
            if body.get("traces") is None:
                raise Unreadable("An OpenTelemetry calibration needs the traces (OTLP JSON)")
            mapping = load_otlp_mapping(body.get("mapping"))
            outcome = calibrate_otlp(
                document_of(body), body["traces"], registry, mapping, limits=limits
            )
    except (Unreadable, BadRequest, DocumentInvalidError):
        raise
    except SimulationLimitError as exc:
        raise Unreadable(
            f"Cannot calibrate: the replay run hit a limit ({exc}). Shorten the design's "
            "durationS, lower the load, or calibrate with replay off."
        ) from None
    except ValidationError as exc:  # before ValueError: its str() repeats the input values
        raise Unreadable(f"Cannot calibrate: {validation_message(exc)}") from None
    except (PrometheusError, ValueError, KeyError) as exc:
        raise Unreadable(f"Cannot calibrate: {exc}") from None
    result: dict[str, Any] = outcome.to_dict()
    result["summary"] = replay_summary(outcome.document.get("calibration"))
    return result


def _calibrate_prometheus(
    body: dict[str, Any],
    registry: ComponentRegistry,
    limits: SimulationLimits,
    policy: tuple[str, tuple[str, ...]],
) -> Any:
    if body.get("mapping") is None:
        raise Unreadable("A Prometheus calibration needs a mapping")
    mapping = load_mapping(body["mapping"])
    client: Any
    if body.get("recorded") is not None:
        client = RecordedPrometheus.from_dict(body["recorded"])
    elif body.get("prometheusUrl"):
        client = _http_prometheus(body, body["prometheusUrl"], policy)
    else:
        raise Unreadable("Give a Prometheus URL or recorded responses")
    step = body.get("step") or (mapping.range.step if mapping.range else 60)
    span = parse_range(body["range"], step) if body.get("range") else None
    return calibrate_prometheus(
        document_of(body), mapping, client, registry, span, body.get("replay", True), limits
    )


def policy_of(body: dict[str, Any]) -> tuple[str, tuple[str, ...]]:
    """The outbound policy a calibration comes with: ``outbound`` and ``allowedHosts``."""
    mode, hosts = body.get("outbound"), body.get("allowedHosts")
    if mode not in OUTBOUND_MODES:
        raise BadRequest(f"outbound must be one of {', '.join(OUTBOUND_MODES)}")
    if not isinstance(hosts, list) or not all(isinstance(host, str) for host in hosts):
        raise BadRequest("allowedHosts must be a list of strings")
    return mode, tuple(hosts)


def _http_prometheus(
    body: dict[str, Any], url: str, policy: tuple[str, tuple[str, ...]]
) -> HttpPrometheus:
    """A Prometheus client for ``url``, if the server's outbound policy lets it be called."""
    mode, allowed = policy
    if mode == "off":
        raise Unreadable(
            "This server does not call Prometheus servers (ARCHSIM_OUTBOUND): send "
            "recorded responses instead (archsim calibrate --record makes them)"
        )
    parts = urlparse(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise Unreadable("The Prometheus URL must be http(s)://host[:port]")
    if not host_allowed(url, allowed):
        raise Unreadable(
            "This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)"
        )
    max_bytes = whole_number(body, "maxBytes")
    if max_bytes is None:
        raise BadRequest("maxBytes must be a whole number")
    return HttpPrometheus(
        url,
        timeout_s=number(body, "timeoutS"),
        max_bytes=max_bytes,
        allowed_hosts=allowed,
        require_global=mode == "allowlist",
    )


async def health(request: Request) -> Response:
    return JSONResponse({"status": "ok", "engineVersion": ENGINE_VERSION})


def create_app() -> Starlette:
    """The service, with the engine's standard component registry."""
    app = Starlette(
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/calibrate", endpoint(calibrate), methods=["POST"]),
        ]
    )
    app.state.registry = default_registry()
    return app
