---
status: accepted
date: 2026-11-06
decision-makers: Majid Abarghooei (owner)
---

# 0044. Read Terraform JSON with Jackson, as Python's json reads it

## Context and Problem Statement

The Terraform importer comes to `archsim-engine` with stage 3. It reads `terraform show -json`
output, a plan or a state, which the frozen engine reads with Python's `json.loads`. That reader
takes any value at the top, reads `NaN`, `Infinity` and `-Infinity` as floats, keeps a key written
twice in its first place with its last value, refuses anything after the value, and words its
refusals as `Expecting value: line 1 column 1 (char 0)`. The importer then refuses text that is
not JSON as ``not JSON: run `terraform show -json plan.out` (or state) first (…)``, and the golden
files record that for text that is not JSON at all. The commonest mistake is pasting HCL, or a
plan file, where the JSON belongs. How should the Java importer read the JSON, and how should it
word what it refuses?

## Decision Drivers

- The same text gives the same values, so the same design.
- A user who pastes HCL gets the same answer as before.
- No new dependency for a format the engine already reads.
- Text from anyone stays safe to read: bounded nesting, no unbounded number parsing.

## Considered Options

- Jackson, set to read as `json.loads` reads, with `json`'s words where no value starts
- Jackson as it is
- A port of Python's `json` decoder

## Decision Outcome

Chosen option: "Jackson, set to read as `json.loads` reads, with `json`'s words where no value
starts". The engine already reads JSON with Jackson. Set as `json.loads` reads, it gives the same
values:

- non-numeric numbers allowed;
- trailing tokens refused;
- a repeated key kept in its first place with its last value, which is Jackson's own way.

Before Jackson reads, the importer looks where `json`'s scanner would look for the first value.
When none starts there, it refuses in `json`'s exact words and form: `Expecting value: line L
column C (char N)`, and `Unexpected UTF-8 BOM …` for a byte order mark. That is HCL, an empty
text, a plan file's binary, and the golden `terraform-not-json` entry. Past the start, a refusal
gives Jackson's description in `json`'s form (`…: line L column C (char N)`).

### Consequences

- Good, because pasted HCL, an empty text and a byte order mark are refused in the frozen
  engine's words, golden entry included.
- Good, because the importer reads JSON with the library the rest of the engine uses.
- Bad, because a refusal past the first value describes the problem as Jackson does (`Unexpected
  character …`), not as `json` did (`Expecting ',' delimiter`). The engine README lists it.
- Bad, because the limits differ. Python's `json` nests as deep as CPython 3.12's C recursion
  limit allows (3,000 on Windows, 10,000 on Linux), and reads whole numbers of up to 4,300 digits
  and floats of any length. The Java refuses past 1,000 levels, as Python refuses a deeper text:
  "the text nests too deeply". It refuses as not JSON a number whose whole part, fraction or
  exponent has more than 4,300 digits. Plans come nowhere near either.
- Bad, because Jackson also reads `-INF`, `+INF` and `+Infinity`, which `json` refuses; it
  refuses `INF`, as `json` does.

### Confirmation

`ImportCasesTests` replays the importer's cases recorded from the frozen engine. They cover the
refusals where no value starts, `NaN` and `Infinity`, repeated keys, long whole numbers and a
text nested 20,000 levels deep. `TerraformJsonTests` pins the rest: Jackson's refusals in `json`'s
form, the nesting and digit limits, long keys, and the infinities Jackson reads.

## Pros and Cons of the Options

### Jackson as it is

- Good, because there is nothing to write.
- Bad, because it refuses `NaN` and `Infinity` and accepts trailing text, so some plans would
  read otherwise. It would also answer pasted HCL in its own words.

### A port of Python's `json` decoder

- Good, because every refusal would be worded as before.
- Bad, because it is a JSON parser of our own, a few hundred lines to keep. What it would add
  over the chosen option is the wording of refusals that the commonest mistakes never reach.

## More Information

- [ADR-0043](0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md): the YAML importers,
  read with a library set to read as PyYAML does.
- `packages/engine/src/archsim_engine/importers/terraform.py`: the importer this ports.
