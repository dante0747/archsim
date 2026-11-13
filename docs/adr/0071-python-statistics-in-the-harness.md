---
status: accepted
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
`log10`, and `sum()`, which since CPython 3.12 adds floats with Neumaier's compensation (twelve
0.8s add up to 9.600000000000001, where a loop gives 9.6). Stage 7's parity bar asks that the
committed results reduce to the same numbers: medians, knees, spreads and digests.

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
- `PythonValues.sum`, CPython 3.12's `sum()`: integers exactly while every value is one, then
  floats by Neumaier's compensated summation, and an integer after the first float plainly.
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

**Status (13 Nov 2026):** with the range fit ported (stage 7, slice S7-5), every place
this decision covers has run against the committed results.
- `StrictMath` and the C library CPython calls on Windows (MSVC's) do differ in a last bit for
  some inputs: `exp(0.7103238548114007)` is 2.034650083178199 in CPython 3.12 and
  2.0346500831781995 in Java.
- So do squares. CPython's `x ** 2` calls the C library's `pow`, which MSVC does not round
  correctly either: on CPython 3.12.13 on Windows about 1 in 2,000 values between 1e-5 and 3,000
  squares a last bit away from `x * x` (953 of 2,000,000 drawn uniformly), and
  `2.983367844600024 ** 2` is 8.900483696193394 where the product is 8.900483696193392. The port
  writes every square as a product, which IEEE 754 rounds correctly on every machine: the range
  fit's `(x - mean) ** 2`, `sum(parts) ** 2` and `cal ** 2 + model ** 2`, and the model inputs'
  `sigma ** 2` (the lognormals and the GC pauses) and `mean ** 2` (`lognormal_moments`,
  `link_leg`).
- No committed number reaches either difference. Every kept run's development check reduces to
  the recorded one byte for byte (65 of 65), and `benchmarks/knee-range.json` refitted from its
  own data comes out byte for byte but for what names it. The floats the harness writes are
  rounded far above a last bit: inputs to 4 or 6 decimals, sigmas to 4, ranges to whole req/s.
- The difference shows only in an unrounded intermediate, where its test holds it to the bit it
  can differ by (`ModelDistributionsTests`: a lognormal's variance within 2e-16).
- `fmean`'s exact sum is CPython's `math.fsum` itself (Shewchuk's partials with its closing
  half-even correction), which replaces the `BigDecimal` sum the Decision's `fmean` bullet names.
  Both give the correctly rounded sum; `math.fsum`'s algorithm also keeps Python's errors for two
  infinities and an overflow.

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
