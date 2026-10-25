"""Simulation runtime: requests, outcomes, worker pools and the call/retry/timeout protocol.

Simulated time is measured in **milliseconds** (``env.now``).

Request lifecycle at a pooled component (service replica, cache, database)::

    arrive -> [queue full?  -> reject "queue_full"]
           -> wait in FIFO queue for a free slot
              [waited longer than queueTimeoutMs? -> reject "queue_timeout"]
           -> hold slot: service time (+ downstream calls when blocking)
           -> release slot -> respond

A call across an edge adds one-way latency to the request and to the response. If the caller has
a timeout it stops waiting, but the callee keeps working (the work is wasted, as in reality).
"""

from __future__ import annotations

import hashlib
import random
from collections.abc import Callable, Generator
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, NamedTuple, cast

import simpy
from simpy.resources.resource import Request as SlotRequest

from archsim_engine.metrics import (
    BucketedHistograms,
    Counter,
    Gauge,
    LatencyHistogram,
    MeanAccumulator,
    Timeline,
)

if TYPE_CHECKING:
    from archsim_engine.distributions import Distribution
    from archsim_engine.schema import EdgeConfig, EdgeSpec, Fault, NodeSpec

SimGen = Generator[simpy.Event, Any, "Outcome"]


class Outcome(NamedTuple):
    """Result of handling a request at some component.

    ``status``: ``ok`` | ``rejected`` (a component refused it) | ``timeout`` (a caller gave up).
    ``node_id``/``reason`` identify where and why a failure originated.
    """

    ok: bool
    status: str
    reason: str | None = None
    node_id: str | None = None
    edge_id: str | None = None


OK = Outcome(True, "ok")


def rejected(reason: str, node_id: str) -> Outcome:
    return Outcome(False, "rejected", reason, node_id)


# A recorded step of a traced request: (node_id, edge_id, kind, start_ms, end_ms, depth, detail).
Span = tuple[str | None, str | None, str, float, float, int, str | None]


@dataclass(slots=True)
class Request:
    id: int
    op: str  # "read" | "write"
    key: int
    created_at: float  # ms
    measured: bool  # arrived inside the measurement window
    abandoned: bool = False  # the client stopped waiting for this attempt
    # Sampled requests carry a span list (shared by all attempts and hops of one user request);
    # ``depth`` is the call depth of the hop currently handling it.
    trace: list[Span] | None = None
    depth: int = 0


def derive_rng(seed: int, stream: str) -> random.Random:
    """Independent, reproducible RNG stream per (seed, name).

    Uses SHA-256 rather than ``hash()`` because Python string hashing is randomised per process.
    """
    digest = hashlib.sha256(f"{seed}:{stream}".encode()).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


class NodeMetrics:
    """Standard metrics every component records. Invariant at end of run::

    arrivals == completed + failed_downstream + sum(rejected) + in_progress
    """

    def __init__(self, tl: Timeline, slots: int | None) -> None:
        self.tl = tl
        self.slots = slots
        self.arrivals = Counter(tl)
        self.completed = Counter(tl)
        self.failed_downstream = Counter(tl)
        self.rejected: dict[str, Counter] = {}
        self.queue = Gauge(tl)
        self.busy = Gauge(tl)
        self.cpu = Gauge(tl)
        self.inflight = Gauge(tl)
        self.queue_wait = MeanAccumulator(tl)
        self.queue_wait_hist = LatencyHistogram()
        self.hold = MeanAccumulator(tl)
        self.service = MeanAccumulator(tl)
        # time from a call reaching this component to its answer (incl. downstream calls)
        self.residence = BucketedHistograms(tl)
        self.extra: dict[str, Counter] = {}
        self.means: dict[str, MeanAccumulator] = {}
        self.has_cpu_split = False

    def arrive(self, t: float) -> None:
        self.arrivals.inc(t)
        self.inflight.add(1, t)

    def depart(self, t: float, outcome: Outcome, req: Request | None = None) -> None:
        self.inflight.add(-1, t)
        if outcome.ok:
            self.completed.inc(t)
            if req is not None and req.abandoned:
                self.count("wasted", t)  # finished after the client had given up
        else:
            self.failed_downstream.inc(t)

    def reject(self, reason: str, t: float) -> None:
        c = self.rejected.get(reason)
        if c is None:
            c = self.rejected[reason] = Counter(self.tl)
        c.inc(t)
        self.inflight.add(-1, t)

    def count(self, name: str, t: float, n: int = 1) -> None:
        c = self.extra.get(name)
        if c is None:
            c = self.extra[name] = Counter(self.tl)
        c.inc(t, n)

    def mean(self, name: str, t: float, v: float) -> None:
        """Record a sample of a named per-bucket mean (e.g. lock wait), reported as a series."""
        acc = self.means.get(name)
        if acc is None:
            acc = self.means[name] = MeanAccumulator(self.tl)
        acc.record(t, v)

    def rejected_total(self) -> int:
        return sum(c.total for c in self.rejected.values())

    def finalize(self, t_end: float) -> None:
        for g in (self.queue, self.busy, self.cpu, self.inflight):
            g.finalize(t_end)


