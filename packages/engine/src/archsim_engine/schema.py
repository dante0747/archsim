"""Versioned architecture document schema (``schemaVersion`` 1.1).

The document deliberately separates four concerns:

* ``architecture`` - topology: typed nodes and routing edges (what exists and how requests flow)
* ``workload``     - simulation input: traffic shape, duration, seed
* ``scenarios``    - named, replayable sets of overrides applied on top of the base document
* ``layout``       - rendering metadata only (positions, viewport); never affects simulation
* ``provenance``   - where numeric inputs came from (measured, assumed, illustrative)
* ``calibration``  - the last calibration from telemetry and how well it replays what was seen

JSON uses camelCase keys; Python attributes are snake_case.
"""

from __future__ import annotations

import itertools
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from archsim_engine.distributions import Constant, Distribution
from archsim_engine.version import SCHEMA_VERSION

assert SCHEMA_VERSION == "1.1", "update ArchitectureDocument.schema_version and add a migration"

RequestType = Literal["read", "write"]
REQUEST_TYPES: tuple[RequestType, ...] = ("read", "write")

ID_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9_.\-]{0,63}$"


class CamelModel(BaseModel):
    """Base model: camelCase JSON aliases, unknown keys rejected."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


# --------------------------------------------------------------------------------------
# Architecture topology
# --------------------------------------------------------------------------------------


class NodeSpec(CamelModel):
    """A component instance in the architecture graph.

    ``config`` is validated by the component model registered for ``type``; it is kept as a
    plain mapping here so the document schema does not depend on the component catalog.
    """

    id: str = Field(pattern=ID_PATTERN, description="Unique node id (letters, digits, _ . -)")
    type: str = Field(min_length=1, max_length=64, description="Registered component type")
    label: str = Field(default="", max_length=120)
    description: str | None = Field(default=None, max_length=2000)
    config: dict[str, Any] = Field(default_factory=dict)


class EdgeConfig(CamelModel):
    """Behaviour of a request crossing a link from a caller to a callee."""

    request_types: list[RequestType] = Field(
        default_factory=lambda: list(REQUEST_TYPES),
        min_length=1,
        description="Request types carried by this link. Used for routing (e.g. reads to cache, writes to DB).",
        json_schema_extra={"x-ui": {"label": "Request types", "group": "Routing"}},
    )
    latency_ms: Distribution = Field(
        default_factory=lambda: Constant(value=0.5),
        description="One-way network latency in ms, applied to the request and again to the response.",
        json_schema_extra={
            "x-ui": {
                "label": "Network latency (one way)",
                "unit": "ms",
                "group": "Network",
                "assumption": True,
            }
        },
    )
    timeout_ms: float | None = Field(
        default=None,
        gt=0,
        le=600_000,
        description="Per-attempt time the caller waits before giving up (ms). Empty = wait indefinitely.",
        json_schema_extra={"x-ui": {"label": "Timeout", "unit": "ms", "group": "Reliability"}},
    )
    max_attempts: int = Field(
        default=1,
        ge=1,
        le=5,
        description="Total attempts including retries (1 = no retries). Bounded to prevent retry storms from running away.",
        json_schema_extra={"x-ui": {"label": "Max attempts", "group": "Reliability"}},
    )
    retry_backoff_ms: float = Field(
        default=50.0,
        ge=0,
        le=60_000,
        description="Base backoff before a retry (ms); doubles on each further retry.",
        json_schema_extra={
            "x-ui": {"label": "Retry backoff", "unit": "ms", "group": "Reliability"}
        },
    )
    order: int = Field(
        default=0,
        ge=0,
        le=1000,
        description="Call order when a service calls several dependencies for the same request (lower first).",
        json_schema_extra={"x-ui": {"label": "Call order", "group": "Routing"}},
    )

    @field_validator("request_types")
    @classmethod
    def _unique_types(cls, v: list[RequestType]) -> list[RequestType]:
        if len(set(v)) != len(v):
            raise ValueError("request types must be unique")
        return sorted(v)


class EdgeSpec(CamelModel):
    """A directed routing/network link from ``source.sourcePort`` to ``target.targetPort``."""

    id: str = Field(pattern=ID_PATTERN)
    source: str
    source_port: str = "out"
    target: str
    target_port: str = "in"
    label: str | None = Field(default=None, max_length=120)
    config: EdgeConfig = Field(default_factory=EdgeConfig)


class Architecture(CamelModel):
    nodes: list[NodeSpec] = Field(default_factory=list, max_length=200)
    edges: list[EdgeSpec] = Field(default_factory=list, max_length=1000)
    auto_size: bool = Field(
        default=True,
        description=(
            "Give parts with an empty CPU, memory or storage spec the size they need for the "
            "design's peak load (see sizing.py). false: an empty spec is not modelled."
        ),
    )


# --------------------------------------------------------------------------------------
# Workload
# --------------------------------------------------------------------------------------


class ConstantPattern(CamelModel):
    """Steady offered load at ``workload.rps``."""

    kind: Literal["constant"] = "constant"


class RampPattern(CamelModel):
    """Offered load ramps linearly from ``startRps`` to ``endRps`` over the run."""

    kind: Literal["ramp"] = "ramp"
    start_rps: float = Field(ge=0, le=100_000)
    end_rps: float = Field(ge=0, le=100_000)


class BurstPattern(CamelModel):
    """Baseline ``workload.rps`` with one burst at ``burstRps`` for ``durationS`` seconds."""

    kind: Literal["burst"] = "burst"
    burst_rps: float = Field(ge=0, le=100_000)
    start_s: float = Field(ge=0, le=3600)
    duration_s: float = Field(gt=0, le=3600)


class TracePattern(CamelModel):
    """A replayed rate curve, e.g. observed traffic from telemetry: ``points`` are ``[t, rps]``
    pairs (seconds from the start of the run), linear between points and flat after the last.
    ``source`` says where it came from (and how much it was time-compressed)."""

    kind: Literal["trace"] = "trace"
    points: list[tuple[float, float]] = Field(min_length=2, max_length=2000)
    source: str | None = Field(default=None, max_length=500)

    @model_validator(mode="after")
    def _check(self) -> TracePattern:
        ts = [t for t, _ in self.points]
        if ts[0] != 0.0:
            raise ValueError("a trace starts at t = 0")
        if any(b <= a for a, b in itertools.pairwise(ts)):
            raise ValueError("trace times must rise strictly")
        if any(not 0 <= r <= 100_000 for _, r in self.points):
            raise ValueError("trace rates must be between 0 and 100,000 req/s")
        return self

    def rate(self, t_s: float) -> float:
        pts = self.points
        if t_s >= pts[-1][0]:
            return pts[-1][1]
        lo, hi = 0, len(pts) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if pts[mid][0] <= t_s:
                lo = mid
            else:
                hi = mid
        (t0, r0), (t1, r1) = pts[lo], pts[hi]
        return r0 + (r1 - r0) * (t_s - t0) / (t1 - t0)

    def area(self, until_s: float) -> float:
        """Expected arrivals from 0 to ``until_s``."""
        total = 0.0
        pts = self.points
        for (t0, r0), (t1, _r1) in itertools.pairwise(pts):
            if t0 >= until_s:
                break
            end = min(t1, until_s)
            total += (r0 + self.rate(end)) / 2 * (end - t0)
        if until_s > pts[-1][0]:
            total += pts[-1][1] * (until_s - pts[-1][0])
        return total


TrafficPattern = Annotated[
    ConstantPattern | RampPattern | BurstPattern | TracePattern,
    Field(discriminator="kind"),
]


class Fault(CamelModel):
    """A fault injected during the run (chaos experiment).

    * ``outage``   - the target node rejects every request arriving in the window ("outage").
    * ``slowdown`` - service times sampled at the target node are multiplied by ``factor``.
    * ``latency``  - each traversal of the target edge gets ``extraMs`` added (one way).
    """

    id: str = Field(pattern=ID_PATTERN)
    kind: Literal["outage", "slowdown", "latency"]
    target: str = Field(
        min_length=1, max_length=64, description="Node id (outage, slowdown) or edge id (latency)"
    )
    start_s: float = Field(ge=0, le=3600, description="When the fault starts (s, simulated time)")
    duration_s: float = Field(gt=0, le=3600, description="How long the fault lasts (s)")
    factor: float = Field(
        default=4.0, ge=1, le=100, description="slowdown: service-time multiplier"
    )
    extra_ms: float = Field(
        default=200.0, ge=0, le=60_000, description="latency: added one-way latency (ms)"
    )
    label: str | None = Field(default=None, max_length=120)

    def active(self, t_s: float) -> bool:
        return self.start_s <= t_s < self.start_s + self.duration_s


class Workload(CamelModel):
    """Synthetic traffic driving the (single) traffic source."""

    rps: float = Field(
        default=100.0,
        ge=0,
        le=100_000,
        description="Baseline offered load (requests/s)",
        json_schema_extra={
            "x-ui": {
                "label": "Baseline load",
                "unit": "req/s",
                "group": "Traffic",
                "assumption": True,
            }
        },
    )
    duration_s: float = Field(
        default=60.0,
        gt=0,
        le=3600,
        description="Arrival window length (s)",
        json_schema_extra={"x-ui": {"label": "Duration", "unit": "s", "group": "Run"}},
    )
    warmup_s: float = Field(
        default=10.0,
        ge=0,
        le=3600,
        description="Initial period excluded from summary statistics (s)",
        json_schema_extra={"x-ui": {"label": "Warm-up", "unit": "s", "group": "Run"}},
    )
    seed: int = Field(
        default=42,
        ge=0,
        le=2**32 - 1,
        description="Random seed for reproducibility",
        json_schema_extra={"x-ui": {"label": "Seed", "group": "Run"}},
    )
    read_ratio: float = Field(
        default=0.9,
        ge=0,
        le=1,
        description="Fraction of requests that are reads",
        json_schema_extra={
            "x-ui": {"label": "Read ratio", "group": "Traffic", "assumption": True, "step": 0.05}
        },
    )
    arrival_process: Literal["poisson", "deterministic"] = Field(
        default="poisson",
        description="poisson: random (exponential) inter-arrivals; deterministic: evenly spaced",
        json_schema_extra={"x-ui": {"label": "Arrival process", "group": "Traffic"}},
    )
    pattern: TrafficPattern = Field(
        default_factory=ConstantPattern,
        description="Shape of the offered load over time",
        json_schema_extra={
            "x-ui": {"label": "Traffic pattern", "kind": "pattern", "group": "Traffic"}
        },
    )
    key_count: int = Field(
        default=100_000,
        ge=1,
        le=1_000_000,
        description="Number of distinct keys requests touch (used by TTL cache models)",
        json_schema_extra={
            "x-ui": {"label": "Distinct keys", "group": "Keyspace", "assumption": True}
        },
    )
    key_skew: float = Field(
        default=0.99,
        ge=0,
        le=3,
        description="Zipf exponent of key popularity (0 = uniform, ~1 = typical web skew)",
        json_schema_extra={
            "x-ui": {
                "label": "Key skew (Zipf s)",
                "group": "Keyspace",
                "assumption": True,
                "step": 0.05,
            }
        },
    )
    drain_s: float | None = Field(
        default=None,
        ge=0,
        le=600,
        description="Time after arrivals stop to let in-flight requests finish (s). Empty = automatic.",
        json_schema_extra={"x-ui": {"label": "Drain time", "unit": "s", "group": "Run"}},
    )
    faults: list[Fault] = Field(
        default_factory=list,
        max_length=20,
        description="Faults injected during the run (outages, slowdowns, network latency)",
        json_schema_extra={
            "x-ui": {"label": "Faults", "kind": "faults", "group": "Chaos", "hidden": True}
        },
    )

    @model_validator(mode="after")
    def _check(self) -> Workload:
        if self.warmup_s >= self.duration_s:
            raise ValueError("warmupS must be shorter than durationS")
        if isinstance(self.pattern, BurstPattern) and self.pattern.start_s >= self.duration_s:
            raise ValueError("burst startS must be within durationS")
        ids = [f.id for f in self.faults]
        if len(set(ids)) != len(ids):
            raise ValueError("fault ids must be unique")
        return self

    def rate_at(self, t_s: float) -> float:
        """Offered rate (requests/s) at simulated time ``t_s`` seconds."""
        p = self.pattern
        if isinstance(p, TracePattern):
            return p.rate(t_s)
        if isinstance(p, RampPattern):
            frac = min(max(t_s / self.duration_s, 0.0), 1.0)
            return p.start_rps + (p.end_rps - p.start_rps) * frac
        if isinstance(p, BurstPattern) and p.start_s <= t_s < p.start_s + p.duration_s:
            return p.burst_rps
        return self.rps

    def peak_rate(self) -> float:
        p = self.pattern
        if isinstance(p, TracePattern):
            inside = [r for t, r in p.points if t <= self.duration_s]
            return max([*inside, p.rate(self.duration_s)])
        if isinstance(p, RampPattern):
            return max(p.start_rps, p.end_rps)
        if isinstance(p, BurstPattern):
            return max(self.rps, p.burst_rps)
        return self.rps

    def expected_arrivals(self) -> float:
        """Expected number of arrivals over the whole arrival window."""
        p = self.pattern
        if isinstance(p, TracePattern):
            return p.area(self.duration_s)
        if isinstance(p, RampPattern):
            return (p.start_rps + p.end_rps) / 2.0 * self.duration_s
        if isinstance(p, BurstPattern):
            burst = max(0.0, min(p.duration_s, self.duration_s - p.start_s))
            return self.rps * (self.duration_s - burst) + p.burst_rps * burst
        return self.rps * self.duration_s


# --------------------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------------------


class OverrideTarget(CamelModel):
    kind: Literal["node", "edge", "workload"]
    id: str | None = Field(default=None, description="Node or edge id; omitted for workload")

    @model_validator(mode="after")
    def _check(self) -> OverrideTarget:
        if self.kind != "workload" and not self.id:
            raise ValueError(f"override target of kind '{self.kind}' requires an id")
        return self


class Override(CamelModel):
    """Set one value. ``path`` is a dotted path inside the node/edge ``config`` or the workload,
    e.g. ``replicas``, ``serviceTimeMs.median``, ``pattern.burstRps``."""

    target: OverrideTarget
    path: str = Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$")
    value: Any


class Scenario(CamelModel):
    id: str = Field(pattern=ID_PATTERN)
    name: str = Field(min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=2000)
    overrides: list[Override] = Field(default_factory=list, max_length=100)


# --------------------------------------------------------------------------------------
# Layout (rendering only)
# --------------------------------------------------------------------------------------


class Position(CamelModel):
    x: float = 0.0
    y: float = 0.0


class Viewport(CamelModel):
    x: float = 0.0
    y: float = 0.0
    zoom: float = 1.0


EdgeSide = Literal["top", "right", "bottom", "left"]


class EdgeLayout(CamelModel):
    """Which side of each component a link attaches to on the canvas (rendering only)."""

    source_side: EdgeSide | None = None
    target_side: EdgeSide | None = None


class Layout(CamelModel):
    nodes: dict[str, Position] = Field(default_factory=dict)
    edges: dict[str, EdgeLayout] = Field(default_factory=dict)
    viewport: Viewport | None = None


# --------------------------------------------------------------------------------------
# Document
# --------------------------------------------------------------------------------------


class Metadata(CamelModel):
    name: str = Field(default="Untitled architecture", min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=4000)
    tags: list[str] = Field(default_factory=list, max_length=20)


class InputProvenance(CamelModel):
    """Where one numeric input came from. ``measured``: fitted from telemetry (``source``,
    ``query``, ``timeRange``, ``fitError``, ``fittedAt``); ``assumed``: typed by a person;
    ``illustrative``: an ArchSim default or preset. Inputs without an entry are assumed when
    they differ from the default and illustrative otherwise (``provenance.py``)."""

    kind: Literal["measured", "assumed", "illustrative"]
    source: str | None = Field(default=None, max_length=500)
    query: str | None = Field(default=None, max_length=2000)
    time_range: str | None = Field(default=None, max_length=100)
    fit_error: float | None = Field(
        default=None, ge=0, description="Largest relative error of the fit at the checked quantiles"
    )
    fitted_at: str | None = Field(default=None, max_length=40)
    note: str | None = Field(default=None, max_length=500)


class ReplayCheck(CamelModel):
    """One observed-against-simulated comparison of a calibration's replay."""

    scope: Literal["end_to_end", "component"]
    id: str | None = None
    label: str = Field(max_length=200)
    metric: Literal["p50", "p95", "p99", "throughputRps"]
    observed: float
    simulated: float | None = None
    error: float | None = Field(default=None, description="(simulated - observed) / observed")


