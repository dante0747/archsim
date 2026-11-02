---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0012. Separate the design model from the simulation engine

## Context and Problem Statement

The Python engine package holds two kinds of code:

- **The design document's model:** the schema, migrations from 1.0 to 1.7, and validation
  ([ADR-0002](0002-design-document-is-the-contract.md)).
- **Everything that works on a design:** the simulator and its components, analysis, sizing, cost,
  pricing, calibration, importers and exports.

The application, the CLI and the MCP server all read and validate designs, but not all of them
simulate. The port also brings the model across first (stage 1), before any engine code.

How should the Java engine be split into modules?

## Considered Options

- Two modules: `archsim-model` and `archsim-engine`
- One `archsim-engine` module, organised by package
- Fine-grained modules: model, kernel, components, analysis, sizing and cost, calibration,
  importers

## Decision Outcome

Chosen option: "Two modules", because the model has consumers of its own and is ported on its own,
while further splits would add build files and dependency wiring that nothing needs yet.

| Module | Contents |
| --- | --- |
| `archsim-model` | The design document as Java records, the upgrades from schema 1.0 to 1.7, validation (whose messages match Python's word for word, because the web app shows them), and the generated JSON Schema |
| `archsim-engine` | The simulation kernel ([ADR-0013](0013-event-driven-simulation-kernel.md)) and components, analysis, sizing, cost, pricing, calibration, importers and exports. It depends on `archsim-model` |

Both are plain Java libraries with no framework: no Spring and no JDBC.

### Consequences

- Good, because the application, the CLI and the MCP server can validate a design without loading
  the simulator.
- Good, because the boundary between the format and the behaviour is enforced by the build, not by
  convention.
- Bad, because `archsim-engine` is still a large module, so its packages must stay well organised.
  A package that grows a separate consumer is a candidate for its own module later.
- Bad, because a change to the format and its effect on the simulation touch two modules.

### Confirmation

Stage 1 of [ADR-0005](0005-port-to-java-25-in-stages.md) checks the model on its own: every example
and test fixture reads and writes back identically, and every invalid fixture gives Python's
errors.

## More Information

- [ADR-0010](0010-gradle-build-at-repository-root.md) (module folders) and
  [ADR-0009](0009-base-package-io-archsim.md) (packages)
