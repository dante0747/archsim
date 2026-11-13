# Working on ArchSim with an AI assistant

Instructions for AI coding assistants (Claude Code, Codex, Cursor, Copilot and others) working in
this repository. People follow the same rules: [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/java-conventions.md](docs/java-conventions.md).

## Standards to follow

Read the skill for the task before starting it. Each is a short, complete set of rules:

| Task | Skill |
| --- | --- |
| Writing, changing or reviewing Java | [`.claude/skills/java-conventions`](.claude/skills/java-conventions/SKILL.md) |
| Adding behaviour or fixing a bug, in any language | [`.claude/skills/test-first`](.claude/skills/test-first/SKILL.md) |
| Writing a commit message | [`.claude/skills/conventional-commits`](.claude/skills/conventional-commits/SKILL.md) |

Before committing a change to Java code, the build or a migration, have it reviewed by the
strict senior Java reviewer, [`.claude/agents/java-reviewer.md`](.claude/agents/java-reviewer.md)
(in Claude Code, the `java-reviewer` agent; elsewhere, give an assistant that file as its
instructions), and commit only once it approves.

The build enforces the Java rules and fails on any finding: never suppress a finding, add
`@SuppressWarnings` for a style rule, or change `config/checkstyle/` to make a change pass.
Ask the owner instead.

## The repository

- `archsim-model/`, `archsim-engine/`, `archsim-app/`, `archsim-api-tests/`, `archsim-cli/`,
  `archsim-mcp/`, `archsim-benchmarks/`, `build-logic/`: Java 25, one Gradle build
  (`./gradlew build`). The Java port's plan is `docs/proposals/java-25-port.md`.
- `parity/`: what the Python engine, frozen at 0.16.0, recorded for the Java tests (golden files,
  simulation baselines, test fixtures). It is data now, never recorded again: stage 5 deleted
  that engine and its MCP server (`packages/`; the tag `engine-0.16.0` keeps them).
- `benchmarks/`: the Python benchmark harness, on the Java engine. `apps/web/`: the Next.js app.
  Stage 6 deleted `services/api`, the Alembic migrations Flyway took over from; a database below
  their revision 0008 upgrades from the tag `alembic-0008` first (ADR-0063).
- `docs/adr/`: architecture decisions. A significant decision gets a new ADR with
  `status: proposed`; never mark one accepted, that is the owner's call.

## Before saying a change is done

```bash
./gradlew format && ./gradlew build            # Java: format, Checkstyle, Error Prone, tests
uv run ruff check benchmarks scripts           # Python
./gradlew :archsim-cli:installDist             # the benchmarks' tests ask the installed CLI
uv run pytest
uv run python scripts/check_commits.py --range main..HEAD
```

## Rules that are never bent

- The repository is private: never change its visibility or publish its content.
- Commits use the configured git identity and carry no AI co-author trailer.
- Never print or edit secrets (`.env*` files other than `.env.example`).
- Python commands run through `uv run`.
