"""The calibration mapping file: which telemetry sets which input of the design.

YAML (or JSON). Parts and links are addressed by their ids in the design::

    range: {start: 2026-10-01T00:00:00Z, end: 2026-10-08T00:00:00Z, step: 300}
    nodes:
      api:
        serviceTimeMs:                # a histogram of handler time without downstream calls
          histogram: sum by (le) (rate(http_server_handler_seconds_bucket{job="api"}[5m]))
          unit: s                     # bucket bounds in seconds (s) or milliseconds (ms)
          fit: lognormal              # or empirical
        workersPerReplica: {gauge: max(tomcat_threads_config_max{job="api"}), reduce: max}
      cache:
        hitRatio:
          ratio: {hits: sum(rate(redis_keyspace_hits_total[5m])), misses: sum(rate(redis_keyspace_misses_total[5m]))}
    edges:
      e-api-db:
        latencyMs: {client: <client-side histogram>, server: <server-side histogram>, unit: s}
    workload:
      rps: {rate: sum(rate(http_requests_total{job="api"}[1m])), trace: true}
    replay:                           # what the calibrated model is checked against
      endToEnd: {histogram: <client-observed latency histogram>, unit: s, throughput: <rate query>}
      components:
        db: {histogram: <the database's own latency histogram>, unit: s}
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class Range(_M):
    start: str
    end: str
    step: float = Field(default=60, gt=0, le=86_400, description="Seconds between points")


class HistogramSource(_M):
    histogram: str
    unit: Literal["s", "ms"] = "s"
    fit: Literal["lognormal", "empirical"] = "lognormal"


class GaugeSource(_M):
    gauge: str
    reduce: Literal["mean", "max", "min", "last"] = "mean"
    round: bool = False


class RatioSource(_M):
    ratio: dict[str, str]

    @model_validator(mode="after")
    def _check(self) -> RatioSource:
        if set(self.ratio) != {"hits", "misses"}:
            raise ValueError("ratio needs exactly 'hits' and 'misses' queries")
        return self


class LinkLatencySource(_M):
    client: str
    server: str
    unit: Literal["s", "ms"] = "s"


class RateSource(_M):
    rate: str
    trace: bool = True


class ReplayTarget(_M):
    histogram: str
    unit: Literal["s", "ms"] = "s"
    throughput: str | None = None


class Replay(_M):
    end_to_end: ReplayTarget | None = Field(default=None, alias="endToEnd")
    components: dict[str, ReplayTarget] = Field(default_factory=dict)


NodeSource = HistogramSource | GaugeSource | RatioSource


class Mapping(_M):
    range: Range | None = None
    nodes: dict[str, dict[str, NodeSource]] = Field(default_factory=dict)
    edges: dict[str, dict[str, LinkLatencySource]] = Field(default_factory=dict)
    workload: dict[Literal["rps"], RateSource] = Field(default_factory=dict)
    replay: Replay | None = None


class MappingError(ValueError):
    """The mapping cannot be used. The message names what is wrong and where, never the values
    it was given (a mapping can come from a request; errors go back to whoever sent it)."""


def validation_message(exc: ValidationError, limit: int = 10) -> str:
    """A pydantic validation error as ``where: what`` lines, without the input values pydantic
    puts in ``str(exc)`` (``input_value=...``)."""
    errors = exc.errors(include_url=False, include_input=False, include_context=False)
    lines = [
        f"{'.'.join(str(x) for x in e['loc']) or '(top level)'}: {e['msg']}" for e in errors[:limit]
    ]
    if len(errors) > limit:
        lines.append(f"... and {len(errors) - limit} more")
    return "; ".join(lines)


def load_mapping(source: str | dict[str, Any]) -> Mapping:
    """A mapping from YAML/JSON text or a dict. A string is always the mapping's text, never a
    path: use ``load_mapping_file`` to read a file (the CLI and the MCP server do, for paths the
    person running them names; the API never reads files)."""
    if isinstance(source, Path):
        raise TypeError("load_mapping takes text or a dict; use load_mapping_file(path) for a file")
    if isinstance(source, str):
        import yaml

        from archsim_engine.safe_yaml import describe_yaml_error, load_yaml

        try:
            data: Any = load_yaml(source)
        except yaml.YAMLError as exc:
            raise MappingError(
                f"the mapping is not valid YAML: {describe_yaml_error(exc)}"
            ) from None
    else:
        data = source
    if not isinstance(data, dict):
        raise MappingError("the mapping must be a YAML or JSON object")
    try:
        return Mapping.model_validate(data)
    except ValidationError as exc:
        raise MappingError(f"the mapping is not valid: {validation_message(exc)}") from None


def load_mapping_file(path: str | Path) -> Mapping:
    """A mapping read from a file (YAML or JSON)."""
    return load_mapping(Path(path).read_text(encoding="utf-8"))
