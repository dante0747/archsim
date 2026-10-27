"""Confidence in a verdict: how much of what decides the result rests on numbers someone checked.

Computed in the engine, so the web app, the Markdown report, the CLI and the MCP server all say the
same thing. Every report has ``confidence`` and every analysis too: a ``level`` (high, medium or
low), the ``reason`` that decided it, further ``reasons``, the ``drivers`` (the inputs to replace
first) and ``howToRaise``. There is no numeric score.

Each part counts by its weight in the result: its share of a request's latency in a report
(``explain.latencyBreakdown``; the network's share goes to the links, by calls times link latency), or
its utilization at the peak in an analysis (the instant estimate, :mod:`archsim_engine.estimate`).
A part's inputs (cost inputs aside) are *grounded* when they are measured with a calibration fit
error of at most 15% or documented by the vendor (:mod:`archsim_engine.provenance`); the weighted
share of grounded inputs decides the level:

- **high**: at least 80% of the weight is grounded and no experimental technology is on the
  request path;
- **low**: under 40% is grounded, or an experimental technology is at the bottleneck, or a
  calibration fit error is above 30%;
- **medium**: otherwise.

The workload's own inputs (traffic, read share, keys) are not weighed: they describe the question
being asked, not the system.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from archsim_engine.export.tech import technology_label
from archsim_engine.provenance import provenance_counts
from archsim_engine.registry import ComponentRegistry

Json = dict[str, Any]
Key = tuple[str, str]

MEASURED_FIT = 0.15
"""Largest calibration fit error at which a measured input counts as grounded."""
BAD_FIT = 0.3
"""A calibration fit error above this makes confidence low."""
HIGH_SHARE = 0.8
LOW_SHARE = 0.4
MAX_DRIVERS = 6
LEVELS = ("high", "medium", "low")
PRIMARY = frozenset(
    {
        "serviceTimeMs",
        "readServiceTimeMs",
        "writeServiceTimeMs",
        "latencyMs",
        "overheadMs",
        "hitRatio",
        "ttlSeconds",
        "publishTimeMs",
        "consumerTimeMs",
        "commitFlushMs",
    }
)
"""Inputs that move latency and capacity most: listed first among the drivers."""


def _key(entry: Json) -> Key | None:
    if entry.get("scope") in ("node", "edge") and entry.get("id"):
        return (entry["scope"], entry["id"])
    return None


def _weighed(entry: Json) -> bool:
    """Inputs that shape the performance result (prices do not)."""
    return _key(entry) is not None and not str(entry.get("field", "")).startswith("cost")


def _fit(entry: Json) -> float | None:
    fit = (entry.get("provenance") or {}).get("fitError")
    return float(fit) if isinstance(fit, int | float) else None


def grounded(entry: Json) -> bool:
    """Measured (fit error at most 15%, or not recorded) or documented."""
    kind = (entry.get("provenance") or {}).get("kind")
    if kind == "documented":
        return True
    if kind == "measured":
        fit = _fit(entry)
        return fit is None or fit <= MEASURED_FIT
    return False


def _names(labels: list[str]) -> str:
    if len(labels) <= 2:
        return " and ".join(labels)
    return ", ".join(labels[:2]) + f" and {len(labels) - 2} more"


def assess(
    inputs: list[Json],
    weights: dict[Key, float],
    *,
    weighted_by: str,
    technologies: Iterable[Json] = (),
    bottleneck: str | None = None,
    on_path: set[str] | None = None,
    labels: dict[Key, str] | None = None,
    bottleneck_word: str = "bottleneck",
) -> Json:
    """The confidence of a result from its inputs (``provenance.input_provenance``), each part's
    or link's weight (``weighted_by`` names it: "latency" or "utilization"), the parts'
    technology tiers, the bottleneck part and the parts on the request path."""
    labels = dict(labels or {})
    by_key: dict[Key, list[Json]] = {}
    for entry in inputs:
        key = _key(entry)
        if key is not None:
            labels.setdefault(key, entry.get("label") or key[1])
        if _weighed(entry):
            assert key is not None
            by_key.setdefault(key, []).append(entry)

    # the weight of each part that has inputs, and how much of it is grounded
    weight = {k: max(0.0, w) for k, w in weights.items() if k in by_key and w > 0}
    basis = weighted_by
    if not weight:
        weight = dict.fromkeys(by_key, 1.0)
        basis = "inputs"
    total = sum(weight.values())
    covered = {k: sum(grounded(e) for e in by_key[k]) / len(by_key[k]) for k in weight}
    share = sum(weight[k] * covered[k] for k in weight) / total if total > 0 else 0.0

    counts = provenance_counts(inputs)
    n_inputs = len(inputs)
    n_grounded = sum(grounded(e) for e in inputs)
    bad_fits = sorted(
        (e for e in inputs if (e.get("provenance") or {}).get("kind") == "measured"),
        key=lambda e: -(_fit(e) or 0.0),
    )
    bad_fits = [e for e in bad_fits if (_fit(e) or 0.0) > BAD_FIT]
    experimental = [t for t in technologies if t.get("tier") == "experimental"]
    on_path_exp = [t for t in experimental if on_path is None or t["id"] in on_path]
    at_bottleneck = [t for t in experimental if bottleneck is not None and t["id"] == bottleneck]

    of_what = {
        "latency": "of the latency",
        "utilization": "of the load (by part utilization)",
        "inputs": "of the parts' inputs",
    }[basis]
    share_text = f"{share:.0%} {of_what} rests on measured or documented inputs"
    if n_inputs and counts["illustrative"] >= n_inputs - n_grounded - counts["illustrative"]:
        ungrounded_text = f"{counts['illustrative']} of {n_inputs} inputs are illustrative defaults"
    else:
        ungrounded_text = (
            f"{n_inputs - n_grounded} of {n_inputs} inputs are assumed or illustrative, "
            "not measured"
        )
    heavy = sorted(weight, key=lambda k: -weight[k] * (1 - covered[k]))
    heavy = [k for k in heavy if covered[k] < 1]

    reasons: list[str] = []
    if bad_fits:
        level = "low"
        e = bad_fits[0]
        reasons.append(
            f"The calibration of {e['label']} {e['fieldLabel'].lower()} fits only within "
            f"{_fit(e):.0%} (above {BAD_FIT:.0%})"
        )
    elif at_bottleneck:
        level = "low"
        t = at_bottleneck[0]
        reasons.append(
            f"The {bottleneck_word}, {t['label']}, uses an experimental technology "
            f"({t['technology']}) "
            "whose model is not yet validated against measurements"
        )
    elif share < LOW_SHARE:
        level = "low"
        if n_inputs - n_grounded >= n_inputs / 2:
            reasons.append(ungrounded_text)
        else:
            names = _names([labels.get(k, k[1]) for k in heavy[:3]])
            reasons.append(f"Only {share_text}: {names} rest on assumed or illustrative inputs")
    elif share >= HIGH_SHARE and not on_path_exp:
        level = "high"
        reasons.append(f"{n_grounded} of {n_inputs} inputs are measured or documented")
    else:
        level = "medium"
        if share >= HIGH_SHARE and on_path_exp:
            t = on_path_exp[0]
            reasons.append(
                f"{t['label']} on the request path uses an experimental technology "
                f"({t['technology']})"
            )
        else:
            reasons.append(f"{share_text}; high confidence needs {HIGH_SHARE:.0%}")

    # the other facts behind the level, after the one that decided it
    extra: list[str] = []
    if share_text not in reasons[0]:
        extra.append(share_text)
    if level != "high" and n_grounded < n_inputs and ungrounded_text != reasons[0]:
        extra.append(ungrounded_text)
    extra += [
        f"The calibration of {e['label']} {e['fieldLabel'].lower()} fits only within {_fit(e):.0%}"
        for e in bad_fits[1:3]
    ]
    extra += [
        f"{t['label']} uses an experimental technology ({t['technology']})"
        for t in on_path_exp
        if t["label"] not in reasons[0]
    ]
    reasons += [r for r in extra if r not in reasons]

    # the inputs to replace first: bad fits, then the ungrounded inputs of the heaviest parts
    def driver(e: Json) -> Json:
        k = _key(e)
        return {
            "scope": e["scope"],
            "id": e["id"],
            "label": labels.get(k, e["label"]) if k else e["label"],
            "field": e["field"],
            "fieldLabel": e["fieldLabel"],
            "kind": (e.get("provenance") or {}).get("kind"),
            "weight": round(weight.get(k, 0.0) / total, 3) if k and total > 0 else None,
        }

    picked = [*bad_fits]
    for primary in (True, False):
        for k in heavy:
            picked += [
                e
                for e in by_key[k]
                if not grounded(e) and (e["field"] in PRIMARY) == primary and e not in picked
            ]
    drivers = [driver(e) for e in picked[:MAX_DRIVERS]]

    if bad_fits:
        how = (
            f"Calibrate {bad_fits[0]['label']} again over a steadier time range, or check that "
            "the part is drawn the way it runs."
        )
    elif at_bottleneck:
        how = (
            f"Measure {at_bottleneck[0]['label']} and calibrate it from telemetry, or use a core "
            "technology there."
        )
    elif level == "high":
        how = "Keep the calibration current: calibrate again after the system changes."
    else:
        names = _names([labels.get(k, k[1]) for k in heavy[:3]]) or "the parts"
        how = (
            "Calibrate from telemetry, or replace the illustrative and assumed inputs of "
            f"{names} with measured values."
        )
    return {
        "level": level,
        "reason": reasons[0],
        "reasons": reasons,
        "drivers": drivers,
        "weightedBy": basis,
        "howToRaise": how,
    }


# ---------------------------------------------------------------------------------------------
# reports and analyses
# ---------------------------------------------------------------------------------------------


def report_weights(report: Json, link_ms: dict[str, float]) -> tuple[dict[Key, float], str]:
    """Each part's and link's share of a request's latency in a report (``link_ms``: each link's
    mean one-way latency), or each part's utilization when no request succeeded."""
    weights: dict[Key, float] = {}
    breakdown = (report.get("explain") or {}).get("latencyBreakdown") or []
    for row in breakdown:
        if row.get("nodeId"):
            weights[("node", row["nodeId"])] = float(row.get("share") or 0.0)
        elif row.get("label") == "Network":
            net = float(row.get("share") or 0.0)
            use = {
                e["id"]: float(e.get("callRps") or 0.0) * link_ms.get(e["id"], 0.0)
                for e in report.get("edges", [])
            }
            total = sum(use.values())
            for eid, u in use.items():
                if total > 0 and u > 0:
                    weights[("edge", eid)] = net * u / total
    if weights:
        return weights, "latency"
    for n in report.get("nodes", []):
        busy = (n.get("summary") or {}).get("utilization")
        if isinstance(busy, int | float) and busy > 0:
            weights[("node", n["id"])] = min(1.0, float(busy))
    return weights, "utilization"


