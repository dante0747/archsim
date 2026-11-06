---
status: accepted
date: 2026-11-06
decision-makers: Majid Abarghooei (owner)
---

# 0042. Exports read settings leniently and write their own YAML

## Context and Problem Statement

Stage 3 of the port moves the exports (docker-compose, Kubernetes manifests, a Mermaid diagram)
to `archsim-engine`. The frozen engine's exporters read each part's settings as written over its
type's defaults (`{**default_config(), **config}`), print some settings exactly as the design
holds them, whatever their kind (a platform of `7` is annotated `7`), and write YAML with a small
emitter of their own. Exports run on designs that may not be valid yet: the editor exports a
draft. [ADR-0038](0038-engine-computes-on-typed-records.md) reads configs as records except in a
listed few places, and says a new reader of raw settings must be caught in review. How should the
Java exports read settings, write YAML, and share with the importers (stage 3's next slice) the
format that lets an import give a design back?

## Decision Drivers

- Every export reads as the frozen engine wrote it, to the character: the golden files and the
  extra cases check the text.
- A draft with a setting of the wrong kind exports as it did, without an error.
- The lenient rules stay in one class (ADR-0038).
- No dependency the engine does not need.

## Considered Options

- Lenient settings over the defaults, a YAML writer of its own over Jackson trees, and the
  round-trip format in a package of its own
- Read each part's config as its record
- Write the YAML with a library (SnakeYAML, or Jackson's YAML module)
- Build the output as records or maps the codec writes
- Keep the round-trip format inside the export package

## Decision Outcome

Chosen option: "lenient settings over the defaults, a YAML writer of its own over Jackson trees,
and the round-trip format in a package of its own", because it is the one that writes the frozen
engine's text for every design, valid or not, with the lenient rules still in one class.

- The exports read a part's settings through `LenientSettings.overDefaults`: as written, over its
  type's defaults, a default of `null` left out. This adds the exports to ADR-0038's lenient
  readers (the load estimate, which kind of machine a part runs on, the CPU work on the design's
  host, the review). `LenientSettings.value` gives a setting of any kind, as a copy, for what
  an export writes out as the design holds it (a platform annotation, a technology label).
- `YamlText` is the Python emitter, ported: block style; a scalar plain when it matches the same
  two patterns, else quoted as `json.dumps` quotes it; a whole number below 1e21 without a point,
  any other number as Python's repr. It keeps the Python's one quirk: its `$` lets a text ending
  in a newline stay plain.
- Each export builds its output as Jackson trees, in the key order of the Python dicts, and
  `YamlText` walks them.
- `io.archsim.engine.roundtrip.RoundTrip` holds what an export carries for an import (each
  part's outgoing links as `target[/port][:types][!flag...]`, the parts nothing deploys, the
  placeholder resources an import skips). The importers read the same format from there.

### Consequences

- Good, because the exports match the frozen engine on every golden entry and every extra case,
  including drafts with settings of the wrong kind.
- Good, because the engine gains no YAML dependency for writing.
- Bad, because `LenientSettings` has another reader, and `value` hands out a setting of any kind.
  Review has to keep `value` to settings an export writes out as they are.
- Bad, because `YamlText` is no general YAML writer: a label ending in a newline is written plain,
  so a YAML reader loses the newline. Fixing it would change the text the frozen engine wrote; it
  can change after the port, as a change of behaviour of its own.
- Bad, because a key of a Jackson tree is a string: a misspelt key compiles, and only the parity
  tests catch it.

### Confirmation

`GoldenParityTests` checks every template's exports (`_auto_sized`, each writer, and
`export_document` in each format); `ExportCasesTests` checks the cases
`scripts/export_export_cases.py` records from the frozen engine for what the templates do not
reach; `YamlTextTests` pins the writer's rules.

## Pros and Cons of the Options

### Read each part's config as its record

- Good, because a setting the exports read would be a record's method, checked by the compiler.
- Bad, because a draft with a setting of the wrong kind (a technology of `5`, CPU of `true`) does
  not parse, and the Python exported it.

### Write the YAML with a library

- Good, because a library writes YAML any reader parses back, with no quirk.
- Bad, because it quotes, indents lists and writes numbers its own way: the text differs from
  every golden export, and matching it would mean configuring a library to imitate a small
  writer.

### Build the output as records or maps the codec writes

- Good, because the shapes would be typed.
- Bad, because manifests have many shapes, keys left out by condition in a fixed order, and
  annotations keyed by setting: dozens of records for scaffolding text, with no rule they check
  that the parity tests do not.

### Keep the round-trip format inside the export package

- Good, because it is one package fewer.
- Bad, because the importers would depend on the export package for a format both sides share.

## More Information

- [ADR-0038](0038-engine-computes-on-typed-records.md): configs as records, and the lenient
  exception this extends.
- [ADR-0027](0027-python-text-conventions-in-one-package.md): Python's text conventions in the
  model's `text` package, where `PythonJson` writes `json.dumps` with the keys in their order.
- [ADR-0020](0020-golden-files-of-the-frozen-engine.md): the golden files the exports are
  checked against.
