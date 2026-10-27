"""Cache-aside: the service looks a key up in the cache and, on a miss, reads the data store
itself over a link set to readsOnMiss and writes the value back; a link set to
continueOnFailure lets a failed lookup count as a miss and a failed invalidation be skipped."""

from __future__ import annotations

import copy
from typing import Any

from archsim_engine import default_registry, load_document, run_simulation, validate_document
from archsim_engine.analyze import analyze_document
from engine_builders import node_by_id, short

REGISTRY = default_registry()


def edges_by_id(d: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {e["id"]: e for e in d["architecture"]["edges"]}


def light(starter: dict[str, Any], rps: float = 100) -> dict[str, Any]:
    """The starter at a load its API carries even when every read goes to the database."""
    d = short(starter, duration=30, warmup=4)
    d["workload"]["rps"] = rps
    return d


def outage(d: dict[str, Any], start: float = 12, duration: float = 8) -> dict[str, Any]:
    d = copy.deepcopy(d)
    d["workload"]["faults"] = [
        {"id": "f1", "kind": "outage", "target": "cache", "startS": start, "durationS": duration}
    ]
    return d


def codes(d: dict[str, Any]) -> list[str]:
    return [i.code for i in validate_document(load_document(d), REGISTRY).issues]


def test_the_starter_is_cache_aside(starter: dict[str, Any]) -> None:
    e = edges_by_id(starter)
    assert "cache-db" not in e  # Redis never calls PostgreSQL
    assert e["api-db"]["config"]["readsOnMiss"] is True
    assert e["api-cache"]["config"]["continueOnFailure"] is True
    assert codes(starter) == []


def test_reads_reach_the_database_only_on_a_miss_and_are_written_back(
    starter: dict[str, Any],
) -> None:
    r = run_simulation(short(starter, duration=20, warmup=4))
    cache, db = node_by_id(r, "cache"), node_by_id(r, "db")
    c = cache["summary"]["counters"]
    assert r["summary"]["successRate"] == 1.0
    assert 0.75 < c["hits"] / (c["hits"] + c["misses"]) < 0.85
    # Counts cover the measured window, so a request near its edge can be counted at one part
    # and not the next: allow a few.
    # every miss is read from PostgreSQL by the API, and every write goes there too
    assert abs(db["summary"]["arrivals"] - (c["misses"] + c["invalidations"])) <= 3
    # and every value read on a miss is written back: one more command on the cache
    assert abs(c["populates"] - c["misses"]) <= 3
    commands = c["hits"] + c["misses"] + c["invalidations"] + c["populates"]
    assert abs(cache["summary"]["arrivals"] - commands) <= 3
    assert cache["accounting"]["invariantHolds"] and db["accounting"]["invariantHolds"]


def test_a_cache_outage_falls_back_to_the_database(starter: dict[str, Any]) -> None:
    r = run_simulation(outage(light(starter)))
    assert r["summary"]["successRate"] == 1.0  # reads go to PostgreSQL, writes skip invalidation
    assert node_by_id(r, "cache")["summary"]["rejected"].get("outage", 0) > 0
    assert not r["summary"]["failureBreakdown"]
    t, rate = r["timeseries"]["t"], node_by_id(r, "db")["timeseries"]["arrivalRps"]

    def mean(lo: float, hi: float) -> float:
        xs = [v or 0.0 for x, v in zip(t, rate, strict=True) if lo <= x < hi]
        return sum(xs) / len(xs)

    # every read hits the database while the cache is out (90% reads, 20% of them before)
    assert mean(13, 19) > 3 * mean(5, 11)


def test_without_carrying_on_a_failed_lookup_fails_the_read(starter: dict[str, Any]) -> None:
    d = light(starter)
    edges_by_id(d)["api-cache"]["config"]["continueOnFailure"] = False
    r = run_simulation(outage(d))
    assert r["summary"]["successRate"] < 0.9
    assert any(
        f["reason"] == "outage" and f["nodeId"] == "cache" for f in r["summary"]["failureBreakdown"]
    )


def test_same_seed_same_result(starter: dict[str, Any]) -> None:
    d = outage(light(starter))
    a, b = run_simulation(d), run_simulation(d)
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]


def test_validation_of_cache_aside_links(starter: dict[str, Any]) -> None:
    # reads only on a miss, but no cache to miss: those reads would never happen
    d = copy.deepcopy(starter)
    d["architecture"]["edges"] = [e for e in d["architecture"]["edges"] if e["id"] != "api-cache"]
    d["architecture"]["nodes"] = [n for n in d["architecture"]["nodes"] if n["id"] != "cache"]
    assert "edge.reads_on_miss_no_cache" in codes(d)

    # a read-through cache answers its own misses: the API's on-miss link is never used
    d = copy.deepcopy(starter)
    d["architecture"]["edges"].append(
        {
            "id": "cache-db",
            "source": "cache",
            "sourcePort": "miss",
            "target": "db",
            "targetPort": "in",
            "config": {"requestTypes": ["read"]},
        }
    )
    edges_by_id(d)["api-cache"]["config"]["continueOnFailure"] = False
    assert "edge.reads_on_miss_unused" in codes(d)

    # the settings belong to a service's links
    d = copy.deepcopy(starter)
    edges_by_id(d)["lb-api"]["config"]["continueOnFailure"] = True
    assert "edge.caller_setting_ignored" in codes(d)


def test_load_estimate_and_review_follow_the_cache_aside_path(starter: dict[str, Any]) -> None:
    a = analyze_document(starter, REGISTRY)
    peak = {k: v["peakRps"] for k, v in a["sizing"]["nodes"].items()}
    # 400 req/s, 90% reads, 80% hits: 72 reads/s miss to PostgreSQL plus 40 writes/s
    assert round(peak["db"]) == 112
    # the cache gets every read and write (400) plus one write-back per miss (72)
    assert round(peak["cache"]) == 472
    rules = {f["rule"] for f in a["review"]}
    assert "cache-dependency" not in rules  # reads fall back to PostgreSQL
    assert not any("waits indefinitely on PostgreSQL" in f["title"] for f in a["review"])

    d = copy.deepcopy(starter)
    edges_by_id(d)["api-cache"]["config"]["continueOnFailure"] = False
    review = analyze_document(d, REGISTRY)["review"]
    dep = next(f for f in review if f["rule"] == "cache-dependency")
    assert "fails the read instead of counting as a miss" in dep["detail"]
