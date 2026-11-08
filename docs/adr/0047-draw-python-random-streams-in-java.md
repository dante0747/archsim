---
status: accepted
date: 2026-11-08
decision-makers: Majid Abarghooei (owner)
---

# 0047. Draw the Python engine's random streams in Java

## Context and Problem Statement

Stage 4 of the [Java port](../proposals/java-25-port.md) ports the simulator. Every random choice
the Python engine makes comes from a named stream: `runtime.derive_rng(seed, name)` seeds a
`random.Random` (Python's Mersenne Twister) with the first 8 bytes of the SHA-256 of
`"<seed>:<name>"`, and each component draws from its own streams (`arrivals`, `mix`, `keys`,
`node:<id>:service`, `edge:<id>:latency`, ...). The engine draws with `random()`,
`expovariate`, `lognormvariate` (through `normalvariate`), `uniform` and `randrange`, and samples
the design's distributions (constant, exponential, uniform, lognormal, empirical) and key ranks
(Zipf) from them. Which generator and which sampling algorithms should the Java simulator use?

## Decision Drivers

- The parity bar compares Java's runs with Python's over 20 seeds per template case; per-component
  counters must match on fixed inputs ([ADR-0013](0013-event-driven-simulation-kernel.md)).
- A semantic slip in a 10,000-line port should show where it happens, not only in a median.
- One seed gives one result on every platform and JVM.
- Speed: a draw is on the simulator's hottest path.

## Considered Options

- Python's generator and algorithms, ported: MT19937 seeded as `random.Random(int)` seeds it,
  Python 3.12's 53-bit `random()` and samplers, the same stream names and derivation
- A Java generator (`SplittableRandom` or an `L64X128MixRandom`) with the same stream names
- One Java generator for the whole run

## Decision Outcome

Chosen option: "Python's generator and algorithms, ported", because a Java run of a Python seed
then draws the same numbers in the same order, so the two runs can be compared draw for draw
(and event for event, with a kernel that keeps SimPy's order of events), not only in distribution.

- `io.archsim.engine.random.PythonRandom`: MT19937 (`MersenneTwister`), seeded with the
  integer's absolute value cut into 32-bit words as CPython's `init_by_array` key, and Python
  3.12's algorithms under Java names: `nextDouble` (`random()`, from two words of 27 and 26
  bits), `nextBits` (`getrandbits`, 1 to 32 bits), `below` (`randrange(n)`, by rejection on
  `n.bit_length()` bits), `uniform`, `exponential` (`expovariate`), `normal` (Kinderman and
  Monahan's `normalvariate`) and `logNormal` (`lognormvariate`).
- `RandomStreams`: `derive_rng` (`"<seed>:<name>"` in UTF-8, SHA-256, first 8 bytes big-endian).
- `Sampler`: the model's distributions sampled as the engine samples them, capped at
  `MAX_SAMPLE_MS`; a constant, and a lognormal whose p95 is its median, draw nothing.
  `ZipfSampler`: key ranks by bisection of the cumulative weights. The model's records stay
  without random numbers.
- Logarithms, exponentials and powers on this path are `StrictMath`'s (fdlibm), the same on every
  platform. They may differ from the C library Python calls in the last place. Measured on
  200,000 draws against Python 3.12.13 on Windows (the platform the oracle was recorded on,
  with the Universal C Runtime's `log` and `exp`), `StrictMath.log` differs on 7.3% of them and
  `StrictMath.exp` on 9.6%, each by one unit in the last place. `Math.log` and `Math.exp` differ
  on only 0.2% and 0.5%, but HotSpot's intrinsics are not specified to give the same results on
  every platform, which would break the driver above. CPython on glibc was not measured. A draw
  one unit off moves a simulated time by about 1e-16 of itself. That changes what follows only
  when it flips a comparison: the order of two events that close, a threshold (a hit ratio, a
  timeout), or the acceptance test of `normal`, which then draws again and shifts every later
  draw on that stream. Each is about as likely as 1e-16 per draw.

### Consequences

- Good, because the Java simulator can replay the runs the golden files record and be checked
  number by number, and per-component counters are compared on the same draws.
- Good, because the statistical bar compares like with like: seed n against seed n.
- Good, because MT19937 is a well-studied generator; its period and equidistribution are ample
  for simulation.
- Bad, because MT19937 is slower and larger (2.5 KB of state per stream) than a modern Java
  generator. A run has a few dozen streams, and a draw costs nanoseconds next to an event.
- Bad, because the draws are tied to Python's algorithms after Python is gone. Changing them
  later changes every result and is an engine change of its own, with a new version.

### Confirmation

`RandomCasesTests` compares the Java draws with those `scripts/export_random_cases.py` recorded
from the frozen engine and Python 3.12 on Windows: derived streams (seeds 0 to 2^32 - 1, a
non-ASCII name), integer seeds of one, two and zero words, every `getrandbits` width,
`randrange` with rejections, the samplers interleaved on one generator, each distribution and
Zipf ranks. Draws that are arithmetic match bit for bit; those through `log` or `exp` to a
relative 1e-14.

## Pros and Cons of the Options

### A Java generator with the same stream names

- Good, because it is faster and needs no port.
- Bad, because Java's runs could only be compared with Python's in distribution: a slip would
  have to move a median by 2% to be seen, and the counters on fixed inputs would only match
  where nothing is random.

### One Java generator for the whole run

- Good, because it is the simplest.
- Bad, because a change in one component would shift every other component's draws, so one
  change would move every number, as the per-component streams were made to prevent.

## More Information

- [ADR-0013](0013-event-driven-simulation-kernel.md): the event-driven kernel.
- `packages/engine/src/archsim_engine/runtime.py` (`derive_rng`) and `distributions.py`.
