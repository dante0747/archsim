"""The calculations behind the numbers.

Every node in the report gets ``explain``: a list of formulas, each written out symbolically,
again with this run's numbers substituted, and the result. Generic entries come from queueing
theory (arrival rate, capacity, utilization, Little's law, the M/M/c waiting-time prediction);
components add their own (buffer cache, hot keys, group commit, partitions, event loop...).

The predictions are the textbook models; the simulation does not use them. Showing both lets a
user check the simulation against theory and see where real effects (variance, bursts, downstream
waits, locks) make it differ.
"""

from __future__ import annotations

import math
from typing import Any


def formula(
    id: str,
    title: str,
    formula: str,
    substituted: str,
    result: float | None,
    unit: str = "",
    note: str | None = None,
    source: str | None = None,
    metric: str | None = None,
) -> dict[str, Any]:
    return {
        "id": id,
        "title": title,
        "formula": formula,
        "substituted": substituted,
        "result": None if result is None or not math.isfinite(result) else round(result, 4),
        "unit": unit,
        "note": note,
        "source": source,
        "metric": metric,
    }


def num(v: float | None, digits: int = 1) -> str:
    """Human-readable number for formulas: thousands separators, trimmed decimals."""
    if v is None or not math.isfinite(v):
        return "–"
    if abs(v) >= 100:
        return f"{v:,.0f}"
    text = f"{v:,.{digits if abs(v) >= 10 else digits + 1}f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def pct(v: float | None) -> str:
    return "–" if v is None else f"{v * 100:.1f}%".replace(".0%", "%")


def erlang_c(c: int, offered: float) -> float | None:
    """Probability that an arrival waits in an M/M/c queue with offered load ``a = λ/μ``.

    Computed through the Erlang B recursion, which is stable for thousands of servers.
    Returns None when the system is unstable (a >= c).
    """
    if c <= 0 or offered <= 0:
        return 0.0 if offered <= 0 else None
    if offered >= c:
        return None
    b = 1.0
    for k in range(1, c + 1):
        b = offered * b / (k + offered * b)
    rho = offered / c
    return b / (1.0 - rho * (1.0 - b))


Q_THEORY = "https://en.wikipedia.org/wiki/Erlang_(unit)#Erlang_C_formula"
LITTLE = "https://en.wikipedia.org/wiki/Little%27s_law"


