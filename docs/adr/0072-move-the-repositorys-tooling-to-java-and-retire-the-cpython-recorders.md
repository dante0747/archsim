---
status: proposed
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0072. Move the repository's tooling to Java, and retire the recorders of CPython's behaviour

## Context and Problem Statement

[ADR-0064](0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md) kept the
repository's Python tooling until stage 7, when "the tooling moves to Java, or to whatever runs
it then, or goes, so that the last Python goes with the harness". That tooling is:

- `check_commits.py`: the `commit-msg` hook and CI's check of a branch's commit messages;
- `ci_changes.py`: which CI jobs a change needs, run first in CI, before any toolchain is set up;
- `prices/` (`aws.py`, `azure.py`, `gcp.py`, `common.py`, `diff.py`): the monthly price refresh
  (`prices.yml`), which writes a snapshot into `archsim-model` and summarises its changes;
- `java_parity_report.py`: the Java engine's parity page, which moves into the harness
  ([ADR-0069](0069-run-the-harness-from-gradle-with-picocli-commands.md), `java-parity`);
- `export_casefold.py`, `export_erfc_points.py` and `export_printable.py`: recorders of CPython
  3.12's behaviour that the Java keeps on purpose (`str.casefold`, `math.erfc`,
  `str.isprintable`); their `--check` is the proof that the data is Python's.

ADR-0064 rejected Java for the first two then: "a commit hook that starts a JVM for every commit is
slow, and `ci_changes.py` runs before CI installs Java". What runs each of them now?

## Decision Drivers

- Stage 7's exit: no Python outside the benchmarked stacks.
- The owner maintains Java; the tooling is checked like the rest of the code.
- A commit hook stays quick; CI's first job stays short.
- Recorded data stays traceable to what recorded it.

## Considered Options

- Java in a module `archsim-tools`: the hook's and CI's checks started from their source by
  Java's source launcher, the price refresh as a Gradle-run command; the CPython recorders
  retired, their data kept as recorded
- Node.js scripts for the hook and CI's changes (as the action's comment, ADR-0064), Java for the
  price refresh
- Everything in Java through Gradle tasks, the hook included

## Decision Outcome

Chosen option: "Java in `archsim-tools`, the quick checks from source", because it leaves one
language for the back end and its tooling, and Java's source launcher (JEP 458, Java 22) runs a
program from its source files without a build.

- **`archsim-tools`**, package `io.archsim.tools`, tested and checked as every module is.
- **Commit messages and CI's changes** (`io.archsim.tools.commits`, `io.archsim.tools.changes`)
  use the JDK alone, so `java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java`
  runs them without Gradle: the hook takes about 0.6 s on the owner's laptop. The hook uses
  `$JAVA_HOME/bin/java` when it is set, else `java`, and says so when that Java is older than 22.
  CI's `changes` job sets up Java 25 first (`actions/setup-java`, which the runner image caches).
- **The price refresh** (`io.archsim.tools.prices`): `./gradlew :archsim-tools:run
  --args="prices aws --region ..."`, and `prices diff`; the snapshots are written as
  `json.dumps` wrote them ([ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md)),
  so the next snapshot diffs cleanly against the last.
- **The CPython recorders go.** Their tables (`casefold.tsv`, `nonprintable.tsv` and the `erfc`
  points) stay as data recorded once, as stage 5 kept the engine's oracles; `parity/README.md`
  names the last commit that has the recorders, to record them again from if Python's behaviour
  must be checked. The Java tests that read the tables stay.

**Status (14 Nov 2026):** stage 7's S7-8 built this as decided, with these differences.

- The hook takes about a second on the owner's laptop (0.95 to 1.07 s), not 0.6 s, nearly all
  of it the source launcher compiling the check; Python's check took a quarter of a second
  through `uv run`.
- The hook refuses the commit (exit 1, naming the Java it found) when that Java is older than 22
  or missing, so committing needs a JDK of 22 or later, `$JAVA_HOME`'s when it is set: a machine
  whose `JAVA_HOME` names an older JDK, as the owner's names JDK 21, sets it to a newer one for
  its commits.
- The refresh workflow runs the installed commands, `./gradlew :archsim-tools:installDist`, then
  `archsim-tools prices aws` and `prices diff`, so that each step's own `GITHUB_OUTPUT` reaches
  them; by hand, `./gradlew :archsim-tools:run --args="prices ..."` runs them as decided.
- The archsim-check action's test, `scripts/tests/test_archsim_check_action.py`, moved too, to
  archsim-tools' `ArchsimCheckActionTests`, and a change to the action now runs the Java job.
- `parity/README.md` names the tag `cpython-recorders` (commit `1634ba9e`) as the last commit
  with the CPython recorders.

Two confirmations wait. CI's `changes` and `commits` jobs have run only locally while Actions
cannot start jobs. The price refresh has run against the parsers' samples, where it writes what
`aws.py` wrote byte for byte, but not yet against live offer files: running `aws.py` and
`prices aws` on the same cached files is the check left before stage 7 deletes `aws.py`.

### Consequences

- Good, because no Python is left in the tooling, and every tool has JUnit tests and the same
  checks as the code.
- Good, because the hook needs a JDK, which every contributor has, and no build.
- Bad, because the hook takes about half a second, where Python's took a tenth.
- Bad, because CI's first job installs Java, a few seconds more.
- Bad, because the CPython tables can no longer be checked against Python without checking out
  an older commit.

### Confirmation

`archsim-tools`' tests (ported from `scripts/tests`); the hook run on this repository's own
commits; CI's `changes` job; one price refresh run locally with a cached price list.

## Pros and Cons of the Options

### Node.js scripts for the hook and CI's changes

- Good, because Node starts in a tenth of a second and every runner has it.
- Bad, because the tooling would be in a third language that the owner does not maintain.

### Everything through Gradle tasks

- Good, because there is one way to run everything.
- Bad, because a hook that starts Gradle takes seconds on every commit, and CI's first job would
  set up Gradle to decide which jobs to run.

## More Information

- [ADR-0064](0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md): the tooling
  until stage 7; this ADR supersedes it once stage 7 is built.
- [JEP 458](https://openjdk.org/jeps/458): launching multi-file source-code programs.
