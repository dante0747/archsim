"""The calibration fixture: Prometheus and OpenTelemetry calibration from the engine's fixtures,
mappings and ranges with their errors, every parse and fit the calibrations make, and fits on
synthetic points."""

from __future__ import annotations

import copy
import inspect
import math
from collections.abc import Iterator
from contextlib import ExitStack, contextmanager
from typing import Any
from unittest import mock

import archsim_engine
from archsim_engine.calibration import apply as calibration_apply
from archsim_engine.calibration import otlp as calibration_otlp
from archsim_engine.calibration import resources as calibration_resources
from golden.adapters import call
from golden.codec import Json, camel, canonical, to_json
from golden.file import GoldenFile
from golden.files.runs import normalize_report
from golden.paths import EXAMPLES, FIXTURES, STARTER, read_json

SPIED: list[tuple[Any, str, str]] = [
    # (module the caller looks it up in, name, recorded as)
    (calibration_otlp, "parse_otlp", "archsim_engine.calibration.otlp.parse_otlp"),
    (calibration_otlp, "summarize", "archsim_engine.calibration.otlp.summarize"),
    (calibration_apply, "histogram", "archsim_engine.calibration.prometheus.histogram"),
    (calibration_apply, "reduce", "archsim_engine.calibration.prometheus.reduce"),
    (calibration_apply, "fit_histogram", "archsim_engine.calibration.fit.fit_histogram"),
    (calibration_apply, "fit_cpu_share", "archsim_engine.calibration.resources.fit_cpu_share"),
    (calibration_apply, "fit_memory", "archsim_engine.calibration.resources.fit_memory"),
    (
        calibration_resources,
        "fit_load_curve",
        "archsim_engine.calibration.resources.fit_load_curve",
    ),
    (calibration_otlp, "fit_samples", "archsim_engine.calibration.fit.fit_samples"),
]


@contextmanager
def recording_fits() -> Iterator[list[tuple[str, Json]]]:
    """The calls calibration makes to its parsing and fitting functions, as (function, recorded
    input)."""
    calls: list[tuple[str, Json]] = []
    with ExitStack() as stack:
        for module, name, fn in SPIED:
            real = getattr(module, name)
            signature = inspect.signature(real)

            def wrapper(
                *args: Any, _r: Any = real, _s: Any = signature, _fn: str = fn, **kw: Any
            ) -> Any:
                bound = _s.bind(*args, **kw)
                bound.apply_defaults()
                calls.append((_fn, {camel(k): to_json(v) for k, v in bound.arguments.items()}))
                return _r(*args, **kw)

            stack.enter_context(mock.patch.object(module, name, wrapper))
        yield calls


@contextmanager
def recording_simulations() -> Iterator[list[Json]]:
    """The reports of the simulations run while inside (a calibration's replay)."""
    reports: list[Json] = []
    real = archsim_engine.run_simulation

    def wrapper(*args: Any, **kwargs: Any) -> Json:
        report = real(*args, **kwargs)
        normalize_report(report)
        reports.append(copy.deepcopy(report))
        return report

    with mock.patch.object(archsim_engine, "run_simulation", wrapper):
        yield reports


