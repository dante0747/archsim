---
status: proposed
date: 2026-11-06
decision-makers: Majid Abarghooei (owner)
---

# 0043. Read imported YAML with SnakeYAML, resolved as PyYAML resolves it

## Context and Problem Statement

Stage 3 of the port moves the importers to `archsim-engine`. Two of them read YAML that
ArchSim does not control: a docker-compose file and Kubernetes manifests. The frozen engine
reads it with PyYAML's safe loader, which implements YAML 1.1. That includes merge keys
(Compose's `<<: *defaults`), `yes`, `no`, `on` and `off` as booleans, `0777` as an octal number
and `1:30` as a number in base 60. It accepts aliases, but refuses a document whose aliases expand
past 200,000 values or that nests deeper than 100 levels (`safe_yaml.py`). What a value is (a
number, a boolean, text) decides what the importers make of it, and the import must give the
same design in Java. Which library should the Java importers read YAML with, and how should its
values reach them?

## Decision Drivers

- The same text gives the same values as in the frozen engine, so the same design.
- Real files use merge keys and YAML 1.1's scalars.
- YAML from anyone stays safe to read: no arbitrary types, and aliases and depth bounded as
  before.
- A maintained library, and one version of it in the app (Spring Boot already manages one).

## Considered Options

- SnakeYAML 2, with PyYAML's implicit resolvers and the Python's bounds
- snakeyaml-engine
- Jackson's YAML module
- SnakeYAML 1.x
- A YAML reader of its own

## Decision Outcome

Chosen option: "SnakeYAML 2, with PyYAML's implicit resolvers and the Python's bounds". SnakeYAML
began as a port of PyYAML: it reads the same YAML 1.1, with merge keys and safe construction. Its
2.x line is maintained. Version 2.6 is what Spring Boot 4.1 manages, so the app carries one copy.

- The engine depends on `org.yaml:snakeyaml` 2.6 for reading imported text only, through
  `SafeConstructor` (no arbitrary classes).
- Its resolver uses PyYAML's implicit resolvers, copied pattern for pattern. SnakeYAML's own
  differ a little: it reads `1e5` as a number, where PyYAML's floats need a point and a signed
  exponent. So a scalar gets the type PyYAML gives it.
- Where SnakeYAML's construction differs from PyYAML's, the engine's constructor follows PyYAML:
  - a mapping's merged keys come before its own, and a key written twice keeps its first place
    and its last value (PyYAML's `flatten_mapping`);
  - a scalar with an explicit tag (`!!int`, `!!float`, `!!bool`, `!!timestamp`) is read, or
    refused, as PyYAML's constructor reads it;
  - a value written `=` is refused, as PyYAML's safe loader refuses it, and a key written so
    stays text.
- The text is limited to 5 MB. Each document's node graph is measured before it is built, aliases
  followed: past 200,000 values, past 100 levels or with a recursive alias, it is refused with the
  Python's words.
- A document becomes a Jackson tree (Python's `int`, `float`, `bool`, `None` and `str` as JSON's
  kinds), which the importers read as the Python read its dicts, failing where it failed. Small
  helpers (`PythonValues`, `PythonMappings`) stand for what the Python did to a value:
  `value or {}`, `value.get`, `dict()`, `int()`, `float()`, `str()`, `value[0]` and
  `key in value`.
  - A timestamp becomes its text as Python's `str()` writes the `date` or `datetime` PyYAML
    builds, the form the importers give it.
  - A key that is not text (`1:`, `yes:`) becomes its text as `str()` writes it.
- Text that is not YAML, or not the format, is refused with the Python's words (`not valid
  YAML: …`, `this is not a docker-compose file the import can read (…)`).
  - A failure of Python's runtime on a value of the wrong kind gives the detail in Python's
    words (`'NoneType' object has no attribute 'get'`, `missing 'name'`, `invalid literal for
    int() with base 10: 'x'`).
  - Only the description of text that is not valid YAML, from the parser or the constructor,
    is SnakeYAML's, or a summary without PyYAML's line and column.
  - When `import_design` comes to Java, in the next step of this stage, the golden entries that
    recorded PyYAML's wording of such text are listed in one place in the parity tests and
    checked against the Java's.

### Consequences

- Good, because a compose file with merge keys, octal modes or `on`/`off` values imports as it
  did.
- Good, because the YAML's types come from the same patterns as in the frozen engine, not from
  a library's reading of the YAML 1.1 spec.
- Bad, because the engine gains a dependency, and another place where a library's version
  matters. Spring Boot's BOM keeps the app's copy the same as the engine's.
- Bad, because a parse error's description (the problem, the line and column, the excerpt) is
  worded as SnakeYAML words it, not as PyYAML did; the web app shows it.
- Bad, because a value of a type JSON has no kind for (`!!binary`, `!!set`, `!!omap`), and a
  mapping key that is not text, read as text, can make the Java import a file the Python could
  not. So can keys that Python counts as one (`1`, `1.0` and `true`), which stay apart as text.
  Explicit tags and keys like these are rare in compose files and manifests.

### Confirmation

The importers' cases, recorded from the frozen engine, check the designs and reports and the
refusals of values of the wrong kind; with `import_design`, `GoldenParityTests` checks them too.
The YAML reader's own tests pin each PyYAML type, explicit tags, the order of merged and repeated
keys, the bounds and their messages.

## Pros and Cons of the Options

### snakeyaml-engine

- Good, because it is maintained, and YAML 1.2 is the current specification.
- Bad, because YAML 1.2 drops merge keys and reads `yes`, `on` and `0777` differently, so real
  compose files would import as different designs.

### Jackson's YAML module

- Good, because it reads YAML straight into the Jackson trees the engine uses.
- Bad, because in Jackson 3 it reads through snakeyaml-engine (YAML 1.2), with the same
  differences, and gives no say over how a scalar is typed.

### SnakeYAML 1.x

- Good, because it is the line the port plan first named, and reads YAML 1.1.
- Bad, because it is no longer maintained, and its default constructor could build any class
  (CVE-2022-1471). Version 2 has the same reading of YAML 1.1 and safe defaults.

### A YAML reader of its own

- Good, because every behaviour would be in our hands.
- Bad, because a YAML parser is large and subtle; the importers need PyYAML's reading, which a
  port of PyYAML already gives.

## More Information

- [ADR-0042](0042-exports-read-settings-leniently-and-write-their-own-yaml.md): the exports,
  which write YAML with a writer of their own.
- [ADR-0038](0038-engine-computes-on-typed-records.md): the engine computes on records; an
  importer builds a design's JSON, as the scenario resolver does.
- `packages/engine/src/archsim_engine/safe_yaml.py`: the bounds this keeps.
