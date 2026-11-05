---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0027. Reproduce Python's text conventions for numbers and values in one package

## Context and Problem Statement

ArchSim's messages, and some of its contract, were written by a Python implementation, and they
show it: `256 + 2 MiB` (`format(x, "g")`), `hit ratio 80%` (`".0%"`), `up to 1,234 calls`
(`",.0f"`), `Input tag '5' found using 'kind'` (`str()` of a value), schema titles such as
`Latencyp95Ms` (`str.title()`), and price snapshots hashed over `json.dumps` text. Java writes the
same numbers differently (`1.0E-5` for `1e-05`, `HALF_UP` where Python rounds the exact binary value
half to even). Stage 1's messages, and stage 3's estimates and reports, must match word for word.

Where should Java get Python's way of writing numbers and values?

## Considered Options

- One package, `io.archsim.model.text`, that reproduces the Python conventions the messages use
- `String.format` and `DecimalFormat` at each message, tuned until the golden files pass
- Change the messages to Java's spelling

## Decision Outcome

Chosen option: "one package", because the conventions are rules with edge cases (rounding, the
switch between fixed and scientific notation, the exponent's digits), and rules belong in one place,
tested once.

- `PythonText`: a float's `repr` (the shortest digits that read back, in Python's layout), `str()`
  and `repr()` of a value read from JSON, `str.title()`, and truthiness.
- `PythonFormat`: the format-spec mini-language for numbers: fill, alignment, sign, zero padding,
  width, grouping and precision with the types `d`, `f`, `e`, `g`, `%` and none, rounding the exact
  binary value half to even as Python does. A spec it does not support throws, so nothing is
  silently formatted differently.
- Messages write numbers through these: `PythonFormat.format(memoryGib, "g")`.
- **What belongs here:** Python's conventions for text that any module may need. Besides the two
  above, `CanonicalJson` writes `json.dumps` text with sorted keys, which price-snapshot hashes
  and report digests are taken over.
- **What the engine adds of its own:** conventions only the engine uses stay in it.
  `io.archsim.engine.text` has `NumberText` (how its formulas, percentages, figures and latencies
  read), `HalfUp` (halves away from zero) and `PythonStrings` (`str.casefold()` and code-point
  order). `io.archsim.engine.numeric` has Python's arithmetic, such as `PythonRound` and
  `FloorDivision`.

### Consequences

- Good, because a message reads as in Python and the format spec in the Java source is the one in
  the Python source, so the two are compared at a glance.
- Good, because stage 3 reuses it for estimates, sizing and reports, where most numbers are.
- Bad, because the names say Python in a Java code base. They describe where the conventions come
  from; after the port they stay the conventions of ArchSim's text.

### Confirmation

`PythonTextTests` and `PythonFormatTests` check Python 3.12's own outputs, and jqwik properties check
that a repr reads back as the same float and that rounding stays within half a unit; the golden
files check the messages that use them.

## Pros and Cons of the Options

### `String.format` at each message

- Good, because it is plain Java.
- Bad, because `%.0f` rounds half up and `%g` keeps trailing zeros: every message would carry its
  own corrections, and the next one would get them wrong.

### Java's spelling

- Bad, because messages are shown to users and compared by the golden files: changing them is a
  contract change, which the port does not make ([ADR-0005](0005-port-to-java-25-in-stages.md)).

## More Information

- Python's format-spec mini-language: <https://docs.python.org/3/library/string.html#formatspec>
