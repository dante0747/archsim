---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0017. Freeze the Python engine during the port; run the held-out campaign before stage 4

## Context and Problem Statement

Engine versions up to 0.16.0 were tuned on four in-sample experiments. On those runs the median
absolute knee error is 15% on the latest run of each experiment and 19% over all 31 counted runs
([`dev-checks.md`](../validation/dev-checks.md)). Five held-out experiments are sealed until a
frozen engine predicts them ([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md)).

The Java port ([ADR-0005](0005-port-to-java-25-in-stages.md)) checks the Java engine against the
Python one, so a Python engine that keeps changing would move the target.

What happens to accuracy work during the port, and when do the held-out experiments run?

## Considered Options

- Keep tuning the model during the port
- Freeze the engine, and run the held-out campaign before stage 4
- Freeze the engine, and run the campaign after the port, on the Java engine
- Freeze the engine, and decide the campaign's timing at stage 4

## Decision Outcome

Chosen option: "Freeze the engine, and run the held-out campaign before stage 4".

**Why freeze:**

- the port needs a fixed reference;
- more tuning on the same four experiments mostly fits those stacks better, and says nothing about
  a customer's;
- each postponed improvement is built once, in Java.

**Why run the campaign before stage 4:**

- it costs two or three nights of benchmark time and no development time;
- stages 0–3 don't touch the simulation, so they go ahead in parallel;
- it gives evidence on unseen stacks in days rather than after 6–10 weeks;
- the Java engine is later checked against the same measurements, labelled re-simulated.

**What this means in practice:**

- Engine 0.16.0 is frozen in `benchmarks/engines.json`: its `packages/engine` tree, the time of
  the freeze and the tag `engine-0.16.0`. Harness fixes made before the freeze stay.
- Nothing under `packages/engine` changes during the port, not even tests: the freeze is that
  directory's git tree. Port tooling that imports the engine lives outside it.
- A bug the port finds in the Python engine is reported, fixed in both engines and recorded. The
  fix is a new engine version with its own entry in `engines.json`; the frozen entry and the
  predictions it saved are never edited.
- **Postponed until the port is complete:**
  - cache-aside's capacity: −24.5% in-sample, outside the ±15% target;
  - pg-mixed's CPU cost near full load;
  - refitting the predicted range with the v6 runs;
  - ranking replicates by mean rather than median;
  - a CPU model for the Kafka broker and consumers;
  - parallel fan-out calls;
  - retry budgets and circuit breakers;
  - right-sizing from a run (W15).

**Status (12 Nov 2026):** the frozen engine is deleted at stage 5's exit of the port, with its
MCP server (`packages/engine`, `packages/mcp`); the tag `engine-0.16.0` keeps it as it was
frozen, and `benchmarks/engines.json` keeps its entry. The Java engine, 1.0.0, replays it figure
for figure, and what it recorded stays as the Java tests' data, not recorded again: the golden
files, the simulation baselines and the recorded cases
([`parity/README.md`](../../parity/README.md)). With the directory gone, no test holds a commit
under `packages/engine` to the frozen tree;
`test_engine_0_16_0_is_frozen_for_the_held_out_campaign` still checks the entry against its
tag's tree where the tag is in the clone.

**Status (13 Nov 2026):** the history rewrite of 13 Nov put the licence in every commit, which
changed the tag's `packages/engine` tree: the recorded `4da78ff6` is `d8340657` today.
`benchmarks/engines.json` keeps the recorded tree, and
[`benchmarks/results/tree-ids.tsv`](../../benchmarks/results/tree-ids.tsv) maps each recorded
engine tree to today's. The test compares the tag's tree with the mapped one, and the held-out
seal compares the running engine's tree with it.

### Consequences

- Good, because the parity checks compare against an engine that doesn't move.
- Good, because the held-out results describe the engine customers use today, predicted before
  anything was measured.
- Bad, because cache-aside's capacity stays predicted about 25% low until after the port.
- Bad, because the Java engine's held-out numbers are re-simulated, not prospective. A prospective
  check of the Java engine needs a later campaign.

### Confirmation

`run.py` measures a held-out experiment only with an engine frozen in `benchmarks/engines.json`
and a clean tree (see ADR-0004). Two tests in `benchmarks/tests/test_held_out.py` hold the freeze:
`test_engine_0_16_0_is_frozen_for_the_held_out_campaign` (the entry, and its tag's tree where the
tag is in the clone) and `test_the_last_engine_entry_is_the_engine_in_this_tree`, which fails on
any commit under `packages/engine` until it has a new version and entry.

## More Information

- [`docs/validation.md`](../validation.md) and [`IMPLEMENTATION_STATUS.md`](../../IMPLEMENTATION_STATUS.md)
- [ADR-0018](0018-p95-within-a-factor-of-two.md): the p95 criterion settled before the campaign
