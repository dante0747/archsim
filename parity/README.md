# Parity golden files

`parity/golden/` holds the inputs and outputs of every deterministic function of the Python
engine **0.16.0**, the frozen parity reference of the [Java port](../docs/proposals/java-25-port.md).
A Java test reads an entry, feeds the function the recorded input and compares its output with the
recorded one: text exactly, numbers to a relative 1e-9. No Python is needed, so the files stayed
when stage 5 deleted the Python engine (the tag `engine-0.16.0` keeps it).

They were written by `scripts/export_golden.py`, the command line of the exporter package
`scripts/golden/`, from the frozen engine. Both went with the engine at stage 5's exit, with
their tests (git history keeps them): the files are data now, not recorded again. Until then
`export_golden.py --check` was the CI guard of the engine freeze: the committed files had to be
what the engine gave, compared by content (text and structure exactly, numbers to a relative
1e-9), not by bytes.

In all: 34 files and about 6,000 entries, 4.5 MB compressed (30 MB of JSON).

**Reading them from Java:** walk each file for entries (objects with `fn` and `input`), resolve the
references in `input`, call the Java counterpart of `fn`, and compare with `output`: strings
exactly, numbers within a relative 1e-9, arrays position by position, objects key by key. An entry
with `error` must fail the same way (its type's counterpart and the same message).

That reader is `io.archsim.parity` in `archsim-model`'s Gradle test fixtures, so every module's
tests can use it with `testImplementation(testFixtures(project(":archsim-model")))`
([ADR-0028](../docs/adr/0028-golden-file-reader-as-test-fixtures.md)): `GoldenFile` reads a file
and resolves references, `GoldenEntry` is one call, `JsonDiff` compares as above, and
`Parity.check` runs an entry against a Java function. The model's
[`GoldenParityTests`](../archsim-model/README.md#tests) checks every entry of the functions stage 1
ports.

## Layout

| File | Cases | What |
| --- | --- | --- |
| `manifest.json` | | engine, schema and report versions, the generator command, and every file with its case and entry counts and the sha256 and size of its **uncompressed** JSON |
| `templates/<id>.json.gz` | 25 files, 149 cases | each template in `examples/` and its scenarios (see below) |
| `catalog/components.json.gz` | 1 | the component catalog as `GET /api/v1/components` serves it, and the type list |
| `catalog/schema.json.gz` | 1 | the document JSON Schema (`archsim_engine.document_json_schema()`, `GET /api/v1/schema/architecture`) |
| `catalog/pricing.json.gz` | 2 snapshots | the shipped price snapshots (embedded under `files`), parsing, hashes, listing, lookups and pinning (with their errors), `default_pricing`, `unknown_families`, `Snapshot.cover` on a grid of sizes, engines, families and CPU-credit loads, `machine_kind` |
| `catalog/assumptions.json.gz` | 1 | `assumptions.constants()`, `assumptions.render()` (the text of `docs/assumptions.md`) and the engine's text constants (review rules, model notes, failure reasons, estimate method, cost notes, tier note, export formats, versions) |
| `catalog/formatting.json.gz` | 1 | number-to-text and small numeric helpers on grids of values, where a port most easily differs: `explain.num`/`pct`/`erlang_c`/`zipf_share`, `load.js_num`/`fmt_int`/`fixed`/`round_half_up`/`mean_ms`/`ttl_hit_ratio`, `sizing.fmt`, `provenance.describe_value`, the `seeds` text helpers, distributions' `expected`/`describe`/`quantile`, `estimate.mmc_wait`, `sweep.levels`/`suggest_range`/`rps_cap`, and `report.canonical_digest` and `pricing.content_sha256` on awkward JSON (unicode, `1e-05`, `1e+22`, `-0.0`, big integers): Python's float and JSON spelling decide every digest |
| `fixtures/migrations.json.gz` | 18 | the smallest template stamped with every schema version 1.0 to 1.7 (and `1.3` as a number), and bad versions (`1.10`, `2.0`, `0.9`, `1.3.0`, text, empty, missing, not an object): `migrate_document` and `load_document` |
| `fixtures/invalid-documents.json.gz` | 56 | templates with one thing wrong (each case says what): the schema errors `load_document` reports, the issues `validate_document` reports, and what scenario resolution says |
| `fixtures/importers.json.gz` | 14 | `import_design` on the compose, Kubernetes and Terraform fixtures of `parity/fixtures/import`, and on text it cannot read |
| `fixtures/calibration.json.gz` | 6 | Prometheus and OpenTelemetry calibration from the fixtures of `parity/fixtures/calibration` (embedded under `files`), mappings and ranges with their errors, and every parse and fit the calibrations call, plus fits on synthetic points |

Each `.json.gz` is gzip (`mtime` 0) of **canonical JSON**: keys sorted, no whitespace (`,` and
`:`), UTF-8 with non-ASCII characters as they are, no NaN. Compare decompressed content: another
zlib may compress the same JSON to other bytes (the exporter left a file alone when its content
had not changed).

## Entries

Every recorded call is an **entry**, an object in an `entries` map keyed by the entry's name:

```json
"estimate_design": {
  "fn": "archsim_engine.estimate.estimate_design",
  "input": {"resolved": {"$ref": "/base/entries/resolve_scenario_sized/output/resolved"},
            "snapshot": {"$ref": "/base/entries/simulation_input/output"}},
  "output": {"busiest": {"id": "api", "...": "..."}, "parts": ["..."]}
}
```

- `fn`: the Python function's qualified name (`module.function`, `module.Class.method`; a
  constant is `module.NAME` with an empty input). A name is used once per map; a function recorded
  more than once there gets a qualifier: `export_document[k8s]`, `generic[api]`, `cover[17]`.
- `input`: the function's parameters by name, in camelCase. The `registry` parameter is left out:
  it is always the default registry without plugins. Defaults that matter are spelled out
  (`maxAmplification`).
- `output`: the return value, or instead `error`: `{type, message}` of what it raised, plus `issues`
  (each `Issue.to_dict()`) for a `DocumentInvalidError`. A pydantic `ValidationError`'s message is
  the engine's own `validation_message` (pydantic's text would name its version).

