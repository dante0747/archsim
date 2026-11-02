---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0009. Use `io.archsim` as the base package

## Context and Problem Statement

Every Java class needs a package, and Java names packages after a reversed internet domain to keep
them unique. The name appears in every source file, in logs and stack traces, and in any published
artifact's coordinates, so changing it later touches the whole codebase.

Which base package should ArchSim's Java code use?

## Considered Options

- `io.archsim`
- `dev.archsim`

## Decision Outcome

Chosen option: "`io.archsim`", because it is short, neutral and a common form for open-source
projects.

Each module gets its own package below it:

| Module | Package |
| --- | --- |
| `archsim-model` | `io.archsim.model` |
| `archsim-engine` | `io.archsim.engine` |
| `archsim-app` | `io.archsim.app` |
| `archsim-cli` | `io.archsim.cli` |
| `archsim-mcp` | `io.archsim.mcp` |

### Consequences

- Good, because package names stay short and match the product's name.
- Good, because each module's package says which module a class belongs to.
- Bad, because the convention assumes the project controls the domain `archsim.io`. If the code is
  ever published under that name, the domain should be registered to the project first.

## More Information

- [ADR-0010](0010-gradle-build-at-repository-root.md) (modules) and
  [ADR-0011](0011-organise-application-code-by-feature.md) (packages inside `archsim-app`)
- Package naming conventions: the Java Language Specification, section 6.1
