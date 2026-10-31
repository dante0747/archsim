"""Message queue / log / broker (Kafka, RabbitMQ, SQS, Pub/Sub, NATS...): asynchronous decoupling.

The producer's request succeeds as soon as the message is published (acknowledged). Consumers
take messages in the background and call the outgoing links (in order, matching the message's
request type). A failed delivery is retried up to ``maxDeliveries`` times, then dead-lettered.
Background processing never changes the outcome of the producer's request.

The technology sets behaviour (see ``archsim_engine.technology``):

* Kafka: messages hash by key to ordered partitions; each partition is consumed by one consumer,
  one message at a time, so a hot key backs up its partition. acks=all and linger.ms add to the
  publish time.
* RabbitMQ: when queued messages pass the memory high watermark, publishers are blocked until
  consumers catch up (they wait instead of failing).
* SQS / Pub/Sub: a message whose processing outlasts the visibility timeout (ack deadline) is
  delivered again to another consumer: duplicate work. SQS FIFO queues cap publishes per second.
* NATS core (at-most-once): overflow beyond the backlog limit is silently dropped.

Node metrics: arrivals/completed/rejected count publishes; ``queue`` is the backlog (lag);
``busy`` is busy consumers (utilization); ``queueWaitMs`` is how long messages waited before a
consumer picked them up. Series: ``lag``, ``partitionLagMax``, ``redeliveryRps``, ``droppedRps``,
``publishBlockedMs``.
"""

from __future__ import annotations

from collections import deque
from collections.abc import Generator
from dataclasses import dataclass
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.components.resources import (
    INFO_ONLY,
    cpu_field,
    instance_type_field,
    memory_field,
    storage_field,
)
from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.explain import formula, num, pct, zipf_share
from archsim_engine.issues import Issue, warning
from archsim_engine.metrics import LatencyHistogram, MeanAccumulator
from archsim_engine.ratelimit import TokenBucket
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    OK,
    EdgeRuntime,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    Span,
    rejected,
    span,
)
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import QUEUE_PROFILES, Profile, resolve

BEHAVIOUR = "Technology behaviour"