def calibration_file() -> GoldenFile:
    g = GoldenFile("fixtures", name="calibration")
    folder = FIXTURES / "calibration"
    starter = read_json(EXAMPLES / f"{STARTER}.json")
    mapping_text = (folder / "starter-mapping.yaml").read_text(encoding="utf-8")
    recorded = read_json(folder / "starter-prometheus.json")
    otlp_text = (folder / "otlp-mapping.yaml").read_text(encoding="utf-8")
    traces = read_json(folder / "checkout-traces.otlp.json")
    g.place(("files", "starter-mapping.yaml"), mapping_text)
    g.place(("files", "starter-prometheus.json"), recorded)
    g.place(("files", "otlp-mapping.yaml"), otlp_text)
    g.place(("files", "checkout-traces.otlp.json"), traces)

    # mappings and ranges
    where = ("cases", "mappings")
    g.entry(where, "load_mapping", "archsim_engine.calibration.mapping.load_mapping",
            {"source": mapping_text})  # fmt: skip
    for n, source in enumerate([
        "nodes: [not, a, mapping]\n", "range: {start: 1}\nnodes: {}\n", ": bad yaml : [\n",
        {"nodes": {"api": {"serviceTimeMs": {"histogram": "q", "unit": "fortnights"}}}},
        {"nodes": {"api": {"serviceTimeMs": {"gauge": "q", "histogram": "q"}}}},
    ]):  # fmt: skip
        g.entry(where, f"load_mapping[{n}]", "archsim_engine.calibration.mapping.load_mapping",
                {"source": source}, errors=True)  # fmt: skip
    g.entry(where, "load_otlp_mapping", "archsim_engine.calibration.otlp.load_otlp_mapping",
            {"source": otlp_text})  # fmt: skip
    for n, otlp_source in enumerate([None, "", "services: [a]\n", {"cpuTime": {"attribute": "x", "unit": "minutes"}}]):  # fmt: skip
        g.entry(where, f"load_otlp_mapping[{n}]",
                "archsim_engine.calibration.otlp.load_otlp_mapping", {"source": otlp_source},
                errors=True)  # fmt: skip
    for n, (text, step) in enumerate([
        ("2026-10-01T00:00:00Z/2026-10-01T00:02:00Z", 5), ("2026-10-01T00:00:00/2026-10-02T00:00:00+02:00", 60),
        ("2026-10-01T00:00:00Z", 60),
    ]):  # fmt: skip
        g.entry(where, f"parse_range[{n}]", "archsim_engine.calibration.apply.parse_range",
                {"text": text, "step": step}, errors=True)  # fmt: skip

    # Prometheus, with the replay off and on (the replay's report is recorded as an input)
    for name, replay in (("prometheus", False), ("prometheus-replay", True)):
        where = ("cases", name)
        inputs: Json = {
            "document": starter, "mapping": mapping_text, "recorded": recorded, "range": None,
            "step": None, "replay": replay, "simulations": None,
        }  # fmt: skip
        with recording_simulations() as reports, recording_fits() as fits:
            call("archsim_engine.calibration.apply.calibrate_prometheus", inputs)
        inputs["simulations"] = reports
        outcome = g.entry(where, "calibrate_prometheus",
                          "archsim_engine.calibration.apply.calibrate_prometheus", inputs)  # fmt: skip
        g.entry(where, "replay_summary", "archsim_engine.calibration.apply.replay_summary",
                {"record": outcome["calibration"]})  # fmt: skip
        if not replay:
            record_fits(g, where, fits)

    # OpenTelemetry traces, with and without the CPU-time mapping
    otlp_mapping = call("archsim_engine.calibration.otlp.load_otlp_mapping", {"source": otlp_text})
    for name, mapping in (("otlp", otlp_mapping), ("otlp-no-mapping", None)):
        where = ("cases", name)
        inputs = {
            "document": starter, "traces": traces, "mapping": mapping, "minSamples": 20,
            "simulations": None,
        }  # fmt: skip
        with recording_simulations() as reports, recording_fits() as fits:
            call("archsim_engine.calibration.otlp.calibrate_otlp", inputs)
        inputs["simulations"] = reports
        outcome = g.entry(where, "calibrate_otlp", "archsim_engine.calibration.otlp.calibrate_otlp",
                          inputs)  # fmt: skip
        g.entry(where, "replay_summary", "archsim_engine.calibration.apply.replay_summary",
                {"record": outcome["calibration"]})  # fmt: skip
        if mapping is not None:
            record_fits(g, where, fits)

    # fits on synthetic points: a CPU-by-load staircase and its extension above the top
    where = ("cases", "synthetic-fits")
    stairs = [[r, r * (0.9 - 0.05 * math.log(r / 50)) / 1000] for r in (50, 100, 200, 400, 800)]
    g.entry(where, "fit_load_curve", "archsim_engine.calibration.resources.fit_load_curve",
            {"points": stairs, "referenceMs": None, "maxBins": 5})  # fmt: skip
    g.entry(where, "fit_load_curve[flat]", "archsim_engine.calibration.resources.fit_load_curve",
            {"points": [[100, 0.05], [110, 0.055]], "referenceMs": 0.5, "maxBins": 6})  # fmt: skip
    for n, (passes, cores) in enumerate([
        ([[r, 0.9 - 0.05 * math.log(r / 50) + 0.002 * (k % 3)] for r in (100, 200, 400, 800) for k in range(3)], 1.0),
        ([[r, 0.5] for r in (100, 200, 400)], 2.0),
        ([[r, 0.9 - 0.08 * math.log(r / 50)] for r in (400, 800, 1200)], 1.0),
    ]):  # fmt: skip
        g.entry(where, f"extend_load_curve[{n}]",
                "archsim_engine.calibration.resources.extend_load_curve",
                {"passes": passes, "cores": cores, "minBusy": calibration_resources.EXTEND_MIN_BUSY,
                 "baseBusy": calibration_resources.EXTEND_BASE_BUSY, "t": calibration_resources.EXTEND_T},
                errors=True)  # fmt: skip
    g.entry(where, "fit_memory", "archsim_engine.calibration.resources.fit_memory",
            {"workingSetMb": [512 + 0.8 * r for r in (10, 50, 100, 200)],
             "rate": [10, 50, 100, 200], "held": None})  # fmt: skip
    g.entry(where, "fit_memory[both]", "archsim_engine.calibration.resources.fit_memory",
            {"workingSetMb": [300 + 0.5 * r + 2 * h for r, h in ((10, 1), (50, 8), (100, 9), (200, 30))],
             "rate": [10, 50, 100, 200], "held": [1, 8, 9, 30]})  # fmt: skip
    g.entry(where, "fit_cpu_share", "archsim_engine.calibration.resources.fit_cpu_share",
            {"points": [[r, r * 0.004] for r in (50, 100, 150)], "serviceMs": 8.0, "replicas": 2.0})  # fmt: skip
    g.entry(where, "fit_samples", "archsim_engine.calibration.fit.fit_samples",
            {"samples": [1 + (k * 37 % 101) / 10 for k in range(200)], "kind": "empirical"})  # fmt: skip
    g.cases = len(g.root["cases"])
    return g


def record_fits(g: GoldenFile, where: tuple[str, ...], fits: list[tuple[str, Json]]) -> None:
    """The fitting functions a calibration called, each on what it was given (once each)."""
    seen: set[bytes] = set()
    counts: dict[str, int] = {}
    for fn, inputs in fits:
        key = canonical([fn, inputs])
        if key in seen:
            continue
        seen.add(key)
        short = fn.rsplit(".", 1)[-1]
        n = counts.get(short, 0)
        counts[short] = n + 1
        g.entry(where, f"{short}[{n}]", fn, inputs)