class WorkerPool:
    """A bounded set of slots (threads, connections, executors) with a bounded FIFO queue."""

    __slots__ = ("env", "m", "queue_capacity", "queue_timeout", "resource")

    def __init__(
        self,
        env: simpy.Environment,
        slots: int,
        queue_capacity: int,
        queue_timeout_ms: float | None,
        metrics: NodeMetrics,
    ) -> None:
        self.env = env
        self.resource = simpy.Resource(env, capacity=slots)
        self.queue_capacity = queue_capacity
        self.queue_timeout = queue_timeout_ms
        self.m = metrics

    @property
    def outstanding(self) -> int:
        return self.resource.count + len(self.resource.queue)

    def acquire(
        self, req: Request | None = None, node_id: str | None = None
    ) -> Generator[simpy.Event, Any, tuple[SlotRequest | None, str | None]]:
        res = self.resource
        env = self.env
        m = self.m
        if res.count >= res.capacity and len(res.queue) >= self.queue_capacity:
            return None, "queue_full"
        tok = res.request()
        wait = 0.0
        if not tok.triggered:
            enq = env.now
            m.queue.add(1, enq)
            if self.queue_timeout is None:
                yield tok
            else:
                yield tok | env.timeout(self.queue_timeout)
            now = env.now
            m.queue.add(-1, now)
            if req is not None and req.trace is not None:
                span(req, node_id, "queue", enq, now)
            if not tok.triggered:
                tok.cancel()
                return None, "queue_timeout"
            wait = now - enq
        now = env.now
        m.queue_wait.record(now, wait)
        if m.tl.window_start <= now < m.tl.window_end:
            m.queue_wait_hist.record(wait)
        m.busy.add(1, now)
        return tok, None

    def release(self, tok: SlotRequest) -> None:
        self.resource.release(tok)
        self.m.busy.add(-1, self.env.now)


class EdgeRuntime:
    """Runtime state and metrics for a link."""

    __slots__ = (
        "attempts",
        "cfg",
        "failures",
        "id",
        "retries",
        "rng",
        "source_id",
        "source_port",
        "spec",
        "target_id",
        "timeouts",
    )

    def __init__(self, spec: EdgeSpec, tl: Timeline, rng: random.Random) -> None:
        self.spec = spec
        self.cfg: EdgeConfig = spec.config
        self.id = spec.id
        self.source_id = spec.source
        self.source_port = spec.source_port
        self.target_id = spec.target
        self.rng = rng
        self.attempts = Counter(tl)
        self.retries = Counter(tl)
        self.timeouts = Counter(tl)
        self.failures = Counter(tl)

    def latency(self) -> float:
        return self.cfg.latency_ms.sample(self.rng)

    def carries(self, op: str) -> bool:
        return op in self.cfg.request_types


Handler = Callable[[Request], SimGen]

MAX_SPANS = 400


def span(
    req: Request,
    node_id: str | None,
    kind: str,
    start: float,
    end: float,
    detail: str | None = None,
    edge_id: str | None = None,
    depth: int | None = None,
) -> None:
    """Record a step of a traced request (no-op for requests that are not traced)."""
    tr = req.trace
    if tr is not None and len(tr) < MAX_SPANS:
        d = req.depth if depth is None else depth
        tr.append((node_id, edge_id, kind, start, end, d, detail))


