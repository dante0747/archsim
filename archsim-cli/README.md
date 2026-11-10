# archsim-cli

The `archsim` command line, on the Java engine. Stage 4 of the
[Java port](../docs/proposals/java-25-port.md) brought `archsim run`, which the benchmark harness
runs to simulate on the Java engine
([ADR-0051](../docs/adr/0051-start-the-java-cli-in-stage-4-with-archsim-run.md)); stage 5 brings
the rest of the Python CLI's commands, with the same flags, lines and exit codes
([ADR-0055](../docs/adr/0055-the-java-clis-output-contract.md)).

```bash
./gradlew :archsim-cli:installDist                    # archsim-cli/build/install/archsim/bin/archsim
archsim-cli/build/install/archsim/bin/archsim run examples/queueing-lab.json
```

The start script runs the `java` that `JAVA_HOME` names, else the one on the `PATH`; it needs
Java 25. The command writes UTF-8 whatever the console's encoding, as the Python CLI's trust
lines did: a Windows console on a legacy code page shows `→` garbled.

## Commands

Each prints what the Python engine's CLI (`packages/engine/src/archsim_engine/cli.py`, engine
0.16.0) printed, word for word, on the same stream, with the same exit code. JSON is written as
Python's `json.dumps(value, indent=2)` writes it, with the same values (ADR-0055). Help and usage
errors are picocli's words; a usage error exits with code 2, as argparse's did.

| Command | What it does |
| --- | --- |
| `archsim validate FILE` | `VALID` or `INVALID` and the design's issues on standard output; exit code 1 for an invalid design |
| `archsim run FILE [--scenario ID] [--seed N] [--sampled] [--out FILE]` | simulates a design: the summary line, a quick check's note, how far to trust the run, its findings and its digests ([below](#archsim-run)) |
| `archsim analyze FILE [--scenario ID]` | the auto sizes, cost estimate, design review, technology tiers, inputs, instant estimate and confidence, as JSON; a design that cannot be read or resolved: `Cannot analyze: document is invalid` on standard error, its issues on standard output, exit code 1 |
| `archsim catalog` | every component type, as JSON |
| `archsim schema` | the design document's JSON Schema |

### `archsim run`

`--seed` replaces the design's seed before its scenario applies, as the Python CLI did, so a
scenario that sets its own seed keeps it; a workload that is not an object is left for validation
to refuse (the Python CLI raised `TypeError`). An invalid design's issues go to standard output and
the refusal to standard error, with exit code 1, as does a run over its limits.

Two flags the Python `run` did not have set the run's limits: `--max-requests` and
`--max-wall-seconds` (the engine's own, two million requests and five minutes, when left out; a
value that is not a number above zero is a usage error, exit code 2). `--out` writes the report as
Python's `json.dumps(report, indent=2)` writes it, with the values the Python report held, in the
Java engine's key order.

### Departures

Where the Python CLI crashed with a traceback, the Java CLI answers:

- A file it cannot read (missing, not UTF-8) or write (a folder that is not there, a name the
  system refuses), or that is not JSON (empty, cut short): one line on standard error that names
  the file and the reason, exit code 1 (Python's crash exited with 1).
- `analyze` of a design with a link to a part that does not exist: the analysis, as `POST /analyze`
  gives it (Python raised `KeyError`).

Elsewhere:

- JSON is read as Python's `json.loads` reads it, `NaN` and `Infinity` included; Jackson also
  takes `+Infinity`, `INF`, `+INF` and `-INF`, which Python refused. A file nested more than 500
  levels deep, or with a number longer than 1,000 digits, is refused in one line, in Jackson's
  words, where Python read it (no design comes near either).
- An option may be named by the start of its name, as argparse allowed (`--scen`), and by the
  start of each of its words (`--m-r` for `--max-requests`), which argparse refused.

## Packages

| Package | What it holds |
| --- | --- |
| `io.archsim.cli` | `Archsim` (the `archsim` command, its `main`, and the answer to a `CommandException`: its message alone, exit code 1), one class per command (`ValidateCommand`, `RunCommand`, `AnalyzeCommand`, `CatalogCommand`, `SchemaCommand`), `RunText` (what `run` prints), `IssueLines` (a design's issues, in the Python CLI's words), `CommandFiles` (the files a command reads and writes, refused with the reason), `JsonOutput` (JSON as `json.dumps(value, indent=2)` wrote it) |

## Tests

```bash
./gradlew :archsim-cli:test
```

- `scripts/export_cli_cases.py` records the frozen engine's CLI, one file per command under
  `src/test/resources/io/archsim/cli/` (`<command>-cases.json.gz`): the arguments, the files read,
  the exit code, standard output and error, and a report written. `RecordedCases` runs each case
  in Java; the `*CommandTests` classes expect the same exit code and the same text, word for word,
  or for JSON printed the same values (`JsonDiff`, the golden files' comparison), written as
  `json.dumps(value, indent=2)` writes them. The cases:
  - `run`: a run, one without failures, one whose every call fails, a scenario with a seed of its
    own, a seed given, a seed for a design without a workload, a quick check, a report written to
    a file (compared as the simulator's recorded runs are, `ReportComparison`), an invalid design,
    an invalid workload and a run over the request limit;
  - `validate`: every template, a design of every schema version and the versions refused, an
    invalid design and workload, a JSON array, an empty object;
  - `analyze`: every template and the first scenario of each that has one, an unknown scenario, a
    small design, a JSON array, an empty object;
  - `catalog` and `schema`.
- Where Python crashed, the case records the exception instead of the traceback, and a test of
  the Java answer replaces the replay: each file recorded as unreadable is refused in one line
  with Python's exit code (`RecordedCases.unreadableFiles`), and `AnalyzeCommandTests` checks the
  analysis of a design Python crashed on; `CommandFilesTests` and `ArchsimTests` (a refusal
  prints no stack trace) the rest.
- `RunOptionsTests` checks what the recordings cannot: the two limits the Python `run` did not
  have, a seed for a workload that is not an object, an option named by the start of its name, a
  report that cannot be written, and `archsim` without a command.
- `RunTextTests` checks the lines a report skips when its quick check, confidence or estimate is
  empty or missing.