class MessageQueueConfig(CamelModel):
    technology: Literal[
        "kafka", "rabbitmq", "sqs", "pubsub", "nats", "redis_streams", "generic"
    ] = Field(
        default="generic",
        description=(
            "The broker or managed service. It sets how messages are partitioned, acknowledged "
            "and redelivered, shown under Technology behaviour. Presets also fill in typical "
            "numbers."
        ),
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    partitions: int = Field(
        default=8,
        ge=1,
        le=10_000,
        description=(
            "Partitions (Kafka) or shards: at most one consumer works per partition, so effective "
            "consumers = min(consumers, partitions). Set it high for brokers without partitions."
        ),
        json_schema_extra=ui(label="Partitions", group="Capacity", headline=True),
    )
    consumers: int = Field(
        default=4,
        ge=1,
        le=10_000,
        description="Concurrent consumers processing messages (consumer instances x threads).",
        json_schema_extra=ui(label="Consumers", group="Capacity", headline=True),
    )
    max_depth: int | None = Field(
        default=100_000,
        ge=1,
        le=100_000_000,
        description=(
            "Messages the queue holds; publishes beyond it are rejected (queue_full), or dropped "
            "silently with at-most-once delivery. Empty = unbounded."
        ),
        json_schema_extra=ui(label="Max backlog", unit="msgs", group="Capacity"),
    )
    publish_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=2.0, p95=8.0),
        description="Time for the broker to accept a publish (ms), before any replication wait or batching.",
        json_schema_extra=ui(
            label="Publish (ack) time",
            unit="ms",
            group="Performance",
            assumption=True,
            headline=True,
        ),
    )
    consumer_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=5.0, p95=20.0),
        description="Consumer handling time per message before its downstream calls (ms).",
        json_schema_extra=ui(
            label="Consumer time", unit="ms", group="Performance", assumption=True
        ),
    )
    consumer_concurrency: int | None = Field(
        default=None,
        ge=1,
        le=10_000,
        description=(
            "Messages one consumer processes at once (kafkajs partitionsConsumedConcurrently, a "
            "consumer's worker threads); a partition's messages still go one at a time. Set, "
            "consumers poll in cycles (see fetch overhead). Empty = one at a time."
        ),
        json_schema_extra=ui(label="Consumer concurrency", group="Capacity"),
    )
    fetch_overhead_ms: Distribution | None = Field(
        default=None,
        description=(
            "A consumer's time per poll beyond processing its messages: the fetch round trip and "
            "the offset commit (ms). Set, a consumer polls in cycles: it takes its partitions' "
            "whole backlog (a shared queue: one message per slot), processes it, pays this, then "
            "polls again, so messages that arrive meanwhile wait for the next poll. Empty = each "
            "message is taken as soon as a consumer is free."
        ),
        json_schema_extra=ui(label="Fetch overhead", unit="ms", group="Performance"),
    )
    max_deliveries: int = Field(
        default=3,
        ge=1,
        le=20,
        description="Delivery attempts per message; after the last failure it goes to the dead-letter queue.",
        json_schema_extra=ui(label="Max deliveries", group="Reliability"),
    )
    # -- technology behaviour ---------------------------------------------------------------
    partitioning: Literal["auto", "key", "round_robin", "none"] = Field(
        default="auto",
        description=(
            "key: messages with the same key go to the same partition and are consumed in order "
            "by its one consumer (Kafka). round_robin: spread evenly, still one consumer per "
            "partition. none: one shared queue with competing consumers (RabbitMQ, SQS)."
        ),
        json_schema_extra=ui(label="Partitioning", group=BEHAVIOUR),
    )
    acks: Literal["auto", "leader", "all"] = Field(
        default="auto",
        description="all: the publish waits until the replicas have the message (Kafka acks=all, the default since 3.0).",
        json_schema_extra=ui(label="Publish acknowledgement", group=BEHAVIOUR),
    )
    replication_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=2.0, p95=8.0),
        description="Extra publish time waiting for replicas when acks = all (ms).",
        json_schema_extra=ui(label="Replication wait", unit="ms", group=BEHAVIOUR, assumption=True),
    )
    linger_ms: float | None = Field(
        default=None,
        ge=0,
        le=10_000,
        description=(
            "The producer holds each message up to this long to batch it (Kafka linger.ms); a "
            "publish waits a uniform share of it. Empty = technology default."
        ),
        json_schema_extra=ui(label="Batching linger", unit="ms", group=BEHAVIOUR),
    )
    delivery: Literal["auto", "at_least_once", "at_most_once"] = Field(
        default="auto",
        description=(
            "at_least_once: a full backlog rejects publishes. at_most_once (core NATS): the "
            "overflow is dropped silently and the publisher still gets an ack."
        ),
        json_schema_extra=ui(label="Delivery guarantee", group=BEHAVIOUR),
    )
    visibility_timeout_s: float | None = Field(
        default=None,
        gt=0,
        le=43_200,
        description=(
            "A message being processed longer than this is delivered again to another consumer "
            "(SQS visibility timeout, Pub/Sub ack deadline). Empty = technology default (SQS 30 "
            "s, Pub/Sub 10 s, none for brokers with consumer acks)."
        ),
        json_schema_extra=ui(label="Visibility timeout", unit="s", group=BEHAVIOUR),
    )
    fifo: bool = Field(
        default=False,
        description="FIFO queue (SQS): ordered, exactly-once, and limited to the publish rate below.",
        json_schema_extra=ui(
            label="FIFO queue", group=BEHAVIOUR, visible_when={"technology": "sqs"}
        ),
    )
    fifo_limit_rps: float = Field(
        default=300.0,
        gt=0,
        le=1_000_000,
        description="Publishes per second a FIFO queue accepts (SQS: 300 without batching, 3,000 with); the excess is throttled.",
        json_schema_extra=ui(
            label="FIFO publish limit",
            unit="msg/s",
            group=BEHAVIOUR,
            visible_when={"technology": "sqs"},
        ),
    )
    flow_control: Literal["auto", "on", "off"] = Field(
        default="auto",
        description=(
            "Block publishers while queued messages use more memory than the high watermark "
            "(RabbitMQ memory alarm). Needs memory set. Auto: on for RabbitMQ."
        ),
        json_schema_extra=ui(label="Memory alarm blocks publishers", group=BEHAVIOUR),
    )
    memory_high_watermark: float = Field(
        default=0.6,
        gt=0,
        le=1,
        description="Share of the broker's memory queued messages may use before publishers are blocked.",
        json_schema_extra=ui(label="Memory high watermark", group=BEHAVIOUR, step=0.05),
    )
    message_size_kb: float = Field(
        default=1.0,
        gt=0,
        le=1_048_576,
        description="Average message size in memory, with overhead (KiB). Used by the memory alarm.",
        json_schema_extra=ui(label="Message size", unit="KiB", group=BEHAVIOUR, assumption=True),
    )
    brokers: int = Field(
        default=1,
        ge=1,
        le=1000,
        description="Broker nodes in the cluster (used for cost; throughput is set by consumers and partitions).",
        json_schema_extra=ui(label="Brokers", group="Cost"),
    )
    cpu_cores: float | None = cpu_field("broker", INFO_ONLY)
    memory_gib: float | None = memory_field(
        "broker", "Used by the memory alarm (RabbitMQ); otherwise shown on the design and priced."
    )
    storage_gib: float | None = storage_field("broker", INFO_ONLY)
    instance_type: str | None = instance_type_field("broker")
    cost_per_hour: float = Field(
        default=0.15,
        ge=0,
        le=10_000,
        description="Price of one broker per hour, in USD. Illustrative default; use your provider's pricing.",
        json_schema_extra=ui(
            label="Cost per broker-hour", unit="$/h", group="Cost", assumption=True
        ),
    )

    def profile(self) -> Profile:
        return QUEUE_PROFILES.get(self.technology, QUEUE_PROFILES["generic"])

    def setting(self, key: str, fallback: Any = None) -> Any:
        return resolve(getattr(self, key, None), self.profile(), key, fallback)

    def partitioned(self) -> bool:
        return self.setting("partitioning", "none") in ("key", "round_robin")

    def fetch_cycles(self) -> bool:
        """Consumers poll in cycles (fetch overhead or a concurrency above one set) rather than
        taking each message as soon as they are free."""
        return self.fetch_overhead_ms is not None or (self.consumer_concurrency or 1) > 1

    def consumer_slots(self) -> int:
        """Messages all consumers process at once: each of min(consumers, partitions) consumers
        has ``consumerConcurrency`` slots, and on a partitioned queue no more than the
        partitions it owns (consumer i owns partitions p with p mod consumers = i)."""
        c = min(self.consumers, self.partitions)
        n = self.consumer_concurrency or 1
        if n == 1:
            return c
        if not self.partitioned():
            return c * n
        return sum(min(n, len(range(i, self.partitions, c))) for i in range(c))

    def flow_threshold(self) -> float | None:
        """Backlog (messages) at which the memory alarm blocks publishers, or None."""
        if self.setting("flow_control", "off") != "on" or not self.memory_gib:
            return None
        return self.memory_high_watermark * self.memory_gib * 1024 * 1024 / self.message_size_kb


