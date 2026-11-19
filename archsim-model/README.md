# archsim-model

The design document in Java: its records, the upgrades from every older schema, reading and
writing its JSON, its JSON Schema, and validation with the Python engine's messages, word for
word. It is a plain library with no framework, so the app, the CLI and the MCP server can read and
check a design without the simulator ([ADR-0012](../docs/adr/0012-separate-model-and-engine-modules.md)).
It is stage 1 of the [Java port](../docs/proposals/java-25-port.md#stage-1--design-model-and-schema-35-days).

```java
ArchitectureDocument design = DesignDocuments.load(json);          // any schema from 1.0 on
ValidationResult result = DocumentValidator.standard().validate(design);
ObjectNode written = DesignDocuments.toJson(design);               // schema 1.11, defaults filled in
ObjectNode schema = DesignDocuments.jsonSchema();                  // what GET /schema/architecture serves
```

`load` throws `DocumentInvalidException` with a `document.version` issue when the document cannot
be upgraded, or one `document.schema` issue per thing wrong with it; `validate` returns every
other issue, errors and warnings, as the Python engine's `validate_document` did.

## Packages

Each package uses only those below it, and ArchUnit checks it
([ADR-0029](../docs/adr/0029-model-packages-in-layers-checked-by-archunit.md)).

| Package | What | ADR |
| --- | --- | --- |
| `io.archsim.model` | `DesignDocuments`: load, parse, write, normalize, the JSON Schema | |
| `validation` | `DocumentValidator` and the checks it runs in order (ids, component types, links, the `CallGraph`, request-type flow, component rules, the workload, prices), `ValidationResult` | [0024](../docs/adr/0024-validation-issues-with-stable-codes.md) |
| `scenario` | finding a scenario and applying its overrides | |
| `catalog` | `ComponentCatalog`: the component catalog the editor renders (`GET /components`), from the specifications and each type's `catalog/<type>.json`; `ComponentFacts`: the same facts typed, for code that reads them (each type's `Setting`s, defaults, `Preset`s, suggested link settings, its technologies' defaults and their documentation); each technology's `TechnologyTrait`s, which a run's report lists for each part (`ComponentCatalog.technologyTraits`) | [0031](../docs/adr/0031-component-catalog-facts-and-presentation.md) |
| `component` | `ComponentSpec` (ports, config, routing, rules), `ComponentRegistry`, technology profiles; one package per type, and `standard` assembles ArchSim's eleven | [0026](../docs/adr/0026-component-specs-and-price-snapshots-in-the-model.md) |
| `pricing` | price snapshots: the `archsim.prices/1` format, which there are, which a document uses; `WholeCoreFamilies`: the instance families whose vCPUs are whole cores, by provider | [0026](../docs/adr/0026-component-specs-and-price-snapshots-in-the-model.md) |
| `upgrade` | the chain of upgrades from schema 1.0 to 1.10, on the JSON | [0023](../docs/adr/0023-upgrade-chain-on-the-json.md) |
| `issue` | `Issue`, `Severity`, `DocumentInvalidException`; codec errors as issues | [0024](../docs/adr/0024-validation-issues-with-stable-codes.md) |
| `document`, `distribution` | the records of schema 1.10 | [0022](../docs/adr/0022-records-and-a-pydantic-compatible-codec.md) |
| `codec` | `RecordCodec`: records to JSON and back as pydantic does, and their JSON Schema; unions told apart by a property (`@Tagged`) or by which key an object has (`@TaggedByKey`), maps keyed by text or by a `JsonEnum` | [0022](../docs/adr/0022-records-and-a-pydantic-compatible-codec.md), [0025](../docs/adr/0025-json-schema-generated-from-the-records.md) |
| `text` | Python's way of writing numbers and values: `repr` (a string's characters escaped as Python 3.12's `str.isprintable()` judges them, from `nonprintable.tsv`, which Python recorded once ([parity/README.md](../parity/README.md#pythons-own-answers))), `format`, `json.dumps` | [0027](../docs/adr/0027-python-text-conventions-in-one-package.md) |

The price snapshots ArchSim ships are this module's resources,
`src/main/resources/io/archsim/model/pricing/snapshots/`: the build lists every file of the folder
in an index `PriceSnapshots` reads, so the model ships what the folder holds. A refreshed snapshot
(archsim-tools' `prices`) is a file added there, but the golden pricing entries and some sizing
tests still expect 0.16.0's two snapshots to be the only ones, so a refresh fails them until they
stop assuming it. Until stage 5 deleted the frozen Python engine, a test checked its snapshots were
shipped byte for byte.

Beside that folder, `whole-core-families.json` lists by provider the instance families whose vCPUs
are whole cores, where most x86 families sell hyperthreads: for AWS the Graviton families, T2 and
the AMD families from M7a on. Each group of families has its reason and the provider's page that
says so, and a family is listed only when the whole family is whole cores. RDS's `x2g` is listed
by two pages, since EC2 sells no such types: RDS's, which says db.x2g runs on Graviton2, and the
Graviton runbook's, which says a Graviton processor runs one thread per core. `u-6tb1` is left
out because it mixes the two: its 56xlarge has one thread per core, its other types two.
`WholeCoreFamilies` reads the list; nothing in the engine uses it yet.

`WholeCoreFamiliesTests` holds the list against the shipped snapshots. Every listed family is one
a snapshot prices. And every family that sells a 1-vCPU type in the newest snapshot of a provider
and region is listed: a 1-vCPU type is not a pair of hyperthreads, and each family that sells one
today is a whole-core family throughout. A refreshed snapshot with a new such family fails that
test until someone reads the family's page: it is listed if the page says its vCPUs are cores,
and if the page does not (a family may mix the two, as u-6tb1 does), the guard is narrowed
rather than the list padded. The converse does not hold: a whole-core family whose smallest type
has 2 vCPU or more (t4g, for one) is on the list by its source alone, and the tests name those
seven families and `u-6tb1`.

## Tests

```bash
./gradlew :archsim-model:test
```

- `GoldenParityTests` runs every recorded call of each function this module ports, in every
  [golden file](../parity/README.md), and expects the same output or the same error:
  `load_document`, `migrate_document`, `validate_document`, `normalize_document`,
  `apply_overrides`, `simulation_input`, `document_json_schema`, the component catalog
  (`ComponentRegistry.catalog_document` and `types`), the distributions' and the price
  snapshots' functions, the experimental badge's words (`technology.TIER_NOTE`) and the schema's
  version (`version.SCHEMA_VERSION`). `ModelFunctions` maps each Python name to its Java call.
  The catalog's objects are compared regardless of their keys' order, but the assumptions page
  lists technologies and preset settings in it, so they keep the Python engine's order: each
  type's technology profiles in the order they are added, and each preset's settings in
  `catalog/<type>.json` as the Python engine defines them.
- The schema has moved on from the 1.7 the frozen engine wrote: 1.8 adds
  `workload.peakHoursPerMonth` ([ADR-0074](../docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)),
  left out of a document while it is 0, so a document differs from Python's by its version
  alone; 1.9 lets a third-party API's `costPerMillionRequests` be left unset
  ([ADR-0081](../docs/adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)),
  where 1.7's defaulted to 0; 1.11 counts a vCPU as a hyperthread by default and adds
  `architecture.siblingSpeed` (ADR-0091). Every golden comparison (`Parity`, and
  the tests that compare with `JsonDiff` themselves) goes through `SchemaDepartures`. It gives a
  recorded document (a parameter named `doc`, `document` or `resolved`, or a report's design) to
  the Java at 1.11 as it was written, pinned to whole cores as the upgrade to 1.11 pins a saved
  design (`architecture.siblingSpeed: 1`, `SiblingSpeedDepartures`), and a document Python's
  fixtures wrote at 1.10, their example of a newer schema, at 1.12. It reads the Java's output as
  Python would have written it at 1.7: a document's `schemaVersion` and the schema's own version
  as 1.7, no note for the upgrades from 1.7 to 1.11, a message naming the supported schema with
  1.7 (and the newer one with 1.10), the JSON Schema without `peakHoursPerMonth`,
  `messageAgeP95Ms` and the architecture's `siblingSpeed`, the catalog's workload settings
  without `peakHoursPerMonth` and without its list of design-wide settings
  (`architectureFields`), a document's architecture without its `siblingSpeed`, a sized part's
  summary without `vcpus`, and a part's `siblingSpeed` described as Python described it. Guided by Python's output, `ApiPriceDepartures` reads an
  API's price as Python's where Python's records it as 0: the setting where the Java leaves it
  empty, an input's value and provenance, and the catalog field's default, description and
  whether it may be empty. The rest is compared exactly.
- The other tests specify the parts: the pydantic rules the codec copies, the upgrades, each
  check of validation, Python's number formats, the component types, the price snapshots; jqwik
  properties feed random and mutated documents and numbers.
- `ArchitectureTests` checks that the model uses no framework, database or engine class, and the
  layers above.

The golden-file reader (`io.archsim.parity`) is in this module's test fixtures, for the later
stages' modules to use ([ADR-0028](../docs/adr/0028-golden-file-reader-as-test-fixtures.md)).

## Not here yet

- **Resolving a scenario in full:** finding it and applying its overrides is here; pinning prices
  and sizing parts left on "auto" need the engine's sizing (stage 3).
