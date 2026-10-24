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

from archsim_engine.metrics import Counter, Gauge, LatencyHistogram, MeanAccumulator, Timeline

if TYPE_CHECKING:
    from archsim_engine.schema import EdgeConfig, EdgeSpec, NodeSpec

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


@dataclass(slots=True)
class Request:
    id: int
    op: str  # "read" | "write"
    key: int
    created_at: float  # ms
    measured: bool  # arrived inside the measurement window
    abandoned: bool = False  # the client stopped waiting for this attempt


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
        self.extra: dict[str, Counter] = {}
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

    def acquire(self) -> Generator[simpy.Event, Any, tuple[SlotRequest | None, str | None]]:
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


class RuntimeComponent:
    """Base class for the executable behaviour of a component instance."""

    def __init__(self, ctx: SimContext, node: NodeSpec, config: Any, slots: int | None) -> None:
        self.ctx = ctx
        self.env = ctx.env
        self.node = node
        self.id = node.id
        self.config = config
        self.m = NodeMetrics(ctx.tl, slots)

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

    def idle(self) -> bool:
        """True when no request is in flight anywhere (pending timers do not count)."""
        if self.in_transit:
            return False
        return all(c.m.inflight.value == 0 for c in self.components.values())

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
            d = edge.latency()
            if d > 0:
                yield env.timeout(d)
            outcome = yield from handler(req)
            d = edge.latency()
            if d > 0:
                yield env.timeout(d)
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
        return Outcome(False, "timeout", timeout_reason, caller_id, edge.id)

    def _deliver(
        self, edge: EdgeRuntime, req: Request, handler: Handler, done: simpy.Event
    ) -> Generator[simpy.Event, Any, None]:
        env = self.env
        self.in_transit += 1
        d = edge.latency()
        if d > 0:
            yield env.timeout(d)
        outcome = yield from handler(req)
        d = edge.latency()
        if d > 0:
            yield env.timeout(d)
        self.in_transit -= 1
        done.succeed(outcome)

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
