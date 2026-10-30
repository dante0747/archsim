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
        cpuPerRequest:                # CPU cores in use over requests per second -> cpuShare
          cpu: sum(rate(container_cpu_usage_seconds_total{container="api"}[5m]))
          requests: sum(rate(http_requests_total{job="api"}[5m]))
        memoryWorkingSet:             # -> memoryBaselineMb, memoryPerRpsMb, memoryPerRequestMb
          workingSet: sum(container_memory_working_set_bytes{container="api"})
          requests: sum(rate(http_requests_total{job="api"}[5m]))   # and/or inflight: <requests held>
      cache:
        hitRatio:
          ratio: {hits: sum(rate(redis_keyspace_hits_total[5m])), misses: sum(rate(redis_keyspace_misses_total[5m]))}
      db:
        workingSetGib: {gauge: max(pg_database_size_bytes{datname="app"}), unit: bytes, reduce: max}
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
from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Discriminator,
    Field,
    Tag,
    ValidationError,
    model_validator,
)


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


MemoryUnit = Literal["bytes", "KiB", "MiB", "GiB"]
BYTES: dict[str, float] = {"bytes": 1.0, "KiB": 2.0**10, "MiB": 2.0**20, "GiB": 2.0**30}
"""Bytes per unit, for memory and data sizes."""


class GaugeSource(_M):
    gauge: str
    reduce: Literal["mean", "max", "min", "last"] = "mean"
    round: bool = False
    unit: MemoryUnit | None = Field(
        default=None,
        description="The gauge's unit, for memory and size settings (converted to theirs)",
    )


class RatioSource(_M):
    ratio: dict[str, str]

    @model_validator(mode="after")
    def _check(self) -> RatioSource:
        if set(self.ratio) != {"hits", "misses"}:
            raise ValueError("ratio needs exactly 'hits' and 'misses' queries")
        return self


class CpuPerRequestSource(_M):
    """CPU per request, from CPU in use and the rate of requests (sets ``cpuShare``)."""

    cpu: str = Field(description="CPU cores in use: the rate of CPU seconds, summed over replicas")
    requests: str = Field(description="Requests per second the part serves (queries: a database)")


class WorkingSetSource(_M):
    """The working set fitted against the request rate and/or requests held (sets
    ``memoryBaselineMb``, ``memoryPerRpsMb`` and ``memoryPerRequestMb``)."""

    working_set: str = Field(alias="workingSet", description="Memory in use, summed over replicas")
    unit: MemoryUnit = "bytes"
    requests: str | None = Field(default=None, description="Requests per second, summed")
    inflight: str | None = Field(default=None, description="Requests held, summed over replicas")
    replicas: str | None = Field(
        default=None, description="Replicas over time (default: the design's replica count)"
    )

    @model_validator(mode="after")
    def _check(self) -> WorkingSetSource:
        if self.requests is None and self.inflight is None:
            raise ValueError(
                "memoryWorkingSet needs 'requests' (the request rate), 'inflight' (requests "
                "held) or both, to fit the working set against"
            )
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


_SOURCE_KEYS = (
    ("histogram", "histogram"),
    ("gauge", "gauge"),
    ("ratio", "ratio"),
    ("cpu", "cpu"),
    ("workingSet", "workingSet"),
    ("working_set", "workingSet"),
)


def _source_kind(v: Any) -> str | None:
    """Which source a node entry is, by the key that names it (one clear error per entry)."""
    if isinstance(v, BaseModel):
        return {
            HistogramSource: "histogram",
            GaugeSource: "gauge",
            RatioSource: "ratio",
            CpuPerRequestSource: "cpu",
            WorkingSetSource: "workingSet",
        }.get(type(v))
    if isinstance(v, dict):
        return next((tag for key, tag in _SOURCE_KEYS if key in v), None)
    return None


NodeSource = Annotated[
    Annotated[HistogramSource, Tag("histogram")]
    | Annotated[GaugeSource, Tag("gauge")]
    | Annotated[RatioSource, Tag("ratio")]
    | Annotated[CpuPerRequestSource, Tag("cpu")]
    | Annotated[WorkingSetSource, Tag("workingSet")],
    Discriminator(
        _source_kind,
        custom_error_type="source_kind",
        custom_error_message=(
            "a node entry is one of {histogram}, {gauge}, {ratio}, {cpu, requests} or "
            "{workingSet, requests|inflight}"
        ),
    ),
]

DERIVED: dict[str, type[_M]] = {
    "cpuPerRequest": CpuPerRequestSource,
    "memoryWorkingSet": WorkingSetSource,
}
"""Node keys that are not settings: each is fitted into settings (``cpuPerRequest`` into
``cpuShare``; ``memoryWorkingSet`` into the memory at idle, per req/s and per request held)."""


class Mapping(_M):
    range: Range | None = None
    nodes: dict[str, dict[str, NodeSource]] = Field(default_factory=dict)
    edges: dict[str, dict[str, LinkLatencySource]] = Field(default_factory=dict)
    workload: dict[Literal["rps"], RateSource] = Field(default_factory=dict)
    replay: Replay | None = None

    @model_validator(mode="after")
    def _derived(self) -> Mapping:
        for node_id, fields in self.nodes.items():
            for key, src in fields.items():
                want = DERIVED.get(key)
                if want is not None and not isinstance(src, want):
                    raise ValueError(f"nodes.{node_id}.{key} needs {_shape(want)}")
                if want is None and isinstance(src, CpuPerRequestSource | WorkingSetSource):
                    name = next(k for k, v in DERIVED.items() if isinstance(src, v))
                    raise ValueError(
                        f"nodes.{node_id}.{key}: CPU and working-set sources go under '{name}'"
                    )
        return self


def _shape(model: type[_M]) -> str:
    return (
        "{"
        + ", ".join(f.alias or n for n, f in model.model_fields.items() if f.is_required())
        + "}"
    )


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
