---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0022. Map the design document with records and a pydantic-compatible codec over Jackson trees

## Context and Problem Statement

The design document is the contract between the web app, the API and the engine
([ADR-0002](0002-design-document-is-the-contract.md)). Today pydantic reads it: it fills in
defaults, accepts some lax input (`"1.5"` for a number, `5.0` for an integer, a snake_case name for
a camelCase one), and reports **every** error at once with a type, a location and a message, which
the API returns and the editor shows. Stage 1 must read and write the same JSON and report the same
errors, word for word ([ADR-0005](0005-port-to-java-25-in-stages.md)), and Spring Boot 4 brings
Jackson 3 ([ADR-0007](0007-spring-boot-4.md)).

How should the Java model map the document to and from JSON?

## Decision Drivers

- The same errors as pydantic, all at once, in its order, with its locations and messages.
- The same JSON out: defaults written, a few fields left out while empty.
- One place that says what a field is (its type, bounds, default and description), not one per
  concern: reading, writing and the JSON Schema ([ADR-0025](0025-json-schema-generated-from-the-records.md)).
- Plain Java a reviewer recognises: records, sealed interfaces, annotations; no framework in the
  model ([ADR-0012](0012-separate-model-and-engine-modules.md)).

## Considered Options

- Jackson databind on records, with Bean Validation (Jakarta) for the constraints
- Hand-written readers and writers per record
- Records that declare their JSON with annotations, read and written by one pydantic-compatible
  codec over Jackson's tree model

## Decision Outcome

Chosen option: "records, annotations and one codec", because it is the only option that gives
pydantic's error list and keeps each field's definition in one place.

- **Jackson parses and prints;** the codec (`io.archsim.model.codec.RecordCodec`) maps Jackson
  trees to records and back. Records are the design document's types: `ArchitectureDocument`,
  `Workload`, sealed `TrafficPattern` and `Distribution`, and so on, named as the Python models are.
- **A record declares its JSON** on its components: `@Default` (or `@DefaultFrom`, naming a
  constant for an object default), `@Ge`/`@Gt`/`@Le`/`@Lt`, `@MinLength`/`@MaxLength`,
  `@Matches`, `@MinItems`/`@MaxItems`, `@Const`, `@Choice` (repeated, a `Literal` of integers),
  `@UniqueItems`, `@OmitWhenDefault`, `@Description`, and `@Ui` for the editor's `x-ui` hints
  (typed annotations, not JSON text: [ADR-0030](0030-spring-style-and-clean-code-checks.md));
  JSpecify's `@Nullable` is `X | None`. A sealed interface marked `@Tagged("kind")` is a discriminated union, its records
  marked `@Tag`. A `JsonEnum` is a `Literal` of strings.
- **Reading follows pydantic 2 exactly:** fields in declaration order, each by its JSON name then its
  Python name, unknown keys after them; lax coercions; bounds checked in pydantic's order; a union's
  errors under its tag; pydantic's message templates. Only when everything fits is a record made;
  its compact constructor then checks what spans fields (a pydantic model validator), and an
  `IllegalArgumentException` there is reported as pydantic's `Value error, ...`.
- **Writing** gives `model_dump(mode="json", by_alias=True)`: every field in declaration order,
  `@OmitWhenDefault` fields left out while they have their default.

### Consequences

- Good, because every invalid fixture's errors, and every example's written JSON, match the Python
  engine's golden files.
- Good, because a field is defined once; the JSON Schema comes from the same annotations.
- Bad, because the codec is a small framework of its own (about 900 lines with its tests) that
  imitates pydantic: a reader must learn its annotations. Its tests specify each pydantic rule it
  copies.
- Bad, because pydantic's quirks are copied too (a boolean is a number, `"5.0"` is an integer),
  since the contract has them. Changing them is a contract change, after the port.
- Neutral: Python's integers have no size limit; an unbounded integer beyond Java's `long` is
  reported as too large. No document field is unbounded and that large.

### Confirmation

`GoldenParityTests` checks `load_document` and `migrate_document` against every golden entry;
`RecordCodecTests` specifies the pydantic rules; a jqwik property feeds random and mutated
documents and accepts only a document or a `DocumentInvalidException`.

## Pros and Cons of the Options

### Jackson databind with Bean Validation

- Good, because it is the most familiar Java combination.
- Bad, because databind stops at the first type error and Bean Validation sees only bound objects:
  neither can report a wrong type, a missing field and an unknown key together, as pydantic does.
- Bad, because the messages, locations and lax coercions would all be custom anyway.

### Hand-written readers per record

- Good, because nothing is reflective.
- Bad, because thirty records would each repeat the same error handling, and the JSON Schema would
  need a third copy of every bound.

## More Information

- [The port proposal, stage 1](../proposals/java-25-port.md#stage-1--design-model-and-schema-35-days)
- [ADR-0024](0024-validation-issues-with-stable-codes.md) turns the codec's errors into issues.
