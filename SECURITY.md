# Security Policy

## Supported versions

ArchSim is pre-1.0. Only the latest commit on `main` receives security fixes. There are no
long-term-support branches yet.

| Version          | Supported |
| ---------------- | --------- |
| `main` (0.1.x)   | yes       |
| anything older   | no        |

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

- Preferred: use GitHub's private vulnerability reporting ("Report a vulnerability" on the
  repository's Security tab) once the repository is published.
- Alternative: email **security@&lt;your-project-domain&gt;** *(maintainers: replace this
  placeholder before publishing)*.

Include the affected component (engine, API, web), steps to reproduce, and the impact you
expect. We aim to acknowledge reports within 5 working days and to agree on a disclosure date
with you. Please give us a reasonable chance to ship a fix before disclosing publicly.

## Threat model and deployment notes

The MVP is designed for **local, single-user use**. Keep these properties in mind before
exposing it to anyone else:

- **No authentication or authorization.** Anyone who can reach the API can read, change and
  delete every project and run. `archsim-api serve` binds to `127.0.0.1` by default; keep it
  that way, or put it behind an authenticating reverse proxy. The Docker Compose file binds
  ports for local development only.
- **No code execution over the API.** Documents are data: they are parsed with strict
  Pydantic models (`extra="forbid"`) and component types must already be registered in the
  server process. There is no endpoint that evaluates expressions, templates or user code.
- **Plugins are local only.** Third-party component models are loaded from Python packages
  installed in the server's environment via the `archsim.components` entry point group, and
  only when `ARCHSIM_ENABLE_PLUGINS=1`. Plugins run with the server's privileges, so install
  only packages you trust. Plugins can never be uploaded through the API.
- **Bounded resource use.** Each request body is capped (`ARCHSIM_MAX_BODY_BYTES`, default
  5 MB). Documents are limited to 200 nodes, 1,000 edges and 50 scenarios. Every simulation is
  bounded by an expected-request limit (`ARCHSIM_MAX_REQUESTS`, default 2,000,000) checked
  before the run starts and a wall-clock limit (`ARCHSIM_MAX_WALL_SECONDS`, default 300 s)
  checked while it runs. Retries are capped at 5 attempts per call and queues at 100,000
  entries per pool. At most `ARCHSIM_MAX_CONCURRENT_RUNS` simulations execute at once; extra
  runs wait in the queue.
- **CORS.** Only origins in `ARCHSIM_CORS_ORIGINS` (default `http://localhost:3000`) may call
  the API from a browser. The web app normally reaches the API through its own same-origin
  proxy, so CORS rarely needs widening.
- **Secrets.** The repository contains no credentials. `.env.example` documents variables
  only; never commit a real `.env`. The Docker Compose PostgreSQL password is a local
  development default and must be changed for any shared deployment.
- **Request size.** Bodies larger than `ARCHSIM_MAX_BODY_BYTES` (default 5 MB) are rejected
  with 413, and POST/PUT/PATCH requests with a chunked body but no `Content-Length` are
  rejected with 411 so the limit cannot be bypassed.
- **No telemetry.** Neither the API nor the web app phones home. Next.js's own anonymous
  telemetry is switched off: the npm scripts run Next through `apps/web/scripts/run-next.mjs`,
  which sets `NEXT_TELEMETRY_DISABLED=1` unless you set it yourself, and the Docker image sets
  it too.

## Out of scope

- Results that differ from production behaviour. ArchSim produces model estimates from user
  assumptions; inaccurate estimates are a modeling limitation, not a security issue
  (see `docs/simulation-model.md`).
- Denial of service by a user who already has full access to an unauthenticated local
  instance.
