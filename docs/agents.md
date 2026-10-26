# AI agents: the MCP server and WebMCP

The ArchSim web app registers [WebMCP](https://webmachinelearning.github.io/webmcp/) tools, so an
AI agent working in your browser can design, simulate and read results through clear,
typed operations instead of clicking through the interface. The agent acts on the page you have
open: every edit shows on the canvas as it happens, each one is announced in a toast
("AI agent · Added Audit DB"), and every design change can be undone like your own.

## Status

WebMCP is a draft standard of the W3C Web Machine Learning Community Group and is
experimental. The draft exposes `document.modelContext`; Chrome's early preview (behind a flag)
and community polyfills expose `navigator.modelContext`. ArchSim supports both and does nothing
in browsers that have neither. See the spec repository's implementation status for which
browsers and agents support it today: <https://github.com/webmachinelearning/webmcp>.

Code: `apps/web/src/lib/agent/` (`webmcp.ts` is the browser adapter, `app-tools.ts` and
`editor-tools.ts` the tools) and `apps/web/src/components/agent/AgentTools.tsx` (registration).

## Tools

On every page:

| Tool | What it does |
| --- | --- |
| `list_templates` | Ready-made designs with their ids, descriptions and scenario counts (read only). |
| `open_template` | Opens a template in the editor: the user's unedited copy of it if there is one, else a new project from it. |
| `new_project` | Creates an empty project and opens it. |
| `list_projects` | Saved projects with ids, sizes and update times (read only). |
| `open_project` | Opens a saved project. |

In the editor (while a design is open):

| Tool | What it does |
| --- | --- |
| `get_design` | Parts (id, type, label, technology and its tier, settings that differ from the defaults, the engine's auto-sized CPU/memory/storage), links, traffic, objectives, scenarios and validation problems (read only). |
| `list_part_types` | Every part type with its technology presets (each with its tier: core or experimental); with a type, its settings (kind, default, options, unit, limits, meaning), ports and presets (read only). |
| `add_part` | Adds a part of a type, optionally from a preset (e.g. `postgresql`, `redis`, `kafka`), with a label, settings and a part to place it next to. Returns its id. |
| `connect_parts` | Links two parts (ports chosen automatically), optionally for reads or writes only, with a timeout, attempts or latency. Returns the link id. |
| `update_part` | Renames a part or changes its settings; `null` goes back to the default or to auto sizing. |
| `update_link` | Changes a link's request types, timeout, attempts, retry backoff or latency. |
| `remove` | Removes parts (with their links) or links by id. |
| `set_traffic` | Changes the workload: rate, read share, duration, warm-up, shape (constant, ramp, burst), keys, skew, seed. |
| `set_objectives` | Sets p95/p99 latency, availability and minimum throughput targets. |
| `review_design` | The engine's static design review (via `POST /analyze`): capacity at peak, timeouts and retries, unbounded queues, single points of failure (read only). |
| `simulate` | Runs the simulation (the design or a scenario), waits, and returns the verdict against the objectives, throughput, success rate, latency, failures, findings, per-part status and monthly cost. |
| `get_results` | The same summary for the latest run or a run id (read only). |
| `tidy_layout` | Arranges the parts along the request flow. |
| `undo` | Undoes the last design change. |
| `save_design` | Saves the design to the project (marked consequential). |

Tool results are JSON. Mistakes come back as errors that say what to do instead (an unknown
setting lists the part's settings; an unknown preset lists the presets), so an agent can correct
itself.

## Safety

- Tools exist only while an ArchSim page is open and only act on that page's own state, through
  the same actions as the interface. There is no code execution and no access to anything else.
- Read-only tools carry `readOnlyHint`; `save_design` carries `consequentialHint`, so agents and
  browsers can ask before using it. Edits are local until saved and can be undone.
- ArchSim sends nothing to an AI service. The agent is yours (in your browser); it sees only the
  tool results.

## MCP server (Claude Code, Claude Desktop, Cursor...)

`packages/mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server over stdio
(the official `mcp` Python SDK). It runs the engine directly: no browser, API server or network
is needed, and runs keep the engine's limits (120 s wall clock and 2 million requests by
default; `ARCHSIM_MCP_MAX_WALL_SECONDS`, `ARCHSIM_MCP_MAX_REQUESTS`).

Add it from a checkout of this repository (it is not published to a package index):

```bash
claude mcp add archsim -- uv run --directory /path/to/archsim archsim-mcp
```

For Claude Desktop, Cursor and other clients, configure a stdio server with the command
`uv` and the arguments `run --directory /path/to/archsim archsim-mcp`. Once the package is
published, `uvx archsim-mcp` will work without a checkout.

| Tool | What it does |
| --- | --- |
| `list_part_types` | Part types with their presets and technology tiers; with a type, its settings, ports and presets (read only). |
| `list_templates` / `get_template` | Ready-made designs and their documents (read only). |
| `validate_design` | Errors and warnings, each with a code, the part or link, and how to fix it. |
| `analyze_design` | Without simulating: auto sizes, monthly cost, design review, technology tiers, input provenance counts. |
| `simulate` | Runs the design or a scenario: verdict against the objectives, throughput, success, p50/p95/p99, findings, per-part status, cost, run id and result digest (deterministic). |
| `get_results` | A run of this session again, as a summary or the full report. |
| `compare` | Two scenarios side by side: KPI deltas and the input changes behind them. |
| `breaking_point` | Steps the load to find the highest that meets the objectives, the knee and the bottleneck. |
| `export` | docker-compose, Kubernetes manifests or a Mermaid diagram of the design. |
| `calibrate` | Sets inputs from telemetry files (recorded Prometheus responses plus a mapping, or an OTLP trace export); returns the calibrated document and the replay check. |

Resources: `archsim://schema/architecture` (the JSON Schema), `archsim://templates/{id}`
and `archsim://docs/simulation-model`. Results are compact JSON; a document that cannot be
used comes back as an error listing each issue with its fix. `calibrate` opens the files at the
paths the agent passes, with your permissions, and never queries a Prometheus server (record
the responses first with `archsim calibrate prometheus --record`). There is no HTTP transport:
it would need authentication first (see the roadmap).
