# AI agents (WebMCP)

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
| `get_design` | Parts (id, type, label, technology, settings that differ from the defaults, the engine's auto-sized CPU/memory/storage), links, traffic, objectives, scenarios and validation problems (read only). |
| `list_part_types` | Every part type with its technology presets; with a type, its settings (kind, default, options, unit, limits, meaning), ports and presets (read only). |
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
