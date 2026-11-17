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

CI's `changes` job works out which jobs a change can affect, and the others are skipped, so that a
run takes no longer than the change needs (`io.archsim.tools.changes`). It uses the JDK alone
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
| `GitDiff`, `ChangedFile` | the files a change touched through `git diff`, each marked when it deleted them, none without a usable base | `changed_files` |

The rules began as `ci_changes.py`'s, but for CI itself, which became this package, and
`archsim-tools/`, which the Java job tests: on main's last 80 commits then, both gave the same
report but for the two that changed `scripts/ci_changes.py`. With the Python gone (stage 7's last
slice) they hold the Java, archsim-check, web and end-to-end jobs alone, and a change to
`.github/actions/` runs the Java job, where `ArchsimCheckActionTests` runs the action's step. The
website's checks joined them after a broken anchor in an ADR reached main unchecked: a change to
what the site is built from (`apps/web/`, `docs/`, `examples/`, the README, the changelog,
`NOTICE`) runs them, and so does a change that deletes a file anywhere, which `GitDiff` reports
(`ChangedFile`), since the docs may link to it.

## The release's check

The release workflow (`.github/workflows/release.yml`) checks its tag before it publishes the
images (`io.archsim.tools.release`,
[ADR-0077](../docs/adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)): the tag
names the version `gradle.properties` says, and `docker-compose.yml` and the hosted kit's
`deploy/compose.yaml` ([ADR-0080](../docs/adr/0080-host-a-public-server-from-a-compose-kit.md))
each run both images from the workflow's registry at it. It uses the JDK alone, and the workflow runs
it from its source:

```bash
java archsim-tools/src/main/java/io/archsim/tools/release/CheckRelease.java --tag v1.0.0 --registry ghcr.io/dante0747
```

| Java | What it does |
| --- | --- |
| `CheckRelease` | the command line: the tag's problems, or the version and whether it moves `latest`, as GitHub's outputs |
| `Arguments` | `--tag`, `--registry` and `--github-output` |
| `Checkout` | the checkout's version, read as Gradle reads `gradle.properties`, and the images each compose file runs, by file |

## The price refresh

A snapshot of a provider's public list prices in ArchSim's format (`archsim.prices/1`), written
among the ones archsim-model ships, and a summary of what changed since the last
(`io.archsim.tools.prices`). Gradle runs it from the repository's root; ArchSim never calls a
price API at runtime:

```bash
./gradlew :archsim-tools:run --args="prices aws --region us-east-1"
./gradlew :archsim-tools:run --args="prices azure --region eastus"
GOOGLE_API_KEY=... ./gradlew :archsim-tools:run --args="prices gcp --region us-central1"
./gradlew :archsim-tools:run --args="prices diff archsim-model/src/main/resources/io/archsim/model/pricing/snapshots/NEW.json"
```

A snapshot is written as `scripts/prices` wrote it, in `json.dumps(snapshot, indent=1)`'s text
with Python's keys, numbers and order, so that the next one diffs cleanly against the last
(ADR-0072): written again, the AWS snapshot of 2026-10-28 is the same file, byte for byte, and
`prices diff` words the shipped snapshots' changes as `diff.py` did. The refresh workflow
(`.github/workflows/prices.yml`) runs the installed commands, `./gradlew :archsim-tools:installDist`
then `archsim-tools/build/install/archsim-tools/bin/archsim-tools prices ...`, so that each step's
`GITHUB_OUTPUT` reaches `prices diff` directly.

| Java | What it does | Python it ports |
| --- | --- | --- |
| `PricesCommand`, `AzureCommand`, `GcpCommand`, `DiffCommand` | the commands, their options and failures | each script's `main` |
| `Sku`, `Shape` | a type's price and shape, rounded as Python rounded them, whole numbers kept apart from floats | `common.sku` |
| `SkuList` | one entry per type, shape and price, in a snapshot's order; a type and engine at two prices refused | `common.dedupe` |
| `Snapshot`, `SnapshotWriter` | a snapshot, written dated and in order among the snapshots | `common.write_snapshot` |
| `HttpDownloads` | the public endpoints, with a cache of whole files | `common.fetch` |
| `AwsCommand`, `AwsPrices` | a region's offer files, fetched by version into the cache, and its snapshot and note | `aws.py`'s `main`, `download`, `provenance` |
| `OfferFile`, `OfferRow`, `CsvRecords`, `OfferText` | an offer file's metadata and rows, streamed and read as Python's `csv` module read them, and its cells | `rows`, `header`, `_price`, `_regional`, `_usage`, `gib` |
| `Ec2Prices`, `RdsPrices`, `ElastiCachePrices`, `MskPrices` | the instance, database, cache and broker types, and EC2's gp3 and RDS's storage | `ec2`, `rds`, `rds_storage`, `elasticache`, `msk` |
| `Burstables` | the burstable types, their baseline CPU and credit prices | `BURSTABLE`, `baseline_cpu`, `_with_credits` |
| `UsageRates` | Lambda, SQS, the load balancers, DynamoDB, API Gateway and EKS at their first paid tiers | `rates`, `dynamodb`, `api_gateway`, `eks`, `_paid_tier`, `_only` |
| `AzurePrices` | Azure's virtual machines from the Retail Prices API | `azure.py` |
| `GcpPrices` | GCP's machine types from the Cloud Billing Catalog API | `gcp.py` |
| `PriceDiff`, `Baselines` | the summary of a snapshot against the newest older one | `diff.py` |
| `JsonValues`, `Queries`, `CodePoints` | Python's truth, `float()`, `int()`, `str()` and `==` of JSON values, `urlencode`, and its order of text | |

### What the port changed

Snapshots and summaries are `scripts/prices`' own but in two cases, the first of which no
snapshot has had; the rest is how the commands fail and wait:

- Two entries of one type, shape and price with no engines are merged without an `engines`
  key; `common.dedupe` added `"engines": []` at the entry's end. Azure's and GCP's types never
  repeat, and the shipped AWS snapshots have no such pair.
- RDS rows whose usage type, after the region prefix, starts with `UnusedBox:` are skipped:
  an unused capacity reservation's hour, which the RDS file has listed beside each instance
  hour since November 2026, at its price but with no `Current Generation`. `aws.py` read them
  as instance hours, which let 35 previous-generation types back in.
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
- An offer file's vCPUs are digits of any script; Python's `isdigit()` also took digits such as
  superscripts, which its `float()` then refused, so such a row is skipped where `aws.py`
  stopped. A database or cache type with no family in its name stops the refresh with a
  message, where `aws.py` stopped with an `IndexError`.
- The commands' help is picocli's, not argparse's.

## Tests

```bash
./gradlew :archsim-tools:check
```

They are `scripts/tests/test_check_commits.py`'s, `test_ci_changes.py`'s (its table of
changes in `changes.csv`), `test_price_refresh_scripts.py`'s and
`test_archsim_check_action.py`'s: `ArchsimCheckActionTests` runs the archsim-check action's check
step in bash with a stand-in for `docker`, from a file as GitHub runs a step, on Linux only (the
step reads `/proc/sys/kernel/random/uuid`). They run the commit and CI
commands through Java's source launcher too, so nothing beyond the JDK creeps into them, and
compare the price refresh's snapshots and summaries with what `scripts/prices` wrote for the same
samples, recorded once (`src/test/resources/io/archsim/tools/prices/`). `CommitMsgHookTests` runs
the `commit-msg` hook itself in `sh`, with a `JAVA_HOME` that ends in a backslash as Windows often
sets it, and is skipped where there is no `sh`.
