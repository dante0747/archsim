"""Number-to-text and small numeric helpers, and the distributions' own functions."""

from __future__ import annotations

from typing import Any

from pydantic import TypeAdapter

from archsim_engine import estimate as estimate_module
from archsim_engine import explain as explain_module
from archsim_engine import load as load_module
from archsim_engine import provenance as provenance_module
from archsim_engine import seeds as seeds_module
from archsim_engine import sizing as sizing_module
from archsim_engine import sweep as sweep_module
from archsim_engine.distributions import Distribution, Empirical
from golden.adapters.support import Adapters
from golden.codec import Json, number

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.explain.num")
def _num(i: Json) -> Any:
    return explain_module.num(number(i["v"]), i["digits"])


@ADAPTERS.add("archsim_engine.explain.pct")
def _pct(i: Json) -> Any:
    return explain_module.pct(number(i["v"]))


@ADAPTERS.add("archsim_engine.explain.erlang_c")
def _erlang_c(i: Json) -> Any:
    return explain_module.erlang_c(i["c"], i["offered"])


@ADAPTERS.add("archsim_engine.explain.zipf_share")
def _zipf_share(i: Json) -> Any:
    return explain_module.zipf_share(i["n"], i["s"], i["top"])


@ADAPTERS.add("archsim_engine.load.js_num")
def _js_num(i: Json) -> Any:
    return load_module.js_num(number(i["x"]))


@ADAPTERS.add("archsim_engine.load.fmt_int")
def _fmt_int(i: Json) -> Any:
    return load_module.fmt_int(number(i["x"]))


@ADAPTERS.add("archsim_engine.load.fixed")
def _fixed(i: Json) -> Any:
    return load_module.fixed(number(i["x"]), i["digits"])


@ADAPTERS.add("archsim_engine.load.round_half_up")
def _round_half_up(i: Json) -> Any:
    return load_module.round_half_up(number(i["x"]), i["digits"])


@ADAPTERS.add("archsim_engine.load.mean_ms")
def _mean_ms(i: Json) -> Any:
    return load_module.mean_ms(i["d"])


@ADAPTERS.add("archsim_engine.load.ttl_hit_ratio")
def _ttl_hit_ratio(i: Json) -> Any:
    return load_module.ttl_hit_ratio(
        i["readRps"], i["writeRps"], i["keys"], i["skew"], i["ttlS"], i["capacityKeys"]
    )


@ADAPTERS.add("archsim_engine.sizing.fmt")
def _sizing_fmt(i: Json) -> Any:
    return sizing_module.fmt(number(i["v"]))


@ADAPTERS.add("archsim_engine.provenance.describe_value")
def _describe_value(i: Json) -> Any:
    return provenance_module.describe_value(i["v"], i["unit"])


@ADAPTERS.add("archsim_engine.seeds.fmt_ms")
def _seeds_fmt_ms(i: Json) -> Any:
    return seeds_module.fmt_ms(number(i["v"]))


@ADAPTERS.add("archsim_engine.seeds.value_range")
def _value_range(i: Json) -> Any:
    return seeds_module.value_range(i["values"])


@ADAPTERS.add("archsim_engine.seeds.range_text")
def _range_text(i: Json) -> Any:
    return seeds_module.range_text(i["label"], i["r"])


@ADAPTERS.add("archsim_engine.seeds.seeds_text")
def _seeds_text(i: Json) -> Any:
    return seeds_module.seeds_text(i["seeds"])


@ADAPTERS.add("archsim_engine.seeds.cap_confidence")
def _cap_confidence(i: Json) -> Any:
    return seeds_module.cap_confidence(i["level"], i["cap"])


def distribution(data: Json) -> Any:
    return TypeAdapter(Distribution).validate_python(data)


@ADAPTERS.add("archsim_engine.distributions.Distribution.expected")
def _expected(i: Json) -> Any:
    return distribution(i["d"]).expected()


@ADAPTERS.add("archsim_engine.distributions.Distribution.describe")
def _describe(i: Json) -> Any:
    return distribution(i["d"]).describe()


@ADAPTERS.add("archsim_engine.distributions.Empirical.quantile")
def _empirical_quantile(i: Json) -> Any:
    d = distribution(i["d"])
    assert isinstance(d, Empirical)
    return d.quantile(i["q"])


@ADAPTERS.add("archsim_engine.estimate.mmc_wait")
def _mmc_wait(i: Json) -> Any:
    return estimate_module.mmc_wait(i["slots"], i["lam"], i["holdMs"])


@ADAPTERS.add("archsim_engine.sweep.suggest_range")
def _suggest_range(i: Json) -> Any:
    return sweep_module.suggest_range(i["currentRps"], i["cap"])


@ADAPTERS.add("archsim_engine.sweep.rps_cap")
def _rps_cap(i: Json) -> Any:
    return sweep_module.rps_cap(None, i["durationS"])