**References.** An input value of 256 bytes or more (canonical JSON, or a string's length) that the
file already holds is written as `{"$ref": "<JSON Pointer>"}`: an RFC 6901 pointer from the file's
root to where it first appears, an earlier entry's output or input (for example
`/entries/load_document/output`, `/runs/base/report/nodes/2`, `/document`). Resolve references
recursively (what one points to may contain more). They appear only inside `input`; outputs,
documents and reports are always literal. A JSON Schema's own `"$ref": "#/$defs/..."` starts with
`#` and is not a reference.

**JSON conventions** (where the engine has no JSON of its own):

- pydantic models are dumped as the engine dumps them: `model_dump(mode="json", by_alias=True)`;
- dataclasses become objects of their fields with camelCase names: `PartSize` (all fields at full
  precision, not `PartSize.to_json()`, which rounds), `NodeLoad`, `Sku`, `Histogram`, `Fit`,
  `CpuFit`, `LoadCurve`, `MemoryFit`, `CurveExtension` (plus `factors()`), `Span`, `TraceSummary`,
  `Constant`; tuples become arrays and sets sorted arrays;
- a dict whose keys are not strings becomes an array of `[key, value]` pairs (`Fit.observed`:
  quantile to value; `TraceSummary.calls`: `[from, to]` to count);
