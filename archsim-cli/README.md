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
| `archsim calibrate prometheus FILE --map FILE (--url URL \| --recorded FILE) [--range START/END] [--step S] [--record FILE] [--no-replay] [-o FILE]` | a design's inputs set from Prometheus range queries, through a mapping of which query sets which input, from a server or from responses recorded earlier ([below](#archsim-calibrate)) |
| `archsim calibrate otlp FILE --traces FILE [--map FILE] [-o FILE]` | a design's inputs set from an OpenTelemetry trace export (OTLP JSON), with an optional mapping of services and databases to parts |
| `archsim import compose\|k8s\|terraform FILE [--name NAME] [-o FILE] [--report FILE]` | a design from a docker-compose file, Kubernetes manifests or a Terraform plan (`FILE` `-` for standard input; UTF-8, or UTF-16 after its byte order mark), as JSON; what was mapped, guessed and ignored, the links to confirm and the counts on standard error; text the import refuses: `cannot import: ...`, exit code 2 |
| `archsim export compose\|k8s\|mermaid FILE [-o FILE]` | docker-compose, Kubernetes manifests or a Mermaid diagram of a design, auto-sized; an invalid design's issues on standard output, exit code 1 |
| `archsim check FILE [--scenario ID] [--baseline FILE \| --baseline-ref REF] [--fail-on objectives\|regression]... [--max-p95-increase X] [--max-success-drop X] [--max-cost-increase X] [--max-knee-drop X] [--knee] [--format markdown\|json] [-o FILE] [--json-out FILE] [--report-url URL]` | an SLO gate for CI, as the GitHub Action runs it: the design simulated and judged against its objectives and, with a baseline from a file or the design's file at a git ref, against it; the pull-request comment (Markdown) or the result (JSON) on standard output or `-o`, the verdict on standard error; exit code 0 for a pass, 1 for a failure, 2 for anything else ([below](#archsim-check)) |
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

### `archsim calibrate`

What was set, the notes, the replay's lines and its summary go to standard error, and the
calibrated design, as JSON, to standard output or `-o`. Whatever stops a calibration is one line,
`Cannot calibrate: ...`, in the Python engine's words, with exit code 1: a file that cannot be read
(`[Errno 2] No such file or directory: 'mapping.yaml'`), is not UTF-8 or is not JSON (`Expecting
value: line 1 column 1 (char 0)`), a mapping or range the engine refuses, a server that cannot be
reached or answers with an error (`  Prometheus said: ...` on the next line), an invalid design
(its issues on standard output) or a replay over its limits. They are checked in Python's order, so
of two problems the same one is reported: the mapping, the recording, the range, then the design.

Files are read as Python's `Path.read_text(encoding="utf-8")` read them, a line ending in `\r\n` or
`\r` read as one ending in `\n`, so a refusal's line and character are Python's for a file saved
on Windows; and named as `pathlib` named them (`./mapping.yaml` is `'mapping.yaml'`, no name is
`'.'`). A folder is refused as Python's `open` refused it there: `[Errno 13] Permission denied` on
Windows, `[Errno 21] Is a directory` elsewhere; a name Windows refuses is `[Errno 22] Invalid
argument`. An option given empty is not given, as Python tested each by its truth: `-o ""` prints
the design, `--range ""` takes the mapping's range, `--record ""` records nothing, `--map ""` (for
`otlp`) maps nothing, and `--recorded ""` asks the server, whose missing URL is then refused.

`--url` calls the server on the open policy, as the Python CLI did: any host, at any address,
private and loopback ones included, with no allowlist, under the engine's 30-second deadline and
50 MiB cap on a response, connecting only to the address it resolved and following no redirects
([ADR-0057](../docs/adr/0057-move-the-prometheus-client-into-the-engine.md)). The command runs
where its user runs it, against the server they name; the app's server-side calls keep their own
policy. `--record` writes every response as the Python engine's `RecordingPrometheus` saved them,
for `--recorded` to replay. `--step` is read as argparse's `type=float` read it, by Python's
`float()` (`PythonFloat`): `nan`, `inf`, underscores between digits and spaces around it are
numbers, `1d` and `0x1p3` are not. The HTTP client logs through SLF4J, which the CLI binds to
`slf4j-nop`, so nothing but the command's own lines reaches standard error.

### `archsim check`