class RuntimeComponent:
    """Base class for the executable behaviour of a component instance."""

    def __init__(self, ctx: SimContext, node: NodeSpec, config: Any, slots: int | None) -> None:
        self.ctx = ctx
        self.env = ctx.env
        self.node = node
        self.id = node.id
        self.config = config
        self.m = NodeMetrics(ctx.tl, slots)
        # Bumped whenever the set of routable instances changes (autoscaling), so load
        # balancers know to rebuild their candidate lists.
        self.instances_version = 0

    # -- routing helpers -------------------------------------------------------------
    def out_edges(self, port: str | None = None) -> list[EdgeRuntime]:
        edges = self.ctx.out_edges.get(self.id, [])
        if port is None:
            return edges
        return [e for e in edges if e.source_port == port]

    # -- instance-aware targeting (used by load balancers) ---------------------------
    def instance_count(self) -> int:
        return 1

    def instance_load(self, index: int) -> int:
        return self.m.inflight.value

    def handle_on(self, req: Request, index: int) -> SimGen:
        return self.handle(req)

    @property
    def occupancy(self) -> Gauge:
        """Gauge whose level / slots is this component's utilization."""
        return self.m.busy

    def capacity_gauge(self) -> Gauge | None:
        """Time-varying slot count (autoscaling); None when capacity is fixed at ``m.slots``."""
        return None

    # -- background work and report extras ---------------------------------------------
    def background_busy(self) -> bool:
        """True while work not tied to an in-flight call remains (e.g. queued messages)."""
        return False

    def extra_summary(self) -> dict[str, Any]:
        """Component-specific summary fields merged into ``nodes[].summary``."""
        return {}

    def resource_capacity(
        self, hold_ms: float | None, service_ms: float | None
    ) -> dict[str, float]:
        """Throughput ceilings (req/s) set by resource specs, e.g. ``{"cpu": 196.0}``.

        Empty when no spec limits the component. ``service_ms`` is the mean service time,
        ``hold_ms`` the mean time a request holds its slot (both over the window).
        """
        return {}

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        """Component-specific per-bucket series (full timeline length) for ``nodes[].timeseries``."""
        return {}

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        """Component-specific calculations behind the numbers (see ``explain.formula``)."""
        return []

    def behaviour(self) -> list[dict[str, Any]]:
        """Technology traits in effect for this node, with the settings they resolved to."""
        return []

    # -- faults --------------------------------------------------------------------------
    def sample_time(self, dist: Distribution, rng: random.Random) -> float:
        """Sample a service/processing time, applying an active ``slowdown`` fault."""
        v = dist.sample(rng)
        f = self.ctx.fault("slowdown", self.id, self.env.now)
        return v * f.factor if f is not None else v

    # -- behaviour ---------------------------------------------------------------------
    def handle(self, req: Request) -> SimGen:  # pragma: no cover - abstract
        raise NotImplementedError
        yield

    def finalize(self, t_end: float) -> None:
        self.m.finalize(t_end)


