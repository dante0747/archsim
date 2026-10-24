"""Message queue / log / broker (Kafka, RabbitMQ, SQS-style...): asynchronous decoupling.

The producer's request succeeds as soon as the message is published (acknowledged). Consumers
take messages in FIFO order in the background and call the outgoing links (in order, matching
the message's request type). A failed delivery is retried up to ``maxDeliveries`` times, then
dead-lettered. Background processing never changes the outcome of the producer's request.

Node metrics: arrivals/completed/rejected count publishes; ``queue`` is the message backlog
(lag); ``busy`` is busy consumers (utilization); ``queueWaitMs`` is how long messages waited
before a consumer picked them up. Counters: ``processed``, ``failedDeliveries``,
``deadLettered``. Summary extras: ``meanMessageAgeMs``/``p95MessageAgeMs`` (publish to
successful processing), ``backlogAtEnd``, ``effectiveConsumers``. Series: ``lag``.
"""

from __future__ import annotations

from collections.abc import Generator
from dataclasses import dataclass
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.distributions import Distribution, LogNormal
from archsim_engine.issues import Issue, warning
from archsim_engine.metrics import LatencyHistogram, MeanAccumulator
from archsim_engine.registry import ComponentModel, GraphView, PortSpec, Preset, ui
from archsim_engine.runtime import (
    OK,
    EdgeRuntime,
    Outcome,
    Request,
    RuntimeComponent,
    SimContext,
    rejected,
)
from archsim_engine.schema import CamelModel, NodeSpec


class MessageQueueConfig(CamelModel):
    technology: Literal[
        "kafka", "rabbitmq", "sqs", "pubsub", "nats", "redis_streams", "generic"
    ] = Field(
        default="generic",
        description="Product or managed service. A label for the design; behaviour comes from the settings below.",
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
        description="Messages the queue holds; publishes beyond it are rejected (queue_full). Empty = unbounded.",
        json_schema_extra=ui(label="Max backlog", unit="msgs", group="Capacity"),
    )
    publish_time_ms: Distribution = Field(
        default_factory=lambda: LogNormal(median=2.0, p95=8.0),
        description="Time for the broker to acknowledge a publish (ms): what the producer waits.",
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
    max_deliveries: int = Field(
        default=3,
        ge=1,
        le=20,
        description="Delivery attempts per message; after the last failure it goes to the dead-letter queue.",
        json_schema_extra=ui(label="Max deliveries", group="Reliability"),
    )
    brokers: int = Field(
        default=1,
        ge=1,
        le=1000,
        description="Broker nodes in the cluster (used for cost; throughput is set by consumers and partitions).",
        json_schema_extra=ui(label="Brokers", group="Cost"),
    )
    cost_per_hour: float = Field(
        default=0.15,
        ge=0,
        le=10_000,
        description="Price of one broker per hour, in USD. Illustrative default; use your provider's pricing.",
        json_schema_extra=ui(
            label="Cost per broker-hour", unit="$/h", group="Cost", assumption=True
        ),
    )


@dataclass(slots=True)
class Message:
    op: str
    key: int
    published_at: float
    measured: bool
    rid: int
    deliveries: int = 0


class MessageQueueRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: MessageQueueConfig) -> None:
        self.effective_consumers = min(config.consumers, config.partitions)
        super().__init__(ctx, node, config, self.effective_consumers)
        self.store: simpy.Store = simpy.Store(ctx.env)
        self.rng = ctx.rng(f"node:{node.id}:publish")
        self.consumer_rng = ctx.rng(f"node:{node.id}:consumer")
        self.age = MeanAccumulator(ctx.tl)
        self.age_hist = LatencyHistogram()
        self.depth = 0
        self.processing = 0
        self._deps: dict[str, list[EdgeRuntime]] = {}
        for i in range(self.effective_consumers):
            ctx.env.process(self._consumer(i))

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
        if cfg.max_depth is not None and self.depth >= cfg.max_depth:
            m.reject("queue_full", env.now)
            return rejected("queue_full", self.id)
        start = env.now
        d = self.sample_time(cfg.publish_time_ms, self.rng)
        if d > 0:
            yield env.timeout(d)
        m.service.record(env.now, d)
        self._enqueue(Message(req.op, req.key, env.now, req.measured, req.id), env.now)
        m.hold.record(env.now, env.now - start)
        m.depart(env.now, OK, req)
        return OK

    def _enqueue(self, msg: Message, now: float) -> None:
        self.depth += 1
        self.m.queue.add(1, now)
        self.store.put(msg)

    # -- consumer side -------------------------------------------------------------------
    def _consumer(self, index: int) -> Generator[simpy.Event, Any, None]:
        env = self.env
        m = self.m
        cfg: MessageQueueConfig = self.config
        while True:
            msg: Message = yield self.store.get()
            now = env.now
            self.depth -= 1
            m.queue.add(-1, now)
            wait = now - msg.published_at
            m.queue_wait.record(now, wait)
            if m.tl.window_start <= now < m.tl.window_end:
                m.queue_wait_hist.record(wait)
            self.processing += 1
            m.busy.add(1, now)
            start = now
            ct = self.sample_time(cfg.consumer_time_ms, self.consumer_rng)
            m.cpu.add(1, now)
            if ct > 0:
                yield env.timeout(ct)
            m.cpu.add(-1, env.now)
            outcome: Outcome = OK
            req = Request(msg.rid, msg.op, msg.key, msg.published_at, msg.measured)
            for e in self.deps_for(msg.op):
                outcome = yield from self.ctx.call(e, req, self.id)
                if not outcome.ok:
                    break
            end = env.now
            m.busy.add(-1, end)
            m.hold.record(end, end - start)
            self.processing -= 1
            msg.deliveries += 1
            if outcome.ok:
                m.count("processed", end)
                age = end - msg.published_at
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
        return {"lag": list(self.m.queue.mean_series())}

    def extra_summary(self) -> dict[str, Any]:
        mean_age = self.age.window_mean()
        p95 = self.age_hist.percentile(95)
        return {
            "effectiveConsumers": self.effective_consumers,
            "backlogAtEnd": self.depth,
            "meanMessageAgeMs": round(mean_age, 4) if mean_age is not None else None,
            "p95MessageAgeMs": round(p95, 4) if p95 is not None else None,
        }


