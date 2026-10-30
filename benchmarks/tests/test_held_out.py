"""The held-out experiments (W19): their registration and seal, the smoke test, their models
(per-part inputs and node builders), their CPU layout, the report's labels, the k6 sampler for a
large keyspace and the Python service's statistics shared across worker processes."""

from __future__ import annotations

import gc
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
import report
import run

HELD_OUT = ["java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl"]
TUNED_ON = ["pg-mixed", "pg-hot-row", "cache-aside", "kafka"]
TREE = "f" * 40
CLEAN = {"engineTree": TREE, "dirty": False}
FROZEN = [{"version": "0.15.0", "engineTree": TREE, "frozenAt": "2026-11-05", "tag": "t"}]
LAPTOP_SIBLINGS = {c: [c - c % 2, c - c % 2 + 1] for c in range(8)}


# ---------------------------------------------------------------------------------------------
# registration and the seal
# ---------------------------------------------------------------------------------------------


def test_the_held_out_experiments_are_registered() -> None:
    held = [e for e, x in run.EXPERIMENTS.items() if x.held_out is not None]
    assert held == HELD_OUT == report.HELD_OUT
    for e in HELD_OUT:
        x = run.EXPERIMENTS[e]
        assert x.held_out is not None and x.held_out.registered == "2026-10-30"
        assert run.STACKS[x.stack] == [e]  # a stack of its own
    for e in TUNED_ON:
        assert run.EXPERIMENTS[e].held_out is None
    # pg-mixed's workload on three more runtimes; chain the same through a gateway
    for e in ("java-mixed", "go-mixed", "python-mixed", "chain"):
        x, pg = run.EXPERIMENTS[e], run.EXPERIMENTS["pg-mixed"]
        assert (x.mix, x.read_ratio, x.keys, x.skew, x.calibration) == (
            pg.mix,
            pg.read_ratio,
            pg.keys,
            pg.skew,
            pg.calibration,
        )
    assert [run.EXPERIMENTS[e].runtime for e in HELD_OUT] == [
        "java",
        "go",
        "python",
        "nodejs",
        "nodejs",
    ]
    ttl = run.EXPERIMENTS["cache-ttl"]
    assert (ttl.mix, ttl.keys, ttl.skew, ttl.ttl_s) == ("cached:1", 1_000_000, 1.1, 5)


def test_engines_json_has_no_frozen_engine_yet() -> None:
    entries = report.engines()
    assert run.frozen_engines() == []
    assert {"version", "engineTree", "frozenAt", "tag", "tunedOn"} <= set(entries[0])
    assert entries[0]["tunedOn"] == TUNED_ON
    assert not any(set(e["tunedOn"]) & set(HELD_OUT) for e in entries)


def test_frozen_engines_need_a_date_a_tag_and_a_tree(tmp_path: Path) -> None:
    path = tmp_path / "engines.json"
    path.write_text(
        json.dumps(
            [
                {"version": "a", "engineTree": TREE, "frozenAt": None, "tag": None},
                {"version": "b", "engineTree": TREE, "frozenAt": "2026-11-01", "tag": None},
                {"version": "c", "engineTree": TREE, "frozenAt": "2026-11-01", "tag": "x"},
            ]
        ),
        encoding="utf-8",
    )
    assert [e["version"] for e in run.frozen_engines(path)] == ["c"]
    assert run.frozen_engines(tmp_path / "missing.json") == []


def test_seal() -> None:
    java = run.EXPERIMENTS["java-mixed"]
    why = run.seal(java, CLEAN, [])
    assert why and "held-out" in why and "not frozen" in why and "run.py smoke java-mixed" in why
    assert run.seal(java, CLEAN, FROZEN) is None
    other = {**CLEAN, "engineTree": "e" * 40}
    assert "not frozen" in (run.seal(java, other, FROZEN) or "")
    for dirty in (True, None):  # uncommitted changes, or git could not tell
        assert "uncommitted" in (run.seal(java, {**CLEAN, "dirty": dirty}, FROZEN) or "")
    for e in TUNED_ON:  # never sealed
        assert run.seal(run.EXPERIMENTS[e], {"engineTree": None, "dirty": True}, []) is None


