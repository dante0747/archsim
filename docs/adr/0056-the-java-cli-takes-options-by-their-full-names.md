---
status: accepted
date: 2026-11-10
decision-makers: Majid Abarghooei (owner)
---

# 0056. The Java CLI takes options by their full names

## Context and Problem Statement

[ADR-0055](0055-the-java-clis-output-contract.md) keeps the Python CLI's flags, and argparse took
an option by the start of its name: `archsim run x.json --scen busy` ran the scenario `busy`.
picocli takes abbreviations too when they are turned on, splitting each name at its hyphens, so it
also takes `--m-r` for `--max-requests`. But with them on, picocli's matcher fails on a lone `-`
with an `IndexOutOfBoundsException`, and `-` is the name `archsim import FORMAT -` gives standard
input, as the Python CLI did. Should the Java CLI take abbreviated options?

## Decision Drivers

- `archsim import compose -` must read standard input, as documented and as the Python CLI did.
- A usage mistake must not end in a stack trace.
- Scripts and the `archsim-check` action name every option in full; an abbreviation is typed by
  hand.

## Considered Options

- Options by their full names only
- picocli's abbreviations, with `-` passed to picocli some other way
- Abbreviations of our own: each argument that starts an option's name unambiguously expanded,
  as argparse does, before picocli parses the arguments

## Decision Outcome

Chosen option: "Options by their full names only", because it is picocli's default, `-` works,
and no script or job depends on abbreviations.

- picocli's abbreviations stay off. A shortened option is a usage error: picocli's words, exit
  code 2, as any other usage error.
- The CLI's README lists it as a departure from the Python CLI.

### Consequences

- Good, because standard input works and the parsing is picocli's own, with nothing added.
- Bad, because a hand-typed `--scen` that argparse ran is refused.

### Confirmation

`RunOptionsTests.run_anOptionNamedByTheStartOfItsName_isAUsageError`, and the recorded
`archsim import compose -` case in `ImportCommandTests`.

## Pros and Cons of the Options

### Options by their full names only

- Good, because it needs no code and nothing can go wrong in it.
- Bad, because abbreviations argparse took are refused.

### picocli's abbreviations, with `-` passed some other way

- Good, because abbreviations work.
- Bad, because `-` would have to be renamed before parsing and back after, a workaround for a
  defect in picocli that every command reading standard input would have to know about.
- Bad, because picocli takes abbreviations argparse refused (`--m-r`).

### Abbreviations of our own

- Good, because it would take exactly what argparse took.
- Bad, because it is a second parser in front of picocli, with its own errors for ambiguous
  prefixes, for a convenience nobody's script uses.

## More Information

- [ADR-0055](0055-the-java-clis-output-contract.md): the CLI's output contract, which this
  narrows for option names.
