# Performance

How fast the engine answers, measured on one laptop (11th Gen Intel Core i7-1165G7, 8 logical
CPUs, Windows 11, Python 3.12, and Java 25 for the Java simulator), and what was done about it.
The Python engine's numbers come from `scripts/perf.py` (`benchmarks/perf/baseline.json`,
`after.json`, and `python-0.16.0.json` for the Java port's comparison) and
`scripts/validate_sampled.py` (`benchmarks/perf/sampled-validation.json`), which went with that
engine at stage 5 of the Java port; the files stay as they were measured. The Java engine's come
from `./gradlew :archsim-engine:speed`, which times every template as shipped
(`-Parchsim.speed.out=benchmarks/perf/java-port.json` writes the committed file; see
[archsim-engine's README](../archsim-engine/README.md#tests)): rerun it on your machine before
quoting.

> The tables before the last section were measured with engine 0.10.0. Engine 0.11.0 runs the
> templates on AWS instance types and rounds generic sizes to standard shapes
> ([pricing.md](pricing.md)), which changes some parts' CPU and memory and so their latencies
> (the starter's p95 at 400 req/s, for example). Engine 0.16.0's templates were timed again for
> the Java port ([the last section](#the-java-simulator-stage-4-of-the-port)).

## Baseline: full simulation

Every template as shipped (its own traffic shape and duration, no scenarios), best of three runs:

<!-- the table below is filled from benchmarks/perf/baseline.json -->
| Template | Wall time | Requests | Simulated requests per second |
| --- | --- | --- | --- |
| black-friday-kubernetes | 45.4 s | 107,990 | 2,377 |
| cache-aside-starter | 6.8 s | 23,980 | 3,545 |
| iot-cassandra-quorum | 23.6 s | 99,869 | 4,238 |
| kafka-order-pipeline | 10.3 s | 54,106 | 5,268 |
| kubernetes-pods-pending | 35.0 s | 108,476 | 3,097 |
| kubernetes-shared-pool | 62.4 s | 110,391 | 1,770 |
| mongodb-hot-shard | 22.2 s | 80,034 | 3,606 |
| queueing-lab | 1.4 s | 24,154 | 17,071 |
| retry-storm-ttl-cache | 19.5 s | 103,083 | 5,278 |
| serverless-burst | 10.9 s | 33,768 | 3,105 |

**The target** (Phase 8 of docs/prompts/how-to-win.md): a 10,000 req/s, 10-minute run of a
six-part design, about 6 million requests, answered in under 60 s. The design
(`target_design` in `scripts/perf.py`, deleted with the Python engine): clients, a load
balancer, 320 API replicas, a Redis cache,
PostgreSQL in 4 shards with 2 read replicas each, and a third-party email API on writes; it
carries the load (99.98% success, no part saturated in a 12 s check). A first attempt used 40
replicas and was overloaded (22% success); its 1,508 s full run is not a valid baseline and is
not quoted below.

## Where the time goes

A profile of the slowest template (`kubernetes-shared-pool`, under cProfile; the top functions
are in `baseline.json`) shows no single hot spot: SimPy's own loop (`step`, the event heap,
`schedule`, resuming processes) leads, then resource requests and releases, ArchSim's metrics
(time buckets, histograms) and the components' own code. Each request is a coroutine that
crosses several components, each crossing a few events; a pure-Python engine of this shape runs
a few thousand requests per second per core. One hot spot did show up on the target design: a
least-connections load balancer scanned all 320 replicas for every request (17% of the run).
It now stops at the first idle replica in its rotation order, which is the same pick (loads are
never negative), so every template's result digest is unchanged. Tuning the rest might gain 20
to 30%, not the order of magnitude the target needs. A compiled core (Rust or Cython) would mean
replacing SimPy and every component's process code: not attempted (it would put determinism and
every accuracy check at risk for a speed-up that sampling gives without touching the model).

## What changed

1. **A process per run** (the API's default; see [architecture.md](architecture.md#run-lifecycle)).
   A simulation no longer shares the API's interpreter: it uses its own core, the API answers
   while it runs (a test checks `/meta` answers in under a second during a 10-minute run), up
   to `ARCHSIM_MAX_CONCURRENT_RUNS` runs use as many cores, and the results are identical to a
   thread's (same digest). `ARCHSIM_RUNNER=thread` keeps the old behaviour. A process that dies
   (out of memory, killed) fails only its own run (tested by killing one of two). One process
   is started ahead of time: importing the engine takes about a second on Windows, so a run
   that finds it ready starts in a tenth of a second. More workers (`archsim-api worker`) on
   more machines share the queue. (Stage 4 of the Java port replaced these workers: a run is a
   thread of the Java worker, with no interpreter to start;
   [ADR-0053](adr/0053-simulate-runs-in-the-java-apps-worker-role.md).)
2. **Parallel sweeps.** The breaking-point sweep runs its steps in separate processes
   (`workers`), used by `archsim check --knee` and the MCP `breaking_point` tool; every step is
   seeded, so the curve is the same either way (tested). An invalid design reports its issues
   from a worker just as it does in-process.
3. **Sampled mode (hybrid).** For a steady load (a constant rate, no scheduled faults) that runs
   much longer than it needs to settle, ArchSim simulates the warm-up and a steady sample in
   full (at least 10 s and 50,000 arrivals), checks that the sample is stationary (the
   measured window's two halves agree: throughput within 3% and at least 97% of the offered
   load, p95 within 15%, no queue, backlog or replica count still growing), and answers the
   whole run from it: rates, success and percentiles of a stationary system do not change with
   run length. Ramps, bursts, traces and faults are always simulated whole, and so is a run
   whose sample is not stationary (an overloaded system's queue grows). The report's
   `simulationMode` says which mode answered and why; counts and time series cover the sample.
   It is opt-in: `archsim run --sampled`, `run_simulation(..., mode="sampled")`, the MCP
   `simulate` tool's `mode`, the API's `mode: "sampled"` and the web app's Simulate menu
   ("Quick check (estimated)", offered only when the design qualifies).

4. **Load balancer pick.** Least-connections routing stops at the first idle replica (the same
   pick as a full scan), which matters for pools of hundreds of replicas.

## After

**The target design** (6 million requests requested):

| Mode | Wall time | Requests simulated | p95 | Throughput |
| --- | --- | --- | --- | --- |
| Full | 3,306 s (55 min) | 6,000,651 | 196.5 ms | 9,999.8 req/s |
| Sampled | **65.6 s** | 200,038 (10 s warm-up + 10 s sample) | 196.5 ms | 10,014.4 req/s (+0.15%) |

Sampled mode answers the 10-minute run 50 times faster with the same p95 and throughput, but at
65.6 s it **misses the 60 s target by 5.6 s** on this laptop. The full run shared the machine
with the validation below, so its time is high; the sampled run had the machine to itself
apart from the usual desktop load. Absolute times on this laptop moved by up to 60% over the
day with that load (the same template, the same code: `mongodb-hot-shard` 22 s at 14:20 and 36 s
at 16:30), so compare numbers measured side by side rather than across files.

**Same code, before and after, interleaved** (the Phase 7 engine against this one, the same
template alternately, two rounds each; identical result digests):

| Template | Before | After |
| --- | --- | --- |
| mongodb-hot-shard | 30.9 / 30.4 s | 29.8 / 29.9 s |
| iot-cassandra-quorum | 28.2 / 29.4 s | 27.3 / 27.2 s |
| black-friday-kubernetes | 38.0 / 37.3 s | 35.8 / 36.5 s |

Full simulation of the templates is 2 to 6% faster (the load balancer pick); the large gains
come from sampling, parallel sweeps and running in processes, not from a faster event loop.

**Sampled against full on every template** (`scripts/validate_sampled.py`): each template's base
rate as a constant load for 10 minutes (no faults, no scenarios), simulated both ways.

| Template | req/s | Answered by | p95 full / sampled | p95 error | Throughput error | Wall full / sampled |
| --- | --- | --- | --- | --- | --- | --- |
| black-friday-kubernetes | 300 | sample | 377.8 / 377.8 ms | 0.0% | +0.5% | 75 / 24 s |
| cache-aside-starter | 400 | sample | 104.3 / 106.4 ms | +2.0% | +0.5% | 86 / 18 s |
| iot-cassandra-quorum | 2,500 | sample | 12.3 / 12.0 ms | -2.0% | +0.1% | 688 / 34 s |
| queueing-lab | 200 | sample | 33.7 / 33.7 ms | 0.0% | -0.2% | 16 / 7 s |
| retry-storm-ttl-cache | 900 | sample | 164.4 / 164.4 ms | 0.0% | -0.1% | 193 / 23 s |
| kafka-order-pipeline | 400 | whole run (sample not steady) | 29.9 ms | - | - | 72 / 85 s |
| mongodb-hot-shard | 2,000 | whole run (overloaded, 70% success) | 518.6 ms | - | - | 754 / 807 s |
| kubernetes-pods-pending | 120 | whole run (too slow to sample: 50,000 arrivals take most of the run) | 68.8 ms | - | - | 29 / 29 s |
| kubernetes-shared-pool | 100 | whole run (same) | 82.2 ms | - | - | 40 / 40 s |
| serverless-burst | 150 | whole run (same) | 92.6 ms | - | - | 31 / 30 s |

Every sampled answer is within ±5% of the full run (the brief's bar); the largest p95 difference
is 2%. Two runs were refused because their samples were not steady (Kafka's backlog grows;
mongodb-hot-shard is overloaded), which costs the sample's time on top of the full run (7 to 19%
here). Sampled mode stays **opt-in**: the web app offers it as "Quick check (estimated)" and
labels its reports "Estimated", with charts that say they cover only the sample; full runs stay
the default.

## What-if questions: the instant estimate, not a faster core

The profile above has no single hot spot, so tuning the Python engine would gain 20 to 30% at
best, and a compiled core is out of scope. Quick what-if answers come from elsewhere: every
`/analyze` (each edit) returns an instant estimate from queueing formulas in about a millisecond
([simulation-model.md](simulation-model.md#instant-estimate-estimate)), which Results shows while
a run is queued or running and then sets against the simulation; and runs go to a queue with one
process each, so several run in parallel ([api.md](api.md)).

## What would close the remaining gap

- A faster event loop: profile-guided work on SimPy's hot path and ArchSim's metrics (20-30% at
  best in Python), or a compiled core (a large rewrite).
- Independent replications: run the steady sample as several shorter samples in parallel
  processes and merge their histograms (each needs its own warm-up).
- A shorter warm-up for designs that settle in seconds (the target design's 10 s is the
  document's own choice).

## The Java simulator (stage 4 of the port)

Stage 4 of the Java port is the compiled core the list above names: the whole simulator,
rewritten in Java. The sections above describe the Python engine, which stage 5 deleted (the
tag `engine-0.16.0` keeps it).

The Java simulator replays the frozen engine 0.16.0's runs figure for figure
([java-parity.md](validation/java-parity.md)) and runs the templates about 14 times faster:
13.9 times at the median of the 25 templates, and 12 to 17 times on each template's best of 5
runs. All 25 templates once take 34 s in Java against 484 s in Python, on the best of 5 runs.

Each template was timed 5 times on each engine, the Java runs after a warm-up run in the same
JVM; [the parity page](validation/java-parity.md#speed) gives each template's median. The
engines were timed one after the other on this laptop on 2026-11-09, with no other heavy work
and the CPU 10 to 12% busy before, between and after. Some runs were still disturbed: a
template's 5 runs spread by up to 68% in Python and 51% in Java, so the extremes of the medians'
ratios (9.4 and 20.6 times) say less than the best of 5. The figures are in
`benchmarks/perf/python-0.16.0.json` (`scripts/perf.py`, before stage 5 deleted it) and
`benchmarks/perf/java-port.json` (`./gradlew :archsim-engine:speed`). The target design has not
been timed in Java yet.
