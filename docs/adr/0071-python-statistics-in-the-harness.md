---
status: proposed
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0071. Reduce the results with Python's statistics, ported exactly

## Context and Problem Statement

The harness reduces measurements to the numbers the results record and the pages show: medians of
repeats and of input replicates, means of logarithms in the range fit, the inverse normal
distribution for the knee's range, binomial tails for the range's coverage verdict, and sums in
many places. The Python harness used `statistics.median`, `statistics.fmean` (an exactly rounded
sum, `math.fsum`, over the count), `statistics.NormalDist().inv_cdf` (Wichura's AS241),
`math.comb`, `round()` (half to even on the exact binary value), `math.log`, `exp`, `sqrt` and
`log10`, and `sum()` in list order. Stage 7's parity bar asks that the committed results reduce to
the same numbers: medians, knees, spreads and digests.

Which implementations does the Java harness use?

## Decision Drivers

- Same inputs, same bits: a median or a fitted sigma that moves in its last digit changes a
  committed JSON file, and a knee search that compares against it may take another branch.
- The functions used are few and small.
- No dependency for them.

## Considered Options

- Port the few functions exactly, in one class, tested against values Python computed
- Apache Commons Statistics or Math
- Plain Java arithmetic (`DoubleStream.average()`, `Math`)

## Decision Outcome

Chosen option: "port the few functions exactly", because only the same algorithms give the same
bits, and they are short.

- `PythonValues.median`, over the results' JSON values: the middle value, which keeps its type
  (an integer stays one), or the two middle ones' sum over two.
- `PythonStatistics` in `archsim-benchmarks`, each added with the first slice that needs it:
  `fmean` (an exactly rounded sum over the count: the sum is made exactly in `BigDecimal` and
  rounded once, which is what `math.fsum` returns), `normalInverse` (AS241, as `statistics`
  implements it) and `comb`.
- `round()` is the engine's `PythonRound`; integer results stay integers
  ([ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md)).
- Logarithms, exponentials and square roots are `StrictMath`'s, as the engine's random streams
  are ([ADR-0047](0047-draw-python-random-streams-in-java.md)): reproducible on every machine.
- Sums run in the order Python's ran (list order), and work spread over threads (re-simulations,
  the range's knee searches, which Python spread over processes) is gathered in submission
  order before anything is summed.

### Consequences

- Good, because the reduced numbers can be compared bit for bit, and are.
- Good, because no library is added for four functions.
- Bad, because `StrictMath` and the C library Python called may differ in a last bit for some
  inputs; where that reaches a committed number, the parity tests show it, and the difference is
  documented with the number.

### Confirmation

`PythonValuesTests` and `PythonStatisticsTests` (values computed by CPython 3.12 for the cases
that need care: even counts, integer inputs, sums that cancel, the tails of the inverse normal),
and the parity tests of [ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md), which
reduce every kept run again.

## Pros and Cons of the Options

### Apache Commons Statistics or Math

- Good, because they are tested, maintained libraries.
- Bad, because their algorithms differ from Python's in the last bits (a mean summed naively, a
  median by interpolation, another inverse normal), so the results would not reduce to the same
  numbers.

### Plain Java arithmetic

- Good, because it needs nothing.
- Bad, because `average()` sums with compensation of its own, and `Math`'s functions may differ
  between JVMs and machines.

## More Information

- [ADR-0047](0047-draw-python-random-streams-in-java.md): Python's random streams in Java, with
  `StrictMath`.
- [ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md): the bytes the numbers are
  written as.