Exit code 1 only ever means the check failed: whatever stops a check is exit code 2 and one line,
`cannot check: ...`, in the Python CLI's words. That covers an invalid design (its issues follow, on
standard error), a run over its limits, a file that cannot be read or written, and a baseline git
cannot give: a design outside a repository, a ref that cannot be read (`git ref 'origin/mian' cannot
be read: check the name, and fetch it in a shallow checkout (actions/checkout with fetch-depth:
0)`), or git not installed. An error nobody expected, an error of the JVM's included, is answered
the same way, `cannot check: unexpected error: ...`, exit code 2, without Python's traceback or a
Java stack trace. A baseline that cannot be simulated is a note in the result, not a failure, as in
Python. The result on standard output is UTF-8 with `\n` line ends on every system, as Python wrote
it to `sys.stdout.buffer`.

`--baseline-ref` reads the design's file at the ref through the `git` on the `PATH`, with the
commands the Python CLI ran (`rev-parse --show-toplevel`, `rev-parse --verify`, `cat-file`), each
under a 60-second deadline that Python did not have. A design the ref's commit does not hold is said
(`... does not exist at HEAD: no baseline`), and checked against its objectives alone. The file at
the ref is read as Python's `json.loads(bytes)` read it (`PythonJsonBytes`): UTF-8 with or without a
byte order mark, UTF-16 or UTF-32 with one or found from the zero bytes of the first four, with
`surrogatepass`. The thresholds are read as Python's `float()` reads them (`PythonFloat`), and an
option given empty is not given, but for `--scenario`: an empty one is no scenario, which the JSON
result names as given, `"scenarioId": ""`. The result names the engine that ran the check, `1.0.0`,
in its JSON (`engineVersion`) and its Markdown, where Python's named `0.16.0`.

### `archsim functions`

A hidden command for the benchmark harness, not for people
([ADR-0061](../docs/adr/0061-answer-the-harnesss-engine-calls-from-one-java-process.md)): it
answers the engine's functions the harness calls (`benchmarks/engine_functions.py`), a JSON
request a line on standard input, `{"function": NAME, "input": {...}}`, and an answer a line on
standard output, `{"output": VALUE}` or `{"error": {"type", "message"}}`, until the input ends.
The functions are the Python engine's that the harness and its tests imported before, by their
names: `expected` (a distribution's mean), `machine_speed` (a machine's thread speed at each busy
count), `load_factor` (a `serviceTimeByLoad` table's factor at each rate), `fit_load_curve` (with
its table), `extend_load_curve`, `fit_memory`, `fit_cpu_share`, `constants` and `report_version`
(which the harness records with a prediction). A fit is its record, or `null` when it finds none.
Numbers are read and written as Python's `json` writes and reads them, `NaN` and `Infinity`
included: the fits leave such points out as the Python engine's did, and a rate's load factor is
the last point's as in Python, but a parameter that is one number must be finite. An input
refused, or of the wrong shape, is a `ValueError` in the engine's words, and a name that is not a
function a `LookupError`. A line that is not a request is answered with an error, and the next
line still is. Each answer is flushed before the next line is read: the harness waits for it.

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
- `calibrate` with a mapping holding a scalar PyYAML failed to read (`!!int ''`,
  `!!timestamp soon`; `!!bool maybe` for `prometheus`, whose `KeyError` it did not catch, where
  `otlp` did): `Cannot calibrate:` and the exception's words, exit code 1.
- `calibrate --recorded` of JSON that is not an object (`[1, 2]`, `null`; Python raised
  `AttributeError`): `Cannot calibrate: a recording must be a JSON object, with its 'responses'`,
  exit code 1.
- `calibrate` of a file nested more than 1,000 levels deep: `Cannot calibrate: the JSON nests more
  than 1000 levels deep`, exit code 1. Python read it up to its C recursion limit (about 3,000
  levels on Windows, more elsewhere) and raised `RecursionError` past it.
- `check` of a design whose run raises an error nobody expected, an error of the JVM's such as a
  stack overflow or running out of memory included: `cannot check: unexpected error: ...`, exit code
  2, as in Python, which answered `RecursionError` and `MemoryError` so, without its traceback.
  `main` answers an error of the JVM's through an uncaught-exception handler, which no handler of
  picocli's sees; for any other command it ends the command as the JVM ends it.

Elsewhere:

