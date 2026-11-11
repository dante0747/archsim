---
status: accepted
date: 2026-11-11
decision-makers: Majid Abarghooei (owner)
---

# 0059. Read files as Python did in the engine, for the CLI and the MCP server

## Context and Problem Statement

The Java CLI reads and writes the files of `calibrate` and `check` as the Python CLI did
(`PythonFiles`): text as `Path.read_text(encoding="utf-8")` read it, JSON as `json.loads`, a file
named as `pathlib` named it, and whatever cannot be used refused in the words of Python's
`OSError`, codec or `json` ([ADR-0055](0055-the-java-clis-output-contract.md)). The Java MCP server
([ADR-0058](0058-serve-mcp-with-the-official-mcp-java-sdk.md)) must read the same files for its
`calibrate` tool, the mapping, the recorded responses and the traces, as the Python server read
them, with the same refusals. The reader is about 450 lines with its UTF-8 decoder, and 280 of
tests. The MCP server cannot depend on the CLI. Where does the reader live, so both use it?

## Decision Drivers

- What Python said about a file nobody could use (a missing file, a folder, bytes that are not
  UTF-8, a name Windows refuses) is tested at length; it should be written once.
- The CLI and the MCP server both ship the engine.
- The engine is a library without a framework
  ([ADR-0012](0012-separate-model-and-engine-modules.md)), and until now it touched no file: its
  callers read and wrote them.

## Considered Options

- Move the reader into the engine, in a package of its own that alone touches the file system
- A module of its own, which the CLI and the MCP server depend on
- The MCP server depends on the CLI
- A reader of the MCP server's own

## Decision Outcome

Chosen option: move the reader into the engine, in a package of its own, because it is written once
with its tests, in the library both commands already ship, and a build rule keeps the file system
out of the rest of the engine.

- `io.archsim.engine.files` holds `PythonFiles` (`readText`, `readJson`, `json`, `writeText`,
  `fileName`) and `PythonFileException`, the refusal in Python's words. The decoder they read
  bytes with, `Utf8` and the `UndecodableBytes` it finds, joins `io.archsim.engine.text`, beside
  `PythonJsonReader`; the CLI's import decoding (`TextDecoding`, `Utf16`) uses them there.
- An ArchUnit rule refuses `java.io.File`, the file streams, `RandomAccessFile`, `java.nio.file`
  and `java.nio.channels` to every engine class outside `files`, as one keeps the HTTP client in
  the Prometheus package ([ADR-0057](0057-move-the-prometheus-client-into-the-engine.md)), and
  another refuses `files` to the app.
- The engine still reads no file of its own accord: `files` reads the paths its caller names, as
  the commands' arguments name them.

### Consequences

- Good, because the CLI and the MCP server refuse a file in the same words, Python's, from one
  implementation and one set of tests.
- Good, because the engine's file access is one package a reader can find and a rule keeps alone.
- Bad, because the engine, which until now only computed, now reads and writes paths it is given:
  a caller that takes a path from someone it does not trust must check it first. The app, whose
  callers are not trusted with its file system, does not use the package (a rule of its
  `ArchitectureTests`); a request's files reach it as text.

### Confirmation

`ArchitectureTests.engineClasses_outsideTheFilesPackage_useNoFileSystem` in the engine and
`appClasses_anyFeature_neverReadFilesThroughTheEngine` in the app; `PythonFilesTests`,
`Utf8Tests` and `UndecodableBytesTests` in the engine; the CLI's recorded cases and the MCP
server's recorded session, which read files through it.

## Pros and Cons of the Options

### The engine, in a package of its own

- Good, because the engine already holds `PythonJsonReader`, which the reader parses JSON with.
- Bad, because the engine's purpose widens from computing to reading its callers' files.

### A module of its own

- Good, because the engine stays free of the file system.
- Bad, because a module of four classes, with its own build and README, for two callers.

### The MCP server depends on the CLI

- Bad, because the server would ship picocli and every command to read three files.

### A reader of the MCP server's own

- Bad, because two readers of the same files, whose refusals would drift apart.

## More Information

- [ADR-0057](0057-move-the-prometheus-client-into-the-engine.md): the same kind of move, for the
  Prometheus client the app and the CLI share.
