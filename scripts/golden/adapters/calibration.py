"""Calibration from Prometheus and OpenTelemetry: mappings, ranges, the calibrations (with
their simulations and clock injected), and every parse and fit they make."""

from __future__ import annotations

from typing import Any

from archsim_engine.calibration import apply as calibration_apply
from archsim_engine.calibration import fit as calibration_fit
from archsim_engine.calibration import mapping as calibration_mapping
from archsim_engine.calibration import otlp as calibration_otlp
from archsim_engine.calibration import prometheus as calibration_prometheus
from archsim_engine.calibration import resources as calibration_resources
from golden.adapters.support import Adapters, fixed_clock, injected_simulations, registry
from golden.codec import Json, from_json, to_json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.calibration.mapping.load_mapping")
def _load_mapping(i: Json) -> Any:
    return calibration_mapping.load_mapping(i["source"])


@ADAPTERS.add("archsim_engine.calibration.otlp.load_otlp_mapping")
def _load_otlp_mapping(i: Json) -> Any:
    return calibration_otlp.load_otlp_mapping(i["source"])


@ADAPTERS.add("archsim_engine.calibration.apply.parse_range")
def _parse_range(i: Json) -> Any:
    return calibration_apply.parse_range(i["text"], i["step"])


@ADAPTERS.add("archsim_engine.calibration.apply.calibrate_prometheus")
def _calibrate_prometheus(i: Json) -> Any:
    mapping = calibration_mapping.load_mapping(i["mapping"])
    client = calibration_prometheus.RecordedPrometheus.from_dict(i["recorded"])
    step = i["step"] or (mapping.range.step if mapping.range else 60)
    rng = calibration_apply.parse_range(i["range"], step) if i["range"] else None
    with fixed_clock(), injected_simulations(i["simulations"]):
        outcome = calibration_apply.calibrate_prometheus(
            i["document"], mapping, client, registry(), rng, i["replay"]
        )
    return outcome.to_dict()


@ADAPTERS.add("archsim_engine.calibration.otlp.calibrate_otlp")
def _calibrate_otlp(i: Json) -> Any:
    with fixed_clock(), injected_simulations(i["simulations"]):
        outcome = calibration_otlp.calibrate_otlp(
            i["document"], i["traces"], registry(), i["mapping"], i["minSamples"]
        )
    return outcome.to_dict()


@ADAPTERS.add("archsim_engine.calibration.apply.replay_summary")
def _replay_summary(i: Json) -> Any:
    return calibration_apply.replay_summary(i["record"])


@ADAPTERS.add("archsim_engine.calibration.otlp.parse_otlp")
def _parse_otlp(i: Json) -> Any:
    return calibration_otlp.parse_otlp(i["data"])


@ADAPTERS.add("archsim_engine.calibration.otlp.summarize")
def _summarize(i: Json) -> Any:
    spans = [from_json(calibration_otlp.Span, s) for s in i["spans"]]
    cpu = tuple(i["cpuTime"]) if i["cpuTime"] is not None else None
    return calibration_otlp.summarize(spans, cpu)


@ADAPTERS.add("archsim_engine.calibration.prometheus.histogram")
def _histogram(i: Json) -> Any:
    return calibration_prometheus.histogram(i["result"], i["unit"])


@ADAPTERS.add("archsim_engine.calibration.prometheus.reduce")
def _reduce(i: Json) -> Any:
    return calibration_prometheus.reduce(i["result"], i["how"])


@ADAPTERS.add("archsim_engine.calibration.fit.fit_histogram")
def _fit_histogram(i: Json) -> Any:
    h = from_json(calibration_fit.Histogram, i["h"])
    return calibration_fit.fit_histogram(h, i["kind"])


@ADAPTERS.add("archsim_engine.calibration.fit.fit_samples")
def _fit_samples(i: Json) -> Any:
    return calibration_fit.fit_samples(i["samples"], i["kind"])


def _pairs(points: list[list[float]]) -> list[tuple[float, float]]:
    return [(float(a), float(b)) for a, b in points]


@ADAPTERS.add("archsim_engine.calibration.resources.fit_cpu_share")
def _fit_cpu_share(i: Json) -> Any:
    return calibration_resources.fit_cpu_share(_pairs(i["points"]), i["serviceMs"], i["replicas"])


@ADAPTERS.add("archsim_engine.calibration.resources.fit_load_curve")
def _fit_load_curve(i: Json) -> Any:
    return calibration_resources.fit_load_curve(_pairs(i["points"]), i["referenceMs"], i["maxBins"])


@ADAPTERS.add("archsim_engine.calibration.resources.fit_memory")
def _fit_memory(i: Json) -> Any:
    return calibration_resources.fit_memory(i["workingSetMb"], i["rate"], i["held"])


@ADAPTERS.add("archsim_engine.calibration.resources.extend_load_curve")
def _extend_load_curve(i: Json) -> Any:
    ext = calibration_resources.extend_load_curve(
        _pairs(i["passes"]), i["cores"], i["minBusy"], i["baseBusy"], i["t"]
    )
    return None if ext is None else {**to_json(ext), "factors": ext.factors()}
