---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0018. Judge p95 within a factor of two either way

## Context and Problem Statement

The proposed tier thresholds ([`docs/validation.md`](../validation.md#proposed-tier-thresholds))
decide when a technology counts as validated. Criterion 2 checks latency: at every load step up to
the measured knee, the predicted p95 must be close to the measured one, in at least two of three
runs.

Until now "close" meant within ±100% of the measured p95, or within 3 ms. That rule is one-sided.
A prediction that is too high fails once it passes double the measured value. A prediction that is
too low can never be more than 100% below, so it always passed:

- pg-mixed passed at 3,788 req/s with 2.0 ms predicted against 30.7 ms measured, 15 times too low.
- Kafka passed at 1,894 and 2,272 req/s with 2.8 and 4.7 ms predicted against 14.5 and 28.7 ms.

A latency prediction that is too low is the more dangerous miss: a design that looks safe breaches
its objective in production. And the rule had to be settled before the held-out campaign
([ADR-0017](0017-freeze-python-engine-during-port.md)) measures anything. Changing it after seeing
those results would look like moving the goalposts.

How should criterion 2 judge a step's p95?

## Considered Options

- Within a factor of two either way (from half to double the measured p95), or within 3 ms
- Keep "within ±100% or 3 ms"

## Decision Outcome

Chosen option: "Within a factor of two either way, or within 3 ms", because it treats a prediction
that is too low exactly like one that is too high. It keeps the old allowance above the measured
value, and the 3 ms floor, so that sub-millisecond noise doesn't fail a step.

- `benchmarks/report.py`:
  - `p95_factor` says how many times off a prediction is: 2.0 for both double and half;
  - `p95_within` applies the rule, with `P95_FACTOR = 2.0` and `P95_TOLERANCE_MS = 3.0`.
- The worst step that /accuracy shows is ranked by the same factor, so half counts as far off as
  double.
- `accuracy.json`'s `thresholds.p95Error` becomes `thresholds.p95Factor`, and the page's wording
  follows.

### Consequences

- Good, because the miss that breaks a customer's objective now fails the criterion.
- Good, because the held-out results will be judged by a rule fixed before they were measured.
- Bad, because no experiment meets criterion 2 on the v6 runs any more. pg-mixed and Kafka (0 of 3
  runs each) join hot-row and cache-aside. No prediction changed; the rule now shows what it hid.
- Neutral, because the thresholds are still a proposal: no technology was validated before this
  change or after it.

### Confirmation

The tests in `benchmarks/tests/test_accuracy_benchmarks.py` pin the rule in both directions, the
3 ms floor, and the misses on the committed results. `docs/validation.md`'s tier table is generated
from the same function.

## More Information

- [ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md): accuracy is proven on sealed
  held-out experiments.
- [ADR-0017](0017-freeze-python-engine-during-port.md): the engine freeze and the campaign's timing.
