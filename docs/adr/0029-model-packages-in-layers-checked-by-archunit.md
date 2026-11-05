---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0029. Build the model's packages in layers, one direction only, checked by ArchUnit

## Context and Problem Statement

`archsim-model` has about a dozen packages: Python's text conventions, the codec, the document's
records, issues, upgrades, pricing, component types, scenarios, validation and the
`DesignDocuments` entry point. The port proposal asks for ArchUnit rules that keep the model free
of Spring and JDBC and of the engine ([the proposal](../proposals/java-25-port.md#target-architecture)),
"plus any layering it needs". Without a rule, packages drift into each other: the first draft had
validation and the component types using each other's classes, and the Kubernetes and service
types each reading the other's config.

Should the model's packages have a fixed order, and how is it kept?

## Considered Options

- Layers in one direction, with no cycles between packages, checked by ArchUnit
- Only the rules the proposal names (no framework, no engine)
- A Gradle module per layer, so the compiler keeps the order

## Decision Outcome

Chosen option: "layers checked by ArchUnit", because it keeps one module (as
[ADR-0012](0012-separate-model-and-engine-modules.md) intends) while making its order a rule the
build checks, not a habit.

From the top, each layer may use only those below it:

| Layer | Packages | Uses |
| --- | --- | --- |
| Entry point | `io.archsim.model` | anything below |
| Validation | `validation` | components, pricing, issues, document, codec, text |
| Scenarios | `scenario` | issues, document, text |
| Catalog | `catalog` | components, document, codec, text |
| Components | `component..` | issues, document, codec, text |
| Pricing | `pricing` | codec, text |
| Upgrades | `upgrade` | document, text |
| Issues | `issue` | codec, text |
| Document | `document`, `distribution` | codec, text |
| Codec | `codec` | text |
| Text | `text` | nothing |

And no two packages, at any depth, depend on each other. Keeping that rule moved three things:

- `Issue`, `Severity`, `SchemaIssues` and `DocumentInvalidException` moved from `validation` to
  `issue`, because component rules report issues and validation runs the rules.
- The standard registry moved from `ComponentRegistry` to `component.standard.StandardComponents`,
  because the shared types (`ComponentSpec`, `Port`, `GraphView`) must not depend on the types
  built on them.
- The Kubernetes cluster's rule finds its services through `ServiceConfig.clusterIn` and
  `GraphView.idsOfType`, so `service` and `kubernetes` no longer use each other.

### Consequences

- Good, because a reader can learn the model from the bottom up, and a new class's place is
  decided by what it uses.
- Good, because each move above gave the type a better home, not just a legal one.
- Bad, because ArchUnit runs only with the tests, so an IDE shows a broken rule later than the
  compiler would.
- Neutral: a later stage that adds a package (the catalog in stage 2) adds a row to the table and
  the test.

### Confirmation

`io.archsim.model.ArchitectureTests` checks four rules on every build: no framework or database
classes, nothing from the engine, no package cycles, and the layers above.

## Pros and Cons of the Options

### Only the rules the proposal names

- Good, because it is the least to keep.
- Bad, because the cycles the first draft had would have stayed, and nothing would stop new ones.

### A Gradle module per layer

- Good, because the compiler enforces the order.
- Bad, because ten modules of a few classes each, each with its own build file and published
  artifact, is more structure than one library needs, and splits what changes together.

## More Information

- ArchUnit: <https://www.archunit.org/>
- [ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md): the component types in the
  model.