@dataclass(slots=True)
class Message:
    op: str
    key: int
    published_at: float  # acknowledged: the backlog wait starts here
    created_at: float  # the publish started: the message's age starts here
    measured: bool
    rid: int
    deliveries: int = 0
    trace: list[Span] | None = None
    depth: int = 0
    lease: object | None = None  # current delivery's token while a consumer holds it


class MessageQueueRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: MessageQueueConfig) -> None:
        self.effective_consumers = min(config.consumers, config.partitions)
        self.cycles = config.fetch_cycles()
        self.concurrency = config.consumer_concurrency or 1
        self.consumer_slots = config.consumer_slots()
        super().__init__(ctx, node, config, self.consumer_slots)
        env = ctx.env
        self.rng = ctx.rng(f"node:{node.id}:publish")
        self.consumer_rng = ctx.rng(f"node:{node.id}:consumer")
        self.fetch_rng = ctx.rng(f"node:{node.id}:fetch") if self.cycles else None
        self.age = MeanAccumulator(ctx.tl)
        self.age_hist = LatencyHistogram()
        self.depth = 0
        self.processing = 0
        self._deps: dict[str, list[EdgeRuntime]] = {}
        self.partitioning = config.setting("partitioning", "none")
        self.acks_all = config.setting("acks", "leader") == "all"
        self.linger = float(config.setting("linger_ms", 0.0) or 0.0)
        self.at_most_once = config.setting("delivery", "at_least_once") == "at_most_once"
        vis = config.setting("visibility_timeout_s")
        self.visibility_ms = float(vis) * 1000.0 if vis else None
        self.fifo_bucket = (
            TokenBucket(config.fifo_limit_rps)
            if config.fifo and config.technology == "sqs"
            else None
        )
        self.threshold = config.flow_threshold()
        self.blocked = False
        self.unblocked: simpy.Event | None = None
        self.part_max = [0] * ctx.tl.n
        if config.partitioned():
            n = config.partitions
            c = self.effective_consumers
            self.parts: list[deque[Message]] | None = [deque() for _ in range(n)]
            self.owner = [p % c for p in range(n)]
            self.wake: list[simpy.Event] = [env.event() for _ in range(c)]
            self.rr_part = 0
            for i in range(c):
                mine = [p for p in range(n) if p % c == i]
                env.process(
                    self._cycle_consumer(i, mine) if self.cycles else self._partition_consumer(i, mine)
                )
        else:
            self.parts = None
            self.store: simpy.Store = simpy.Store(env)
            # fetch cycles on a shared queue: the backlog, and consumers waiting for a message
            self.shared: deque[Message] = deque()
            self.idle: deque[simpy.Event] = deque()
            for i in range(self.effective_consumers):
                env.process(self._cycle_consumer(i, None) if self.cycles else self._shared_consumer())

    def deps_for(self, op: str) -> list[EdgeRuntime]:
        d = self._deps.get(op)
        if d is None:
            d = sorted(
                (e for e in self.out_edges("out") if e.carries(op)),
                key=lambda e: (e.cfg.order, e.id),
            )
            self._deps[op] = d
        return d

    # -- producer side -------------------------------------------------------------------
    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        env = self.env
        m = self.m
        cfg: MessageQueueConfig = self.config
        m.arrive(env.now)
        if self.fifo_bucket is not None and not self.fifo_bucket.try_take(env.now):
            m.reject("throttled", env.now)
            span(req, self.id, "rejected", env.now, env.now, "FIFO publish limit")
            return rejected("throttled", self.id)
        drop = False
        if cfg.max_depth is not None and self.depth >= cfg.max_depth:
            if not self.at_most_once:
                m.reject("queue_full", env.now)
                span(req, self.id, "rejected", env.now, env.now, "backlog full")
                return rejected("queue_full", self.id)
            drop = True
        start = env.now
        if self.threshold is not None and self.blocked:
            t0 = env.now
            while self.blocked and self.unblocked is not None:
                yield self.unblocked
            m.mean("publishBlocked", env.now, env.now - t0)
            span(req, self.id, "blocked", t0, env.now, "memory alarm: publisher blocked")
        elif self.threshold is not None:
            m.mean("publishBlocked", env.now, 0.0)
        d = self.sample_time(cfg.publish_time_ms, self.rng)
        if self.linger > 0:
            d += self.rng.random() * self.linger
        if self.acks_all:
            d += cfg.replication_ms.sample(self.rng)
        if d > 0:
            yield env.timeout(d)
        m.service.record(env.now, d)
        span(req, self.id, "publish", start, env.now, "acks=all" if self.acks_all else None)
        if drop:
            m.count("dropped", env.now)
        else:
            msg = Message(
                req.op,
                req.key,
                env.now,
                start,
                req.measured,
                req.id,
                trace=req.trace,
                depth=req.depth,
            )
            self._enqueue(msg, env.now)
        m.hold.record(env.now, env.now - start)
        m.depart(env.now, OK, req)
        return OK

    def _partition_of(self, msg: Message) -> int:
        n = len(self.parts or [])
        if self.partitioning == "key":
            return (((msg.key + 1) * 2654435761) & 0xFFFFFFFF) % n
        p = self.rr_part
        self.rr_part = (p + 1) % n
        return p

    def _enqueue(self, msg: Message, now: float) -> None:
        self.depth += 1
        self.m.queue.add(1, now)
        if self.parts is not None:
            p = self._partition_of(msg)
            q = self.parts[p]
            q.append(msg)
            i = self.ctx.tl.index(now)
            if len(q) > self.part_max[i]:
                self.part_max[i] = len(q)
            w = self.wake[self.owner[p]]
            if not w.triggered:
                w.succeed()
        elif self.cycles:
            self.shared.append(msg)
            while self.idle:  # wake one waiting consumer
                w = self.idle.popleft()
                if not w.triggered:
                    w.succeed()
                    break
        else:
            self.store.put(msg)
        if self.threshold is not None and not self.blocked and self.depth >= self.threshold:
            self.blocked = True
            self.unblocked = self.env.event()
            self.m.count("memoryAlarms", now)

    def _dequeued(self, now: float) -> None:
        self.depth -= 1
        self.m.queue.add(-1, now)
        if self.blocked and self.threshold is not None and self.depth < 0.9 * self.threshold:
            self.blocked = False
            if self.unblocked is not None and not self.unblocked.triggered:
                self.unblocked.succeed()

    # -- consumer side -------------------------------------------------------------------
    def _shared_consumer(self) -> Generator[simpy.Event, Any, None]:
        while True:
            msg: Message = yield self.store.get()
            yield from self._process(msg)

    def _partition_consumer(self, index: int, mine: list[int]) -> Generator[simpy.Event, Any, None]:
        env = self.env
        parts = self.parts
        assert parts is not None
        rr = 0
        n = len(mine)
        while True:
            msg = None
            for k in range(n):
                p = mine[(rr + k) % n]
                if parts[p]:
                    msg = parts[p].popleft()
                    rr = (rr + k + 1) % n
                    break
            if msg is None:
                self.wake[index] = env.event()
                yield self.wake[index]
                continue
            yield from self._process(msg)

    def _cycle_consumer(
        self, index: int, mine: list[int] | None
    ) -> Generator[simpy.Event, Any, None]:
        """A consumer that polls in cycles, as Kafka's consumers do: it takes the backlog of its
        partitions (``mine``; on a shared queue up to one message per slot), processes it in
        ``consumerConcurrency`` slots, each partition's messages one after another, waits until
        all are done, pays the fetch overhead (the fetch and the offset commit), then polls
        again. Messages that arrive meanwhile wait for the next poll; with no backlog it waits
        for the next message and takes it at once."""
        env = self.env
        overhead = self.config.fetch_overhead_ms
        while True:
            work = self._poll(mine)
            if not work:
                ev = env.event()
                if mine is None:
                    self.idle.append(ev)
                else:
                    self.wake[index] = ev
                yield ev
                continue
            slots = [env.process(self._slot(work)) for _ in range(min(self.concurrency, len(work)))]
            yield env.all_of(slots)
            if overhead is not None:
                assert self.fetch_rng is not None
                d = self.sample_time(overhead, self.fetch_rng)
                if d > 0:
                    yield env.timeout(d)

    def _poll(self, mine: list[int] | None) -> deque[list[Message]]:
        """One poll's batches: each owned partition's whole backlog, or on a shared queue one
        message per slot."""
        work: deque[list[Message]] = deque()
        if mine is None:
            while self.shared and len(work) < self.concurrency:
                work.append([self.shared.popleft()])
            return work
        assert self.parts is not None
        for p in mine:
            q = self.parts[p]
            if q:
                work.append(list(q))
                q.clear()
        return work

    def _slot(self, work: deque[list[Message]]) -> Generator[simpy.Event, Any, None]:
        """One slot of a polling consumer: the poll's batches in turn, a batch's messages in
        order."""
        while work:
            for msg in work.popleft():
                yield from self._process(msg)

    def _visibility(self, msg: Message, lease: object) -> Generator[simpy.Event, Any, None]:
        assert self.visibility_ms is not None
        yield self.env.timeout(self.visibility_ms)
        if msg.lease is lease:
            # still being processed: the message reappears and another consumer gets it too
            now = self.env.now
            self.m.count("redeliveries", now)
            copy = Message(
                msg.op,
                msg.key,
                msg.published_at,
                msg.created_at,
                msg.measured,
                msg.rid,
                msg.deliveries,
                msg.trace,
                msg.depth,
            )
            self._enqueue(copy, now)

    def _process(self, msg: Message) -> Generator[simpy.Event, Any, None]:
        env = self.env
        m = self.m
        cfg: MessageQueueConfig = self.config
        now = env.now
        self._dequeued(now)
        wait = now - msg.published_at
        m.queue_wait.record(now, wait)
        if m.tl.window_start <= now < m.tl.window_end:
            m.queue_wait_hist.record(wait)
        self.processing += 1
        m.busy.add(1, now)
        start = now
        lease: object | None = None
        if self.visibility_ms is not None:
            lease = object()
            msg.lease = lease
            env.process(self._visibility(msg, lease))
        req = Request(
            msg.rid,
            msg.op,
            msg.key,
            msg.published_at,
            msg.measured,
            trace=msg.trace,
            depth=msg.depth,
        )
        if msg.trace is not None:
            span(req, self.id, "queued", msg.published_at, now, "waiting in the backlog")
        ct = self.sample_time(cfg.consumer_time_ms, self.consumer_rng)
        m.cpu.add(1, now)
        if ct > 0:
            yield env.timeout(ct)
        m.cpu.add(-1, env.now)
        span(req, self.id, "consume", start, env.now, None)
        outcome: Outcome = OK
        for e in self.deps_for(msg.op):
            outcome = yield from self.ctx.call(e, req, self.id)
            if not outcome.ok:
                break
        end = env.now
        if lease is not None and msg.lease is lease:
            msg.lease = None
        m.busy.add(-1, end)
        m.hold.record(end, end - start)
        self.processing -= 1
        msg.deliveries += 1
        if outcome.ok:
            m.count("processed", end)
            age = end - msg.created_at  # from the start of the publish, as a producer stamps it
            self.age.record(end, age)
            if m.tl.window_start <= end < m.tl.window_end:
                self.age_hist.record(age)
        elif msg.deliveries < cfg.max_deliveries:
            m.count("failedDeliveries", end)
            self._enqueue(msg, end)  # redelivered at the back of the queue
        else:
            m.count("failedDeliveries", end)
            m.count("deadLettered", end)

    # -- engine hooks ----------------------------------------------------------------------
    def background_busy(self) -> bool:
        return self.depth > 0 or self.processing > 0

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        out: dict[str, list[float | None]] = {"lag": list(self.m.queue.mean_series())}
        if self.parts is not None:
            out["partitionLagMax"] = [float(v) for v in self.part_max]
        for name, counter in (("redeliveryRps", "redeliveries"), ("droppedRps", "dropped")):
            if counter in self.m.extra:
                out[name] = list(self.m.extra[counter].rate_series())
        if "publishBlocked" in self.m.means:
            out["publishBlockedMs"] = self.m.means["publishBlocked"].series()
        return out

    def extra_summary(self) -> dict[str, Any]:
        mean_age = self.age.window_mean()
        p95 = self.age_hist.percentile(95)
        out = {
            "effectiveConsumers": self.effective_consumers,
            **({"consumerSlots": self.consumer_slots} if self.concurrency > 1 else {}),
            "backlogAtEnd": self.depth,
            "meanMessageAgeMs": round(mean_age, 4) if mean_age is not None else None,
            "p95MessageAgeMs": round(p95, 4) if p95 is not None else None,
        }
        if self.parts is not None:
            out["deepestPartitionAtEnd"] = max((len(q) for q in self.parts), default=0)
        return out

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: MessageQueueConfig = self.config
        active = {
            "partitions": self.parts is not None,
            "acks_all": self.acks_all,
            "linger": self.linger > 0,
            "memory_alarm": self.threshold is not None,
            "visibility_timeout": self.visibility_ms is not None,
            "ack_deadline": self.visibility_ms is not None,
            "fifo_limit": self.fifo_bucket is not None,
        }
        return [t.to_dict() for t in cfg.profile().traits if active.get(t.id, True)]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: MessageQueueConfig = self.config
        out: list[dict[str, Any]] = []
        mean_proc = summary.get("meanHoldMs")
        c = self.effective_consumers
        out.append(
            formula(
                "consumers",
                "Effective consumers",
                "effective consumers = min(consumers, partitions)",
                f"min({cfg.consumers}, {cfg.partitions})",
                float(c),
                "consumers",
                metric="utilization",
            )
        )
        slots = self.consumer_slots
        if self.concurrency > 1:
            out.append(
                formula(
                    "consumer_slots",
                    "Consumer slots",
                    "slots = Σ over consumers of min(concurrency, partitions it owns)"
                    if self.parts is not None
                    else "slots = effective consumers × concurrency",
                    f"{c} consumers × up to {self.concurrency} messages at once",
                    float(slots),
                    "slots",
                    note="A partition's messages go one at a time, so a consumer uses at most one slot per partition it owns.",
                    metric="utilization",
                )
            )
        if mean_proc:
            what = "consumer slots" if self.concurrency > 1 else "effective consumers"
            note = (
                f"Publishes: {num(summary.get('arrivalRps'), 2)}/s. When they exceed the "
                "consumer throughput the backlog (lag) grows."
            )
            if self.cycles:
                note += (
                    " Consumers poll in cycles: each poll also costs the fetch overhead, spread "
                    "over more messages per poll as the backlog grows, so the throughput "
                    "approaches this limit only under load; below it, a message waits for the "
                    "next poll."
                )
            out.append(
                formula(
                    "drain_rate",
                    "Consumer throughput",
                    f"messages/s = {what} × 1000 ÷ mean processing time",
                    f"{slots} × 1000 ÷ {num(mean_proc, 2)} ms",
                    slots * 1000.0 / mean_proc,
                    "msg/s",
                    note=note,
                    metric="lag",
                )
            )
        wl = self.ctx.shared.get("workload")
        if self.parts is not None and self.partitioning == "key" and wl is not None:
            share = zipf_share(wl.key_count, wl.key_skew)
            out.append(
                formula(
                    "hot_partition",
                    "Hottest partition",
                    "hot partition share ≥ hottest key's share = 1 ÷ Σ k^−s",
                    f"{wl.key_count:,} keys, Zipf s = {wl.key_skew:g}; fair share 1 ÷ {cfg.partitions}",
                    share,
                    "",
                    note=(
                        f"The partition holding the hottest key gets at least {pct(share)} of "
                        f"messages against a fair {pct(1 / cfg.partitions)}; its one consumer "
                        "has to keep up with that alone."
                    ),
                    metric="partitionLagMax",
                )
            )
        if self.threshold is not None:
            out.append(
                formula(
                    "memory_alarm",
                    "Memory alarm threshold",
                    "backlog limit = watermark × memory ÷ message size",
                    f"{num(cfg.memory_high_watermark, 2)} × {num(cfg.memory_gib)} GiB ÷ {num(cfg.message_size_kb)} KiB",
                    self.threshold,
                    "messages",
                    note="Past it, publishers block until the backlog falls below 90% of the limit.",
                    metric="publishBlockedMs",
                )
            )
        if self.visibility_ms is not None:
            out.append(
                formula(
                    "visibility",
                    "Visibility timeout",
                    "redelivered if processing time > visibility timeout",
                    f"timeout {num(self.visibility_ms / 1000.0)} s",
                    self.visibility_ms / 1000.0,
                    "s",
                    note="Processing (consumer time plus downstream calls) longer than this makes the message visible again: a second consumer repeats the work.",
                    metric="redeliveryRps",
                )
            )
        return out


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


