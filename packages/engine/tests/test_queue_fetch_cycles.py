"""Message queues: message age from the publish's start, and consumers that poll in fetch cycles
(``consumerConcurrency``, ``fetchOverheadMs``; schema 1.6)."""

from __future__ import annotations

from typing import Any

from archsim_engine import default_registry, load_document, run_simulation, validate_document
from archsim_engine.components.message_queue import MessageQueueConfig
from engine_builders import doc, edge, node, node_by_id

CONST = lambda v: {"kind": "constant", "value": v}  # noqa: E731


def queue(rps: float, duration: float = 6, **q: Any) -> dict[str, Any]:
    """Clients publish to a queue whose consumers do ``consumerTimeMs`` per message and nothing
    else (no downstream calls), arrivals evenly spaced."""
    cfg = {
        "technology": "generic",
        "partitioning": "round_robin",
        "acks": "leader",
        "lingerMs": 0,
        "partitions": 6,
        "consumers": 1,
        "maxDepth": None,
        "publishTimeMs": CONST(1),
        "consumerTimeMs": CONST(2),
    }
    cfg.update(q)
    return doc(
        [node("clients", "traffic_source"), node("q", "message_queue", **cfg)],
        [edge("c-q", "clients", "q")],
        rps=rps,
        durationS=duration,
        warmupS=1,
        arrivalProcess="deterministic",
    )


def summary(d: dict[str, Any]) -> dict[str, Any]:
    return node_by_id(run_simulation(d), "q")["summary"]


def codes(d: dict[str, Any]) -> set[str]:
    return {i.code for i in validate_document(load_document(d), default_registry()).issues}


def max_lag(d: dict[str, Any]) -> float:
    lag = node_by_id(run_simulation(d), "q")["timeseries"]["lag"]
    return max(v for v in lag if v is not None)


def test_message_age_counts_the_publish() -> None:
    # 10 ms to publish, 1 ms to consume, never a wait: 11 ms old when processed (it was 1 ms
    # before 0.15, when the clock started after the acknowledgement)
    s = summary(queue(100, publishTimeMs=CONST(10), consumerTimeMs=CONST(1), consumers=6))
    assert abs(s["meanMessageAgeMs"] - 11) < 1e-6
    assert abs(s["p95MessageAgeMs"] - 11) < 0.2
    assert s["p95QueueWaitMs"] < 1e-6  # the backlog wait still starts at the acknowledgement


def test_empty_settings_change_nothing() -> None:
    plain = run_simulation(queue(800, consumers=3))
    one = run_simulation(queue(800, consumers=3, consumerConcurrency=1))
    assert plain["summary"] == one["summary"]
    assert plain["timeseries"] == one["timeseries"]
    assert node_by_id(plain, "q")["summary"] == node_by_id(one, "q")["summary"]
    assert "consumerSlots" not in node_by_id(plain, "q")["summary"]


def test_consumer_slots() -> None:
    def slots(**c: Any) -> int:
        return MessageQueueConfig(**c).consumer_slots()

    assert slots(consumers=4, partitions=6) == 4
    # consumers own 2, 2, 1 and 1 of the 6 partitions; one slot per partition at most
    assert slots(consumers=4, partitions=6, consumer_concurrency=3, partitioning="key") == 6
    assert slots(consumers=1, partitions=6, consumer_concurrency=3, partitioning="key") == 3
    assert slots(consumers=2, partitions=100, consumer_concurrency=4, partitioning="none") == 8


def test_a_polling_consumer_keeps_up_but_messages_wait_for_the_next_poll() -> None:
    # One consumer, 3 slots over 6 partitions, 2 ms a message, 4 ms a poll, 1 message per ms:
    # a poll takes T ms of backlog (T messages, T/6 a partition), 3 slots take 2 partitions each
    # in 2T/3 ms, so T = 2T/3 + 4 = 12 ms. Every message is processed, up to a cycle late.
    d = queue(1000, consumerConcurrency=3, fetchOverheadMs=CONST(4))
    s = summary(d)
    assert s["consumerSlots"] == 3
    assert s["backlogAtEnd"] == 0
    assert 8 < s["meanMessageAgeMs"] < 20
    # three independent consumers, taking each message at once, are hardly late
    fast = summary(queue(1000, consumers=3))
    assert fast["meanMessageAgeMs"] < 4


def test_polling_spreads_the_overhead_until_the_slots_are_full() -> None:
    # 3 slots at 2 ms a message: 1,500 messages/s at most, whatever the 2 ms poll costs
    below = queue(1400, consumerConcurrency=3, fetchOverheadMs=CONST(2))
    above = queue(1600, consumerConcurrency=3, fetchOverheadMs=CONST(2))
    assert max_lag(below) < 200
    assert max_lag(above) > 500


def test_a_shared_queue_polls_one_message_per_slot() -> None:
    # 2 consumers x 2 slots, 4 ms a message and 1 ms a poll: 2 messages per 5 ms per consumer,
    # 800 messages/s
    def d(rps: float) -> dict[str, Any]:
        return queue(
            rps,
            partitioning="none",
            partitions=100,
            consumers=2,
            consumerConcurrency=2,
            consumerTimeMs=CONST(4),
            fetchOverheadMs=CONST(1),
        )

    assert summary(d(700))["consumerSlots"] == 4
    assert max_lag(d(700)) < 100
    assert max_lag(d(900)) > 300


def test_slots_a_consumer_cannot_use_are_flagged() -> None:
    d = queue(100, consumers=4, consumerConcurrency=3)  # owns at most 2 of the 6 partitions
    assert "queue.idle_slots" in codes(d)
    assert "queue.idle_slots" not in codes(queue(100, consumerConcurrency=3))


def test_the_explanation_counts_slots_and_mentions_the_poll() -> None:
    r = run_simulation(queue(1000, consumerConcurrency=3, fetchOverheadMs=CONST(4)))
    ex = {e["id"]: e for e in node_by_id(r, "q")["explain"]}
    assert ex["consumer_slots"]["result"] == 3
    assert ex["drain_rate"]["substituted"].startswith("3 \N{MULTIPLICATION SIGN} 1000")
    assert "poll" in ex["drain_rate"]["note"]
