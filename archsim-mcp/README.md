# archsim-mcp

ArchSim's [MCP](https://modelcontextprotocol.io) server, on the Java engine: the tools an AI coding
agent (Claude Code, Claude Desktop, Cursor and others) starts over stdio to list part types and
templates, validate, analyze, simulate, compare, find the breaking point, export, import and
calibrate designs. Stage 5 of the [Java port](../docs/proposals/java-25-port.md) brought it in
place of the Python server (`packages/mcp`, which stage 5 deleted and the tag `engine-0.16.0`
keeps), on the official MCP Java SDK
([ADR-0058](../docs/adr/0058-serve-mcp-with-the-official-mcp-java-sdk.md)).

```bash
./gradlew :archsim-mcp:installDist      # archsim-mcp/build/install/archsim-mcp/bin/archsim-mcp
claude mcp add archsim -- /path/to/archsim/archsim-mcp/build/install/archsim-mcp/bin/archsim-mcp
```

The start script runs the `java` that `JAVA_HOME` names, else the one on the `PATH`; it needs
Java 25. [`docs/agents.md`](../docs/agents.md) lists the tools and resources and says how to add
the server to other clients.

## What it serves

The Python server's tools, resources, resource template and instructions, word for word: names,
descriptions, input and output schemas as pydantic generated them, and annotations, kept as data in
`src/main/resources/io/archsim/mcp/server.json`. A call gives what the Python server gave, from the
Java engine: the result as `structuredContent` (a list or text under `result`) and as text, JSON
indented by two with its characters as they are and its floats as `pydantic_core.to_json` wrote them
(`0.00001` and `5e-6`, where `json.dumps` wrote `1e-05` and `5e-06`: `PythonJson.writePydantic`); a
list is one text per item. A refusal is a result marked `isError`, `Error executing tool NAME: ...`,
in the Python server's words, but for arguments the input schema does not allow, which the SDK's
validator refuses in its own (ADR-0058). `calibrate` reads its files as the Python server did
(`read_text`, `json.loads`), through the engine's `PythonFiles`, as the CLI does: one it cannot use
is refused in the words of Python's `OSError`, codec or `json`, named as `pathlib` names it
(`[Errno 2] No such file or directory: 'mapping.yaml'`). A report names the engine that made it,
`1.0.0`, where Python's named `0.16.0`. An import's report says ArchSim scales on worker
utilization or, with a CPU size, CPU utilization if higher, where Python's said it scales on CPU
(archsim-engine's README).

The server reads its settings from the environment, as the Python server did:

| Variable | What it sets | When unset |
| --- | --- | --- |
| `ARCHSIM_EXAMPLES_DIR` | the templates' folder; the page `archsim://docs/simulation-model` is `docs/simulation-model.md` beside it | the first `examples/` folder holding JSON above the server's files: a checkout's |
| `ARCHSIM_MCP_MAX_REQUESTS` | the requests a run may simulate | 2,000,000 |
| `ARCHSIM_MCP_MAX_WALL_SECONDS` | the seconds a run may take | 120 |

A limit that is not a number stops the server from starting, as Python's `int()` and `float()`
stopped it; spaces around one are read, but not Python's underscores between digits or `inf`.
A session keeps its last twenty runs for `get_results` and `compare`. Standard output is the
protocol's alone: SLF4J, which the SDK logs through, is bound to `slf4j-nop`. The server reads its
standard input as the Python SDK did: one message per line, of any length, a line that is not one
skipped. It ends when its standard input does, or cannot be read, as the Python server did; like
it, it does not answer requests still waiting when input ends, so a client reads each answer
before it closes the server's input.

ADR-0058 lists where the Java server's answers differ from the Python server's: the handshake's
version and capabilities, an unknown tool, arguments the SDK's validator refuses, a document that
is not JSON, the schema's key order, and an export Python crashed on.

## Packages

| Package | What it holds |
| --- | --- |
| `io.archsim.mcp` | `ArchsimMcp` (the `archsim-mcp` command, and the server on any streams), `RequestLines` (its input, as the Python SDK read it), `McpSettings` (the templates' folder and the limits, from the environment), `ServerDefinitions` (the tools, resources and instructions of `server.json`), `McpTools` (each tool's handler, a refusal answered as a result marked `isError`), `CatalogTools` (`list_part_types`, `list_templates`, `get_template`), `DesignTools` (`validate_design`, `analyze_design`, `export`, `import_design`), `RunTools` (`simulate`, `get_results`, `compare`, `breaking_point`) with `RunSummaries` (a run's summary, as the Python server summarized it) and `KeptRuns` (the session's last twenty runs), `CalibrationTool` (`calibrate`, from recorded Prometheus responses or OTLP traces, its files read as the CLI reads them, through the engine's `PythonFiles`), `McpResources` (the schema, the templates and the page), `Templates` (the templates' folder), `Arguments` (a call's arguments), `JsonPicks`, `ToolResults` (a value as the Python SDK made a result of it) and `ToolRefusal` (a refused call, and a design that cannot be used, in the Python server's words) |

## Tests

```bash
./gradlew :archsim-mcp:test
```

- `scripts/export_mcp_cases.py`, which went with the Python server at stage 5's exit, recorded
  one stdio session of it in `src/test/resources/io/archsim/mcp/session.json.gz`, data now and
  not recorded again: 55 requests, the handshake, the listings, every resource and calls of
  every tool (among them a run answered from a sample, one with more
  findings than a summary keeps, compares with a label left out or empty, a calibration echoing
  floats below 1e-4), refusals included (files that are not there, arguments the input schema
  does not allow), with the files the calibrations read and the templates and page the server
  served; a file the server names, as `pathlib` names it, is named by its placeholder whatever
  the system's separator. `McpSessionTests` serves the same files and sends the same requests to
  the Java server, in-process over the SDK's stdio transport (`McpSession`), and compares each
  answer (`AnswerComparison`): results by their flag, texts and structured content, JSON as
  values (a report as the simulator's recorded runs are, `ReportComparison`) laid out as Python
  laid it out (the writer gives each of Python's texts exactly from its value, then Java's from
  Java's), other text exactly; ADR-0058's departures are checked as they are. A report's findings
  count one request in the singular, `1 request failed`, where Python wrote `1 requests`
  (archsim-engine's README), and `ReportComparison` reads Python's report so. A run of a design
  that prices a third-party API at 0 the frozen engine's way (unset, or 0 in a design older
  than schema 1.9) departs by its result digest, the run id taken from it, and a monthly cost
  left empty where only that API had a price
  ([ADR-0081](../docs/adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)):
  `RunsPricedAtZero` learns each such run's digest and id from the answer that first names them
  and reads later answers with Python's in their place.
- `McpSettingsTests` checks the settings from the environment, `KeptRunsTests` the runs a session
  keeps, `ToolResultsTests` short and empty lists (the session's one list holds every template),
  `ToolRefusalTests` a document's refusal with empty ids and hints, `RequestLinesTests` the input,
  and `McpServerTests` what the session cannot show: a server without templates or with a folder
  that is not there, a page saved with other line ends, a recording that names no source, lines
  that are not messages, input that ends after one, a message longer than the SDK reads by
  default, and `slf4j-nop` as the only SLF4J provider.
