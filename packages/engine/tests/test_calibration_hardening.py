"""Calibration against hostile or awkward input, and the review fixes that keep its answers
honest: mappings are text (never a path), errors repeat no input, trace parent links that loop,
a Prometheus client with one deadline and a size cap, "measured" only while the value is the
fitted one, the run's duration left alone, histogram tables that end where the data ends."""

from __future__ import annotations

import contextlib
import copy
import http.server
import json
import math
import random
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from archsim_engine import (
    analyze_document,
    create_default_registry,
    load_document,
    run_simulation,
)
from archsim_engine.calibration import (
    Histogram,
    HttpPrometheus,
    MappingError,
    PrometheusError,
    RecordedPrometheus,
    calibrate_otlp,
    calibrate_prometheus,
    fit_histogram,
    load_mapping,
    load_mapping_file,
    load_otlp_mapping,
    replay_summary,
    summarize,
)
from archsim_engine.calibration.apply import parse_time
from archsim_engine.calibration.otlp import Span
from archsim_engine.calibration.prometheus import host_allowed
from archsim_engine.cli import main as cli
from archsim_engine.distributions import Empirical
from archsim_engine.engine import SimulationLimitError, SimulationLimits
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "calibration"
STARTER = EXAMPLES / "cache-aside-starter.json"
START = 1790812800  # 2026-10-01T00:00:00Z
RATE_Q = "sum(rate(http_requests_total[1m]))"


def starter(**workload: Any) -> dict[str, Any]:
    doc: dict[str, Any] = json.loads(STARTER.read_text(encoding="utf-8"))
    doc["workload"].update(workload)
    return doc


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, UTC).isoformat().replace("+00:00", "Z")


def matrix(values: list[list[Any]], metric: dict[str, str] | None = None) -> dict[str, Any]:
    return {
        "status": "success",
        "data": {"resultType": "matrix", "result": [{"metric": metric or {}, "values": values}]},
    }


def rate_recording(rate: float, span_s: int, step: int = 60, **extra: Any) -> dict[str, Any]:
    """A recording of a flat request rate over ``span_s`` seconds (plus ``extra`` responses)."""
    values = [[START + t, f"{rate:.3f}"] for t in range(0, span_s + 1, step)]
    return {
        "source": "test recording",
        "range": {"start": iso(START), "end": iso(START + span_s), "step": step},
        "responses": {RATE_Q: matrix(values), **extra},
    }


def calibrated_starter() -> dict[str, Any]:
    return calibrate_prometheus(
        starter(),
        load_mapping_file(FIXTURES / "starter-mapping.yaml"),
        RecordedPrometheus.load(FIXTURES / "starter-prometheus.json"),
        REGISTRY,
        replay=False,
    ).document


# -- the mapping is text, never a path; errors repeat no input ---------------------------------


def test_a_mapping_string_is_text_never_a_path(tmp_path: Path) -> None:
    secret = tmp_path / "secret.yaml"
    secret.write_text("nodes: TOPSECRET-file-content-41\n", encoding="utf-8")
    # one line naming an existing file: parsed as YAML text (a plain string), not opened
    with pytest.raises(MappingError) as caught:
        load_mapping(str(secret))
    assert "TOPSECRET" not in str(caught.value)
    assert "must be a YAML or JSON object" in str(caught.value)
    with pytest.raises(TypeError, match="load_mapping_file"):
        load_mapping(secret)  # type: ignore[arg-type]
    # reading a file is explicit (the CLI and MCP do it for paths their user names)
    with pytest.raises(MappingError) as caught:
        load_mapping_file(secret)
    assert "nodes" in str(caught.value) and "TOPSECRET" not in str(caught.value)


def test_mapping_errors_name_the_place_not_the_value() -> None:
    with pytest.raises(MappingError) as caught:
        load_mapping("nodes: {api: {serviceTimeMs: {histogram: q, unit: SECRET-UNIT-77}}}")
    text = str(caught.value)
    assert "SECRET-UNIT-77" not in text and "input_value" not in text
    assert "nodes.api.serviceTimeMs" in text
    with pytest.raises(MappingError) as caught:
        load_mapping("nodes: {api: [SECRET-in-a-broken-line\n")
    assert "not valid YAML" in str(caught.value) and "SECRET" not in str(caught.value)
    with pytest.raises(ValueError, match="services") as caught:
        load_otlp_mapping("services: [SECRET-LIST]")
    assert "SECRET" not in str(caught.value)


# -- OTLP: parent links that loop ---------------------------------------------------------------


