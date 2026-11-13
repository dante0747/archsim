# Troubleshooting

Something not starting, or not finishing? Start here. If your problem isn't listed, open an issue
with the bug report form.

## The stack

**`docker compose up` cannot find the image `archsim-app:local`.** Compose never pulls or builds
the API's image: build it first with `./gradlew :archsim-app:jibDockerBuild` (`make image`), and
again after changing the Java code. See [getting-started.md](getting-started.md#run-everything-in-docker).

**The web app says it cannot reach the API.** Start the API as
[getting-started.md](getting-started.md#run-from-source) shows. If it runs elsewhere, set
`ARCHSIM_API_URL` for the web server (default `http://127.0.0.1:8000`).

**Runs stay queued.** No worker is running: start one (`make worker`, or the `worker` role of
archsim-app's jar) with the API's `ARCHSIM_DATABASE_URL`. With Compose, check that the `worker`
service is up.

**A worker stops with `OutOfMemoryError`.** Its runs share its heap, and a run that exhausts it
stops the worker on purpose (`-XX:+ExitOnOutOfMemoryError`); its runs are requeued, and failed only
if their worker stops a second time. Give the worker the heap
[archsim-app's README](../archsim-app/README.md#the-workers-heap) asks for, or lower
`ARCHSIM_MAX_CONCURRENT_RUNS`.

## Windows

**Connections to `localhost` stall.** With Docker Desktop on Windows, `localhost` may try IPv6
first and stall. Use `127.0.0.1`, as the defaults do.

**The CLI shows `→` garbled.** The `archsim` command writes UTF-8 whatever the console's encoding;
a console on a legacy code page shows it garbled. Switch the console to UTF-8 (`chcp 65001`)
first.

**`DLL load failed ... The filename or extension is too long`.** This is the Python development
environment (the benchmark harness and the scripts): the checkout path is too deep for Windows'
path limit. Clone into a short path such as `C:\src\archsim`, or put the virtual environment
somewhere short with `UV_PROJECT_ENVIRONMENT`.

## Privacy

**Next.js telemetry.** Neither the API nor the web app phones home, and the npm scripts and the
Docker image switch off Next.js's own anonymous telemetry (`NEXT_TELEMETRY_DISABLED=1`) unless you
set it yourself. See [SECURITY.md](../SECURITY.md).
