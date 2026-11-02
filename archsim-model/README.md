# archsim-model

The design document in Java: its records, the upgrades from every older schema, reading and
writing its JSON, its JSON Schema, and validation with the Python engine's messages, word for
word. It is a plain library with no framework, so the app, the CLI and the MCP server can read and
check a design without the simulator ([ADR-0012](../docs/adr/0012-separate-model-and-engine-modules.md)).
It is stage 1 of the [Java port](../docs/proposals/java-25-port.md#stage-1--design-model-and-schema-35-days).

```java
ArchitectureDocument design = DesignDocuments.load(json);          // any schema from 1.0 on
ValidationResult result = DocumentValidator.standard().validate(design);
ObjectNode written = DesignDocuments.toJson(design);               // schema 1.7, defaults filled in
ObjectNode schema = DesignDocuments.jsonSchema();                  // what GET /schema/architecture serves
```

`load` throws `DocumentInvalidException` with a `document.version` issue when the document cannot
be upgraded, or one `document.schema` issue per thing wrong with it; `validate` returns every
other issue, errors and warnings, as the Python engine's `validate_document` does.

## Packages

Each package uses only those below it, and ArchUnit checks it
([ADR-0029](../docs/adr/0029-model-packages-in-layers-checked-by-archunit.md)).

| Package | What | ADR |
| --- | --- | --- |
| `io.archsim.model` | `DesignDocuments`: load, parse, write, normalize, the JSON Schema | |
| `validation` | `DocumentValidator`, its `CallGraph`, `ValidationResult` | [0024](../docs/adr/0024-validation-issues-with-stable-codes.md) |
| `scenario` | finding a scenario and applying its overrides | |
| `component` | `ComponentSpec` (ports, config, routing, rules), `ComponentRegistry`, technology profiles; one package per type, and `standard` assembles ArchSim's eleven | [0026](../docs/adr/0026-component-specs-and-price-snapshots-in-the-model.md) |
| `pricing` | price snapshots: the `archsim.prices/1` format, which there are, which a document uses | [0026](../docs/adr/0026-component-specs-and-price-snapshots-in-the-model.md) |
| `upgrade` | the chain of upgrades from schema 1.0 to 1.7, on the JSON | [0023](../docs/adr/0023-upgrade-chain-on-the-json.md) |
| `issue` | `Issue`, `Severity`, `DocumentInvalidException`; codec errors as issues | [0024](../docs/adr/0024-validation-issues-with-stable-codes.md) |
| `document`, `distribution` | the records of schema 1.7 | [0022](../docs/adr/0022-records-and-a-pydantic-compatible-codec.md) |
| `codec` | `RecordCodec`: records to JSON and back as pydantic does, and their JSON Schema | [0022](../docs/adr/0022-records-and-a-pydantic-compatible-codec.md), [0025](../docs/adr/0025-json-schema-generated-from-the-records.md) |
| `text` | Python's way of writing numbers and values: `repr`, `format`, `json.dumps` | [0027](../docs/adr/0027-python-text-conventions-in-one-package.md) |

The price snapshots ArchSim ships are the Python engine's files, copied into this module's
resources by the build while the engine exists.

## Tests

```bash
./gradlew :archsim-model:test
```

- `GoldenParityTest` runs every recorded call of each function this module ports, in every
  [golden file](../parity/README.md), and expects the same output or the same error:
  `load_document`, `migrate_document`, `validate_document`, `normalize_document`,
  `apply_overrides`, `simulation_input`, `document_json_schema`, the distributions' and the price
  snapshots' functions. `ModelFunctions` maps each Python name to its Java call.
- The other tests specify the parts: the pydantic rules the codec copies, the upgrades, each
  check of validation, Python's number formats, the component types, the price snapshots; jqwik
  properties feed random and mutated documents and numbers.
- `ArchitectureTest` checks that the model uses no framework, database or engine class, and the
  layers above.

The golden-file reader (`io.archsim.parity`) is in this module's test fixtures, for the later
stages' modules to use ([ADR-0028](../docs/adr/0028-golden-file-reader-as-test-fixtures.md)).

## Not here yet

- **The component catalog** the editor renders (field descriptions, `x-ui` hints, presets,
  technology traits): stage 2, when the API serves it.
- **Resolving a scenario in full:** finding it and applying its overrides is here; pinning prices
  and sizing parts left on "auto" need the engine's sizing (stage 3).
