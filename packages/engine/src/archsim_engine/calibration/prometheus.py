"""Reading Prometheus: range queries over HTTP, or replayed from a recording (tests, demos and
offline use; no network). Nothing is sent anywhere except the queries to the server you name."""

from __future__ import annotations

import json
import math
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from archsim_engine.calibration.fit import Histogram
from archsim_engine.calibration.mapping import Range

Series = list[dict[str, Any]]  # Prometheus matrix: [{"metric": {...}, "values": [[ts, "v"], ...]}]


class PrometheusClient(Protocol):
    source: str

    def query_range(self, query: str, start: float, end: float, step: float) -> Series: ...


class PrometheusError(RuntimeError):
    pass


@dataclass
class HttpPrometheus:
    """``GET <url>/api/v1/query_range``. Only called when you run a calibration."""

    url: str
    timeout_s: float = 30.0

    @property
    def source(self) -> str:
        return f"Prometheus {self.url}"

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        params = urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": step})
        url = f"{self.url.rstrip('/')}/api/v1/query_range?{params}"
        try:
            with urllib.request.urlopen(url, timeout=self.timeout_s) as r:
                body = json.loads(r.read())
        except OSError as exc:
            raise PrometheusError(f"cannot query {self.url}: {exc}") from None
        return _matrix(body, query)


@dataclass
class RecordedPrometheus:
    """Responses recorded earlier: ``{"source": ..., "range": {start, end, step},
    "responses": {query: response}}``. ``range`` is the window the queries covered; when a
    recording has one, calibration uses it (the data came from there)."""

    responses: dict[str, Any]
    label: str = "recorded Prometheus responses"
    recorded_range: Range | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any], name: str = "") -> RecordedPrometheus:
        window = data.get("range")
        return cls(
            data.get("responses") or {},
            data.get("source") or f"recorded responses {name}".strip(),
            Range.model_validate(window) if window else None,
        )

    @classmethod
    def load(cls, path: str | Path) -> RecordedPrometheus:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_dict(data, Path(path).name)

    @property
    def source(self) -> str:
        return self.label

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        if query not in self.responses:
            raise PrometheusError(f"no recorded response for query: {query}")
        return _matrix(self.responses[query], query)


@dataclass
class RecordingPrometheus:
    """Wraps a client and keeps every response, to save as a recording (``save``)."""

    inner: PrometheusClient
    responses: dict[str, Any] = field(default_factory=dict)
    window: tuple[float, float, float] | None = None

    @property
    def source(self) -> str:
        return self.inner.source

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        result = self.inner.query_range(query, start, end, step)
        self.window = (start, end, step)
        self.responses[query] = {
            "status": "success",
            "data": {"resultType": "matrix", "result": result},
        }
        return result

    def save(self, path: str | Path) -> None:
        out: dict[str, Any] = {"source": self.source}
        if self.window:
            start, end, step = self.window
            out["range"] = {"start": _iso(start), "end": _iso(end), "step": step}
        out["responses"] = self.responses
        Path(path).write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, UTC).isoformat().replace("+00:00", "Z")


def _matrix(body: dict[str, Any], query: str) -> Series:
    if body.get("status") != "success":
        raise PrometheusError(f"query failed ({body.get('error', 'unknown error')}): {query}")
    data = body.get("data") or {}
    if data.get("resultType") != "matrix":
        raise PrometheusError(f"expected a range (matrix) result for: {query}")
    return list(data.get("result") or [])


def _values(series: dict[str, Any]) -> list[tuple[float, float]]:
    out = []
    for ts, v in series.get("values") or []:
        x = float(v)
        if math.isfinite(x):
            out.append((float(ts), x))
    return out


def time_mean(series: dict[str, Any]) -> float | None:
    vals = [v for _, v in _values(series)]
    return sum(vals) / len(vals) if vals else None


def summed(result: Series) -> list[tuple[float, float]]:
    """The series added up per timestamp (one series stays as it is)."""
    acc: dict[float, float] = {}
    for s in result:
        for ts, v in _values(s):
            acc[ts] = acc.get(ts, 0.0) + v
    return sorted(acc.items())


def histogram(result: Series, unit: str) -> Histogram:
    """A histogram from ``*_bucket`` series (one per ``le``), averaged over the range."""
    buckets: dict[float, float] = {}
    for s in result:
        le = s.get("metric", {}).get("le")
        if le is None:
            raise PrometheusError(
                "a histogram query must return series with an 'le' label (use sum by (le))"
            )
        m = time_mean(s)
        if m is not None:
            bound = math.inf if le in ("+Inf", "Inf", "inf") else float(le)
            buckets[bound] = buckets.get(bound, 0.0) + m
    if not buckets:
        raise PrometheusError("the histogram query returned no data in the range")
    return Histogram.from_buckets(buckets, 1000.0 if unit == "s" else 1.0)


def reduce(result: Series, how: str) -> float:
    pts = [v for _, v in summed(result)]
    if not pts:
        raise PrometheusError("the query returned no data in the range")
    if how == "max":
        return max(pts)
    if how == "min":
        return min(pts)
    if how == "last":
        return pts[-1]
    return sum(pts) / len(pts)
