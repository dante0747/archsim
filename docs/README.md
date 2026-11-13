# ArchSim documentation

Everything about using, running, trusting and changing ArchSim. New here? Start with
[Getting started](getting-started.md).

## Start here

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | Run it in Docker or from source, a five-minute tour, the CLI and the MCP server |
| [Features](features.md) | Every workspace, technology behaviour and integration, with screenshots, and the scope and limitations |
| [Troubleshooting](troubleshooting.md) | What to do when something doesn't start or runs stay queued |

## Using ArchSim

| Page | What it covers |
| --- | --- |
| [Simulation model](simulation-model.md) | What is and isn't modeled, and how each component behaves |
| [Assumptions](assumptions.md) | Every default the engine uses, documented or illustrative (generated from the code) |
| [Calibration](calibration.md) | Setting a design's inputs from Prometheus metrics or OpenTelemetry traces |
| [Importing a design](import.md) | Starting from docker-compose, Kubernetes manifests or a Terraform plan |
| [Prices and machine shapes](pricing.md) | Real instance types and price snapshots behind every cost line |
| [An SLO gate in CI](ci.md) | `archsim check` and the GitHub Action that comments on pull requests |
| [AI agents](agents.md) | The MCP server for coding agents and the WebMCP tools in the browser |
| [Performance](performance.md) | How fast the engine answers, and the sampled mode for long runs |

## How accurate is it?

| Page | What it covers |
| --- | --- |
| [How accurate is ArchSim?](validation.md) | Predictions saved before measuring, then checked with k6 against real stacks |
| [Java parity](validation/java-parity.md) | The Java simulator against the frozen Python engine it replaced |

## Running a server

| Page | What it covers |
| --- | --- |
| [Running ArchSim for a team](deployment.md) | Several API instances and workers, sign-in, teams, API tokens, and what to do before exposing a server |
| [Security policy and threat model](../SECURITY.md) | How to report a vulnerability, and how each threat is handled |
| [HTTP API](api.md) | Every endpoint under `/api/v1` (OpenAPI at `/api/v1/openapi.json`, interactive docs at `/api/v1/docs`) |
| [The API's module](../archsim-app/README.md) | Running the API and workers, their settings and the worker's heap |

## Reference

| Page | What it covers |
| --- | --- |
| [Document schema](schema.md) | The design document, field by field, and how versions upgrade |
| [Architecture](architecture.md) | The layers, modules and how a run flows through them |
| [Architecture decision records](adr/README.md) | Why ArchSim is built the way it is, decision by decision |
| [The command line](../archsim-cli/README.md) | Every `archsim` command, flag and exit code |
| [The MCP server's module](../archsim-mcp/README.md) | What it serves and how it is tested |

## Contributing

| Page | What it covers |
| --- | --- |
| [Contributing guide](../CONTRIBUTING.md) | Setup, checks, pull requests and the Contributor License Agreement |
| [Developing ArchSim](development.md) | The repository layout and what the tests check |
| [Java conventions](java-conventions.md) | How the Java code is written, and what the build enforces |
| [Adding a component type](extending-components.md) | A new technology, from the model to the engine |
| [Roadmap](roadmap.md) | Directions the design leaves room for |

## Records

How ArchSim got here, kept for reference:

| Page | What it covers |
| --- | --- |
| [Changelog](../CHANGELOG.md) | What changed in each release |
| [Implementation status](../IMPLEMENTATION_STATUS.md) | What is done, week by week |
| [Implementation plan](plan.md) | The plan the first version was built against |
| [The Java 25 port](proposals/java-25-port.md) | The plan that moved the engine, API, CLI and MCP server from Python to Java |
| [Template review](templates-review.md) | The audit of the built-in templates |
| [UI critique and design principles](ui-redesign.md) | The review behind the current interface |