- JSON is read as Python's `json.loads` reads it, `NaN` and `Infinity` included; Jackson also
  takes `+Infinity`, `INF`, `+INF` and `-INF`, which Python refused. A file nested more than 500
  levels deep, or with a number longer than 1,000 digits, is refused in one line, in Jackson's
  words, where Python read it (no design comes near either). `calibrate` reads through the
  engine's reader instead (`PythonJsonReader`), which refuses text in `json`'s words where no value
  starts or something follows the value, and a number longer than 4,300 digits in Jackson's
  (`Number value length (4301) exceeds the maximum allowed (4300)`): Python refused a whole number
  that long in its own words and read a float of any length.
- `import`: text that is not YAML is refused in SnakeYAML's words, not PyYAML's
  ([ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), and
  Terraform text that is not JSON past its first character with Jackson's description of the
  problem in Python's form
  ([ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)), as
  `POST /import` refuses them.
- `calibrate`: a mapping that is not YAML is refused in SnakeYAML's words, as `POST /calibrate`
  refuses it, and a recording that is not JSON past its first character in Jackson's, with
  Python's line, column and character; the refusal's start, `Cannot calibrate: the mapping is not
  valid YAML:` or the position, is Python's. A step the range refuses (not above 0, or above a
  day), given as `--step` or in a recording's range, is refused on one line,
  `Cannot calibrate: step: Input should be greater than 0`, as `POST /calibrate` refuses it;
  Python printed pydantic's whole error over four lines, with its count, model, type, the value
  and a link to pydantic's documentation.
- A file a command writes (`run --out`, `import -o` and `--report`, `export -o`, `calibrate -o`
  and `--record`, `check -o` and `--json-out`) ends its lines
  with `\n` on every system; Python's `write_text` wrote the system's line ends, `\r\n` on
  Windows.
- `check --baseline-ref` gives each git command 60 seconds, and refuses one that takes longer
  (`cannot check: git cat-file took more than 60 s`); Python waited without end.
- An option is named in full
  ([ADR-0056](../docs/adr/0056-the-java-cli-takes-options-by-their-full-names.md)):
  argparse took the start of its name (`--scen` for `--scenario`); picocli's abbreviations would
  take it too, but fail on `-`, the name `import` gives standard input.

## Packages

| Package | What it holds |
| --- | --- |
| `io.archsim.cli` | `Archsim` (the `archsim` command, its `main`, and the answer to a `CommandException`: its message alone, exit code 1), one class per command (`ValidateCommand`, `RunCommand`, `CompareCommand`, `AnalyzeCommand`, `ImportCommand`, `ExportCommand`, `CatalogCommand`, `SchemaCommand`, and `CalibrateCommand` with `CalibratePrometheusCommand` and `CalibrateOtlpCommand`), `Choices` (what `import` and `export` take, and `check`'s `--fail-on` and `--format`, by name), `RunText` (what `run` and `compare` print of a run), `IssueLines` (a design's issues, in the Python CLI's words), `CommandFiles` (the files a command reads and writes, refused with the reason), `JsonOutput` (JSON as `json.dumps(value, indent=2)` wrote it), `TextOutput` (text as `sys.stdout.write` wrote it), `TextDecoding` (a file's text as `decode_text` read it: `Utf16` after its byte order mark, else the engine's `Utf8`, each finding where bytes stop being text in CPython's words), `PythonOptions` (an option given empty is not given), `PrometheusCalibrator` (a calibration from Prometheus, in the Python CLI's order, from the `PrometheusOptions` asked and a `PrometheusSource`: a server or a recording), `PrometheusClients` (the server, on the open policy, the recorded responses, and a recording written), `CalibrationAttempt` (what stops a calibration, as `Cannot calibrate: ...`), `CalibrationText` (what a calibration prints, and the calibrated design), `PythonFloat` (a number option read as Python's `float()` reads it), `CheckCommand` with its `Refs` (a git ref, never one starting with `-`) and `CheckOptions` (what a check was asked) and `CheckRun` (the check, in the Python CLI's order, and what stops it), `Checks` (what runs a check: the engine's `CheckRunner`), `CheckFormat` (Markdown or JSON), `GitBaseline` (the design's file at a git ref, through `git`), `PythonJsonBytes` (JSON from bytes as `json.loads(bytes)` read it), `AnswersUnexpectedErrors` (a command that answers an error nobody expected itself, as `check` does), and `FunctionsCommand` with `HarnessFunctions` (the engine's functions the benchmark harness calls, a JSON line each). The files `calibrate` and `check` read and write go through the engine's `PythonFiles` ([the engine's README](../archsim-engine/README.md), [ADR-0059](../docs/adr/0059-read-files-as-python-did-in-the-engine.md)) |

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
  - `calibrate`: from recorded responses (to standard output and a file, without the replay, over
    a range the recording does not cover, recorded again) and from a server (a stub the case
    describes: answering with the recorded responses, refusing the query, answering as a page that
    is not Prometheus, or not there), URLs, ports and ranges that are not ones, a mapping, a
    recording or a design missing or not readable, a design not JSON after Windows line ends, a
    file named from `./`, a mapping of a part that is not there, YAML Python refused or failed to
    read, recordings that are not objects or whose step is refused, steps refused, read as Python
    reads them or not numbers, options given empty, a replay over the request limit, an invalid
    design, and pairs of problems, to show which is reported; from OpenTelemetry traces, with and
    without a mapping, to a file, traces missing, not JSON or not an export, a mapping Python
    refused or failed to read, traces replayed over the request limit; and the usage errors. The
    files a case reads, byte for byte, and the files it writes are recorded with it, and the time
    a calibration stamps is fixed (`2026-11-10T12:00:00Z`) in Python and Java alike;
  - `check`: a design meeting and missing its objectives, failing on objectives, regressions or
    both, as JSON, to files, with the JSON too and a report's link; a scenario, one the design
    lacks, an empty one (as JSON too); against baselines slower and faster, itself, with loose and
    NaN thresholds, and baselines that cannot be simulated, are not JSON or are missing; designs
    invalid, not designs, not JSON, missing or over the request limit; outputs that cannot be
    written; options given empty; at a git ref (a repository the case makes, as the recorder made
    it): a regression, a design new at the ref, refs that cannot be read, a design outside a
    repository, a folder and a baseline named in Unicode, with a byte order mark, in UTF-16 with and
    without one, not text or not JSON; breaking points; and the usage errors;
  - `catalog` and `schema`.
