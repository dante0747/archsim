@AGENTS.md

Claude Code loads the skills in `.claude/skills/` by their descriptions: use `java-conventions`
for any Java work, `test-first` before writing production code, and `conventional-commits` for
every commit message. Before each commit that touches Java, the build or a migration, run the
`java-reviewer` agent on the staged diff and commit only when it answers APPROVE.