class SimContext:
    """Everything a component needs at run time: clock, RNG streams, topology, metrics."""

    def __init__(self, env: simpy.Environment, seed: int, tl: Timeline) -> None:
        self.env = env
        self.seed = seed
        self.tl = tl
        self.components: dict[str, RuntimeComponent] = {}
        self.edges: dict[str, EdgeRuntime] = {}
        self.out_edges: dict[str, list[EdgeRuntime]] = {}
        self.in_transit = 0  # calls currently on the wire or being handled
        self._faults: dict[tuple[str, str], list[Fault]] = {}
        # Objects several components of one run share (e.g. the end-to-end client metrics).
        self.shared: dict[str, Any] = {}
        # Finished traced requests (sampled at arrival; see engine._arrivals).
        self.traces: list[dict[str, Any]] = []

    # -- faults ------------------------------------------------------------------------
    def set_faults(self, faults: list[Fault]) -> None:
        """Index faults by (kind, target) for fast lookup on the hot path."""
        self._faults = {}
        for f in faults:
            self._faults.setdefault((f.kind, f.target), []).append(f)

    def fault(self, kind: str, target: str, now_ms: float) -> Fault | None:
        """The fault of ``kind`` active on ``target`` at ``now_ms``, if any."""
        lst = self._faults.get((kind, target))
        if not lst:
            return None
        t_s = now_ms / 1000.0
        for f in lst:
            if f.active(t_s):
                return f
        return None

    def edge_latency(self, edge: EdgeRuntime) -> float:
        d = edge.latency()
        f = self.fault("latency", edge.id, self.env.now)
        return d + f.extra_ms if f is not None else d

    def _outage(self, edge: EdgeRuntime) -> Outcome | None:
        """Reject a call to a node that is down (connection refused); counted at the callee."""
        target = edge.target_id
        if self.fault("outage", target, self.env.now) is None:
            return None
        comp = self.components.get(target)
        if comp is not None:
            now = self.env.now
            comp.m.arrive(now)
            comp.m.reject("outage", now)
        return rejected("outage", target)

    def idle(self) -> bool:
        """True when no request is in flight anywhere (pending timers do not count)."""
        if self.in_transit:
            return False
        return all(
            c.m.inflight.value == 0 and not c.background_busy() for c in self.components.values()
        )

    def rng(self, stream: str) -> random.Random:
        return derive_rng(self.seed, stream)

    def handler_for(self, edge: EdgeRuntime) -> Handler:
        return self.components[edge.target_id].handle

    def call_once(
        self,
        edge: EdgeRuntime,
        req: Request,
        handler: Handler,
        timeout_ms: float | None,
        caller_id: str,
        timeout_reason: str = "dependency_timeout",
    ) -> SimGen:
        """One attempt across ``edge``: latency -> handler -> latency, optionally bounded."""
        env = self.env
        edge.attempts.inc(env.now)
        if timeout_ms is None:
            self.in_transit += 1
            depth = req.depth
            t0 = env.now
            d = self.edge_latency(edge)
            if d > 0:
                yield env.timeout(d)
            down = self._outage(edge)
            t1 = env.now
            if down is None:
                req.depth = depth + 1
                outcome = yield from handler(req)
                req.depth = depth
            else:
                outcome = down
            t2 = env.now
            d = self.edge_latency(edge)
            if d > 0:
                yield env.timeout(d)
            self._after_hop(edge, req, outcome, depth, t0, t1, t2, down is not None)
            self.in_transit -= 1
            if not outcome.ok:
                edge.failures.inc(env.now)
            return outcome

        done = env.event()
        env.process(self._deliver(edge, req, handler, done))
        yield done | env.timeout(timeout_ms)
        if done.triggered:
            result = cast(Outcome, done.value)
            if not result.ok:
                edge.failures.inc(env.now)
            return result
        edge.timeouts.inc(env.now)
        if req.trace is not None:
            span(
                req,
                caller_id,
                "timeout",
                env.now,
                env.now,
                f"gave up after {timeout_ms:g} ms",
                edge.id,
            )
        return Outcome(False, "timeout", timeout_reason, caller_id, edge.id)

    def _deliver(
        self, edge: EdgeRuntime, req: Request, handler: Handler, done: simpy.Event
    ) -> Generator[simpy.Event, Any, None]:
        env = self.env
        self.in_transit += 1
        depth = req.depth
        t0 = env.now
        d = self.edge_latency(edge)
        if d > 0:
            yield env.timeout(d)
        down = self._outage(edge)
        t1 = env.now
        if down is None:
            req.depth = depth + 1
            outcome = yield from handler(req)
            req.depth = depth
        else:
            outcome = down
        t2 = env.now
        d = self.edge_latency(edge)
        if d > 0:
            yield env.timeout(d)
        self._after_hop(edge, req, outcome, depth, t0, t1, t2, down is not None)
        self.in_transit -= 1
        done.succeed(outcome)

    def _after_hop(
        self,
        edge: EdgeRuntime,
        req: Request,
        outcome: Outcome,
        depth: int,
        t0: float,
        t1: float,
        t2: float,
        down: bool,
    ) -> None:
        """Bookkeeping after one hop (request leg t0-t1, handling t1-t2, response leg t2-now):
        the callee's residence time and, for traced requests, the spans."""
        if not down:
            target = self.components.get(edge.target_id)
            if target is not None:
                target.m.residence.record(t2, t2 - t1)
        if req.trace is None:
            return
        now = self.env.now
        if t1 > t0:
            span(req, None, "network", t0, t1, None, edge.id, depth)
        if down:
            span(req, edge.target_id, "rejected", t1, t1, "outage", None, depth + 1)
        else:
            detail = None if outcome.ok else (outcome.reason or outcome.status)
            span(req, edge.target_id, "handle", t1, t2, detail, None, depth + 1)
        if now > t2:
            span(req, None, "network", t2, now, None, edge.id, depth)

    def call(
        self,
        edge: EdgeRuntime,
        req: Request,
        caller_id: str,
        handler: Handler | None = None,
    ) -> SimGen:
        """Call across ``edge`` honouring its timeout and bounded retries (exponential backoff)."""
        env = self.env
        h = handler or self.handler_for(edge)
        cfg = edge.cfg
        outcome = OK
        for attempt in range(cfg.max_attempts):
            outcome = yield from self.call_once(edge, req, h, cfg.timeout_ms, caller_id)
            if outcome.ok:
                return outcome
            if attempt + 1 < cfg.max_attempts:
                edge.retries.inc(env.now)
                backoff = cfg.retry_backoff_ms * (2**attempt)
                if backoff > 0:
                    yield env.timeout(backoff)
        return outcome
