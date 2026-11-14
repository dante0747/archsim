# archsim-tools

The repository's tooling, in Java ([ADR-0072](../docs/adr/0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md)):
what `scripts/` held in Python until stage 7 of the Java port
([plan](../docs/proposals/java-25-port.md)).

## The commit messages' check

Every commit message is a [Conventional Commit](https://www.conventionalcommits.org) with no AI
co-author trailer (`io.archsim.tools.commits`). The check uses the JDK alone, so Java's source
launcher (Java 22 or later) runs it from its source without a build:

```bash
java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java --file .git/COMMIT_EDITMSG
java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java --range origin/main..HEAD
```

The `commit-msg` hook (`.githooks/commit-msg`, enabled by `make hooks`) runs the first with
`$JAVA_HOME`'s Java when it is set, else `java`, and says so when that Java is older than 22; CI's
`commits` job runs the second on every commit a pull request or push adds. Either takes about a
second on the owner's laptop, most of it the launcher compiling the check.

| Java | What it does | Python it ports |
| --- | --- | --- |
| `CheckCommits` | the command line: a message's file or a revision range, the exit code | `check_commits.py`'s `main` |
| `Arguments` | `--file` or `--range`, one and not both, as `argparse` read them | its `argparse` parser |
| `CommitMessage` | a message's problems: the header, the blank line after it, AI co-author trailers; a commit git squashes away locally is fine before it is pushed | `problems`, `TYPES`, `HEADER`, `NAME`, `AI_TRAILER`, `AUTOSQUASH` |
| `GitLog` | a range's commits through `git log`, read as UTF-8 | `commits` |
| `PythonStr` | `repr()`, `splitlines()` and whitespace (`isspace()`, `strip()`, `re`'s `\s`) as CPython's `str` has them, wider than Java's: the check accepts and words what `check_commits.py` did | `repr`, `str.splitlines`, `str.strip`, `str.isspace` |

## CI's changed parts

The repository is private, so every CI minute is billed: CI's `changes` job works out which jobs a
change can affect, and the others are skipped (`io.archsim.tools.changes`). It uses the JDK alone
too, and the job runs it from its source after setting up Java 25:

```bash
java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --base BASE_SHA --head HEAD_SHA
java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --all --github-output "$GITHUB_OUTPUT"
```

| Java | What it does | Python it ports |
| --- | --- | --- |
| `ChangedParts` | the command line: the jobs a change needs, each with the first file that needs it, and GitHub's outputs | `ci_changes.py`'s `main` |
| `Arguments` | `--all`, or `--base` and `--head`, and `--github-output`, as `argparse` read them | its `argparse` parser |
| `Rules`, `Rule` | the paths CI knows and the jobs each needs, the first covering rule deciding, every job for a path no rule names | `RULES`, `covers`, `needed_by`, `jobs` |
| `Job` | the jobs that run only when a change needs them | `JOBS` |
| `GitDiff` | the files a change touched through `git diff`, none without a usable base | `changed_files` |

The rules are `ci_changes.py`'s but for CI itself, which is now this package
(`scripts/ci_changes.py` falls under `scripts/`, which the Python job tests), and
`archsim-tools/`, which the Java job tests. On main's last 80 commits both give the same report
but for the two that changed `scripts/ci_changes.py`.

## The price refresh

A snapshot of a provider's public list prices in ArchSim's format (`archsim.prices/1`), written
among the ones archsim-model ships, and a summary of what changed since the last
(`io.archsim.tools.prices`). Gradle runs it from the repository's root; ArchSim never calls a
price API at runtime:

```bash
./gradlew :archsim-tools:run --args="prices azure --region eastus"
GOOGLE_API_KEY=... ./gradlew :archsim-tools:run --args="prices gcp --region us-central1"
./gradlew :archsim-tools:run --args="prices diff archsim-model/src/main/resources/io/archsim/model/pricing/snapshots/NEW.json"
```

A snapshot is written as `scripts/prices` wrote it, in `json.dumps(snapshot, indent=1)`'s text
with Python's keys, numbers and order, so that the next one diffs cleanly against the last
(ADR-0072): written again, the AWS snapshot of 2026-10-28 is the same file, byte for byte, and
`prices diff` words the shipped snapshots' changes as `diff.py` did.

| Java | What it does | Python it ports |
| --- | --- | --- |
| `PricesCommand`, `AzureCommand`, `GcpCommand`, `DiffCommand` | the commands, their options and failures | each script's `main` |
| `Sku`, `Shape` | a type's price and shape, rounded as Python rounded them, whole numbers kept apart from floats | `common.sku` |
| `SkuList` | one entry per type, shape and price, in a snapshot's order; a type and engine at two prices refused | `common.dedupe` |
| `Snapshot`, `SnapshotWriter` | a snapshot, written dated and in order among the snapshots | `common.write_snapshot` |
| `HttpDownloads` | the public endpoints, with a cache of whole files | `common.fetch` |
| `AzurePrices` | Azure's virtual machines from the Retail Prices API | `azure.py` |
| `GcpPrices` | GCP's machine types from the Cloud Billing Catalog API | `gcp.py` |
| `PriceDiff`, `Baselines` | the summary of a snapshot against the newest older one | `diff.py` |
| `JsonValues`, `Queries`, `CodePoints` | Python's truth, `float()`, `int()`, `str()` and `==` of JSON values, `urlencode`, and its order of text | |

### What the port changed

Snapshots and summaries are `scripts/prices`' own, but for one case no snapshot has had; the
rest is how the commands fail and wait:

- Two entries of one type, shape and price with no engines are merged without an `engines`
  key; `common.dedupe` added `"engines": []` at the entry's end. Azure's and GCP's types never
  repeat, and the shipped AWS snapshots have no such pair.
- A failure the price lists or the machine cause (a download, a file, a price list or
  snapshot that does not read as expected) prints its message, or its class when it has none,
  and exits with 1, as the scripts' traceback did; anything else is a bug and keeps its stack
  trace.
- An HTTP error names the address without its query, which can hold `GOOGLE_API_KEY`, and a
  failed download's `.part` file is deleted.
- Each download has a deadline for its whole answer, two minutes for a page and half an hour
  for an offer file: urllib's two minutes bounded each read, HttpClient's only the wait for
  the answer's headers.
- `prices diff` takes the newest older snapshot from the folder's JSON objects in the order
  of their names, so that two of one date pick the same; it passed over files that are not
  JSON, and stopped at JSON that is not an object. A snapshot missing its provider, region or
  date prints `None` where `diff.py` stopped with a `KeyError`. Its lines end in LF on
  Windows too, where Python's `print` wrote CRLF.
- The commands' help is picocli's, not argparse's.

## Tests

```bash
./gradlew :archsim-tools:check
```

They are `scripts/tests/test_check_commits.py`'s, `test_ci_changes.py`'s (its table of
changes in `changes.csv`) and `test_price_refresh_scripts.py`'s. They run the commit and CI
commands through Java's source launcher too, so nothing beyond the JDK creeps into them, and
compare the price refresh's snapshots and summaries with what `scripts/prices` wrote for the same
samples, recorded once (`src/test/resources/io/archsim/tools/prices/`).