class MessageQueueModel(ComponentModel):
    type = "message_queue"
    display_name = "Message Queue / Stream"
    category = "messaging"
    description = (
        "Asynchronous messaging (Kafka, RabbitMQ, SQS-style). Producers get an acknowledgement "
        "after publishing; consumers process messages in the background and call the outgoing "
        "links. Absorbs spikes as backlog (lag) instead of rejecting requests, and behaves like "
        "the technology you pick."
    )
    icon = "inbox"
    config_model = MessageQueueConfig
    technology_profiles = QUEUE_PROFILES
    ports = (
        PortSpec(
            "in",
            "in",
            "Publish",
            "Messages published by producers (the caller gets an ack, not the result).",
        ),
        PortSpec(
            "out",
            "out",
            "Consumers",
            "Downstream calls each consumer makes per message, in order, for matching request types.",
        ),
    )
    modeled = (
        "Publish acknowledgement latency (plus replication wait and batching linger) and a bounded backlog",
        "Partitioned logs: key-hashed ordered partitions, one consumer each (hot partitions lag)",
        "Fetch cycles: a consumer polls its partitions' backlog, processes it in parallel slots (each partition in order), pays the fetch and commit overhead, then polls again",
        "Shared queues: competing consumers, FIFO",
        "Redelivery of failed messages up to max deliveries, then dead-lettering",
        "Visibility timeouts / ack deadlines: slow processing is delivered again (duplicates)",
        "Memory alarm flow control: publishers block past the high watermark",
        "At-most-once delivery: overflow dropped silently; FIFO publish rate limits",
        "Backlog (lag), consumer utilization and message age",
    )
    not_modeled = (
        "Broker disk and network throughput limits",
        "Consumer group rebalancing pauses",
        "Exactly-once transactions",
    )
    presets = (
        Preset(
            id="kafka",
            name="Kafka",
            technology="kafka",
            family="Log / stream",
            description=(
                "Partitioned log: messages hash by key to 12 ordered partitions, one consumer "
                "each; acks=all and 5 ms linger on publish."
            ),
            config={
                "cpuCores": 4,
                "memoryGib": 16,
                "storageGib": 1000,
                "technology": "kafka",
                "partitions": 12,
                "consumers": 6,
                "maxDepth": 10_000_000,
                "publishTimeMs": _lognormal(1, 4),
                "consumerTimeMs": _lognormal(2, 8),
                "maxDeliveries": 3,
                "brokers": 3,
                "costPerHour": 0.21,
            },
            tags=("log", "stream", "partitions"),
        ),
        Preset(
            id="rabbitmq",
            name="RabbitMQ",
            technology="rabbitmq",
            family="Broker",
            description=(
                "Message broker with competing consumers on a queue; past the memory high "
                "watermark it blocks publishers."
            ),
            config={
                "cpuCores": 2,
                "memoryGib": 8,
                "storageGib": 200,
                "technology": "rabbitmq",
                "partitions": 1000,
                "consumers": 8,
                "maxDepth": 10_000_000,
                "publishTimeMs": _lognormal(1, 4),
                "consumerTimeMs": _lognormal(3, 12),
                "maxDeliveries": 5,
                "messageSizeKb": 4,
                "brokers": 1,
                "costPerHour": 0.10,
            },
            tags=("broker", "amqp"),
        ),
        Preset(
            id="sqs",
            name="SQS-style queue",
            technology="sqs",
            family="Managed queue",
            description=(
                "Managed HTTP queue: slower publishes, unbounded backlog, 30 s visibility timeout "
                "(slow processing gets delivered twice)."
            ),
            config={
                "technology": "sqs",
                "partitions": 10_000,
                "consumers": 10,
                "maxDepth": None,
                "publishTimeMs": _lognormal(15, 40),
                "consumerTimeMs": _lognormal(5, 20),
                "maxDeliveries": 5,
                "brokers": 1,
                "costPerHour": 0.0,
            },
            tags=("managed", "queue"),
        ),
        Preset(
            id="pubsub",
            name="Pub/Sub-style topic",
            technology="pubsub",
            family="Managed queue",
            description="Managed publish/subscribe with a 10 s ack deadline (illustrative numbers).",
            config={
                "technology": "pubsub",
                "partitions": 10_000,
                "consumers": 10,
                "maxDepth": None,
                "publishTimeMs": _lognormal(20, 60),
                "consumerTimeMs": _lognormal(5, 20),
                "maxDeliveries": 5,
                "brokers": 1,
                "costPerHour": 0.0,
            },
            tags=("managed", "pubsub"),
        ),
        Preset(
            id="nats",
            name="NATS JetStream",
            technology="nats",
            family="Broker",
            description="Lightweight messaging with JetStream persistence (at-least-once); switch delivery to at-most-once for core NATS.",
            config={
                "cpuCores": 2,
                "memoryGib": 4,
                "storageGib": 100,
                "technology": "nats",
                "partitions": 1000,
                "consumers": 8,
                "maxDepth": 1_000_000,
                "publishTimeMs": _lognormal(0.5, 2),
                "consumerTimeMs": _lognormal(2, 8),
                "brokers": 3,
                "costPerHour": 0.08,
            },
            tags=("broker", "low-latency"),
        ),
        Preset(
            id="redis-streams",
            name="Redis Streams",
            technology="redis_streams",
            family="Log / stream",
            description="Append-only stream with consumer groups, in memory.",
            config={
                "cpuCores": 2,
                "memoryGib": 6.4,
                "technology": "redis_streams",
                "partitions": 1000,
                "consumers": 4,
                "maxDepth": 1_000_000,
                "publishTimeMs": _lognormal(0.5, 2),
                "consumerTimeMs": _lognormal(2, 8),
                "brokers": 1,
                "costPerHour": 0.10,
            },
            tags=("stream", "in-memory"),
        ),
    )

    def capacity_slots(self, config: Any) -> int | None:
        return int(config.consumer_slots())

    def uses_keys(self, config: Any) -> bool:
        return bool(config.setting("partitioning", "none") == "key" and config.partitions > 1)

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        issues: list[Issue] = []
        name = node.label or node.id
        if not view.out_edges.get(node.id):
            issues.append(
                warning(
                    "queue.no_consumers",
                    f"{name} has no consumer links: messages are acknowledged and dropped after the consumer time.",
                    node_id=node.id,
                    hint="Link the queue's 'Consumers' port to the service that processes messages.",
                )
            )
        if config.consumers > config.partitions:
            issues.append(
                warning(
                    "queue.idle_consumers",
                    f"{name} has {config.consumers} consumers but only {config.partitions} partitions; "
                    f"{config.consumers - config.partitions} consumers stay idle.",
                    node_id=node.id,
                    hint="Add partitions or reduce consumers.",
                )
            )
        n = config.consumer_concurrency or 1
        owned = -(-config.partitions // min(config.consumers, config.partitions))
        if n > 1 and config.partitioned() and n > owned:
            issues.append(
                warning(
                    "queue.idle_slots",
                    f"{name} lets each consumer process {n} messages at once, but a consumer owns "
                    f"at most {owned} partitions and a partition's messages go one at a time; "
                    f"{n - owned} slots per consumer stay idle.",
                    node_id=node.id,
                    path="consumerConcurrency",
                    hint="Add partitions or lower the consumer concurrency.",
                )
            )
        if config.setting("flow_control", "off") == "on" and not config.memory_gib:
            issues.append(
                warning(
                    "queue.alarm_without_memory",
                    f"{name} blocks publishers on a memory alarm, but no memory is set, so the "
                    "alarm never fires.",
                    node_id=node.id,
                    path="memoryGib",
                    hint="Set the broker's memory and the message size.",
                )
            )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return MessageQueueRuntime(ctx, node, config)
