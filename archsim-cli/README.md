# archsim-cli

The `archsim` command line, on the Java engine. Stage 4 of the
[Java port](../docs/proposals/java-25-port.md) brings one command, `archsim run`, which the
benchmark harness runs to simulate on the Java engine
([ADR-0051](../docs/adr/0051-start-the-java-cli-in-stage-4-with-archsim-run.md)); stage 5 brings
the rest of the Python CLI's commands, with the same flags and output.

```bash
./gradlew :archsim-cli:installDist                    # archsim-cli/build/install/archsim/bin/archsim
archsim-cli/build/install/archsim/bin/archsim run examples/queueing-lab.json
```

The start script runs the `java` that `JAVA_HOME` names, else the one on the `PATH`; it needs
Java 25. The command writes UTF-8 whatever the console's encoding, as the Python CLI's trust
lines did: a Windows console on a legacy code page shows `→` garbled.

## `archsim run`

`archsim run FILE [--scenario ID] [--seed N] [--sampled] [--out FILE]` simulates a design and
prints what the Python engine's CLI printed (`cmd_run`, `_summary_line`, `_trust_lines` and
`_print_issues` in `packages/engine/src/archsim_engine/cli.py`), word for word: the summary line,
a quick check's note, how far to trust the run, its findings and its digests. An invalid design's
issues go to standard output and the refusal to standard error, with exit code 1, as does a run
over its limits. `--seed` replaces the design's seed before its scenario applies, as the Python
CLI did, so a scenario that sets its own seed keeps it; a workload that is not an object is left
for validation to refuse (the Python CLI raised `TypeError`).

Two flags the Python `run` did not have set the run's limits: `--max-requests` and
`--max-wall-seconds` (the engine's own, two million requests and five minutes, when left out; a
value that is not a number above zero is a usage error, exit code 2). `--out` writes the report as
Jackson's pretty printer writes JSON (`"key" : value`, arrays on one line): it holds what the
Python report held, not in the same text (its keys' order and floats' spelling may differ).

## Packages

| Package | What it holds |
| --- | --- |
| `io.archsim.cli` | `Archsim` (the `archsim` command, its `main`), `RunCommand` (`archsim run`), `RunText` (what `run` prints, in the Python CLI's words) |

## Tests

```bash
./gradlew :archsim-cli:test
```

- `RunCommandTests` runs each recorded run of `run-cases.json.gz`, from
  `scripts/export_cli_cases.py` (the frozen engine's CLI on small designs: a run, one without
  failures, one whose every call fails, a scenario with a seed of its own, a seed given, a seed
  for a design without a workload, a quick check, a report written to a file, an invalid design,
  an invalid workload and a run over the request limit), and expects the same exit code and the
  same standard output and error, word for word; a report written to a file must hold what
  Python's held, as the simulator's recorded runs are compared (`ReportComparison`, from
  `archsim-engine`'s test fixtures).
- `RunOptionsTests` checks what the recordings cannot: the two limits the Python `run` did not
  have, a seed for a workload that is not an object, and `archsim` without a command.
- `RunTextTests` checks the lines a report skips when its quick check, confidence or estimate is
  empty or missing.
