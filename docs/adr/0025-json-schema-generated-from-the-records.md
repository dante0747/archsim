---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0025. Generate the design document's JSON Schema from the records, as pydantic writes it

## Context and Problem Statement

The design document's JSON Schema is part of the contract ([ADR-0002](0002-design-document-is-the-contract.md)):
the API serves it at `GET /api/v1/schema/architecture`, the MCP server at
`archsim://schema/architecture`, and agents and editors read it to write designs. Today pydantic
writes it from the Python models (`ArchitectureDocument.model_json_schema(by_alias=True)`), so it
always says what the reader accepts. Stage 1 must produce it from Java and compare it with today's
([the port proposal](../proposals/java-25-port.md#stage-1--design-model-and-schema-35-days)).

Where should the Java schema come from, so that it stays the same as today's and stays true to
what the records accept?

## Decision Drivers

- The same schema as today, so that clients, agents and the golden file see no change.
- The schema cannot drift from the reader: a bound, a default or an enum value lives in one place.
- No framework in the model ([ADR-0012](0012-separate-model-and-engine-modules.md)).

## Considered Options

- Generate it from the records and their codec annotations, writing what pydantic writes
- A JSON Schema generator library (victools `jsonschema-generator`) with modules for our annotations
- Check in today's schema as a file and test that the records agree with it

## Decision Outcome

Chosen option: "generate it from the records", because the codec already knows every field's type,
bounds, default and description ([ADR-0022](0022-records-and-a-pydantic-compatible-codec.md)), so
the schema is one more thing it writes from the same annotations.

- `RecordCodec.jsonSchema(type)` writes a record's definition with every record it uses under
  `$defs`; `DesignDocuments.jsonSchema()` is the design document's.
- It follows pydantic's conventions, because today's schema has them: a property's title is
  Python's `str.title()` of its JSON name (`Latencyp95Ms`) and is left out beside a `$ref`;
  `X | None` is `anyOf` with `null`; a tagged union has `oneOf` and a `discriminator` with its
  `mapping`; a tuple has `prefixItems`; a default is shown unless it is a list or a map (pydantic's
  `default_factory`); `$defs` are sorted by name; a record's `@ModelName` names its definition when
  the Python model's name differs (`Override`, `SkuModel`).
- Node configs stay open objects in the document's schema, as today: each component type's config
  schema belongs to the component catalog, which stage 2 ports.

### Consequences

- Good, because the schema matched today's golden file exactly, and stays true to the reader: a
  new bound is in the schema the moment it is checked.
- Good, because nothing new is needed: no library, no checked-in copy to keep up to date.
- Bad, because the generator copies pydantic's choices (the titles, the hidden factory defaults);
  changing them is a contract change, after the port.
- Neutral: a Java-only field would appear in the schema at once, which is what the contract needs.

### Confirmation

`GoldenParityTests` compares `DesignDocuments.jsonSchema()` with the golden file
`parity/golden/catalog/schema.json.gz`, which the Python CI job keeps equal to
`document_json_schema()`; `RecordCodecTests` specifies the conventions on small records.

## Pros and Cons of the Options

### A JSON Schema generator library

- Good, because generating a schema from Java types is its whole job.
- Bad, because pydantic's conventions (titles, nullable unions, discriminator mappings, hidden
  factory defaults) would each need a custom module, and its annotations would sit beside the
  codec's: two descriptions of one field.
- Bad, because it adds a dependency to the model for about 150 lines of code.

### Today's schema as a checked-in file

- Good, because it is the simplest: the file is today's schema.
- Bad, because a change to a record would need a matching change to the file by hand; a test can
  catch the difference but not make it.
- Bad, because the schema would no longer follow from the code that reads the document.

## More Information

- [Design document reference](../schema.md)
- [ADR-0022](0022-records-and-a-pydantic-compatible-codec.md): the records and their annotations.