- non-finite floats are the strings `"Infinity"`, `"-Infinity"` and `"NaN"` (only in the
  formatting grid's inputs);
- numbers keep Python's spelling (`1` or `1.0`); compare them as numbers.

Outputs that are more than the return value: `validate_document` is `ValidationResult.to_dict()`
plus `edgeFlows` (link id to the request types that reach it, sorted); `resolve_scenario_sized` is
`{resolved, scenario, sizes}`; `migrate_document` `{document, notes}`; `apply_auto_sizes`
`{document, sizes}`; `export._auto_sized` `{document, needs}`; `estimate_load` `{loads, edgeLoads}`
(the return value and the `edge_loads` map it fills); `import_design` `{document, report}`;
calibrations `Outcome.to_dict()`; `parse_snapshot` `Snapshot.to_json()` plus `skus` and `rates`, and
`load_snapshot`/`snapshot_for` `Snapshot.to_json()`; `engine.stationary` `[ok, checks]`.

## Template files

```
document                 the template as in examples/
entries                  migrate, load, validate, normalize, pin prices, auto-size; the exports
                         (_auto_sized, to_docker_compose, to_kubernetes, to_mermaid, export_document
                         per format) and importing the compose and Kubernetes exports back
base                     the base design (scenarioId null) ...
scenarios/<id>           ... and each scenario: resolve_scenario_sized, validate_document,
                         simulation_input, review_design, count_by_severity, input_provenance,
                         provenance_counts, technology_tiers, peak_rps, base_rps, ttl_miss_shares,
                         estimate_load, estimate_design, sizing_summary, base_busy, estimate_cost,
                         analysis_confidence, quick_check, analyze_document, sweep_range, levels
                         (a scenario also apply_overrides)
runs/base, runs/<id>     a recorded simulation and the functions of a report (below)
comparison               the two runs compared: compare_reports, input_diff, summarize_seeds,
                         sweep analyze, check regressions, run_check, headline, to_markdown
```

The case entries follow `analyze_document` step by step, so each function can be tested alone and
the composition checked against `analyze_document`; the exporter failed if they disagreed.

### Recorded runs

Functions that read a simulation report (`analysis.analyze` and the findings, `report_confidence`,
`estimate_cost` after a run, `explain.generic`, `engine.stationary`, `report.canonical_digest`,
`slo.*`, `sweep.row_from`/`meets`/`analyze`, `check.*`, `compare.*`, `seeds.summarize_seeds`) are
deterministic given the report, so the report is recorded as an **input**: `runs/<key>` holds
`document` (what was simulated), `timeScale`, `report` and the entries on it. The Java simulator
also replays each run: `archsim-engine`'s `GoldenRunsTests` simulates `document` with the run's
scenario and compares the whole report with `report`, as the recorded simulation cases are
compared (counts, events processed and digests exactly, numbers to a relative 1e-9, Python's
integers kept but for two cost fields). Stage 4's statistical parity, 20 seeds a template, is
measured apart.

- Two runs per template: the base design, and the first scenario with faults (else the first that
  changes the traffic pattern, else the last). Every scenario's static analysis is recorded, but
  simulating all 149 cases would cost minutes and megabytes for little more coverage.
- To keep runs short, the template's time axis is compressed by `timeScale` so the run offers about
  1,500 requests (at least 2 s, never longer than the template's run): duration, warm-up, bursts and
  faults all scale, so they keep their places in the run. Rates and every other setting are the
  template's. The compressed document is recorded.
- The report's machine-dependent provenance is blanked: `wallSeconds` is 0 and `python` is
  `"normalized"`.
- The exporter checked that each function fed the finished report reproduced what the report
  holds (findings, confidence, cost, each node's generic formulas, both digests), so the report is
  enough input. `explain.generic`'s `poolBased`, which a report does not show, was captured from the
  run. Component-specific formulas follow the generic ones in each node's `explain`.
- `summarize_seeds` gets the two runs' summaries as seeds *n* and *n*+1 and an unfinished run *n*+2;
  `sweep.analyze` the two runs as rows: synthetic groups, but the functions are pure.

### Injected simulations and clock

A few functions simulate inside. Their entries carry what `run_simulation` returned, and a Java test
injects it instead of simulating:

- `check.measure`: `simulation`, the report;
- `calibrate_prometheus` (with the replay) and `calibrate_otlp`: `simulations`, the reports
  `run_simulation` returned, in order;
- `check.run_check`: `current` and `baseline`, the results `measure` returned (no knee); its
  `thresholds` are `Thresholds` as `run_check` writes them (`dataclasses.asdict`, snake_case).

Calibration records the time it ran (`fittedAt`, `calibration.at`): it is fixed at
`2026-11-02T00:00:00Z`. A Java calibration needs an injectable clock.

## The simulation baselines

`parity/simulation/` holds the frozen engine's runs of every template case (the 25 templates'
designs and their scenarios, 149 cases) with 20 seeds each, the workload's seed and the 19 after
it, simulated in full: the Python side of stage 4's statistical bar, recorded once
([ADR-0052](../docs/adr/0052-record-the-frozen-engines-simulation-baselines-once.md)). Each run
keeps its figures, not its whole report: the events processed, the requests generated, both
digests, the summary's values and latencies, and each part's summary values; a run that failed
keeps its error. `<template>.json.gz` holds a template's cases, each its runs by seed; the
manifest has each file's SHA-256 and size (uncompressed) and its count of runs and failures.

They were recorded by `scripts/export_simulation_baselines.py`, in about twenty hours on four
cores; the script went with the engine at stage 5's exit (git history keeps it), and the
baselines are data now, not recorded again.

`./gradlew :archsim-engine:parity` simulates the same runs in Java, judges each case by the bar
(the Java median p50, p95, throughput and each part's utilization inside Python's range and
within 2% of its median, and no seed failing otherwise than Python's), counts the runs that give
exactly Python's figures and digest, and writes
`archsim-engine/build/parity/simulation-parity.json`, with the cases that miss and what they
missed. `-Parchsim.parity.baselines=DIR` judges another recording, a partial one say.

`./gradlew :archsim-benchmarks:run --args="java-parity"` writes stage 4's parity report,
`docs/validation/java-parity.md`, from that file (written to `docs/validation/` with
`-Parchsim.parity.out=docs/validation`), the kept calibrations' knees re-simulated on both
engines by the same harness over every kept run with a two-pass calibration
(`benchmarks/results/dev/0.16.0-port/`, which `uv run python benchmarks/run.py resimulate --label
0.16.0-port` wrote while the frozen engine was the harness's, and `0.16.0-java/`, which the Java
engine wrote before it was 1.0.0, paired over both folders, with whether each pair derived the
same inputs; no engine writes either folder now, and git restores them) and both engines'
speed, the median of several runs each with no other heavy work running (`benchmarks/perf/`:
the frozen engine's from `scripts/perf.py`, which went with it, and the Java engine's from
`./gradlew :archsim-engine:speed`); `--check` fails when the page is stale.

## What is left out

- **The simulation kernel, components, runtime and report building** (`run_simulation`,
  `build_report`, trace selection, latency breakdown, metrics): not recorded function by function.
  The recorded runs (above) and `archsim-engine`'s simulation cases replay them whole.
- **Sweeps, knee search and seed groups** (`breaking_point`, `knee_sweeps`, `run_check --knee`):
  many simulations each. Their pure parts are recorded here (`sweep_range`, `levels`,
  `suggest_range`, `rps_cap`, `row_from`, `meets`, `analyze`, `summarize_seeds`, `regressions`,
  `run_check`); whole sweeps, knee searches and checks are in `archsim-engine`'s
  `sweep/cases.json.gz`, which `SweepCasesTests` runs in Java.
- **Component-specific explain formulas** (`RuntimeComponent.explain`) need a live runtime component;
  they are in the recorded reports.
- **Reports of every scenario**: two runs per template (above).
- **The Prometheus HTTP client and outbound checks** (network), the CLI and MCP server (recorded
  apart in stage 5, in `archsim-cli`'s and `archsim-mcp`'s tests) and the HTTP API (stage 2's
  black-box tests). Plugins: the default registry has none.
- **Inline test documents** in the Python engine's tests: built by test code, not fixture files.
  The invalid documents here are mutations of templates instead, one per validation code or
  schema error the tests covered and more.
- **The benchmark harness's fits** (`benchmarks/run.py`): it stays Python until stage 7.
  `fit_load_curve` and `extend_load_curve` are recorded on synthetic points, and every fit a
  calibration makes on its real inputs.

## The database schema

`parity/schema/` holds the schema the Python API's Alembic migrations built at their last
revision, 0008, which the Java app's Flyway baseline must reproduce
([ADR-0032](../docs/adr/0032-flyway-takes-over-from-alembic.md)).
[`describe.sql`](schema/describe.sql) describes the public tables (columns, constraints, indexes,
sequences) as one canonical JSON document; `alembic-0008.json` is that document for the database
Alembic built. Like the golden files, it is data now, recorded once: stage 6 deleted the
migrations ([ADR-0063](../docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)),
and their revision 0008 never changes. The tag `alembic-0008` keeps them and the script that
recorded it. The document needs recording again only when `describe.sql` changes (or a new
PostgreSQL major version describes the same schema differently): in a worktree of the tag, with
this tree's query, since the script reads the query beside it. It drops and creates the database
`archsim_schema_snapshot` on the server it connects to:

```sh
git worktree add ../archsim-alembic-0008 alembic-0008
cp parity/schema/describe.sql ../archsim-alembic-0008/parity/schema/
cd ../archsim-alembic-0008
uv sync
uv run python scripts/schema_snapshot.py --url postgresql://postgres@localhost:5432/postgres
cd -
cp ../archsim-alembic-0008/parity/schema/alembic-0008.json parity/schema/
git worktree remove --force ../archsim-alembic-0008
```

archsim-app's `MigrationTests` describe the database Flyway builds with the same query and expect
the same document.

## The test fixtures

`parity/fixtures/` holds the files the golden files and recordings were made from, copied from the
frozen engine's `packages/engine/tests/fixtures/` in stage 5 so they stayed when the engine went:
`calibration/` (a mapping and Prometheus responses generated for the cache-aside starter by
`scripts/make_calibration_fixture.py`, which went with the engine, and an OTLP trace export and
its mapping by `scripts/make_otlp_fixture.py`, deleted at stage 6 as data made once), `import/` (a
compose file, Kubernetes manifests and a Terraform plan written for the importers' tests) and
`pricing/` (a snapshot of a made-up provider). Unlike `golden/fixtures/`, a category of golden
files, they are inputs. The golden exporter and the CLI, import and MCP recorders read them
before stage 5's exit deleted them; the API and app tests (`Examples.fixture`) and the API tests'
local profile (its `ARCHSIM_PRICES_DIR`) read them now.

## Python's own answers

Three tables hold what CPython 3.12 answers where the Java keeps its behaviour on purpose
([ADR-0072](../docs/adr/0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md)):

| Table | What | Read by |
| --- | --- | --- |
| `archsim-engine/src/main/resources/io/archsim/engine/text/casefold.tsv` | `str.casefold()` of every code point that does not fold to itself | `PythonStrings` |
| `archsim-model/src/main/resources/io/archsim/model/text/nonprintable.tsv` | the code points `str.isprintable()` rejects, by Python's Unicode 15.0 | `PrintableCharacters` |
| `archsim-engine/src/test/resources/io/archsim/engine/numeric/erfc.csv` | `math.erfc` at points over every interval of the approximation, as hex floats | `ErfTests` |

`scripts/export_casefold.py`, `scripts/export_printable.py` and `scripts/export_erfc_points.py`
wrote them, and their `--check` (with `scripts/tests/test_export_printable.py`) compared them with
the Python that ran it. Stage 7 retired the recorders with the repository's last Python: the
tables are data now, recorded once, as the golden files are. The tag `cpython-recorders` (commit
`1634ba9e` at the time of writing) is the last commit that has the recorders, to record the tables
again from, or to check them against a newer Python:

```bash
git worktree add ../archsim-recorders cpython-recorders
cd ../archsim-recorders
uv sync
uv run python scripts/export_casefold.py --check
uv run python scripts/export_printable.py --check
uv run python scripts/export_erfc_points.py --check
```
