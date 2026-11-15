# Getting started

Three ways in, from the quickest:

1. [Run everything in Docker](#run-everything-in-docker): the web app, the API and a simulation
   worker, without installing Node.js or PostgreSQL.
2. [Run from source](#run-from-source): the same stack, each part in a terminal of its own, for
   working on ArchSim.
3. [The command line](#the-command-line) and [the MCP server](#the-mcp-server): the engine alone,
   no web stack or database.

Then take [the five-minute tour](#a-five-minute-tour).

## What you need

| To... | You need |
| --- | --- |
| Run everything in Docker | Docker with Compose. To run images built from your checkout instead of the release's, a JDK 17 or later to start Gradle too (it downloads JDK 25 for the build when none is installed) |
| Run from source | A JDK 25, Node.js 20.9 or later (24 recommended) with npm, and PostgreSQL 16, which Docker Compose can start for you |
| Use the CLI or the MCP server | A JDK 25 to build and run them |
| Work on ArchSim | The same as running from source: the benchmark harness and the repository's tools are Java too, built by Gradle, and the accuracy benchmarks run their stacks in Docker ([CONTRIBUTING.md](../CONTRIBUTING.md)) |

Commands below are for a POSIX shell (Linux, macOS, Git Bash). On Windows, `./gradlew` also runs in
PowerShell; the differences are noted where they matter.

## Run everything in Docker

```bash
git clone https://github.com/dante0747/archsim.git
cd archsim
docker compose up        # PostgreSQL, migrations, the API, a worker and the web app
```

Open <http://localhost:3000>, choose **Open the starter example**, and press **Simulate**
(Ctrl/⌘ Enter).

- Compose pulls ArchSim's release images, `ghcr.io/dante0747/archsim-app` (the API, the worker and
  the migration, in one image) and `ghcr.io/dante0747/archsim-web`, at the version the checkout
  names (`version` in `gradle.properties`). Both are built for amd64 and arm64.
- A one-shot `migrate` service brings the database to the latest schema first, then the `api`
  starts, and a `worker` simulates the runs the API queues.
- Ports are bound to `127.0.0.1` because the stack runs without sign-in by default. Read
  [deployment.md](deployment.md) before letting anyone else in.
- More workers: `docker compose up --scale worker=3`.

### Your own build

The release's images don't hold changes you've made to the checkout. To run the stack on images
built from it, build the API's image with Jib, `archsim-app:local` (it needs a JDK 17 or later to
start Gradle), and add `docker-compose.local.yml`, which runs that image and builds the web image
from `apps/web/Dockerfile`:

```bash
./gradlew :archsim-app:jibDockerBuild    # make image; again after changing the Java code
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build
```

`make up-local` does both. Compose never pulls `archsim-app:local`, and the local images have names
of their own, so they're never mistaken for a release's.

## Run from source

Start PostgreSQL and build the API's jar:

```bash
docker compose up -d postgres
./gradlew :archsim-app:bootJar
```

Then start the back end, each part in a terminal of its own: the API (it migrates the database when
it starts) and a simulation worker (three runs at once, with the heap
[archsim-app's README](../archsim-app/README.md#the-workers-heap) asks for):

```bash
java -jar archsim-app/build/libs/archsim-app.jar api
```

```bash
ARCHSIM_MAX_CONCURRENT_RUNS=3 java -Xmx2g -XX:+ExitOnOutOfMemoryError -jar archsim-app/build/libs/archsim-app.jar worker
```

In another terminal, start the web app:

```bash
cd apps/web
npm ci
npm run dev
```

Open <http://localhost:3000>, choose **Open the starter example**, and press **Simulate**.
Optionally, `java -jar archsim-app/build/libs/archsim-app.jar seed` creates one project per example
file once the API is running.

The parts find each other with their defaults: PostgreSQL at
`postgresql://archsim:archsim@127.0.0.1:5432/archsim` (compose's), and the API at
`http://127.0.0.1:8000` for the web server (`ARCHSIM_API_URL`). For another database, set
`ARCHSIM_DATABASE_URL` for the API and the worker alike. Every setting is listed in
[.env.example](../.env.example).

- **Windows PowerShell:** set variables with `$env:ARCHSIM_DATABASE_URL = "postgresql://..."`, and
  likewise `$env:ARCHSIM_MAX_CONCURRENT_RUNS = "3"` before the worker's `java` command.
- **`make`:** the [Makefile](../Makefile) wraps these commands (`make dev-db`, `make api`,
  `make worker`, `make web`, `make seed`) on a POSIX shell.
- **`127.0.0.1`, not `localhost`:** with Docker Desktop on Windows, `localhost` may try IPv6 first
  and stall.

## A five-minute tour

1. On the home page, open **Black Friday on Kubernetes**: Shoppers → Kong gateway → a Go checkout
   service on Kubernetes (HPA 3→14 pods) → Redis, PostgreSQL with two read replicas, and a payments
   API. The **design review** (bottom left) already warns that reads fail when the cache is down,
   because there is no fallback path.
2. Press **2** for **Test**, then **Simulate** (Ctrl/⌘ Enter). The run is recorded and replays
   automatically: around t = 60 s the checkout service goes red while new pods start, then
   recovers. Scrub the timeline to any second; the canvas shows that moment. The **Test plan**
   panel reports whether what must stay true (p95 ≤ 500 ms, availability ≥ 99.5%) held.
3. In the Test plan, **Add failure** offers quick tests such as **Lose Catalog cache for 15 s**.
   Pick one and simulate again: the verdict flips, and the failure's block on the timeline shows
   exactly when and where it broke.
4. Press **3** for **Results**. **Answer** gives the verdict, the objectives and a monthly cost
   estimate; **Compare → Simulate all scenarios** puts every scenario on the cost-against-latency
   map: 3 fixed pods are cheaper but fail a third of the requests, the autoscaler misses the p95
   objective while its pods start, and 14 fixed pods meet both objectives at $590 a month more.
5. **Breaking point** sweeps the load and finds the highest load that still meets the objectives.
   **Report** gives you a Markdown write-up; **Ctrl/⌘ K → Export** gives docker-compose,
   Kubernetes or Mermaid scaffolding.

The numbers come from the shipped examples with seed 42 and are illustrative assumptions. Every
workspace is described in [features.md](features.md).

## The command line

The engine works without the web stack, with the same answers as the web app. Gradle installs the
`archsim` command into `archsim-cli/build/install/archsim/bin/` (`archsim.bat` on Windows); put
that folder on your `PATH`. It runs on the Java 25 that `JAVA_HOME` names, else the one on the
`PATH`.

```bash
./gradlew :archsim-cli:installDist
archsim validate examples/cache-aside-starter.json
archsim analyze examples/cache-aside-starter.json
archsim run examples/cache-aside-starter.json --scenario one-api-replica --out report.json
archsim run long-steady-design.json --sampled   # answer a long steady run from a sample
archsim compare examples/cache-aside-starter.json --a baseline --b three-api-replicas
archsim calibrate prometheus examples/cache-aside-starter.json \
  --map parity/fixtures/calibration/starter-mapping.yaml \
  --recorded parity/fixtures/calibration/starter-prometheus.json -o calibrated.json
archsim import compose docker-compose.yml -o design.json --report import-report.json
archsim export k8s examples/cache-aside-starter.json -o manifests.yaml
archsim check examples/cache-aside-starter.json --baseline-ref origin/main --fail-on regression
```

`analyze` prints the auto sizes, the cost estimate, the design review and each part's technology
tier without simulating. Untrusted documents are bounded: the wall-clock limit and cancellation
hold inside the event loop, and a design whose one request would fan out into more than 10,000
calls is rejected (`design.amplification`). Every command, flag and exit code is in
[archsim-cli's README](../archsim-cli/README.md); the CI gate is in [ci.md](ci.md).

## The MCP server

`archsim-mcp` gives AI coding agents (Claude Code, Claude Desktop, Cursor and others) the engine as
tools over stdio: list part types and templates, validate, analyze, simulate, compare, find the
breaking point, export, import and calibrate. It needs no browser, API server or network.

```bash
./gradlew :archsim-mcp:installDist
claude mcp add archsim -- "$PWD/archsim-mcp/build/install/archsim-mcp/bin/archsim-mcp"
```

For other clients, configure a stdio server with that command (`archsim-mcp.bat` on Windows).
[agents.md](agents.md) lists the tools and resources, and the WebMCP tools the web app offers to
agents in your browser.

## Next steps

- [Features](features.md): every workspace, technology behaviour and integration.
- [Simulation model](simulation-model.md): what is and isn't modeled, and how.
- [How accurate is ArchSim?](validation.md): predictions checked against real stacks.
- [Troubleshooting](troubleshooting.md) if something doesn't start.
- [All documentation](README.md).