def test_run_experiment_refuses_a_sealed_experiment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[str] = []

    def calibrate(exp: run.Experiment) -> dict[str, Any]:
        calls.append(exp.id)
        raise RuntimeError("calibration started")

    monkeypatch.setattr(run, "calibrate", calibrate)
    monkeypatch.setattr(run, "provenance", lambda: dict(CLEAN))
    engines = tmp_path / "engines.json"
    monkeypatch.setattr(run, "ENGINES", engines)
    # the committed engines.json: nothing is frozen, so nothing is calibrated or measured
    engines.write_text((run.HERE / "engines.json").read_text(encoding="utf-8"), encoding="utf-8")
    for e in HELD_OUT:
        with pytest.raises(SystemExit, match="held-out experiment"):
            run.run_experiment(run.EXPERIMENTS[e])
    assert calls == []
    # a frozen engine, but uncommitted changes
    engines.write_text(json.dumps(FROZEN), encoding="utf-8")
    monkeypatch.setattr(run, "provenance", lambda: {**CLEAN, "dirty": True})
    with pytest.raises(SystemExit, match="uncommitted"):
        run.run_experiment(run.EXPERIMENTS["chain"])
    assert calls == []
    # a frozen engine and a clean tree: it goes ahead
    monkeypatch.setattr(run, "provenance", lambda: dict(CLEAN))
    with pytest.raises(RuntimeError, match="calibration started"):
        run.run_experiment(run.EXPERIMENTS["chain"])
    assert calls == ["chain"]


