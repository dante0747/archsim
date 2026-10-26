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

from pydantic import BaseModel, ConfigDict, Field, model_validator


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


def load_mapping(source: str | Path | dict[str, Any]) -> Mapping:
    """A mapping from a dict, a YAML/JSON string, or a file path."""
    if isinstance(source, dict):
        return Mapping.model_validate(source)
    text = Path(source).read_text(encoding="utf-8") if isinstance(source, Path) else source
    if isinstance(source, str) and "\n" not in source and Path(source).exists():
        text = Path(source).read_text(encoding="utf-8")
    from archsim_engine.safe_yaml import load_yaml

    data = load_yaml(text)
    if not isinstance(data, dict):
        raise ValueError("the mapping must be a YAML or JSON object")
    return Mapping.model_validate(data)
