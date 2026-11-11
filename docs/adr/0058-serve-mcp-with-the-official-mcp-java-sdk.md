---
status: accepted
date: 2026-11-11
decision-makers: Majid Abarghooei (owner)
---

# 0058. Serve ArchSim's MCP tools with the official MCP Java SDK

## Context and Problem Statement

AI coding agents use ArchSim through an MCP server over stdio: twelve tools (from
`list_part_types` to `import_design`), three resources and the server's instructions. The server
is Python (`packages/mcp`, on the `mcp` package's `MCPServer`), and stage 5 of the
[Java port](../proposals/java-25-port.md) deletes it with the Python engine. The Java server must
give agents the same tools: the same names, descriptions, input and output schemas and
annotations, the same resources, and answers with the same values. What does it serve them with?

## Decision Drivers

- The protocol (initialization, version negotiation, JSON-RPC, tool and resource listings) is the
  protocol's to define; ArchSim's part is the tools.
- An agent starts the server for every session: it should start quickly and print nothing but the
  protocol on standard output.
- The Java engine is a library ([ADR-0012](0012-separate-model-and-engine-modules.md)); the server
  needs neither Spring nor the app.
- Whatever differs from the Python server must be known and tested
  ([ADR-0055](0055-the-java-clis-output-contract.md), as for the CLI).

## Considered Options

- The official MCP Java SDK (`io.modelcontextprotocol.sdk`: `mcp-core` and `mcp-json-jackson3`),
  its synchronous server on its stdio transport
- Spring AI's MCP server starter, which runs the same SDK inside a Spring Boot application
- JSON-RPC over stdio written for ArchSim

## Decision Outcome

Chosen option: the official MCP Java SDK, because it implements the protocol, maintained by the
protocol's own project, without bringing an application framework to a command an agent starts.

- A module of its own, `archsim-mcp`, on the engine alone; `./gradlew :archsim-mcp:installDist`
  builds `archsim-mcp`, the command an agent runs. `docs/agents.md` says how to add it.
- The SDK at 2.1.0 (its latest release), pinned in the version catalog, as Spring Boot does not
  manage it: `mcp-core` with the Jackson 3 mapper and JSON Schema validator of
  `mcp-json-jackson3`. It brings Reactor and networknt's validator; its Jackson resolves to the
  build's.
- SLF4J is bound to `slf4j-nop`: the SDK logs, and on stdio standard output is the protocol's.
- The tools' definitions are the Python server's, word for word, kept as data in the module
  (`server.json`): names, descriptions (Python's docstrings, indentation included), input and
  output schemas as pydantic generated them, and annotations. The resources, the template and the
  instructions are Python's too.
- A result is what Python's SDK made of one: the value as `structuredContent`, and as text, JSON
  indented by two with its characters as they are and its floats as `pydantic_core.to_json` wrote
  them (`0.00001`, `5e-6`, where `json.dumps` wrote `1e-05` and `5e-06`); a refusal is a result
  marked `isError`, `Error executing tool NAME: ...`, in the Python server's words. The limits
  and the number of runs kept are the Python server's, from the same environment variables.
- The server reads its input as the Python SDK did: a line that is not a JSON-RPC message is
  skipped, where the SDK's transport stops reading at one, and a message may be of any length,
  where the transport reads 16 MiB by default. The server ends when its input ends, or cannot be
  read.
- `calibrate` reads its files through the engine's `PythonFiles`, as the CLI does
  ([ADR-0059](0059-read-files-as-python-did-in-the-engine.md)): a file it cannot use is refused in
  the words of Python's exceptions, named as `pathlib` names it.

### Consequences

- Good, because agents keep their tools: the same calls give the same values, from the Java
  engine.
- Good, because the protocol's details (versions, handshake, listings) stay the SDK's to follow.
- Bad, because some answers are the SDK's, not the Python SDK's, and differ (below); an agent
  sees the same tools, but a client that compared the handshake byte for byte would not.
- Bad, because the SDK is young and its API moves (2.1.0 already deprecates the `Tool` builder of
  2.0): an upgrade is deliberate, with the recorded session as its check.

The Java server's answers differ from the Python server's here: the SDK's design for the first
four, the Java engine's for the rest.

- `serverInfo.version` is the engine's version; the SDK requires one, where the Python SDK sent
  an empty one.
- The capabilities include `logging`, which the SDK always declares.
- A call of a tool that does not exist is a JSON-RPC error (`-32602`,
  `Unknown tool: invalid_tool_name`, data `Tool not found: NAME`), where the Python SDK answered
  with a result marked `isError` (`Unknown tool: NAME`).
- Arguments that do not match a tool's input schema are refused by the SDK's validator, in its
  words (`Tool (get_template) input validation failed: Validation failed: JSON schema validation
  errors: [: required property 'id' not found]`), where pydantic refused them in its own; pydantic
  also took a number given as text (`"steps": "3"`), which the validator refuses, and the Python
  SDK read a string argument holding JSON as that JSON (`"[1]"` as a list), which the Java SDK
  passes as the string.
- Where the Python server failed on what it did not expect, the Java server answers as the API
  does: `export` of a design with a link to a part that does not exist gives the export, where
  Python answered `'nowhere'` (its `KeyError`).
- A document that is not JSON past its first character is refused in Jackson's words at the place
  `json` gives, as the engine reads JSON
  ([ADR-0044](0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)).
- The `schema` resource has the Java model's schema, the same values with its keys in the Java
  model's order, laid out as `json.dumps(value, indent=1)` laid it out.

### Confirmation

`scripts/export_mcp_cases.py` records one stdio session of the Python server: the handshake, the
listings, every resource and calls of every tool, refusals included, with the files the
calibrations read and the templates and page the server served. `McpSessionTests` serves the same
files and sends the same requests to the Java server, in-process over the SDK's stdio transport,
and compares each answer as a JSON value, as the golden files compare them, laid out as Python
laid it out (the writer gives each of Python's texts exactly from its value, then Java's from
Java's), but for the departures above, which it checks as they are.

## Pros and Cons of the Options

### The official MCP Java SDK

- Good, because the protocol is implemented and kept up to date by its own project.
- Good, because it runs in a plain JVM: a command, not an application.
- Bad, because its API is still moving between minor releases.

### Spring AI's MCP server starter

- Good, because tools could be declared as annotated methods.
- Bad, because an agent's every session would start a Spring application for a stdio command.
- Bad, because it is the same SDK underneath, with Spring AI's release train on top.

### JSON-RPC over stdio written for ArchSim

- Good, because no dependency, and every answer could match the Python server's.
- Bad, because ArchSim would own the protocol's handshake, versions and listings, and follow
  every revision of the specification itself.

## More Information

- [ADR-0055](0055-the-java-clis-output-contract.md): the CLI's contract, which this follows for
  the MCP server's values and departures.
- The [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).
