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
| `archsim compare FILE [--a ID] [--b ID]` | simulates two scenarios (or the design as drawn, `base`) and prints each run's summary line, what changed (`  - `) and why the runs may differ other than by their designs (`  ! `) |
| `archsim analyze FILE [--scenario ID]` | the auto sizes, cost estimate, design review, technology tiers, inputs, instant estimate and confidence, as JSON; a design that cannot be read or resolved: `Cannot analyze: document is invalid` on standard error, its issues on standard output, exit code 1 |
| `archsim import compose\|k8s\|terraform FILE [--name NAME] [-o FILE] [--report FILE]` | a design from a docker-compose file, Kubernetes manifests or a Terraform plan (`FILE` `-` for standard input; UTF-8, or UTF-16 after its byte order mark), as JSON; what was mapped, guessed and ignored, the links to confirm and the counts on standard error; text the import refuses: `cannot import: ...`, exit code 2 |
| `archsim export compose\|k8s\|mermaid FILE [-o FILE]` | docker-compose, Kubernetes manifests or a Mermaid diagram of a design, auto-sized; an invalid design's issues on standard output, exit code 1 |
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

- A file it cannot read (missing, not UTF-8, not JSON: empty or cut short) or write (a folder
  that is not there, a name the system refuses), or standard input it cannot read: one line on
  standard error that names the file and the reason, exit code 1 (Python's crash exited with 1).
- `analyze` and `export` of a design with a link to a part that does not exist: the analysis and
  the export, as `POST /analyze` and `POST /export` give them (Python raised `KeyError`).
- `compare` of a design or scenario that cannot be simulated (Python caught nothing): refused as
  `run` refuses it, `Cannot compare: document is invalid` on standard error with the issues on
  standard output, or `Cannot compare:` and the limit's words, exit code 1.

Elsewhere:

- JSON is read as Python's `json.loads` reads it, `NaN` and `Infinity` included; Jackson also
  takes `+Infinity`, `INF`, `+INF` and `-INF`, which Python refused. A file nested more than 500
  levels deep, or with a number longer than 1,000 digits, is refused in one line, in Jackson's
  words, where Python read it (no design comes near either).
- `import`: text that is not YAML is refused in SnakeYAML's words, not PyYAML's
  ([ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), and
  Terraform text that is not JSON past its first character with Jackson's description of the
  problem in Python's form
  ([ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)), as
  `POST /import` refuses them.
- A file a command writes (`run --out`, `import -o` and `--report`, `export -o`) ends its lines
  with `\n` on every system; Python's `write_text` wrote the system's line ends, `\r\n` on
  Windows.
- An option is named in full
  ([ADR-0056](../docs/adr/0056-the-java-cli-takes-options-by-their-full-names.md), proposed):
  argparse took the start of its name (`--scen` for `--scenario`); picocli's abbreviations would
  take it too, but fail on `-`, the name `import` gives standard input.

## Packages

| Package | What it holds |
| --- | --- |
| `io.archsim.cli` | `Archsim` (the `archsim` command, its `main`, and the answer to a `CommandException`: its message alone, exit code 1), one class per command (`ValidateCommand`, `RunCommand`, `CompareCommand`, `AnalyzeCommand`, `ImportCommand`, `ExportCommand`, `CatalogCommand`, `SchemaCommand`), `Choices` (the formats `import` and `export` take, by name), `RunText` (what `run` and `compare` print of a run), `IssueLines` (a design's issues, in the Python CLI's words), `CommandFiles` (the files a command reads and writes, refused with the reason), `JsonOutput` (JSON as `json.dumps(value, indent=2)` wrote it), `TextOutput` (text as `sys.stdout.write` wrote it), `TextDecoding` (a file's text as `decode_text` read it: `Utf16` after its byte order mark, else `Utf8`, each finding where bytes stop being text in CPython's words) |

## Tests

```bash
./gradlew :archsim-cli:test
```

- `scripts/export_cli_cases.py` records the frozen engine's CLI, one file per command under
  `src/test/resources/io/archsim/cli/` (`<command>-cases.json.gz`): the arguments, the files read,
  the exit code, standard output and error, and the files written. Each design is recorded in the
  order of its keys, as Python read it. `RecordedCases` runs each case in Java, and `Replays`
  makes tests of them: the same exit code and the same text, word for word, or for JSON printed or
  written the same values (`JsonDiff`, the golden files' comparison), laid out as
  `json.dumps(value, indent=2)` lays them out. A usage error is checked by its exit code. The
  cases:
  - `run`: a run, one without failures, one whose every call fails, a scenario with a seed of its
    own, a seed given, a seed for a design without a workload, a quick check, a report written to
    a file (compared as the simulator's recorded runs are, `ReportComparison`), an invalid design,
    an invalid workload and a run over the request limit;
  - `validate`: every template, a design of every schema version and the versions refused, an
    invalid design and workload, a JSON array, an empty object;
  - `analyze`: every template and the first scenario of each that has one, an unknown scenario, a
    small design, a JSON array, an empty object;
  - `compare`: a design with itself, a scenario against the design and the design against a
    scenario, two scenarios, a design whose every call fails, and what Python crashed on;
  - `export`: six templates in each format, an export written to a file, an invalid design, a
    format that is not one, and files it cannot use;
  - `import`: each format's fixture, a name given, the design and report written to files,
    standard input, UTF-16 and UTF-8 with a byte order mark, sixteen kinds of bytes that are not
    text, text that is not the format, not JSON or not YAML, a format that is not one, a missing
    file;
  - `catalog` and `schema`.
- Where Python crashed, the case records the exception instead of the traceback, and a test of
  the Java answer replaces the replay: each file recorded as unreadable is refused in one line
  with Python's exit code (`Replays.unreadableFiles`); `AnalyzeCommandTests`,
  `ExportCommandTests` and `CompareCommandTests` check the designs Python crashed on, and
  `ImportCommandTests` the YAML and JSON refused in the libraries' words; `CommandFilesTests` and
  `ArchsimTests` (a refusal prints no stack trace) the rest. `TextDecodingTests` checks text that
  decodes, at the edges of each codec.
- `RunOptionsTests` checks what the recordings cannot: the two limits the Python `run` did not
  have, a seed for a workload that is not an object, an option named by the start of its name, a
  report that cannot be written, and `archsim` without a command.
- `RunTextTests` checks the lines a report skips when its quick check, confidence or estimate is
  empty or missing.