def _lognormal(median: float, p95: float) -> dict[str, Any]:
    return {"kind": "lognormal", "median": median, "p95": p95}


class MessageQueueModel(ComponentModel):
    type = "message_queue"
    display_name = "Message Queue / Stream"
    category = "messaging"
    description = (
        "Asynchronous messaging (Kafka, RabbitMQ, SQS-style). Producers get an acknowledgement "
        "after publishing; consumers process messages in the background and call the outgoing "
        "links. Absorbs spikes as backlog (lag) instead of rejecting requests."
    )
    icon = "inbox"
    config_model = MessageQueueConfig
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
        "Publish acknowledgement latency and a bounded backlog (rejects when full)",
        "FIFO consumption by min(consumers, partitions) parallel consumers",
        "Redelivery of failed messages up to max deliveries, then dead-lettering",
        "Backlog (lag), consumer utilization and message age",
    )
    not_modeled = (
        "Per-partition ordering and key-based partition skew",
        "Broker replication, disk and network throughput limits",
        "Consumer rebalancing, visibility timeouts and duplicate delivery",
    )
    presets = (
        Preset(
            id="kafka",
            name="Kafka",
            technology="kafka",
            family="Log / stream",
            description="Partitioned log: consumer parallelism is capped by partitions; acks=all publish latency.",
            config={
                "technology": "kafka",
                "partitions": 12,
                "consumers": 6,
                "maxDepth": 10_000_000,
                "publishTimeMs": _lognormal(3, 10),
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
            description="Message broker with competing consumers on a queue.",
            config={
                "technology": "rabbitmq",
                "partitions": 1000,
                "consumers": 8,
                "maxDepth": 1_000_000,
                "publishTimeMs": _lognormal(1, 4),
                "consumerTimeMs": _lognormal(3, 12),
                "maxDeliveries": 5,
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
            description="Managed HTTP queue: slower publishes, effectively unbounded backlog (illustrative numbers).",
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
            description="Managed publish/subscribe with push or pull subscribers (illustrative numbers).",
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
            name="NATS",
            technology="nats",
            family="Broker",
            description="Lightweight messaging (JetStream for persistence) with very low publish latency.",
            config={
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
        return int(min(config.consumers, config.partitions))

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
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return MessageQueueRuntime(ctx, node, config)