class CalibrationRecord(CamelModel):
    """The last calibration from telemetry, and how well the calibrated model replays it."""

    at: str = Field(max_length=40)
    source: str = Field(max_length=500)
    time_range: str | None = Field(default=None, max_length=100)
    inputs: int = Field(ge=0, description="Inputs set from telemetry")
    replay: list[ReplayCheck] = Field(default_factory=list, max_length=500)
    replay_digest: str | None = Field(default=None, max_length=80)
    notes: list[str] = Field(default_factory=list, max_length=50)


class Objectives(CamelModel):
    """Service-level objectives the design should meet. Not used by the simulation itself; the UI
    and reports evaluate runs against them (pass/fail, error budget)."""

    latency_p95_ms: float | None = Field(
        default=300.0, gt=0, le=600_000, description="p95 end-to-end latency target (ms)"
    )
    latency_p99_ms: float | None = Field(
        default=None, gt=0, le=600_000, description="p99 end-to-end latency target (ms)"
    )
    availability: float | None = Field(
        default=0.999, ge=0, le=1, description="Minimum share of requests that succeed"
    )
    min_throughput_rps: float | None = Field(
        default=None, ge=0, le=1_000_000, description="Minimum successful requests/s"
    )


class ArchitectureDocument(CamelModel):
    """Top-level interchange document. Serialize with ``to_json_dict()``."""

    schema_version: Literal["1.1"] = "1.1"
    kind: Literal["archsim.architecture"] = "archsim.architecture"
    metadata: Metadata = Field(default_factory=Metadata)
    architecture: Architecture = Field(default_factory=Architecture)
    workload: Workload = Field(default_factory=Workload)
    scenarios: list[Scenario] = Field(default_factory=list, max_length=50)
    objectives: Objectives = Field(default_factory=Objectives)
    layout: Layout = Field(default_factory=Layout)
    provenance: dict[str, InputProvenance] = Field(
        default_factory=dict,
        description=(
            "Where inputs came from, keyed by path: nodes/<id>/<config key>, edges/<id>/<config "
            "key> or workload/<field>."
        ),
    )
    calibration: CalibrationRecord | None = None

    def to_json_dict(self) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True)

    def node(self, node_id: str) -> NodeSpec | None:
        return next((n for n in self.architecture.nodes if n.id == node_id), None)

    def scenario(self, scenario_id: str) -> Scenario | None:
        return next((s for s in self.scenarios if s.id == scenario_id), None)
