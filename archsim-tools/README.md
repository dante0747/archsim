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

## Tests

```bash
./gradlew :archsim-tools:check
```

They are `scripts/tests/test_check_commits.py`'s, and run the check through Java's source
launcher too (`SourceLaunchTests`), so nothing beyond the JDK creeps into it.
