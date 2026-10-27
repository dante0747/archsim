"""Calibration from telemetry (``POST /calibrate``): the engine's Prometheus and OpenTelemetry
calibration, returning a new document and what changed. The mapping is always text or an
object, never a path: the API reads no files. Errors name what is wrong, never the values a
request (or a server) sent.

The API calls a Prometheus server only when a request names one, under ``ARCHSIM_OUTBOUND``:
``open`` (local use) calls any host (``ARCHSIM_PROMETHEUS_ALLOWED_HOSTS`` still narrows it when
set); ``allowlist`` only listed hosts, and a listed *name* must resolve to public addresses (the
connection goes to the address checked); ``off`` none, so only recorded responses and OTLP
traces work. Sandbox workspaces are always ``off``. The replay inside the request is bounded by
the workspace's limits (20 s when hosted)."""

from __future__ import annotations

from typing import Any, Literal
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field, ValidationError

from archsim_api import limits as quotas
from archsim_api.config import Settings
from archsim_api.db import Database, Workspace
from archsim_api.deps import get_db, get_principal, get_registry, get_settings_dep, get_workspace
from archsim_api.schemas import ApiModel
from archsim_api.security import Principal, WorkspaceAccess
from archsim_engine import (
    ComponentRegistry,
    DocumentInvalidError,
    SimulationLimitError,
    SimulationLimits,
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

router = APIRouter(tags=["calibration"])


class CalibrateRequest(ApiModel):
    document: dict[str, Any]
    source: Literal["prometheus", "otlp"]
    mapping: str | dict[str, Any] | None = Field(
        default=None, description="YAML text or an object (see docs/calibration.md); never a path"
    )
    prometheus_url: str | None = Field(default=None, description="e.g. http://localhost:9090")
    recorded: dict[str, Any] | None = Field(
        default=None,
        description="Recorded responses ({responses: {query: response}}) instead of a URL",
    )
    range: str | None = Field(default=None, description="START/END in ISO 8601")
    step: float | None = Field(
        default=None, gt=0, le=86_400, description="Seconds (default: the mapping's, else 60)"
    )
    traces: dict[str, Any] | None = Field(default=None, description="OTLP JSON (resourceSpans)")
    replay: bool = True


def _fail(message: str, issues: list[dict[str, Any]] | None = None) -> HTTPException:
    return HTTPException(422, detail={"message": message, "issues": issues or []})


def _replay_limits(settings: Settings, workspace: Workspace) -> SimulationLimits:
    """The replay runs inside the request, so it gets the tightest wall clock: the server's, its
    calibration limit's and the workspace's."""
    run = quotas.simulation_limits(settings, workspace)
    return SimulationLimits(
        max_requests=run.max_requests,
        max_wall_seconds=quotas.calibrate_wall_seconds(settings, workspace),
    )


def outbound_mode(settings: Settings, workspace: Workspace) -> str:
    """How freely this request may call a Prometheus server (see the module notes)."""
    if workspace.kind == "sandbox":
        return "off"
    mode = settings.outbound_mode
    if mode == "allowlist" and not settings.prometheus_allowed_hosts:
        return "off"  # an empty allowlist allows nothing
    return mode


@router.post(
    "/calibrate",
    responses={
        422: {"description": "The document, mapping or telemetry cannot be used."},
        429: {"description": "Too many calibrations this hour (Retry-After)."},
    },
)
def calibrate(
    body: CalibrateRequest,
    access: WorkspaceAccess = Depends(get_workspace),
    principal: Principal = Depends(get_principal),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> dict[str, Any]:
    """Set a design's inputs from telemetry and check the result by replaying it. Returns the
    calibrated document (the stored project is not changed), the changes, notes and the
    calibration record. Runs in the request's workspace (editors)."""
    access.require("editor")
    quotas.enforce(
        settings,
        db,
        f"user:{principal.user_id}:calibrate",
        quotas.CALIBRATIONS_PER_USER,
        "You have run many calibrations this hour.",
    )
    limits = _replay_limits(settings, access.workspace)
    try:
        if body.source == "prometheus":
            if body.mapping is None:
                raise _fail("A Prometheus calibration needs a mapping")
            mapping = load_mapping(body.mapping)
            if body.recorded is not None:
                client: Any = RecordedPrometheus.from_dict(body.recorded)
            elif body.prometheus_url:
                mode = outbound_mode(settings, access.workspace)
                if mode == "off":
                    raise _fail(
                        "This server does not call Prometheus servers (ARCHSIM_OUTBOUND): send "
                        "recorded responses instead (archsim calibrate --record makes them)"
                    )
                url = urlparse(body.prometheus_url)
                if url.scheme not in ("http", "https") or not url.netloc:
                    raise _fail("The Prometheus URL must be http(s)://host[:port]")
                if not host_allowed(body.prometheus_url, settings.prometheus_allowed_hosts):
                    raise _fail(
                        "This Prometheus host is not allowed here "
                        "(ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)"
                    )
                client = HttpPrometheus(
                    body.prometheus_url,
                    timeout_s=settings.prometheus_timeout_s,
                    max_bytes=settings.prometheus_max_bytes,
                    allowed_hosts=tuple(settings.prometheus_allowed_hosts),
                    require_global=mode == "allowlist",
                )
            else:
                raise _fail("Give a Prometheus URL or recorded responses")
            step = body.step or (mapping.range.step if mapping.range else 60)
            rng = parse_range(body.range, step) if body.range else None
            outcome = calibrate_prometheus(
                body.document, mapping, client, registry, rng, body.replay, limits
            )
        else:
            if body.traces is None:
                raise _fail("An OpenTelemetry calibration needs the traces (OTLP JSON)")
            outcome = calibrate_otlp(
                body.document, body.traces, registry, load_otlp_mapping(body.mapping), limits=limits
            )
    except HTTPException:
        raise
    except DocumentInvalidError as exc:
        raise _fail(
            "The document cannot be calibrated", [i.to_dict() for i in exc.issues]
        ) from None
    except SimulationLimitError as exc:
        raise _fail(
            f"Cannot calibrate: the replay run hit a limit ({exc}). Shorten the design's "
            "durationS, lower the load, or calibrate with replay off."
        ) from None
    except ValidationError as exc:  # before ValueError: its str() repeats the input values
        raise _fail(f"Cannot calibrate: {validation_message(exc)}") from None
    except (PrometheusError, ValueError, KeyError) as exc:
        raise _fail(f"Cannot calibrate: {exc}") from None
    result = outcome.to_dict()
    result["summary"] = replay_summary(outcome.document.get("calibration"))
    return result
