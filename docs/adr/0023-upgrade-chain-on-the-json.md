---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0023. Upgrade older documents with a chain of steps on the JSON, and model only the current schema

## Context and Problem Statement

Documents of schema 1.0 to 1.6 are still around: exports, stored projects, examples in other
repositories. The Python engine upgrades them on load with one registered function per version
(`migrations.py`): each takes the raw JSON of version *n* to *n+1*, and the chain runs until the
document is current. A document newer than the engine, or with no path to it, is refused with a
message. So far every step only adds optional fields, so each is the identity.

Stage 1 must upgrade every older schema to 1.7 as Python does. How should the Java model do it?

## Considered Options

- A chain of steps on the JSON tree, then records of the current schema only
- Records for every schema version, each converted to the next
- No chain: read older documents with the current records, since every step so far is additive

## Decision Outcome

Chosen option: "a chain of steps on the JSON tree", because it is how the contract defines
upgrades, it keeps one set of records, and a future step that renames or restructures a field
slots in without touching them.

- `io.archsim.model.upgrade.Upgrades.CHAIN` is an ordered list of `Upgrade(from, to, change,
  apply)` records, oldest first; `apply` is a function on the document's `ObjectNode`.
- `Upgrades.toCurrent(json)` copies the input, runs the steps from its version, stamps each new
  version and returns the document with a note per step (`migrated schema 1.5 -> 1.6`).
- Versions are compared as Python compares tuples of integers (`1.10` is newer than `1.7`, and so is
  `1.7.0`); a version written as a number is read as Python prints it. The refusals are Python's
  messages, word for word.
- `DesignDocuments.load` upgrades, then binds the current records
  ([ADR-0022](0022-records-and-a-pydantic-compatible-codec.md)); an upgrade failure is one
  `document.version` issue.

### Consequences

- Good, because the records describe one schema, so the code that uses them never asks which
  version it holds.
- Good, because a schema change is a new record field plus, when needed, one new step at the end of
  the chain, the same policy as in Python (`docs/schema.md`).
- Bad, because a step works on untyped JSON, so it is tested on JSON fixtures rather than checked by
  the compiler.

### Confirmation

`GoldenParityTests` compares `Upgrades.toCurrent` with every recorded `migrate_document`, including
the version errors of `fixtures/migrations`; `UpgradesTests` specifies the chain's shape and
refusals.

## Pros and Cons of the Options

### Records for every version

- Good, because each version is typed.
- Bad, because seven copies of thirty records would differ only by a field here and there.

### No chain

- Good, because it is the least code today.
- Bad, because the first step that is not additive would need the chain anyway, and the version
  errors and notes would still have to be written.

## More Information

- [`docs/schema.md`](../schema.md#versioning-and-migrations): the versions and the change policy.
