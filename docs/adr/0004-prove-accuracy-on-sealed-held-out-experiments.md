---
status: accepted
date: 2026-10-30
decision-makers: Majid Abarghooei (owner)
---

# 0004. Prove accuracy on held-out experiments, sealed until a frozen engine predicts them

*Recorded retroactively on 2026-11-02. The decision dates from 30 Oct 2026, when the five held-out
experiments were registered in commit `ce2d109`.*

## Context and Problem Statement

ArchSim's value depends on how close its predictions come to real systems. Its first four benchmark
experiments (pg-mixed, pg-hot-row, cache-aside and Kafka) are in-sample: the model and the
calibration protocol were revised with their results in view. Errors measured on them show how
well the model fits those stacks, not how it will do on a customer's.

How should ArchSim show accuracy that a customer can trust?

## Considered Options

- Report accuracy on the experiments the model was tuned on
- Measure new experiments, then re-simulate them with the current engine
- Register held-out experiments in advance and keep them sealed until a frozen engine has predicted
  them; save each prediction before the first measurement

## Decision Outcome

Chosen option: "Register held-out experiments in advance and keep them sealed", because a prediction
saved before anything is measured can't have been tuned on the answer.

- **Registration:** java-mixed, go-mixed, python-mixed, chain and cache-ttl were registered in
  `benchmarks/run.py` (`Experiment.held_out`) with their date and commit.
- **Seal:** `run.py` refuses to calibrate or measure a held-out experiment unless the engine's source
  tree (`git rev-parse HEAD:packages/engine`) matches an entry frozen in `benchmarks/engines.json`
  and nothing in the engine or the harness is uncommitted. Until then, `run.py smoke` only checks
  that the stack works, and keeps no latency or results.
- **Prediction first:** the predictions are written, and their sha256 logged, before the first
  measured step.
- **Labels:** `report.py` labels each pair of engine and experiment `held-out` or `tuned-on`. An
  experiment counts as held out only for a frozen engine that doesn't list it in `tunedOn`.

### Consequences

- Good, because held-out numbers measure how ArchSim does on stacks it wasn't built on, which is the
  claim that matters to customers.
- Good, because the seal is enforced in code, not just by agreement.
- Bad, because a frozen engine's held-out numbers need a campaign of overnight benchmark runs before
  they exist.
- Bad, because an experiment used to tune a later engine becomes in-sample for that engine, so
  held-out experiments get used up over time.
- Bad, because the five held-out stacks, in four runtimes, have to be maintained.

### Confirmation

`benchmarks/tests/test_held_out.py` checks the seal. The `seal()` and `require_unsealed()` functions
in `benchmarks/run.py` enforce it.

## More Information

- [`docs/validation.md`, "Held-out experiments"](../validation.md#held-out-experiments)
- [ADR-0017](0017-freeze-python-engine-during-port.md) freezes engine 0.16.0 and runs the held-out
  campaign before the Java kernel is ported.