- Where Python crashed, the case records the exception instead of the traceback, and a test of the
  Java answer replaces the replay: each file recorded as unreadable is refused in one line with
  Python's exit code (`Replays.unreadableFiles`); `AnalyzeCommandTests`, `ExportCommandTests` and
  `CompareCommandTests` check the designs Python crashed on, and `ImportCommandTests` and
  `CalibrateCommandTests` the YAML and JSON refused in the libraries' words (and
  `CalibrateCommandTests` the steps refused in pydantic's, and the mappings and recordings Python
  crashed on); the engine's `PythonFilesTests` what the recordings cannot show on every system:
  line ends, a byte order mark, bytes that are not UTF-8, folders, names Windows refuses, files it
  may not read, and names as `pathlib` gives them; `PythonFloatTests` numbers as Python's
  `float()` reads them, each checked against Python 3.12; `CheckCommandTests` an error nobody
  expected (a check that throws, and an error of the JVM's: one line, exit code 2), the result's
  line feeds, and each recorded
  check with the Java engine named; `PythonJsonBytesTests` JSON from bytes in every encoding `json`
  finds, each checked against Python 3.12; `GitBaselineTests` git not installed, a git slower than
  the deadline (a Java program that sleeps) and refs starting with `-`; `PrometheusClientsTests` the
  open policy (a loopback address and name, with no allowlist, are called) and `slf4j-nop` as the
  only SLF4J provider; `CommandFilesTests` and `ArchsimTests` (a refusal prints no stack trace) the
  rest. `TextDecodingTests` checks text that decodes, at the edges of each codec.
- `RunOptionsTests` checks what the recordings cannot: the two limits the Python `run` did not
  have, a seed for a workload that is not an object, an option named by the start of its name, a
  report that cannot be written, and `archsim` without a command.
- `RunTextTests` checks the lines a report skips when its quick check, confidence or estimate is
  empty or missing.
- `FunctionsCommandTests` checks each function `archsim functions` answers against the Python
  engine's answer to the same call (a fit that finds nothing, a point that is not a number and a
  table's unusable points among them), the refusals (an input refused or of the wrong shape, a
  function that is not one, a line that is not a request), that the next line is answered after
  one, and that each answer is written before the next line is read.