def test_the_runner_refuses_before_starting_a_sealed_stack(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def no_docker(*a: Any, **k: Any) -> Any:
        raise AssertionError("Docker was called")

    monkeypatch.setattr(run, "stack_up", no_docker)
    monkeypatch.setattr(run, "environment", no_docker)
    monkeypatch.setattr(run, "provenance", lambda: dict(CLEAN))
    monkeypatch.setattr(run, "frozen_engines", lambda path=None: [])
    with pytest.raises(SystemExit, match="held-out experiment"):
        run.main(["go-mixed"])
    with pytest.raises(SystemExit, match="held-out experiment"):
        run.runnable(["cache-ttl"])
    # all: the sealed stacks are left out, the others run
    assert run.runnable(list(run.STACKS), refuse=False) == {
        "postgres": ["pg-mixed", "pg-hot-row"],
        "cache": ["cache-aside"],
        "kafka": ["kafka"],
    }
    assert run.runnable(["postgres"], only="pg-hot-row") == {"postgres": ["pg-hot-row"]}
    monkeypatch.setattr(run, "frozen_engines", lambda path=None: FROZEN)
    assert run.runnable(["chain"]) == {"chain": ["chain"]}


def test_smoke_needs_an_experiment() -> None:
    for argv in (["smoke"], ["postgres", "pg-mixed"], ["smoke", "nope"]):
        with pytest.raises(SystemExit):
            run.main(argv)


# ---------------------------------------------------------------------------------------------
# smoke tests
# ---------------------------------------------------------------------------------------------


def k6_result(sent: int = 200, succeeded: int | None = None, dropped: int = 0) -> dict[str, Any]:
    ok = sent if succeeded is None else succeeded
    return {"sent": sent, "succeeded": ok, "failed": sent - ok, "dropped": dropped}


def counted(n: int, **timings: int) -> dict[str, Any]:
    return {
        "requests": n,
        "cpuMs": 12.5,
        "counters": {},
        "timings": {k: {"count": v} for k, v in {"handler": n, **timings}.items()},
    }


def test_smoke_problems() -> None:
    java = run.EXPERIMENTS["java-mixed"]
    good = {**counted(200, db_read=181, db_write=19), "gc": {"collections": 0, "pauseMs": 0.0}}
    assert run.smoke_problems(java, k6_result(), good) == []
    no_gc = {k: v for k, v in good.items() if k != "gc"}
    assert run.smoke_problems(java, k6_result(), no_gc) == [
        "the service reported no garbage collection counts"
    ]
    python = run.EXPERIMENTS["python-mixed"]
    assert run.smoke_problems(python, k6_result(), {**good, "workers": 10}) == []
    assert run.smoke_problems(python, k6_result(), {**good, "workers": 9}) == [
        "9 gunicorn workers hold a statistics slot, not 10"
    ]
    assert run.smoke_problems(java, k6_result(succeeded=197), good) == ["3 of 200 requests failed"]
    assert "k6 dropped 4 requests" in run.smoke_problems(java, k6_result(dropped=4), good)[0]
    assert "k6 sent 120 requests" in run.smoke_problems(java, k6_result(120), good)[0]
    short = run.smoke_problems(java, k6_result(), counted(199, db_read=180, db_write=19))
    assert "the service counted 199 requests, k6 sent 200" in short
    assert "PostgreSQL calls timed: 199, k6 sent 200" in short
    assert "reported no CPU time" in run.smoke_problems(java, k6_result(), {**good, "cpuMs": 0})[0]


def test_smoke_problems_on_chain_and_cache_ttl() -> None:
    chain = run.EXPERIMENTS["chain"]
    gateway = {**counted(200, upstream=200), "upstream": counted(200, db_read=180, db_write=20)}
    assert run.smoke_problems(chain, k6_result(), gateway) == []
    lost = {**gateway, "upstream": counted(150, db_read=140, db_write=10)}
    found = run.smoke_problems(chain, k6_result(), lost)
    assert "the API behind the gateway counted 150 requests, k6 sent 200" in found
    assert "PostgreSQL calls timed: 150, k6 sent 200" in found
    ttl = run.EXPERIMENTS["cache-ttl"]
    hits = {"cache_hit:measure": 120, "cache_miss:measure": 80}
    redis = {"get": {"calls": 200, "usec": 1200}, "set": {"calls": 80, "usec": 500}}
    ok = {**counted(200), "counters": hits, "redis": redis}
    assert run.smoke_problems(ttl, k6_result(), ok) == []
    assert run.smoke_problems(ttl, k6_result(), {**ok, "counters": {}}) == [
        "the cache counted 0 hits and misses, k6 sent 200"
    ]
    # INFO commandstats not parsed (its lines end in CR LF): no GET counted
    assert run.smoke_problems(ttl, k6_result(), {**ok, "redis": {}}) == [
        "Redis counted None GET commands, k6 sent 200"
    ]


def test_smoke_keeps_nothing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    results, k6_out = tmp_path / "results", tmp_path / "results" / ".k6"
    k6_out.mkdir(parents=True)
    monkeypatch.setattr(run, "RESULTS", results)
    monkeypatch.setattr(run, "K6_OUT", k6_out)
    events: list[str] = []
    monkeypatch.setattr(run, "stack_up", lambda stack: events.append(f"up {stack}"))
    monkeypatch.setattr(run, "stack_down", lambda stack: events.append(f"down {stack}"))
    monkeypatch.setattr(run, "compose", lambda *a, **k: "")
    measure = "{scenario:measure}"
    summary = {
        "http_req_duration{scenario:measure,expected_response:true}": {
            "values": {"med": 3.0, "p(95)": 9.0, "count": 200}
        },
        f"http_reqs{measure}": {"values": {"count": 200}},
        f"http_req_failed{measure}": {"values": {"passes": 0}},
        f"dropped_iterations{measure}": {"values": {"count": 0}},
    }

    def k6(
        target: str, mix: str, rate: float, warm: int, duration: int, out: str, *a: Any
    ) -> dict[str, Any]:
        events.append(f"k6 {target} {mix} {rate:g}/s for {duration} s, warm-up {warm} s")
        (k6_out / f"{out}.json").write_text(json.dumps(summary), encoding="utf-8")
        return summary

    def http_json(url: str, method: str = "GET") -> dict[str, Any]:
        events.append(f"{method} {url.rsplit('/', 1)[1]}")
        if not url.endswith("/stats"):
            return {}
        return {**counted(200, db_read=181, db_write=19), "gc": {"collections": 1}, "workers": 10}

    monkeypatch.setattr(run, "k6", k6)
    monkeypatch.setattr(run, "http_json", http_json)
    assert run.smoke(run.EXPERIMENTS["python-mixed"]) == []
    assert events == [
        "up python-mixed",
        "POST reset",
        "k6 http://api-python:8080 read:0.9,write:0.1 20/s for 10 s, warm-up 0 s",
        "GET stats",
        "down python-mixed",
    ]
    assert [p for p in results.rglob("*") if p.is_file()] == []  # no latency, no results


# ---------------------------------------------------------------------------------------------
# the models: per-part inputs and node builders
# ---------------------------------------------------------------------------------------------


def stats(
    cpu_ms: float, requests: int, timings: dict[str, tuple[float, float]], **extra: Any
) -> dict[str, Any]:
    """A service's /stats: ``timings`` maps a name to (median, p95); the mean is 10% above the
    median."""
    return {
        "requests": requests,
        "cpuMs": cpu_ms,
        "cpuMsPerRequest": cpu_ms / requests,
        "counters": {},
        "timings": {
            n: {"count": requests, "mean": m * 1.1, "median": m, "p95": p, "p99": p * 1.5}
            for n, (m, p) in timings.items()
        },
        **extra,
    }


def entry(rate: float, p50: float, service: dict[str, Any], pg_cpu_ms: float) -> dict[str, Any]:
    return {
        "rateRps": rate,
        "k6": {"p50": p50, "p95": p50 * 2, "p99": p50 * 3, "successRate": 1.0},
        "service": service,
        "containerCpuMs": {"postgres": pg_cpu_ms},
    }


DB = {"db_read": (0.6, 1.2), "db_write": (2.0, 4.0)}
GC = {"collections": 6, "pauseMs": 18.0}


def passes_for(exp: run.Experiment) -> dict[str, Any]:
    """Two calibration passes and the auxiliary ones, shaped as calibration_pass records them."""

    def service(n: int, cpu: float) -> dict[str, Any]:
        own = {"handler": (1.2, 3.0), "own": (0.3, 0.9)}
        if exp.stack == "chain":
            api = stats(cpu * 0.8, n, {**own, **DB})
            return stats(cpu, n, {**own, "upstream": (1.0, 2.5)}, upstream=api)
        if exp.stack == "cache-ttl":
            redis = {"get": {"calls": n, "usec": n * 3.0}, "set": {"calls": n // 3, "usec": n}}
            t = {**own, "redis_get": (0.2, 0.5), "redis_set": (0.2, 0.5), "db_read": (0.6, 1.2)}
            return stats(cpu, n, t, redis=redis)
        extra = {"gc": GC} if exp.runtime in ("java", "go") else {}
        return stats(cpu, n, {**own, **DB}, **extra)

    out = {
        "low": entry(50, 2.0, service(1500, 900.0), 300.0),
        "high": entry(400, 2.1, service(12000, 4500.0), 2100.0),
    }
    if "write_async:1" in exp.calibration:
        no_flush = stats(30.0, 100, {"db_write_async": (0.8, 1.5)})
        if exp.stack == "chain":
            no_flush = stats(30.0, 100, {}, upstream=no_flush)
        out["write_async:1"] = entry(400, 2.0, no_flush, 50.0)
    return out


@pytest.mark.parametrize("exp_id", HELD_OUT)
def test_held_out_inputs(exp_id: str) -> None:
    exp = run.EXPERIMENTS[exp_id]
    inputs = run.model_inputs(exp, passes_for(exp))
    for v in inputs.values():
        assert set(v) >= ({"value", "how"} if "how" in v else set())
    # CPU per request: the slope between the passes, (4500 - 900) / (12000 - 1500)
    assert "0.3429 ms" in inputs["serviceTimeMs"]["how"]
    if exp.stack in run.DB_STACKS:
        assert inputs["readServiceTimeMs"]["value"]["median"] == 0.6
        assert inputs["writeServiceTimeMs"]["value"]["median"] == 0.8
        assert inputs["commitFlushMs"]["value"]["median"] == 1.2  # 2.0 - 0.8
        assert inputs["dbCpuShare"]["value"] > 0
    if exp.runtime in ("java", "go"):
        # 18 ms over 6 collections: a 3 ms mean, a median of 3 / 1.17; 12,000 requests / 6
        assert inputs["gcPauseMs"]["value"] == pytest.approx(3 / 1.1677, rel=1e-3)
        assert inputs["gcEveryRequests"]["value"] == 2000
    elif exp.runtime == "python":
        assert inputs["gcPauseMs"]["value"] == 0 and "gcEveryRequests" not in inputs
    else:
        assert "gcPauseMs" not in inputs
    if exp.stack == "chain":
        # the API's slope: (3600 - 720) / (12000 - 1500); the link: (1.0 - 1.2) / 2, at least 0
        assert "0.2743 ms" in inputs["apiServiceTimeMs"]["how"]
        assert inputs["apiLinkLatencyMs"]["value"] == 0.0
    if exp.stack == "cache-ttl":
        # Redis's own time from the service's /stats: (36,000 + 12,000) us / 16,000 calls
        assert inputs["cacheServiceTimeMs"]["value"]["mean"] == 0.003


def test_gc_inputs_without_a_collection() -> None:
    java = run.EXPERIMENTS["java-mixed"]
    none = run.gc_inputs(java, {"requests": 100, "gc": {"collections": 0, "pauseMs": 0.0}})
    assert none["gcPauseMs"]["value"] == 0 and "gcEveryRequests" not in none
    assert run.gc_inputs(java, {"requests": 100})["gcPauseMs"]["value"] == 0


@pytest.mark.parametrize("exp_id", HELD_OUT)
def test_held_out_designs(exp_id: str) -> None:
    exp = run.EXPERIMENTS[exp_id]
    inputs = run.model_inputs(exp, passes_for(exp))
    inputs["clientLinkLatencyMs"]["value"] = 0.2
    doc = run.design(exp, inputs, 100, warm=1, duration=3)
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    edges = [(e["source"], e["target"]) for e in doc["architecture"]["edges"]]
    api = nodes["api"]["config"]
    wl = doc["workload"]
    assert (wl["keyCount"], wl["keySkew"], wl["readRatio"]) == (exp.keys, exp.skew, exp.read_ratio)
    if exp.runtime in ("java", "go", "python"):
        workers = {"java": 200, "go": 4096, "python": 10}[exp.runtime]
        assert api["technology"] == exp.runtime
        assert (api["workersPerReplica"], api["cpuCores"], api["blockingDownstream"]) == (
            workers,
            2,
            True,
        )
        assert api["gcPauseMs"] == inputs["gcPauseMs"]["value"]
        assert "processesPerReplica" not in api
    else:
        assert (api["technology"], api["processesPerReplica"], api["cpuCores"]) == ("nodejs", 1, 1)
    if exp.stack == "chain":
        gateway = nodes["gateway"]["config"]
        assert gateway["serviceTimeMs"] == inputs["serviceTimeMs"]["value"]
        assert api["serviceTimeMs"] == inputs["apiServiceTimeMs"]["value"]
        assert edges == [("clients", "gateway"), ("gateway", "api"), ("api", "db")]
    elif exp.stack == "cache-ttl":
        assert nodes["cache"]["config"]["ttlSeconds"] == 5
        assert edges == [("clients", "api"), ("api", "cache"), ("cache", "db")]
    else:
        assert edges == [("clients", "api"), ("api", "db")]
    r = run.simulate(doc)
    assert r["successRate"] == 1.0 and r["p50"] > 0
    for node in ("api", "db", *(["gateway"] if exp.stack == "chain" else [])):
        assert r["resources"][node]["cores"] > 0
    if exp.stack == "cache-ttl":
        assert 0 < r["cacheHitRatio"] < 1


def test_the_tuned_on_designs_are_unchanged() -> None:
    # the node builders' defaults keep the original four's designs exactly as predicted
    for e in TUNED_ON:
        pred = json.loads((run.RESULTS / e / "predictions.json").read_text(encoding="utf-8"))
        cal = json.loads((run.RESULTS / e / "calibration.json").read_text(encoding="utf-8"))
        knee = pred["knee"]["kneeRps"]
        assert run.design(run.EXPERIMENTS[e], cal["inputs"], knee) == pred["design"], e


def test_container_nodes_and_report_names_cover_every_stack() -> None:
    for stack, services in run.STACK_SERVICES.items():
        for s in services:
            assert s in report.CONTAINERS and s in report.PARTS, (stack, s)
        assert all(e in report.NAMES and e in report.TECHNOLOGIES for e in run.STACKS[stack])
    for c in ("api-java", "api-go", "api-python", "chain-api", "api-ttl"):
        assert run.CONTAINER_NODES[c] == "api"
    assert (run.CONTAINER_NODES["gateway"], run.CONTAINER_NODES["redis-ttl"]) == (
        "gateway",
        "cache",
    )
    assert run.target_for(run.EXPERIMENTS["chain"]) == "http://gateway:8080"
    assert run.target_for(run.EXPERIMENTS["pg-mixed"]) == "http://api:8080"
    assert run.exp_image(run.EXPERIMENTS["java-mixed"]).startswith("eclipse-temurin:21")


def test_the_service_under_test_comes_first() -> None:
    # calibration reads the first container's CPUs against the /stats of the one k6 calls
    for stack, services in run.STACK_SERVICES.items():
        assert services[0] == run.ENTRY.get(stack, "api"), stack
    assert run.cpu_count(run.cpusets("java-mixed", env={})["api-java"]) == 2
    assert run.cpu_count(run.cpusets("chain", env={})["gateway"]) == 1


def test_stack_items() -> None:
    assert run.stack_items("cache-ttl") == 1_000_000
    for stack in run.STACKS:
        if stack != "cache-ttl":
            assert run.stack_items(stack) == run.DEFAULT_ITEMS, stack


def test_the_hosted_runner_overrides_every_cpuset() -> None:
    # benchmark.yml shares the runner's few CPUs between all containers by naming each cpuset
    # variable; a container it misses would keep compose.yaml's default and may not fit
    declared = {var.removeprefix("BENCH_CPUS_") for var, _ in run.compose_cpusets().values()}
    workflow = (run.HERE.parent / ".github" / "workflows" / "benchmark.yml").read_text("utf-8")
    loop = next(ln for ln in workflow.splitlines() if "for name in" in ln and "BENCH_CPUS_" in ln)
    named = set(loop.split("for name in", 1)[1].split(";", 1)[0].split())
    assert declared == named


def test_held_out_stacks_share_no_physical_core() -> None:
    for e in HELD_OUT:
        sets = run.cpusets(run.EXPERIMENTS[e].stack, env={})
        assert run.topology(sets, LAPTOP_SIBLINGS)["sharedCores"] == [], e
    assert run.cpusets("java-mixed", env={}) == {
        "api-java": "2,3",
        "postgres": "4,5",
        "k6": "0,1,6,7",
    }
    assert run.cpusets("chain", env={}) == {
        "gateway": "0",
        "chain-api": "2",
        "postgres": "4,5",
        "k6": "6,7",
    }
    assert run.cpusets("cache-ttl", env={}) == {
        "api-ttl": "2",
        "postgres": "4,5",
        "redis-ttl": "0",
        "k6": "6,7",
    }


# ---------------------------------------------------------------------------------------------
# the report
# ---------------------------------------------------------------------------------------------


def test_sample_label() -> None:
    frozen: list[dict[str, Any]] = [
        {"version": "0.15.0", "frozenAt": "2026-11-05", "tag": "t", "tunedOn": TUNED_ON},
        {"version": "0.16.0", "frozenAt": "2026-12-01", "tag": "u", "tunedOn": ["chain"]},
        {"version": "0.17.0", "frozenAt": None, "tag": None, "tunedOn": []},
    ]
    for e in TUNED_ON:
        assert report.sample_label("0.15.0", e, frozen) == "tuned-on"
    assert report.sample_label("0.15.0", "chain", frozen) == "held-out"
    assert report.sample_label("0.16.0", "chain", frozen) == "tuned-on"  # tuned on it
    assert report.sample_label("0.16.0", "go-mixed", frozen) == "held-out"
    assert report.sample_label("0.17.0", "go-mixed", frozen) == "tuned-on"  # never frozen
    assert report.sample_label("9.9.9", "go-mixed", frozen) == "tuned-on"  # not listed
    assert report.sample_label("0.14.0", "chain") == "tuned-on"  # engines.json: not frozen yet


def test_held_out_experiments_stay_out_of_the_page_until_measured() -> None:
    assert not set(report.ORDER) & set(HELD_OUT)
    data = report.accuracy()
    assert data is not None
    assert [x["id"] for x in data["experiments"]] == TUNED_ON
    assert {x["sample"] for x in data["experiments"]} == {"tuned-on"}
    assert data["summary"]["heldOut"] == []
    assert not set(data["history"]) & set(HELD_OUT)


# ---------------------------------------------------------------------------------------------
# the k6 sampler for a large keyspace, and the Python service's shared statistics
# ---------------------------------------------------------------------------------------------

ZIPF_CHECK = r"""
const src = require("fs").readFileSync(process.argv[1], "utf8");
const part = src.slice(src.indexOf("const helper1"), src.indexOf("function sampleKey"));
const out = [];
for (const [KEYS, SKEW] of [[1000000, 1.1], [1000000, 0.8], [1000000, 1.0]]) {
  const sample = new Function("KEYS", "SKEW", part + "; return sampleRejectionInversion;")(KEYS, SKEW);
  let total = 0;
  for (let k = 1; k <= KEYS; k++) total += Math.pow(k, -SKEW);
  const bins = [[1, 1], [2, 10], [11, 1000], [1001, 100000], [100001, KEYS]];
  const exact = bins.map(([a, b]) => { let s = 0; for (let k = a; k <= b; k++) s += Math.pow(k, -SKEW); return s / total; });
  const n = 400000, counts = bins.map(() => 0);
  for (let i = 0; i < n; i++) { const k = sample(); counts[bins.findIndex(([a, b]) => k >= a && k <= b)]++; }
  out.push(Math.max(...bins.map((_, j) => Math.abs(counts[j] / n - exact[j]) / Math.sqrt(exact[j] * (1 - exact[j]) / n))));
}
console.log(JSON.stringify(out));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js")
def test_k6_samples_a_large_keyspace_from_the_exact_zipf_distribution() -> None:
    script = run.HERE / "k6" / "load.js"
    proc = subprocess.run(
        ["node", "-e", ZIPF_CHECK, str(script)],
        capture_output=True,
        text=True,
        check=True,
        timeout=120,
    )
    worst_z = json.loads(proc.stdout)
    assert max(worst_z) < 4.5, worst_z  # every bin within 4.5 standard errors of exact


@pytest.fixture
def shared_stats(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Any:
    monkeypatch.syspath_prepend(str(run.HERE / "service-python"))
    sys.modules.pop("shared_stats", None)
    import shared_stats as module

    monkeypatch.setattr(module, "PATH", tmp_path / "stats")
    before = list(gc.callbacks)
    yield module
    gc.callbacks[:] = before
    sys.modules.pop("shared_stats", None)


def test_python_stats_add_up_every_worker(shared_stats: Any) -> None:
    shared_stats.create()
    workers = [shared_stats.Shared() for _ in range(3)]
    for w in workers:
        w.claim()
    assert [w.slot for w in workers] == [0, 1, 2]
    for i, w in enumerate(workers):
        for j in range(4):
            w.record("db_read", 1.0 + i)
            w.request(2.0 + i + j, 1.0)
    s = workers[0].snapshot()
    assert s["requests"] == 12 and s["workers"] == 3
    assert s["timings"]["db_read"]["count"] == 12
    assert s["timings"]["db_read"]["median"] == 2.0  # 4 x 1.0, 4 x 2.0, 4 x 3.0
    assert s["timings"]["handler"]["p95"] == 7.0
    assert s["timings"]["own"]["mean"] == 1.0
    assert "db_write" not in s["timings"]
    workers[2].reset()  # any worker's /reset clears them all
    s = workers[1].snapshot()
    assert s["requests"] == 0 and s["timings"] == {}
    late = shared_stats.Shared()  # a fourth worker takes the next slot
    late.claim()
    assert late.slot == 3
