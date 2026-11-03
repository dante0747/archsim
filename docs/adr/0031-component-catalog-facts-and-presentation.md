---
status: proposed
date: 2026-11-03
decision-makers: Majid Abarghooei (owner)
---

# 0031. Build the component catalog from the specifications, with its presentation as data

## Context and Problem Statement

The editor renders its palette and inspector from the component catalog (`GET /components`):
for each component type its ports, settings (label, kind, bounds, default, unit, group, when it
is shown), default config, palette text and icon, what the model covers and leaves out, its
technology presets, and each technology's defaults, tier, traits and the documentation behind
them. The Python engine builds it from class attributes and pydantic field metadata; it is about
160 KB of JSON, recorded in the golden file `catalog/components.json.gz`.
[ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md) put the component
specifications in `archsim-model` and left the catalog to stage 2.

Where should each part of the catalog live in Java, so it stays true to the code and readable?

## Decision Drivers

- What validation and simulation rely on (types, bounds, defaults, technology defaults) must have
  one source, so the catalog cannot drift from the behaviour.
- The rest is presentation text and data, much of it long prose and links.
- No JSON in Java strings ([ADR-0030](0030-spring-style-and-clean-code-checks.md)).
- The catalog must match the Python engine's, word for word.

## Considered Options

- Everything in Java: presets, traits and texts as Java code beside the specifications
- Facts from the code, presentation as JSON resources per component type
- The whole catalog as one JSON resource

## Decision Outcome

Chosen option: "Facts from the code, presentation as JSON resources", because what the code
already knows is read from it, and what it doesn't is data that reads best as data.

- **From the code:** each setting's kind, bounds, default and whether it may be empty come from
  the config record's components and the codec's annotations; its description and editor hints
  from `@Description` and `@Ui`; the technologies' defaults and tiers from the component's
  `TechnologyProfiles` (`ComponentSpec.technologies()`).
- **As data:** `io/archsim/model/catalog/<type>.json` holds the palette text and icon, the
  modeled and not-modeled lists, the presets, the suggested link settings, and each technology's
  traits and documentation links.
- **Descriptions:** a package's setting descriptions and form-group names are constants in its
  package-private `Descriptions` class, so each setting's annotations stay short; a record's
  own description (a docstring) stays on the record.
- `io.archsim.model.catalog` builds the document (`ComponentCatalog.of(registry)`); it is a layer
  above the component specifications ([ADR-0029](0029-model-packages-in-layers-checked-by-archunit.md)).
- The texts and data were generated once from the golden catalog file; `ComponentCatalogTests`
  and `GoldenParityTests` keep them equal to the Python engine's.

### Consequences

- Good, because a setting's bounds and default appear once, in the record that validation reads.
- Good, because prose and links are edited as data, without touching Java.
- Bad, because a component type's catalog is spread over three places: its config record, its
  package's `Descriptions` and its JSON resource. The catalog test names any part that differs.
- Bad, because a new component type outside this repository must ship its own
  `io/archsim/model/catalog/<type>.json` beside its specification.

### Confirmation

`ComponentCatalogTests` compares each component type's entry, and the link and workload
settings, with the golden file; `GoldenParityTests` checks the catalog whole.

## Pros and Cons of the Options

### Everything in Java

- Good, because a component type's catalog sits in its own package.
- Bad, because about 40 presets and 60 traits of prose and links become builder code, and preset
  configs would be JSON strings or long builders.

### One JSON resource for the whole catalog

- Good, because it is the simplest port: the golden output, served.
- Bad, because bounds, defaults and technology defaults would be written twice, in the records
  and in the JSON, and could drift apart.

## More Information

- [ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md): the specifications in
  the model.
- [`docs/api.md`](../api.md), `GET /components`.
