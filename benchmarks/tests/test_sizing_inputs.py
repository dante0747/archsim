"""Sizing inputs that match what the containers use (accuracy plan W24); nothing starts Docker.

- Redis: the command time is Redis's CPU per command at the top calibration pass, net of idle.
  INFO commandstats' own time leaves out reading requests and writing replies, about nine
  tenths of Redis's CPU, so the model's Redis CPU was about 90% low. The service-to-Redis link
  is derived again from what is left of the GET round trip, so the calibrated latency stays.
- Service memory: calibration passes read each container's working set (the idle pass and
  every staircase pass), and each service's memory at idle and per req/s comes from W17's
  ``fit_memory`` over them. Calibrations without readings keep ArchSim's defaults, so their
  designs do not change. Memory never moves a latency in these designs.
"""

from __future__ import annotations

import json
import math
import time
from typing import Any

import engine_functions
import pytest
import run

CACHE = run.EXPERIMENTS["cache-aside"]
PG_MIXED = run.EXPERIMENTS["pg-mixed"]


def calibration(path: str) -> dict[str, Any]:
    return json.loads((run.RESULTS / path / "calibration.json").read_text(encoding="utf-8"))


def replicate_sets(cal: dict[str, Any]) -> list[dict[str, Any]]:
    """The replicate sets calibrate() derived the inputs from (as rederive_inputs builds them)."""
    return [
        {**r, "low": cal["passes"]["low"], "idle": cal["idle"], "staircase": cal["staircase"]}
        for r in cal["replicates"]
    ]


# ---------------------------------------------------------------------------------------------
# Redis
# ---------------------------------------------------------------------------------------------


def test_redis_command_time_is_its_cpu_per_command_net_of_idle() -> None:
    cal = calibration("cache-aside")
    for s in replicate_sets(cal):
        high = s["high"]
        calls = high["redis"]["get"]["calls"] + high["redis"]["set"]["calls"]
        own = (high["redis"]["get"]["usec"] + high["redis"]["set"]["usec"]) / calls / 1000
        net = high["containerCpuMs"]["redis"] - cal["idle"]["containerCpuMsPerSecond"]["redis"] * 30
        v = run.cache_inputs(CACHE, s)
        mean = v["cacheServiceTimeMs"]["value"]["mean"]
        assert mean == round(net / calls, 5)
        assert 0.03 < mean < 0.04 and mean > 9 * own  # 0.032 to 0.038 ms against 0.003 ms
        assert "net of idle" in v["cacheServiceTimeMs"]["how"]
        # the link is the rest of the GET round trip's median: the command's median is mean x ln 2
        get = high["service"]["timings"]["redis_get"]["median"]
        assert v["cacheLinkLatencyMs"]["value"] == round((get - mean * math.log(2)) / 2, 4)


def test_redis_cpu_before_protocol_v5_is_the_slope_between_the_passes() -> None:
    # run 1 (two passes, no idle pass): (763 - 586 ms) / (9,362 - 2,358 GET/SET calls)
    cal = calibration("cache-aside/run-1")
    v = run.cache_inputs(CACHE, cal["passes"])
    assert v["cacheServiceTimeMs"]["value"]["mean"] == pytest.approx(0.02527, abs=1e-5)
    assert "per extra command between the passes" in v["cacheServiceTimeMs"]["how"]


