# Parity golden files

`parity/golden/` holds the inputs and outputs of every deterministic function of the Python
engine **0.16.0**, the frozen parity reference of the [Java port](../docs/proposals/java-25-port.md).
A Java test reads an entry, feeds the function the recorded input and compares its output with the
recorded one: text exactly, numbers to a relative 1e-9. No Python is needed, so the files stay
after the Python engine is deleted (stage 4).

They are written by [`scripts/export_golden.py`](../scripts/export_golden.py), the command line of
the exporter package [`scripts/golden/`](../scripts/golden/__init__.py):

```sh
uv run python scripts/export_golden.py                     # write parity/golden/ (~40 s; ~95 s in one process)
uv run python scripts/export_golden.py --check             # exit 1, naming what differs
uv run python scripts/export_golden.py --only queueing-lab # one template (or e.g. catalog/pricing)
uv run python scripts/export_golden.py --jobs 1            # one process (default: up to 8)
```

`--check` is the CI guard of the engine freeze: the committed files must be what the engine gives.
It compares content, not bytes: text and structure exactly, numbers to a relative 1e-9, because
another platform's math library (`log`, `exp`, `pow`) may round the last digit of a float
differently; a file that differs only there is noted, not failed. If a change to the files is
intended, regenerate and say why in the commit.
`scripts/tests/test_export_golden.py` checks one template against its file, replays its entries, and
checks the manifest and the format of every file; `scripts/tests/test_golden_codec.py` checks the
JSON conventions below.

The exporter's modules, from the bottom up: `paths` (where it reads and writes), `codec` (the JSON
conventions), `adapters` (the engine functions an entry may name, one module per engine area),
`file` (one file under construction: its entries and references), `files` (what each file records),
`build`, `store` and `compare` (building, writing and `--check`), and `cli`.

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
[`GoldenParityTest`](../archsim-model/README.md#tests) checks every entry of the functions stage 1
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
| `fixtures/importers.json.gz` | 14 | `import_design` on the compose, Kubernetes and Terraform fixtures of `packages/engine/tests/fixtures/import`, and on text it cannot read |
| `fixtures/calibration.json.gz` | 6 | Prometheus and OpenTelemetry calibration from the fixtures of `packages/engine/tests/fixtures/calibration` (embedded under `files`), mappings and ranges with their errors, and every parse and fit the calibrations call, plus fits on synthetic points |

Each `.json.gz` is gzip (`mtime` 0) of **canonical JSON**: keys sorted, no whitespace (`,` and
`:`), UTF-8 with non-ASCII characters as they are, no NaN. Compare decompressed content: another
zlib may compress the same JSON to other bytes (the exporter leaves a file alone when its content
has not changed).

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
the composition checked against `analyze_document`; the exporter fails if they disagree.

### Recorded runs

Functions that read a simulation report (`analysis.analyze` and the findings, `report_confidence`,
`estimate_cost` after a run, `explain.generic`, `engine.stationary`, `report.canonical_digest`,
`slo.*`, `sweep.row_from`/`meets`/`analyze`, `check.*`, `compare.*`, `seeds.summarize_seeds`) are
deterministic given the report, but the Java kernel will not reproduce Python's reports bit for bit.
So the report is recorded as an **input**: `runs/<key>` holds `document` (what was simulated),
`timeScale`, `report` and the entries on it. Do not compare a Java simulation with `report`; that is
stage 4's statistical parity.

- Two runs per template: the base design, and the first scenario with faults (else the first that
  changes the traffic pattern, else the last). Every scenario's static analysis is recorded, but
  simulating all 149 cases would cost minutes and megabytes for little more coverage.
- To keep runs short, the template's time axis is compressed by `timeScale` so the run offers about
  1,500 requests (at least 2 s, never longer than the template's run): duration, warm-up, bursts and
  faults all scale, so they keep their places in the run. Rates and every other setting are the
  template's. The compressed document is recorded.
- The report's machine-dependent provenance is blanked: `wallSeconds` is 0 and `python` is
  `"normalized"`.
- The exporter checks that each function fed the finished report reproduces what the report holds
  (findings, confidence, cost, each node's generic formulas, both digests), so the report is
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

## What is left out

- **The simulation kernel, components, runtime and report building** (`run_simulation`,
  `build_report`, trace selection, latency breakdown, metrics): not functions a recorded JSON input
  determines across implementations. Stage 4's parity is statistical.
- **Sweeps, knee search and seed groups** (`breaking_point`, `knee_sweeps`, `run_check --knee`):
  many simulations each. Their pure parts are recorded (`sweep_range`, `levels`, `suggest_range`,
  `rps_cap`, `row_from`, `meets`, `analyze`, `summarize_seeds`, `regressions`, `run_check`).
- **Component-specific explain formulas** (`RuntimeComponent.explain`) need a live runtime component;
  they are in the recorded reports.
- **Reports of every scenario**: two runs per template (above).
- **The Prometheus HTTP client and outbound checks** (network), the CLI and MCP server (stage 5) and
  the HTTP API (stage 2's black-box tests). Plugins: the default registry has none.
- **Inline test documents** in `packages/engine/tests`: built by test code, not fixture files. The
  invalid documents here are mutations of templates instead, one per validation code or schema
  error the tests cover and more.
- **The benchmark harness's fits** (`benchmarks/run.py`): it stays Python until stage 7.
  `fit_load_curve` and `extend_load_curve` are recorded on synthetic points, and every fit a
  calibration makes on its real inputs.
