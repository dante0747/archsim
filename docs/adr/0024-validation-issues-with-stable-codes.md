---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0024. Report what is wrong with a design as issues with stable codes and the Python engine's words

## Context and Problem Statement

The editor shows validation results next to the part they concern, the API returns them from
`POST /validate` and in a refused document's 422, and the CLI prints them. Today each is an
`Issue`: a severity, a stable code (`edge.unknown_node`), a message, the node, link or path it
concerns, and a hint. A document that cannot be read at all is a `DocumentInvalidError` carrying
its issues, its message the first error's. Stage 1 must give the same results, word for word
([ADR-0005](0005-port-to-java-25-in-stages.md)).

How should the Java model report validation results?

## Considered Options

- An `Issue` record and a `DocumentInvalidException` carrying issues, as the Python engine has
- Exceptions per kind of problem
- Bean Validation's `ConstraintViolation`

## Decision Outcome

Chosen option: "an `Issue` record and a `DocumentInvalidException`", because it is the contract the
web app and API clients already read, and a list of values is what a validator naturally returns.

- `Issue(severity, code, message, nodeId, edgeId, path, hint)`, built fluently
  (`Issue.error(code, message).atEdge(id).withHint(hint)`), written as the API writes it.
- `DocumentValidator.validate(document)` returns a `ValidationResult`: the issues in the order the
  checks run (the Python order), each node's parsed config, and the request types each link
  carries. Errors stop a simulation; warnings do not.
- Reading a document that does not fit the schema throws `DocumentInvalidException`; each codec
  error ([ADR-0022](0022-records-and-a-pydantic-compatible-codec.md)) becomes a
  `document.schema` issue naming the node or link it is in; a node config's errors become
  `node.config_invalid` issues; an upgrade's refusal becomes one `document.version` issue.
- Messages keep the Python wording, numbers written as Python writes them
  ([ADR-0027](0027-python-text-conventions-in-one-package.md)). Codes are stable: they are
  what a client may branch on.

### Consequences

- Good, because the API can return the Java results unchanged, and the editor needs no change.
- Good, because a validator that returns values is easy to test: the golden files check every
  template and invalid fixture issue by issue.
- Bad, because the messages, written for Python's f-strings, are long format strings in Java. They
  stay next to their check, one per issue, so each can be compared with its Python line.

### Confirmation

`GoldenParityTest` checks `validate_document` on every template, every scenario's resolved
document and every invalid fixture; `DocumentValidatorTest` shows the shape of the results.

## More Information

- [ADR-0002](0002-design-document-is-the-contract.md): the design document is the contract.
- [ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md): what validation needs
  to know about component types and prices.
