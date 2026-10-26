"""Calibration from telemetry: Prometheus (``calibrate_prometheus``) and OpenTelemetry traces
(``calibrate_otlp``). See ``docs/calibration.md``."""

from archsim_engine.calibration.apply import (
    Outcome,
    calibrate_prometheus,
    parse_range,
    replay_summary,
)
from archsim_engine.calibration.fit import Histogram, fit_histogram, fit_samples
from archsim_engine.calibration.mapping import (
    Mapping,
    MappingError,
    load_mapping,
    load_mapping_file,
    validation_message,
)
from archsim_engine.calibration.otlp import (
    calibrate_otlp,
    load_otlp_mapping,
    parse_otlp,
    summarize,
)
from archsim_engine.calibration.prometheus import (
    HttpPrometheus,
    PrometheusError,
    RecordedPrometheus,
    RecordingPrometheus,
)

__all__ = [
    "Histogram",
    "HttpPrometheus",
    "Mapping",
    "MappingError",
    "Outcome",
    "PrometheusError",
    "RecordedPrometheus",
    "RecordingPrometheus",
    "calibrate_otlp",
    "calibrate_prometheus",
    "fit_histogram",
    "fit_samples",
    "load_mapping",
    "load_mapping_file",
    "load_otlp_mapping",
    "parse_otlp",
    "parse_range",
    "replay_summary",
    "summarize",
    "validation_message",
]
