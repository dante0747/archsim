---
status: proposed
date: 2026-11-08
decision-makers: Majid Abarghooei (owner)
---

# 0050. One report per seed on every platform, with the Python engine's digest definitions

## Context and Problem Statement

The Java simulator now writes a run's whole report, through its provenance: the versions, the
seed, the scenario and its overrides, `inputDigest` (the SHA-256 of the design simulated, as
canonical JSON) and `resultDigest` (the same over the summary, time series, parts, links and
window). Reports from both engines will sit side by side in the database: stored runs are not
migrated. The Python engine promised the same digest for the same input, seed and engine version
only on the same platform and Python version, and its provenance names `python` and `simpy`. What
should a Java report promise about repeating itself, what does its digest mean beside a Python
report's, and what does its provenance name?

## Decision Drivers

- A run repeated anywhere should give the same report; a support question should not depend on
  the machine.
- Where a Java run and a Python run of the same input meet (a comparison, a re-run), the app
  should say truthfully why their digests differ.
- The provenance should say truthfully what ran.
- Java's `Math.log`, `exp` and `pow` may differ in the last place by platform; `StrictMath`'s may
  not.

## Considered Options

- Keep the digests' definitions, each naming a result within one engine; make every Java report
  a function of its input and seed alone; name the Java runtime in the provenance
- Keep the digests' definitions and the Python engine's promise (same platform only)
- New digests, over the Java's own JSON writing

## Decision Outcome

Chosen option: "Keep the digests' definitions, each naming a result within one engine; make every
Java report a function of its input and seed alone; name the Java runtime in the provenance".

- **One report per seed.** A Java run is deterministic for a given seed on every platform and
  JVM: the same document, scenario, seed and engine version give the same report, but for the
  provenance's `wallSeconds` and `java`.
  - Every transcendental function in the engine and the model goes through `StrictMath`; one
    ArchUnit rule (`StrictMathRule`), checked in each module, refuses `Math.log`, `exp`, `pow`
    and the like. The random
    streams already did (ADR-0047).
  - What the report lists, it lists in an order fixed by the design or by sorting, never by a
    hash table's iteration.
  - A run is one thread, and reads no clock but for `wallSeconds`.
- **A digest names a result within one engine.** The digests keep their definitions: SHA-256 of
  canonical JSON (sorted keys, no spaces, Python's spelling of floats), `inputDigest` over the
  design simulated and `resultDigest` over the summary, time series, parts, links and window. A
  Java run and a Python run of the same input and seed agree to the parity bar (ADR-0049) but
  need not share a `resultDigest`; they share it where every number agrees to the last bit, as
  in all twelve runs replayed today, which is measured, not promised: the parity report
  (`docs/validation/java-parity.md`, later in stage 4) will say how many template runs share it.
  Their `inputDigest`s match.
- **Where digests are compared:**
  - A run comparison warns, for the same inputs and seed with other digests, of "different engine
    builds or platforms". When one run is Python's and the other Java's (only one provenance has
    `java`), it names the two engines instead, whatever the inputs and seeds, as it names two
    engine versions.
  - The web app's re-run says "Reproduced exactly" for an equal digest; otherwise it now names
    both engine versions when they differ, and suggests a different engine, build or platform
    when they do not. The Java engine's version becomes 1.0.0 at stage 4's end, so the versions
    tell the engines apart from then.
  - The golden files' check and the performance records (`scripts/perf.py`) compare the Python
    engine's digests with its own. The stage-4 speed report compares Java with Python by its
    figures, not its digests.
  - Share links, the check action's comment, sweep rows, the benchmark harness's predictions and
    the MCP server's run id show or store a digest and compare none.
  - The re-run's promise of the same digest is worded "on the same engine" now: the API's
    reference (`docs/api.md`), the report's Markdown export and the re-run's Javadoc
    (`RunController`, `RunChanges`). The component guide's determinism test runs one engine
    twice (`docs/extending-components.md`), so it stands.
- **The provenance names the Java runtime**: `java` (the `java.version` property, such as
  `25.0.3`) in place of `python`; `simpy` goes, and no kernel version takes its place: SimPy was
  a dependency that could change results, the Java kernel is the engine's own code, and
  `engineVersion` names it.
- **The report's version is 1.15** from this change: `EngineVersions.REPORT`, which `/meta`
  serves and the Java simulator writes. Until the Java worker writes runs (a later slice of stage
  4) the Python workers still store 1.14 reports. Every reader accepts both shapes: the engine
  reads `engineVersion`, `seed`, `resultDigest` and `java` of a provenance, and neither `python`
  nor `simpy`; the web app declares `python` and `simpy` optional and `java` (1.15+), and
  otherwise passes the version through. The golden files' `REPORT_VERSION`, the Python engine's
  1.14, is no longer checked.

### Consequences

- Good, because a Java run reproduces on any machine, which the Python engine could not promise.
- Good, because a Java run beside a Python run of the same input is named for what it is, not
  taken for another build of one engine.
- Good, because the provenance says which runtime ran the report.
- Bad, because `StrictMath` is slower than `Math`'s intrinsics on some functions; the simulator's
  speed is measured against `docs/performance.md` with it.
- Bad, because `/meta` says 1.15 while the Python workers still store 1.14 reports, until the
  Java worker replaces them; nothing reads the version but to show it.
- Neutral: the report's model note keeps its words ("on the same platform") while the replays
  hold Java to Python's report; it is still true, if less than Java promises. The determinism
  notes in `docs/architecture.md` and `docs/simulation-model.md` change when the Java worker
  writes runs.

### Confirmation

- `SimulationCasesTests` compares both digests exactly for every recorded run, and fails at
  `/provenance/resultDigest` with the window left out of it (checked); it checks the version and
  the `java` key the Java writes.
- `ReportRecordKeysTests` checks the report records against the Java simulator's reports as
  well as the golden files' (ADR-0039), and reads `java` back from one.
- `StrictMathRule` fails on a `Math.exp` call (checked in the model).
- `ComparisonWarningsTests` and the web app's `reproduction.test.ts` check the two places that
  meet both engines' digests.

## Pros and Cons of the Options

### Keep the Python engine's promise

- Good, because nothing changes.
- Bad, because it promises less than Java can keep, for no gain.

### New digests

- Good, because the Java would owe nothing to Python's spelling of floats.
- Bad, because no Java report's digest would equal a Python report's, so every stored run would
  look different from its re-run, even where the figures agree to the last bit.

## More Information

- [ADR-0047](0047-draw-python-random-streams-in-java.md): the random streams, through
  `StrictMath`.
- [ADR-0039](0039-engine-reads-reports-as-partial-records.md): the report records, now checked
  against the Java writer too.
- [ADR-0027](0027-python-text-conventions-in-one-package.md): Python's spelling of numbers,
  which the digests hash.
