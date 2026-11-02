---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0026. Component specifications and the price-snapshot lookup belong to the model

## Context and Problem Statement

[ADR-0012](0012-separate-model-and-engine-modules.md) puts validation in `archsim-model` and the
components and pricing in `archsim-engine`. But validating a design, as the Python engine does,
needs to know about the component types: each type's ports and the request types they take, its
config's fields and bounds, how request types flow through it, and its own rules (a cache that
can miss needs somewhere to read on a miss; a pod must fit a Kubernetes node). Many of those rules
read a technology's defaults (Redis runs commands on one thread). Validation also looks prices up:
a document that pins a price snapshot this machine does not have, or names an unknown instance
family or type, gets a warning naming the snapshots there are.

Where does that knowledge live, so the app, the CLI and the MCP server can validate a design
without the simulator?

## Considered Options

- In `archsim-model`: each component type's specification (ports, config record, request-type
  routing, rules, technology defaults) and reading and looking up price snapshots; the simulator's
  behaviour of each type, sizing and costing stay in the engine
- In `archsim-engine`, with validation moved to the engine
- In `archsim-engine`, behind interfaces the model's validator calls

## Decision Outcome

Chosen option: "in `archsim-model`", because these are facts about the design format (what a
`cache` node may say and connect to, which instance types a price snapshot has), not about
simulating it, and they are what a client needs to check a design.

- `io.archsim.model.component`: `ComponentSpec<C>` is the extension point, as Python's
  `ComponentModel` is: a type's name, ports, config record, `emittedTypes` and `validate`.
  `ComponentRegistry.standard()` holds ArchSim's eleven types; each type has a package with its
  config record and spec. A config's "auto" settings resolve through `TechnologyProfile` tables,
  the values of the engine's `technology.py`.
- `io.archsim.model.pricing`: the `archsim.prices/1` file format, `PriceSnapshot` and
  `PriceSnapshots` (which snapshots there are, the newest or pinned one for a document's pricing,
  their content hashes), with the Python messages. Choosing an instance type for a part and costing
  a design stay in the engine (stage 3).
- The engine (stage 3 and 4) adds to a type what it does when simulated, keyed by the same type
  name.
- While the Python engine exists, its shipped snapshot files are the one copy: the build copies
  them into the model's resources.

### Consequences

- Good, because one module answers "is this design valid?", with no simulator on the class path.
- Good, because each component type's specification is in one package, next to its config.
- Bad, because a component type is now split across two modules: what it is (model) and how it
  behaves (engine). The type name joins them, and a test in the engine will check that every
  specification has a behaviour.
- Neutral: the component catalog the editor renders (field descriptions, presets, technology
  traits) also describes component types; it is added to these specifications when stage 2 serves
  it.

### Confirmation

The golden files' `validate_document`, `normalize_document` and pricing entries pass against the
model alone; ArchUnit checks that the model depends on no framework.

## Pros and Cons of the Options

### Validation in the engine

- Good, because ADR-0012's split would hold as written.
- Bad, because every client that checks a design would load the simulator, the reason ADR-0012
  separated the modules.

### Interfaces in the model, implementations in the engine

- Good, because the model would stay smaller.
- Bad, because the model could not validate on its own, and its interfaces would mirror the
  component types one to one: the same knowledge, split for no consumer.

## More Information

- Refines [ADR-0012](0012-separate-model-and-engine-modules.md), which stays accepted.
- [`docs/pricing.md`](../pricing.md): price snapshots.
