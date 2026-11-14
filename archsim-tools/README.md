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

## Tests

```bash
./gradlew :archsim-tools:check
```

They are `scripts/tests/test_check_commits.py`'s and `test_ci_changes.py`'s (its table of
changes in `changes.csv`), and run both commands through Java's source launcher too, so nothing
beyond the JDK creeps into them.
