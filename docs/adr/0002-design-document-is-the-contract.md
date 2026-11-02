---
status: accepted
date: 2026-10-24
decision-makers: Majid Abarghooei (owner)
---

# 0002. The versioned design document is the contract between web app, API and engine

*Recorded retroactively on 2026-11-02. The decision dates from the first commit (`ea38af6`,
24 Oct 2026), which shipped schema 1.0 with `migrations.py`.*

## Context and Problem Statement

Many parts of ArchSim create, store, edit or simulate a design: the web app's editor, the API, the
engine, the `archsim` CLI, the MCP server, the `archsim check` GitHub Action, and the example files
in `examples/`. They need one description of a design that each can validate, that survives
version changes, and that makes a run reproducible.

What should that shared description be?

## Considered Options

- One versioned JSON document, defined by the engine's models, with migrations between versions
- A model per layer (database tables, API payloads, engine objects), translated at each boundary

## Decision Outcome

Chosen option: "One versioned JSON document", because the engine is the single source of truth for
domain rules, a single file moves unchanged between the editor, the CLI, the MCP server, CI and
version control, and versioning lets old documents keep loading.

The rules, as [`docs/schema.md`](../schema.md) states them:

- A project is one human-readable JSON document: `architecture`, `workload`, `scenarios` and
  `layout`. The layout never affects a simulation.
- Keys are camelCase and unknown keys are rejected. Times in settings are milliseconds (`…Ms`),
  workload times are seconds (`…S`), and rates are requests per second.
- `schemaVersion` is `MAJOR.MINOR` (1.7 today). Older documents are migrated on load, newer ones
  are rejected, and export always writes the current version.
- A node's `config` is validated by the component model registered for its `type`, so the document
  schema doesn't depend on the component catalogue.
- The JSON Schema is served at `GET /api/v1/schema/architecture` and printed by `archsim schema`.

### Consequences

- Good, because any tool can work from a design file alone, and a run stores the document it ran,
  so its result can be reproduced.
- Good, because adding a component type needs no schema change, no API change and no web change
  ([`extending-components.md`](../extending-components.md)).
- Bad, because every change to the format needs a version bump and a migration, and every reader
  has to follow.
- Bad, because validation messages are part of the contract: the web app shows them, so a port must
  reproduce them word for word.

### Confirmation

The engine's tests cover the migrations and load the example designs in `examples/`.

## More Information

- [`docs/schema.md`](../schema.md) and [`docs/architecture.md`](../architecture.md)
- The Java port keeps this contract unchanged ([ADR-0005](0005-port-to-java-25-in-stages.md)), and
  gives the document its own module ([ADR-0012](0012-separate-model-and-engine-modules.md)).