def _span(span_id: str, parent: str | None, kind: str = "CLIENT", start_s: float = 0) -> Span:
    start = int(start_s * 1e9)
    return Span("t1", span_id, parent, "api", "op", kind, start, start + 1_000_000, {})


def _within(seconds: float, fn: Callable[[], Any]) -> Any:
    """``fn()``'s result, failing (not hanging) when it takes longer than ``seconds``."""
    box: dict[str, Any] = {}
    worker = threading.Thread(target=lambda: box.update(result=fn()), daemon=True)
    worker.start()
    worker.join(seconds)
    assert not worker.is_alive(), f"still running after {seconds} s (a loop?)"
    return box["result"]


def test_otlp_parent_links_that_loop_end() -> None:
    spans = [
        _span("self", "self"),  # its own parent
        _span("a", "b"),  # a 2-cycle
        _span("b", "a"),
        _span("srv", None, "SERVER"),
        _span("ok", "srv", start_s=59),  # the export spans a minute: a slow replay rate
    ]
    summary = _within(10, lambda: summarize(spans))
    assert summary.parent_loops == 3
    assert summary.services["api"]  # the well-formed SERVER span still counts

    def export(items: list[Span]) -> dict[str, Any]:
        return {
            "resourceSpans": [
                {
                    "resource": {
                        "attributes": [{"key": "service.name", "value": {"stringValue": "api"}}]
                    },
                    "scopeSpans": [
                        {
                            "spans": [
                                {
                                    "traceId": s.trace_id,
                                    "spanId": s.span_id,
                                    "parentSpanId": s.parent_id or "",
                                    "name": s.name,
                                    "kind": s.kind,
                                    "startTimeUnixNano": str(s.start_ns),
                                    "endTimeUnixNano": str(s.end_ns),
                                }
                                for s in items
                            ]
                        }
                    ],
                }
            ]
        }

    out = _within(60, lambda: calibrate_otlp(starter(), export(spans), REGISTRY))
    assert any("loop back on themselves" in n for n in out.notes)


# -- the Prometheus client: one deadline, a size cap, clean errors, an allowlist ---------------