def link_labels(architecture: Json) -> dict[Key, str]:
    """Readable names for links: "Clients → Load Balancer" instead of their ids."""
    names = {n["id"]: n.get("label") or n["id"] for n in architecture.get("nodes", [])}
    return {
        (
            "edge",
            e["id"],
        ): f"{names.get(e['source'], e['source'])} → {names.get(e['target'], e['target'])}"
        for e in architecture.get("edges", [])
    }


def _named(technologies: Iterable[Json], registry: ComponentRegistry) -> list[Json]:
    """Technology tiers with each technology's display name (``postgresql`` -> PostgreSQL)."""
    return [
        {
            **t,
            "technology": technology_label(registry, t["type"], t["technology"]) or t["technology"],
        }
        for t in technologies
    ]


def report_confidence(
    report: Json, inputs: list[Json], link_ms: dict[str, float], registry: ComponentRegistry
) -> Json:
    """``report.confidence``."""
    weights, basis = report_weights(report, link_ms)
    bottleneck = next(
        (f.get("nodeId") for f in report.get("findings", []) if f.get("kind") == "bottleneck"),
        None,
    )
    if bottleneck is None:
        # a healthy run names no bottleneck: the busiest part is the one that would saturate first
        busy = [
            (u, n["id"])
            for n in report.get("nodes", [])
            if isinstance(u := (n.get("summary") or {}).get("utilization"), int | float)
        ]
        bottleneck = max(busy)[1] if busy else None
    breakdown = (report.get("explain") or {}).get("latencyBreakdown") or []
    on_path = {r["nodeId"] for r in breakdown if r.get("nodeId")} or {
        n["id"] for n in report.get("nodes", []) if (n.get("summary") or {}).get("arrivals")
    }
    return assess(
        inputs,
        weights,
        weighted_by=basis,
        technologies=_named((report.get("assumptions") or {}).get("technologies") or [], registry),
        bottleneck=bottleneck,
        on_path=on_path,
        labels=link_labels((report.get("input") or {}).get("architecture") or {}),
    )


def analysis_confidence(
    estimate: Json,
    inputs: list[Json],
    technologies: list[Json],
    registry: ComponentRegistry,
    architecture: Json | None = None,
) -> Json:
    """``analysis.confidence``: parts weighted by their estimated utilization at the peak
    (``architecture``: the design's, to name its links)."""
    weights: dict[Key, float] = {}
    on_path: set[str] = set()
    for p in estimate.get("parts", []):
        u = p.get("utilization")
        if isinstance(u, int | float) and u > 0:
            weights[("node", p["id"])] = min(1.0, float(u))
        if p.get("onPath"):
            on_path.add(p["id"])
    busiest = (estimate.get("busiest") or {}).get("id")
    return assess(
        inputs,
        weights,
        weighted_by="utilization",
        technologies=_named(technologies, registry),
        bottleneck=busiest,
        on_path=on_path or None,
        labels=link_labels(architecture or {}),
        bottleneck_word="busiest part",
    )
