"""Functions of a simulation report: findings, confidence, explain formulas, digests, SLOs,
sweep rows, comparisons, seed groups and ``archsim check``."""

from __future__ import annotations

import copy
from typing import Any
from unittest import mock

from archsim_engine import analysis as analysis_module
from archsim_engine import check as check_module
from archsim_engine import compare as compare_module
from archsim_engine import confidence as confidence_module
from archsim_engine import engine as engine_module
from archsim_engine import explain as explain_module
from archsim_engine import report as report_module
from archsim_engine import seeds as seeds_module
from archsim_engine import slo as slo_module
from archsim_engine import sweep as sweep_module
from golden.adapters.support import Adapters, doc_of, injected_simulations, registry
from golden.codec import Json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.analysis.analyze")
def _analysis(i: Json) -> Any:
    return analysis_module.analyze(i["report"], doc_of(i["resolved"]))


@ADAPTERS.add("archsim_engine.confidence.report_confidence")
def _report_confidence(i: Json) -> Any:
    return confidence_module.report_confidence(i["report"], i["inputs"], i["linkMs"], registry())


@ADAPTERS.add("archsim_engine.explain.generic")
def _generic(i: Json) -> Any:
    return explain_module.generic(i["node"], i["windowS"], i["poolBased"])


@ADAPTERS.add("archsim_engine.engine.stationary")
def _stationary(i: Json) -> Any:
    return engine_module.stationary(i["report"])


@ADAPTERS.add("archsim_engine.report.canonical_digest")
def _canonical_digest(i: Json) -> Any:
    return report_module.canonical_digest(i["obj"])


@ADAPTERS.add("archsim_engine.slo.slo_checks")
def _slo_checks(i: Json) -> Any:
    return slo_module.slo_checks(i["objectives"], i["summary"])


@ADAPTERS.add("archsim_engine.slo.evaluate")
def _slo_evaluate(i: Json) -> Any:
    return slo_module.evaluate(i["objectives"], i["summary"])


@ADAPTERS.add("archsim_engine.slo.verdict_text")
def _verdict_text(i: Json) -> Any:
    return slo_module.verdict_text(i["result"])


@ADAPTERS.add("archsim_engine.sweep.row_from")
def _row_from(i: Json) -> Any:
    return sweep_module.row_from(i["rps"], i["report"])


@ADAPTERS.add("archsim_engine.sweep.meets")
def _meets(i: Json) -> Any:
    return sweep_module.meets(i["row"], i["objectives"])


@ADAPTERS.add("archsim_engine.sweep.analyze")
def _sweep_analyze(i: Json) -> Any:
    return sweep_module.analyze(i["rows"], i["objectives"])


@ADAPTERS.add("archsim_engine.compare.compare_reports")
def _compare(i: Json) -> Any:
    return compare_module.compare_reports(i["a"], i["b"], i["labelA"], i["labelB"])


@ADAPTERS.add("archsim_engine.compare.input_diff")
def _input_diff(i: Json) -> Any:
    return compare_module.input_diff(i["a"], i["b"])


@ADAPTERS.add("archsim_engine.seeds.summarize_seeds")
def _summarize_seeds(i: Json) -> Any:
    return seeds_module.summarize_seeds(i["runs"], i["objectives"])


@ADAPTERS.add("archsim_engine.check.measure")
def _measure(i: Json) -> Any:
    with injected_simulations([i["simulation"]]):
        return check_module.measure(i["document"], registry(), i["scenarioId"])


def _thresholds(data: Json) -> check_module.Thresholds:
    return check_module.Thresholds(**data)


@ADAPTERS.add("archsim_engine.check.regressions")
def _regressions(i: Json) -> Any:
    return check_module.regressions(i["current"], i["baseline"], _thresholds(i["thresholds"]))


@ADAPTERS.add("archsim_engine.check.run_check")
def _run_check(i: Json) -> Any:
    measured = [i["current"], i["baseline"]]

    def measure(*_args: Any, **_kwargs: Any) -> Json:
        return copy.deepcopy(measured.pop(0))

    with mock.patch.object(check_module, "measure", measure):
        return check_module.run_check(
            {},
            registry(),
            baseline={},
            thresholds=_thresholds(i["thresholds"]),
            fail_on=tuple(i["failOn"]),
        )


@ADAPTERS.add("archsim_engine.check.headline")
def _headline(i: Json) -> Any:
    return check_module.headline(i["result"])


@ADAPTERS.add("archsim_engine.check.to_markdown")
def _to_markdown(i: Json) -> Any:
    return check_module.to_markdown(i["result"], i["reportUrl"])