def redis_passes(stack_container: str | None, idle: bool = True) -> dict[str, Any]:
    """A cache-ttl replicate set: Redis's statistics in the service's /stats (REDIS_STATS=1)."""

    def entry(rate: float, n: int, redis_ms: float) -> dict[str, Any]:
        timing = {"count": n, "mean": 0.3, "median": 0.25, "p95": 0.6, "p99": 0.9}
        cpu = {stack_container: redis_ms} if stack_container else {}
        return {
            "rateRps": rate,
            "k6": {"p50": 1.0, "throughputRps": rate},
            "service": {
                "timings": {"redis_get": timing},
                "redis": {
                    "get": {"calls": n, "usec": n * 3.0},
                    "set": {"calls": n // 2, "usec": n},
                },
            },
            "containerCpuMs": cpu,
        }

    out = {"low": entry(50, 1500, 300.0), "high": entry(1000, 30_000, 2_000.0)}
    if idle:
        out["idle"] = {"containerCpuMsPerSecond": {stack_container: 5.0}}
    return out


def test_cache_ttl_reads_its_own_redis_container() -> None:
    ttl = run.EXPERIMENTS["cache-ttl"]
    v = run.cache_inputs(ttl, redis_passes("redis-ttl"))
    # (2,000 - 5 x 30 ms at rest) / 45,000 GET/SET calls
    assert v["cacheServiceTimeMs"]["value"]["mean"] == round(1850 / 45_000, 5)


def test_redis_without_its_cpu_keeps_the_command_time() -> None:
    ttl = run.EXPERIMENTS["cache-ttl"]
    v = run.cache_inputs(ttl, redis_passes(None, idle=False))
    # INFO commandstats alone: (30,000 x 3 + 30,000) us / 45,000 calls
    assert v["cacheServiceTimeMs"]["value"]["mean"] == round(120_000 / 45_000 / 1000, 5)
    assert "no CPU figure" in v["cacheServiceTimeMs"]["how"]


# ---------------------------------------------------------------------------------------------
# service memory
# ---------------------------------------------------------------------------------------------


def staircase(container: str, rates: list[float], mib: Any) -> list[dict[str, Any]]:
    return [
        {
            "rateRps": r,
            "k6": {"throughputRps": r},
            "resources": {"containers": {container: {"workingSetMiB": mib(r)}}},
        }
        for r in rates
    ]


def test_memory_inputs_fit_the_staircase_and_the_idle_pass() -> None:
    rates = [50.0, 400.0, 1000.0, 1600.0, 1600.0, 1000.0, 400.0, 50.0]
    passes = {
        "idle": {"workingSetMiB": {"api": 40.0}},
        "staircase": staircase("api", rates, lambda r: 40.0 + 0.05 * r),
    }
    v = run.memory_inputs(PG_MIXED, passes)
    assert set(v) == {
        "serviceMemoryBaselineMb",
        "serviceMemoryPerRpsMb",
        "serviceMemoryPerRequestMb",
    }
    assert v["serviceMemoryBaselineMb"]["value"] == pytest.approx(40.0, abs=1e-3)
    assert v["serviceMemoryPerRpsMb"]["value"] == pytest.approx(0.05, abs=1e-6)
    assert v["serviceMemoryPerRequestMb"]["value"] == engine_functions.min_per_request_mb()
    how = v["serviceMemoryBaselineMb"]["how"]
    assert "9 points (at rest and 8 passes, 50 to 1,600 req/s)" in how
    assert "40.0 MiB + 0.0500 MiB per req/s" in how


def test_chain_fits_the_gateway_and_the_api_apart() -> None:
    chain = run.EXPERIMENTS["chain"]
    rates = [50.0, 500.0, 1000.0]
    passes: dict[str, Any] = {"staircase": staircase("gateway", rates, lambda r: 30.0 + 0.02 * r)}
    for p, r in zip(passes["staircase"], rates, strict=True):
        p["resources"]["containers"]["chain-api"] = {"workingSetMiB": 50.0 + 0.04 * r}
    v = run.memory_inputs(chain, passes)
    assert v["serviceMemoryBaselineMb"]["value"] == pytest.approx(30.0, abs=1e-3)
    assert v["apiMemoryBaselineMb"]["value"] == pytest.approx(50.0, abs=1e-3)
    assert v["apiMemoryPerRpsMb"]["value"] == pytest.approx(0.04, abs=1e-6)
    inputs = {
        **fake_inputs(),
        **v,
        "apiServiceTimeMs": {"value": 0.2},
        "apiLinkLatencyMs": {"value": 0.05},
    }
    doc = run.design(chain, inputs, 100)
    nodes = {n["id"]: n["config"] for n in doc["architecture"]["nodes"]}
    assert nodes["gateway"]["memoryBaselineMb"] == v["serviceMemoryBaselineMb"]["value"]
    assert nodes["api"]["memoryPerRpsMb"] == v["apiMemoryPerRpsMb"]["value"]
    assert nodes["api"]["memoryPerRequestMb"] == engine_functions.min_per_request_mb()


def fake_inputs() -> dict[str, Any]:
    return {
        "serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.18, "p95": 0.4}},
        "clientLinkLatencyMs": {"value": 0.05},
        "readServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.5, "p95": 1.0}},
        "writeServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.7, "p95": 1.4}},
        "commitFlushMs": {"value": {"kind": "lognormal", "median": 1.0, "p95": 2.0}},
    }


def test_no_memory_inputs_from_calibrations_without_readings() -> None:
    # every kept calibration before the v6 campaign predates W24: no working set in its passes,
    # so no memory inputs, and its design keeps ArchSim's defaults exactly
    for path in (
        "pg-mixed/run-10",
        "pg-hot-row/run-8",
        "cache-aside/run-5",
        "kafka/run-5",
        "pg-mixed/run-6",
    ):
        cal = calibration(path)
        for s in replicate_sets(cal):
            assert run.memory_inputs(run.EXPERIMENTS[path.split("/")[0]], s) == {}
    api = run.service_node({"serviceTimeMs": 1.0})["config"]
    assert not set(run.MEMORY_KEYS) & set(api)


def test_one_point_or_one_rate_fits_nothing() -> None:
    one = {"staircase": staircase("api", [400.0, 400.0], lambda r: 60.0)}
    assert run.memory_inputs(PG_MIXED, one) == {}
    # rest and one rate: a staircase that never rose (the host was busy) fits nothing either
    stuck = {**one, "idle": {"workingSetMiB": {"api": 24.0}}}
    assert run.memory_inputs(PG_MIXED, stuck) == {}


def test_calibrated_memory_moves_no_latency() -> None:
    cal = calibration("pg-mixed")
    cal["inputs"] = {k: v for k, v in cal["inputs"].items() if "Memory" not in k}  # the defaults
    memory = {
        "serviceMemoryBaselineMb": {"value": 40.0},
        "serviceMemoryPerRpsMb": {"value": 0.03},
        "serviceMemoryPerRequestMb": {"value": engine_functions.min_per_request_mb()},
    }
    plain = run.simulate(run.design(PG_MIXED, cal["inputs"], 1500, warm=2, duration=5))
    fitted = run.simulate(
        run.design(PG_MIXED, {**cal["inputs"], **memory}, 1500, warm=2, duration=5)
    )
    for k in ("p50", "p95", "p99", "mean", "successRate", "throughputRps"):
        assert fitted[k] == plain[k], k
    assert plain["resources"]["api"]["memoryMiB"] > 256  # ArchSim's default at idle
    # 40 MiB + 0.03 MiB x the busiest rate (about 1,500 req/s) + 0.01 MiB per request held
    assert fitted["resources"]["api"]["memoryMiB"] == pytest.approx(85, abs=3)


# ---------------------------------------------------------------------------------------------
# calibration reads the working set
# ---------------------------------------------------------------------------------------------


def test_idle_pass_records_each_working_set(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run.time, "sleep", lambda s: None)
    monkeypatch.setattr(run, "container", lambda service: f"id-{service}")
    monkeypatch.setattr(run, "cpu_usec", lambda service: 1_000_000)
    monkeypatch.setattr(run, "http_json", lambda url, method="GET": {"cpuMs": 0.0})
    monkeypatch.setattr(
        run, "read_cgroup", lambda cid: run.Reading(time.time(), 1, 42 * 2**20, 40 * 2**20)
    )
    monkeypatch.setattr(run, "cpu_ms", lambda a, b: 0.0)
    idle = run.idle_pass(PG_MIXED)
    assert idle["workingSetMiB"] == {"api": 42.0, "postgres": 42.0}


def test_calibration_pass_samples_from_the_start_of_the_recorded_load(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seen: dict[str, Any] = {}

    class Sampler:
        def __init__(self, services: list[str], warm: float, duration: float) -> None:
            seen.update(services=services, warm=warm, duration=duration)

        def __enter__(self) -> Sampler:
            return self

        def __exit__(self, *exc: object) -> None:
            pass

        def result(self) -> dict[str, Any]:
            return {"method": "window", "containers": {"api": {"workingSetMiB": 50.0}}}

    monkeypatch.setattr(run, "ResourceSampler", Sampler)
    monkeypatch.setattr(run, "k6", lambda *a, **k: {})
    monkeypatch.setattr(run, "k6_measured", lambda summary, duration: {"p50": 1.0})
    monkeypatch.setattr(run, "http_json", lambda url, method="GET": {"requests": 1})
    monkeypatch.setattr(run, "cpu_usec", lambda service: 0)
    monkeypatch.setattr(run.time, "sleep", lambda s: None)
    entry = run.calibration_pass(PG_MIXED, PG_MIXED.mix, 400, "up 2")
    assert seen == {"services": ["api", "postgres"], "warm": 0, "duration": run.CALIBRATION_S}
    assert entry["resources"]["containers"]["api"]["workingSetMiB"] == 50.0


def test_calibrate_hands_the_staircase_to_the_fit_and_saves_it_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fake_pass(mix: str, rate: float) -> dict[str, Any]:
        n = round(rate * run.CALIBRATION_S)

        def t(mean: float) -> dict[str, float]:
            return {
                "count": n,
                "mean": mean,
                "median": mean * 0.9,
                "p95": mean * 2,
                "p99": mean * 3,
            }

        return {
            "mix": mix,
            "rateRps": rate,
            "k6": {"p50": 1.0, "p95": 2.0, "p99": 3.0, "throughputRps": rate},
            "service": {
                "cpuMs": 0.3 * n,
                "cpuMsPerRequest": 0.3,
                "requests": n,
                "timings": {
                    n_: t(m)
                    for n_, m in (
                        ("own", 0.02),
                        ("handler", 0.8),
                        ("db_read", 0.5),
                        ("db_write", 2.0),
                        ("db_write_async", 0.7),
                    )
                },
            },
            "containerCpuMs": {"api": 0.3 * n, "postgres": 0.15 * n},
            "resources": {"containers": {"api": {"workingSetMiB": 40.0 + 0.05 * rate}}},
        }

    idle = {
        "seconds": run.IDLE_S,
        "containerCpuMsPerSecond": {"api": 0.0, "postgres": 0.0},
        "serviceCpuMsPerSecond": 0.0,
        "workingSetMiB": {"api": 40.0},
    }
    monkeypatch.setattr(run, "settle", lambda exp: {"at": "t"})
    monkeypatch.setattr(run, "idle_pass", lambda exp: idle)
    monkeypatch.setattr(run, "steady_pass", lambda exp, mix, rate, label, d: fake_pass(mix, rate))
    monkeypatch.setattr(run, "anchor", lambda exp, inputs, high: None)
    cal = run.calibrate(PG_MIXED)
    assert all("staircase" not in r for r in cal["replicates"])  # once, at the top level
    assert cal["inputs"]["serviceMemoryPerRpsMb"]["value"] == pytest.approx(0.05, abs=1e-6)
    # and derived again from the saved file, the same memory (every replicate fits the same
    # staircase, so the median is any of them)
    again = run.memory_inputs(PG_MIXED, replicate_sets(cal)[0])
    assert {k: v["value"] for k, v in again.items()} == {
        k: v["value"] for k, v in cal["inputs"].items() if "Memory" in k
    }


# ---------------------------------------------------------------------------------------------
# the W24 trial: memory fitted from calibration passes only, against the latest kept runs
# ---------------------------------------------------------------------------------------------

TRIAL = run.RESULTS / "trials"
SERVICE = {"pg-mixed": "api", "pg-hot-row": "api", "cache-aside": "api", "kafka": "producer"}
BASELINE = {
    "pg-mixed": "pg-mixed/run-10",
    "pg-hot-row": "pg-hot-row/run-8",
    "cache-aside": "cache-aside/run-5",
    "kafka": "kafka/run-5",
}
"""Each experiment's latest run of the baseline campaign (v5, engine 0.14.0, memory defaults)."""


@pytest.mark.parametrize("exp_id", list(SERVICE))
def test_memory_fitted_in_the_trial_is_closer_than_the_defaults(exp_id: str) -> None:
    # 31 Oct trial: calibration passes only, nothing measured. Against the baseline campaign's
    # latest run's steps at or below both knees (predicted with the defaults), the fit is within 66% of the measured working set at every
    # step where ArchSim's defaults (256 MiB + 2 MiB per request held) were 59% to 485% high.
    # It is low at the top step (-25% to -65%): the staircase stops at a third of the knee, and
    # V8's heap grows faster than the rate beyond it, which a line through the staircase misses.
    cal = json.loads((TRIAL / f"2026-10-31-{exp_id}-w24" / "calibration.json").read_text("utf-8"))
    fit = run.memory_inputs(
        run.EXPERIMENTS[exp_id], {"idle": cal["idle"], "staircase": cal["staircase"]}
    )
    # the trial's own inputs are this fit (every replicate fits the same staircase)
    assert {k: v["value"] for k, v in fit.items()} == {
        k: v["value"] for k, v in cal["inputs"].items() if "Memory" in k
    }
    base, per_rps = fit["serviceMemoryBaselineMb"]["value"], fit["serviceMemoryPerRpsMb"]["value"]
    cmp_ = json.loads((run.RESULTS / BASELINE[exp_id] / "comparison.json").read_text("utf-8"))
    rows = run.at_or_below_both_knees(cmp_["rows"], cmp_["measuredKnee"], cmp_["predictedKnee"])
    cells = [(r["offeredRps"], r["resources"][SERVICE[exp_id]]) for r in rows]
    assert len(cells) >= 3
    before = [c["error"]["memory"] for _, c in cells]
    after = [(base + per_rps * rps) / c["measured"]["memoryMiB"] - 1 for rps, c in cells]
    assert min(before) > 0.5  # the defaults: far too high everywhere
    assert max(abs(e) for e in after) < 0.7
    assert -0.7 < after[-1] < -0.2  # low at the top step: the known limit