def generic(node: dict[str, Any], window_s: float, pool_based: bool) -> list[dict[str, Any]]:
    """Queueing-theory entries for any node that has slots (workers, connections, executors)."""
    s = node["summary"]
    out: list[dict[str, Any]] = []
    arrivals = s.get("arrivals") or 0
    lam = s.get("arrivalRps")
    if arrivals and window_s:
        out.append(
            formula(
                "arrival_rate",
                "Arrival rate",
                "λ = arrivals ÷ measurement window",
                f"{arrivals:,} ÷ {num(window_s)} s",
                lam,
                "req/s",
                metric="arrivalRps",
            )
        )
    slots = s.get("slots")
    hold = s.get("meanHoldMs")
    if not (pool_based and slots and hold and lam):
        return out
    c_slots = float(slots)
    cap = c_slots * 1000.0 / hold
    out.append(
        formula(
            "slot_capacity",
            "Capacity from slots",
            "capacity = slots × 1000 ÷ mean holding time",
            f"{num(c_slots)} × 1000 ÷ {num(hold, 2)} ms",
            cap,
            "req/s",
            note=(
                "Holding time is how long a request keeps its slot (worker, connection or "
                "executor), including downstream calls when the slot blocks on them."
            ),
        )
    )
    util_pred = lam * hold / (1000.0 * c_slots)
    measured = s.get("utilization")
    out.append(
        formula(
            "utilization",
            "Utilization",
            "ρ = λ × mean holding time ÷ (1000 × slots)",
            f"{num(lam, 2)} × {num(hold, 2)} ÷ (1000 × {num(c_slots)})",
            util_pred,
            "",
            note=f"Measured by the simulation: {pct(measured)} of slot-time busy."
            if measured is not None
            else None,
            metric="utilization",
        )
    )
    in_sys = s.get("meanInSystem")
    w = s.get("latencyMs", {}).get("mean") if isinstance(s.get("latencyMs"), dict) else None
    if w is not None and in_sys is not None:
        out.append(
            formula(
                "littles_law",
                "Little's law",
                "requests inside = λ × mean time inside",
                f"{num(lam, 2)} req/s × {num(w, 2)} ms ÷ 1000",
                lam * w / 1000.0,
                "requests",
                note=f"Measured: {num(in_sys, 2)} requests inside on average. Little's law holds "
                "for any stable system, so the two should agree closely.",
                source=LITTLE,
                metric="inflightMean",
            )
        )
    svc = s.get("meanServiceMs")
    c_int = round(c_slots)
    if hold > 0 and c_int >= 1:
        mu = 1000.0 / hold
        offered = lam / mu
        pw = erlang_c(c_int, offered)
        measured_wait = s.get("meanQueueWaitMs")
        if pw is None:
            out.append(
                formula(
                    "mmc_wait",
                    "Queueing model (M/M/c)",
                    "stable only if λ < slots × μ, with μ = 1000 ÷ mean holding time",
                    f"{num(lam, 2)} ≥ {num(c_slots)} × {num(mu, 3)}",
                    None,
                    "ms",
                    note="Arrivals exceed what the slots can serve, so the queue grows until "
                    "requests are rejected or time out; theory predicts no steady wait.",
                    source=Q_THEORY,
                    metric="queueWaitMs",
                )
            )
        else:
            wq = pw / (c_int * mu - lam) * 1000.0
            out.append(
                formula(
                    "mmc_wait",
                    "Queueing model (M/M/c)",
                    "Wq = C(c, a) ÷ (c·μ − λ),  a = λ ÷ μ,  μ = 1000 ÷ mean holding time",
                    f"C({c_int}, {num(offered, 2)}) = {pct(pw)} chance to wait; "
                    f"{pct(pw)} ÷ ({c_int} × {num(mu, 3)} − {num(lam, 2)}) s",
                    wq,
                    "ms",
                    note=(
                        f"Simulated mean wait: {num(measured_wait, 2)} ms. M/M/c assumes "
                        "random arrivals and exponential holding times; bursts, heavier tails "
                        "and downstream waits push the real wait above it."
                        if measured_wait is not None
                        else None
                    ),
                    source=Q_THEORY,
                    metric="queueWaitMs",
                )
            )
    if svc and s.get("cpuCapacityRps"):
        out.append(
            formula(
                "cpu_capacity",
                "Capacity from CPU",
                "CPU ceiling = cores × instances × 1000 ÷ CPU time per request",
                f"from the CPU spec and {num(svc, 2)} ms of work per request",
                s["cpuCapacityRps"],
                "req/s",
                metric="cpuUtilization",
            )
        )
    return out


def zipf_share(n: int, s: float, top: int = 1) -> float:
    """Share of requests that go to the ``top`` most popular of ``n`` keys under Zipf(s)."""
    if n <= 0:
        return 0.0
    # exact for small n; integral approximation of the harmonic number beyond 10k keys
    if n <= 10_000:
        total = math.fsum(k**-s for k in range(1, n + 1))
    else:
        head = math.fsum(k**-s for k in range(1, 10_001))
        if abs(s - 1.0) < 1e-9:
            tail = math.log(n / 10_000.5)
        else:
            tail = ((n + 0.5) ** (1 - s) - 10_000.5 ** (1 - s)) / (1 - s)
        total = head + tail
    top_sum = math.fsum(k**-s for k in range(1, min(top, n) + 1))
    return top_sum / total if total > 0 else 0.0
