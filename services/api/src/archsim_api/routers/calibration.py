"""Calibration from telemetry (``POST /calibrate``): the engine's Prometheus and OpenTelemetry
calibration, returning a new document and what changed. The API calls a Prometheus server only
when a request names one; like the rest of the API it is meant to run on your machine."""

from __future__ import annotations

from typing import Any, Literal
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field

from archsim_api.deps import get_registry
from archsim_api.schemas import ApiModel
from archsim_engine import ComponentRegistry, DocumentInvalidError
from archsim_engine.calibration import (
    HttpPrometheus,
    PrometheusError,
    RecordedPrometheus,
    calibrate_otlp,
    calibrate_prometheus,
    load_mapping,
    parse_range,
    replay_summary,
)

router = APIRouter(tags=["calibration"])


class CalibrateRequest(ApiModel):
    document: dict[str, Any]
    source: Literal["prometheus", "otlp"]
    mapping: str | dict[str, Any] | None = Field(
        default=None, description="YAML text or an object (see docs/calibration.md)"
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


@router.post(
    "/calibrate",
    responses={422: {"description": "The document, mapping or telemetry cannot be used."}},
)
def calibrate(
    body: CalibrateRequest, registry: ComponentRegistry = Depends(get_registry)
) -> dict[str, Any]:
    """Set a design's inputs from telemetry and check the result by replaying it. Returns the
    calibrated document (the stored project is not changed), the changes, notes and the
    calibration record."""
    try:
        if body.source == "prometheus":
            if body.mapping is None:
                raise _fail("A Prometheus calibration needs a mapping")
            mapping = load_mapping(body.mapping)
            if body.recorded is not None:
                client: Any = RecordedPrometheus.from_dict(body.recorded)
            elif body.prometheus_url:
                url = urlparse(body.prometheus_url)
                if url.scheme not in ("http", "https") or not url.netloc:
                    raise _fail("The Prometheus URL must be http(s)://host[:port]")
                client = HttpPrometheus(body.prometheus_url)
            else:
                raise _fail("Give a Prometheus URL or recorded responses")
            step = body.step or (mapping.range.step if mapping.range else 60)
            rng = parse_range(body.range, step) if body.range else None
            outcome = calibrate_prometheus(
                body.document, mapping, client, registry, rng, body.replay
            )
        else:
            if body.traces is None:
                raise _fail("An OpenTelemetry calibration needs the traces (OTLP JSON)")
            import yaml

            otlp_map = (
                yaml.safe_load(body.mapping) if isinstance(body.mapping, str) else body.mapping
            )
            outcome = calibrate_otlp(body.document, body.traces, registry, otlp_map or None)
    except HTTPException:
        raise
    except DocumentInvalidError as exc:
        raise _fail(
            "The document cannot be calibrated", [i.to_dict() for i in exc.issues]
        ) from None
    except (PrometheusError, ValueError, KeyError) as exc:
        raise _fail(f"Cannot calibrate: {exc}") from None
    result = outcome.to_dict()
    result["summary"] = replay_summary(outcome.document.get("calibration"))
    return result
