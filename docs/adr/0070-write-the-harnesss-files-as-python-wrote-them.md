---
status: proposed
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0070. Write the harness's files as the Python harness wrote them, byte for byte

## Context and Problem Statement

The harness writes JSON under `benchmarks/results` (each run's calibration, predictions,
measurements and comparison, the development checks) and generates `docs/validation.md`'s blocks,
one SVG chart per experiment, `accuracy.json` (twice: `docs/validation/` and the web app's copy),
`dev-checks.md`, `knee-range.md` and `java-parity.md`. Stage 7's parity bar: regenerating them
from the committed results gives the same bytes, or differences that are exact, documented and
justified; and past and future runs stay comparable.

The Python harness wrote them with Python's conventions: a float as its shortest `repr`
(`1e-05`, `2.0`), an `int` without a decimal point, f-string format specs (`{v:,.0f}`,
`{v:+.0f}%`, `{t:g}`, `{x:.2g}`) rounding the float's exact binary value half to even,
`json.dumps(indent=2)` (non-ASCII escaped in the results, kept in `accuracy.json`), and charts
as SVG text written line by line. How does the Java harness write the same bytes?

## Decision Drivers

- The committed results are the record: a number must not change in its last digit because the
  language changed.
- A JSON number read as an integer stays one: `"offeredRps": 250` must not come back `250.0`.
- No new dependency for formatting or charts.

## Considered Options

- Python's conventions through the model's text package, values kept as JSON nodes, SVG written
  as text
- Java's own conventions (`String.format`, Jackson's default writer) and an SVG or charting
  library, with the committed files regenerated once in the new form
- Java's conventions for new runs only, leaving the committed files as they are

## Decision Outcome

Chosen option: "Python's conventions through the model's text package", because the package
already does exactly this for the engine's and the CLI's output
([ADR-0027](0027-python-text-conventions-in-one-package.md)), and only that keeps every
committed file reproducible.

- **Numbers in text:** `PythonFormat` (format specs, half to even on the exact value) and
  `PythonText.repr`; Python's `round()` is the engine's `PythonRound`.
- **JSON:** values are Jackson trees, read from the results and built with the tree API, never
  converted to `double` and back: an integer node stays an integer, and arithmetic follows
  Python's (`int + int` is an `int`, `/` a float, `round(x)` an `int`). Files are written by
  `PythonJson.writeIndented` with each file's own `ensure_ascii`.
- **Charts:** the SVG is text, line for line as `report.py` built it, its coordinates formatted
  by the same specs. A charting library would redraw every chart and add a dependency for two
  panels of lines and dots.
- **Documented differences**, the only ones: where a generated file names the command that
  wrote it, it names the Gradle command ([ADR-0069](0069-run-the-harness-from-gradle-with-picocli-commands.md)):
  `accuracy.json`'s `generatedBy`, and the opening lines of `dev-checks.md`, `knee-range.md` and
  `java-parity.md`. Each is listed in `archsim-benchmarks/README.md` with the commit that made
  it.

### Consequences

- Good, because the parity bar is a test: regenerate everything, compare bytes.
- Good, because a future run is written exactly as the kept ones, so comparing them needs no
  conversion.
- Bad, because the harness's code works on JSON trees and Python's number rules rather than on
  typed records, which reads less like ordinary Java; the reasons are in the module's README.

### Confirmation

Tests in `archsim-benchmarks` regenerate, from `benchmarks/results` as committed:
`docs/validation.md`, its nine charts, both `accuracy.json` files, `dev-checks.md`,
`knee-range.md`, `java-parity.md`, and every kept run's `comparison.json`, and compare them with
the committed bytes.

## Pros and Cons of the Options

### Java's own conventions and a charting library

- Good, because the code would be plain Java.
- Bad, because every committed file would change in form, many numbers in their last digit, and
  the old and new files could no longer be compared as text.

### Java's conventions for new runs only

- Good, because nothing committed changes.
- Bad, because `report` reads old and new runs into one page, which would mix both forms, and
  the parity bar could not be tested.

## More Information

- [ADR-0027](0027-python-text-conventions-in-one-package.md): Python's text conventions in one
  package.
- [ADR-0071](0071-python-statistics-in-the-harness.md): the statistics behind the numbers.