@contextmanager
def serve(handler: Callable[[http.server.BaseHTTPRequestHandler], None]) -> Iterator[str]:
    """A local HTTP server answering every GET with ``handler``; yields its base URL."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            with contextlib.suppress(OSError):  # the client gave up (the slow cases)
                handler(self)

        def log_message(self, *args: Any) -> None:
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def _trickle(chunks: list[bytes], pause: float) -> Callable[[Any], None]:
    def handle(h: Any) -> None:
        for c in chunks:
            h.wfile.write(c)
            h.wfile.flush()
            time.sleep(pause)

    return handle


def _query(url: str, **kw: Any) -> Any:
    return HttpPrometheus(url, **kw).query_range("up", START, START + 60, 15)


def test_prometheus_deadline_covers_a_trickled_body_and_slow_headers() -> None:
    body = b"x" * 40
    head = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 40\r\n\r\n"
    for chunks in (
        [head] + [body[i : i + 1] for i in range(40)],  # body: one byte at a time
        [head[i : i + 1] for i in range(len(head))],  # headers: one byte at a time
    ):
        with serve(_trickle(chunks, 0.2)) as url:  # raw bytes, status line included
            t0 = time.monotonic()
            with pytest.raises(PrometheusError, match="did not answer within 1 s"):
                _query(url, timeout_s=1.0)
            assert time.monotonic() - t0 < 2.5


def test_prometheus_cut_off_oversized_and_non_prometheus_answers_are_clean_errors() -> None:
    def cut_off(h: Any) -> None:
        h.wfile.write(b'HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{"status": ')
        h.wfile.flush()
        h.close_connection = True

    with serve(cut_off) as url, pytest.raises(PrometheusError, match="cut off"):
        _query(url, timeout_s=5)

    big = json.dumps(matrix([[START, "1"]] * 200)).encode()

    def large(h: Any) -> None:
        h.send_response(200)
        h.send_header("Content-Type", "application/json")
        h.end_headers()  # no Content-Length: the cap applies while reading
        h.wfile.write(big)

    with serve(large) as url:
        with pytest.raises(PrometheusError, match="larger than 1,024 bytes"):
            _query(url, timeout_s=5, max_bytes=1024)
        assert _query(url, timeout_s=5)  # under the default cap it is read

    def not_json(h: Any) -> None:
        h.send_response(200)
        h.end_headers()
        h.wfile.write(b"<html>an internal page</html>")

    with serve(not_json) as url, pytest.raises(PrometheusError) as caught:
        _query(url, timeout_s=5)
    assert "internal page" not in str(caught.value)


def test_prometheus_errors_do_not_repeat_what_the_server_said() -> None:
    for status in (200, 400):

        def refuse(h: Any, status: int = status) -> None:
            payload = {"status": "error", "errorType": "bad_data", "error": "SECRET-ROW-913"}
            h.send_response(status)
            h.send_header("Content-Type", "application/json")
            h.end_headers()
            h.wfile.write(json.dumps(payload).encode())

        with serve(refuse) as url, pytest.raises(PrometheusError) as caught:
            _query(url, timeout_s=5)
        assert "SECRET" not in str(caught.value) and "bad_data" in str(caught.value)
        assert caught.value.detail == "SECRET-ROW-913"  # kept for the local CLI to show


def test_prometheus_host_allowlist() -> None:
    hits: list[int] = []

    def ok(h: Any) -> None:
        hits.append(1)
        h.send_response(200)
        h.end_headers()
        h.wfile.write(json.dumps(matrix([[START, "1"]])).encode())

    with serve(ok) as url:
        with pytest.raises(PrometheusError, match="not an allowed Prometheus host"):
            _query(url, timeout_s=5, allowed_hosts=("prom.internal:9090",))
        assert not hits  # refused before connecting
        port = url.rsplit(":", 1)[1]
        assert _query(url, timeout_s=5, allowed_hosts=(f"127.0.0.1:{port}",))
    assert host_allowed("http://anything:1", [])
    assert host_allowed("http://Prom.Internal:9090/x", ["prom.internal"])
    assert not host_allowed("http://prom.internal:9091", ["prom.internal:9090"])
    assert host_allowed("https://prom.internal", ["prom.internal:443"])
    assert host_allowed("http://[::1]:9090", ["[::1]:9090"])
    assert not host_allowed("http://prom.internal.evil.example", ["prom.internal"])


# -- "measured" means: still the fitted value ----------------------------------------------------


def _inputs(doc: dict[str, Any], scenario: str | None = None) -> dict[tuple[Any, str], Any]:
    return {
        (e["id"], e["field"]): e["provenance"]
        for e in analyze_document(doc, REGISTRY, scenario)["inputs"]
    }


def test_measured_only_while_the_value_is_the_fitted_one() -> None:
    doc = calibrated_starter()
    assert doc["provenance"]["nodes/cache/hitRatio"]["value"] == pytest.approx(0.85)
    doc["scenarios"] = [
        {
            "id": "low-hits",
            "name": "Low hits",
            "overrides": [
                {"target": {"kind": "node", "id": "cache"}, "path": "hitRatio", "value": 0.2}
            ],
        },
        {
            "id": "more-load",
            "name": "More load",
            "overrides": [{"target": {"kind": "workload"}, "path": "rps", "value": 900}],
        },
    ]
    base = _inputs(doc)
    assert base[("cache", "hitRatio")]["kind"] == "measured"
    assert "value" not in base[("cache", "hitRatio")]  # reports stay compact
    # a scenario that overrides a measured value: no longer measured
    low = _inputs(doc, "low-hits")
    assert low[("cache", "hitRatio")]["kind"] == "assumed"
    assert "changed since it was measured" in low[("cache", "hitRatio")]["note"]
    assert low[("api", "serviceTimeMs")]["kind"] == "measured"  # untouched inputs keep it
    assert _inputs(doc, "more-load")[(None, "rps")]["kind"] == "assumed"
    # ... and the run's report says the same
    report = run_simulation(copy.deepcopy(doc), scenario_id="low-hits")
    user = {(e["id"], e["field"]): e["provenance"] for e in report["assumptions"]["userInputs"]}
    assert user[("cache", "hitRatio")]["kind"] == "assumed"
    assert report["assumptions"]["provenanceCounts"]["measured"] == 6
    # a direct edit (the CLI, MCP, an API PUT or a sweep keep the provenance record)
    edited = copy.deepcopy(doc)
    nodes = {n["id"]: n for n in edited["architecture"]["nodes"]}
    nodes["api"]["config"]["serviceTimeMs"] = {"kind": "lognormal", "median": 9, "p95": 30}
    edited["workload"]["rps"] = 300
    for e in edited["architecture"]["edges"]:
        if e["id"] == "api-db":
            e["config"]["latencyMs"] = {"kind": "constant", "value": 2}
    after = _inputs(edited)
    assert after[("api", "serviceTimeMs")]["kind"] == "assumed"
    assert after[(None, "rps")]["kind"] == "assumed"
    assert after[("api-db", "latencyMs")]["kind"] == "assumed"
    assert after[("db", "readServiceTimeMs")]["kind"] == "measured"
    # the same value written differently is still the fitted one
    same = copy.deepcopy(doc)
    cfg = {n["id"]: n for n in same["architecture"]["nodes"]}["db"]["config"]
    cfg["writeServiceTimeMs"] = dict(reversed(cfg["writeServiceTimeMs"].items()))
    assert same["workload"]["rps"] == 250.0
    same["workload"]["rps"] = 250  # an int where a float was stored
    assert _inputs(same)[("db", "writeServiceTimeMs")]["kind"] == "measured"
    assert _inputs(same)[(None, "rps")]["kind"] == "measured"
    # records written before values were kept are taken at their word
    legacy = copy.deepcopy(edited)
    for record in legacy["provenance"].values():
        record.pop("value")
    assert _inputs(legacy)[("api", "serviceTimeMs")]["kind"] == "measured"


# -- calibration never changes the run's duration; replay limits are clean errors ---------------


def test_a_short_range_is_fitted_into_the_run_not_the_run_into_the_range() -> None:
    doc = starter()
    mapping = load_mapping(f"workload:\n  rps: {{rate: '{RATE_Q}', trace: true}}\n")
    for span in (3600, 600, 30):  # one hour, ten minutes, shorter than the run
        rec = RecordedPrometheus.from_dict(rate_recording(600, span, step=min(60, span // 3)))
        out = calibrate_prometheus(doc, mapping, rec, REGISTRY, replay=False)
        wl = out.document["workload"]
        assert (wl["durationS"], wl["warmupS"]) == (60, 10)
        points = wl["pattern"]["points"]
        assert points[0][0] == 0 and points[-1][0] == pytest.approx(60)
        assert all(v == pytest.approx(600) for _, v in points)
        # every workload input that changed is listed
        before = load_document(doc).to_json_dict()["workload"]
        changed = {k for k in wl if wl[k] != before.get(k)}
        assert {f"workload/{k}" for k in changed} == {c.path for c in out.changes}
    assert any("stretched over the 60 s run" in n for n in out.notes)


def test_a_replay_past_the_limits_is_a_clean_error(tmp_path: Path) -> None:
    mapping = load_mapping_file(FIXTURES / "starter-mapping.yaml")
    rec = RecordedPrometheus.load(FIXTURES / "starter-prometheus.json")
    with pytest.raises(SimulationLimitError):
        calibrate_prometheus(
            starter(), mapping, rec, REGISTRY, limits=SimulationLimits(max_requests=1000)
        )
    # the CLI says so instead of crashing: a one-hour design at 600 req/s is over 2M requests
    design = tmp_path / "design.json"
    design.write_text(json.dumps(starter(durationS=3600, warmupS=10)), encoding="utf-8")
    m = tmp_path / "mapping.yaml"
    m.write_text(
        f"workload:\n  rps: {{rate: '{RATE_Q}', trace: true}}\n"
        "replay:\n  endToEnd: {histogram: e2e, unit: s}\n",
        encoding="utf-8",
    )
    r = tmp_path / "recorded.json"
    r.write_text(json.dumps(rate_recording(600, 3600)), encoding="utf-8")
    code = cli(["calibrate", "prometheus", str(design), "--map", str(m), "--recorded", str(r)])
    assert code == 1


def test_the_otlp_replay_run_is_capped() -> None:
    mapping = {"services": {"checkout-api": "api"}, "databases": {"orders": "db"}}
    traces = json.loads((FIXTURES / "checkout-traces.otlp.json").read_text(encoding="utf-8"))
    # about 6.7 traced req/s: an hour would be ~24,000 requests, the 300 s cap ~2,000
    out = calibrate_otlp(
        starter(durationS=3600, warmupS=600),
        traces,
        REGISTRY,
        mapping,
        limits=SimulationLimits(max_requests=5000),
    )
    assert out.document["calibration"]["replay"]
    assert out.document["workload"]["durationS"] == 3600  # the design itself is unchanged


# -- histogram fits ---------------------------------------------------------------------------------


def test_an_empirical_table_ends_where_the_data_ends() -> None:
    # everything below 100 ms, buckets up to 1 s: the table must not reach 1 s
    h = Histogram.from_buckets({10: 20, 50: 70, 100: 100, 250: 100, 1000: 100, math.inf: 100})
    fit = fit_histogram(h, "empirical")
    assert fit.distribution["quantiles"][-1] == [1.0, 100.0]
    h = Histogram.from_buckets({100: 50, 1000: 50, math.inf: 50})
    assert fit_histogram(h, "empirical").distribution["quantiles"] == [[0.0, 0.0], [1.0, 100.0]]
    # lognormal data in coarse buckets: the table's tail and mean stay near the data's
    rng = random.Random(1)
    samples = [rng.lognormvariate(math.log(30), 0.6) for _ in range(2000)]
    bounds = [5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000]
    buckets: dict[float, float] = {b: sum(1 for v in samples if v <= b) for b in bounds}
    buckets[math.inf] = len(samples)
    fit = fit_histogram(Histogram.from_buckets(buckets), "empirical")
    table = Empirical(quantiles=[tuple(p) for p in fit.distribution["quantiles"]])
    top = max(samples)
    assert table.quantile(0.9999) <= 2 * top  # was 8,050 ms against a 289 ms maximum
    assert table.expected() == pytest.approx(sum(samples) / len(samples), rel=0.1)
    # the table passes through every bound, so no fit error can be measured: say so
    assert fit.error is None
    assert any("only as precise as the bucket" in n for n in fit.notes)


def test_observations_past_the_last_bucket_are_reported() -> None:
    h = Histogram.from_buckets({10: 50, 50: 90, 100: 95, math.inf: 100})
    for kind in ("lognormal", "empirical"):
        fit = fit_histogram(h, kind)  # type: ignore[arg-type]
        assert any(
            "5.0% of observations were above the highest bucket bound" in n for n in fit.notes
        )
    assert not fit_histogram(Histogram.from_buckets({10: 50, 50: 100, math.inf: 100})).notes
    # through calibration: in the notes and in the input's provenance
    q = "sum by (le) (rate(h_bucket[1m]))"
    series = [
        {"metric": {"le": le}, "values": [[START, c], [START + 60, c]]}
        for le, c in (("0.01", "50"), ("0.05", "90"), ("0.1", "95"), ("+Inf", "100"))
    ]
    rec = RecordedPrometheus.from_dict(
        rate_recording(
            1,
            60,
            step=30,
            **{q: {"status": "success", "data": {"resultType": "matrix", "result": series}}},
        )
    )
    mapping = load_mapping(f"nodes: {{api: {{serviceTimeMs: {{histogram: '{q}', unit: s}}}}}}\n")
    out = calibrate_prometheus(starter(), mapping, rec, REGISTRY, replay=False)
    assert any("api.serviceTimeMs: 5.0% of observations" in n for n in out.notes)
    assert "5.0% of observations" in out.document["provenance"]["nodes/api/serviceTimeMs"]["note"]


# -- smaller fixes ------------------------------------------------------------------------------------


def test_no_cache_traffic_leaves_the_hit_ratio_assumed() -> None:
    zero = matrix([[START, "0"], [START + 60, "0"]])
    rec = RecordedPrometheus.from_dict(rate_recording(1, 60, step=30, hits=zero, misses=zero))
    mapping = load_mapping("nodes: {cache: {hitRatio: {ratio: {hits: hits, misses: misses}}}}\n")
    out = calibrate_prometheus(starter(), mapping, rec, REGISTRY, replay=False)
    assert not out.changes
    assert "nodes/cache/hitRatio" not in out.document["provenance"]
    assert any("no hits or misses" in n for n in out.notes)
    assert _inputs(out.document)[("cache", "hitRatio")]["kind"] != "measured"


def test_replay_summary_names_the_worst_check_not_only_p95() -> None:
    def check(scope: str, label: str, metric: str, error: float) -> dict[str, Any]:
        return {"scope": scope, "id": None, "label": label, "metric": metric,
                "observed": 1.0, "simulated": 1.0 + error, "error": error}  # fmt: skip

    record = {
        "timeRange": "a/b",
        "replay": [
            check("end_to_end", "End to end", "p95", 0.03),
            check("end_to_end", "End to end", "throughputRps", -0.01),
            check("component", "PostgreSQL", "p99", -0.21),
        ],
    }
    line = replay_summary(record) or ""
    assert line.startswith("This model reproduces the observed p95 (a/b) within 3% (over)")
    assert "the largest of 3 replay errors is -21% (PostgreSQL p99)" in line
    # without an end-to-end p95 (traces) the worst check is still reported
    only = {"timeRange": "a/b", "replay": [check("component", "API", "p50", 0.4)]}
    assert (
        replay_summary(only) == "Replay of a/b: the largest of 1 replay errors is +40% (API p50)."
    )


def test_a_time_without_an_offset_is_utc() -> None:
    assert parse_time("2026-10-01T00:00:00") == parse_time("2026-10-01T00:00:00Z") == START
