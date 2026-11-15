---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0014. One deployable application, started as `api`, `worker` or `migrate`

## Context and Problem Statement

Today one Python image runs as three services in `docker-compose.yml`:

| Service | Command |
| --- | --- |
| `migrate` | `archsim-api migrate` |
| `api` | the API |
| `worker` | `archsim-api worker` |

`archsim-api serve` also contains a worker by default, and `archsim-api worker --slots N` adds more
on any host that can reach the database ([ADR-0003](0003-postgresql-run-queue-with-leases.md)). The
API and the workers share the queue, the persistence code and the engine.

How should the Java application be packaged and deployed?

## Considered Options

- One Spring Boot application, `archsim-app`, in one image, whose role is chosen at start-up
- Two applications, `archsim-api` and `archsim-worker`, each with its own module and image

## Decision Outcome

Chosen option: "One application whose role is chosen at start-up", because it keeps today's
deployment model unchanged and gives one image to build, version and scan, with no third module
just to hold shared code.

- **`api`:** serves HTTP.
- **`worker`:** claims and runs simulations. A Spring profile leaves out the web layer.
- **`migrate`:** applies the Flyway migrations ([ADR-0008](0008-jdbcclient-and-flyway.md)) and
  exits.

Images are built with Jib, and `docker compose up` works as it does today.

**Status (10 Nov 2026):** the `worker` role runs since stage 4 of the port
([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)), and compose starts it.
`archsim-api serve` and `archsim-api worker` are gone; `archsim-api migrate` stays until stage 6.

**Status (12 Nov 2026):** a fourth role, `seed`, makes one project per example in the default
workspace and exits, as `archsim-api seed` did, which is gone with the Python engine it read the
examples with. Like the worker, it never migrates.

**Status (15 Nov 2026):** a release publishes the image to GHCR, and `docker-compose.yml` pulls it
at the release's version, where compose ran only the image Jib built into the local Docker
([ADR-0077](0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)).
`docker-compose.local.yml` runs that local build, `archsim-app:local`.

### Consequences

- Good, because operators see the same three services as before the port.
- Good, because API and workers can never run mismatched versions of the shared code.
- Bad, because the worker image carries the web dependencies it doesn't use.
- Bad, because the role is a start-up setting, so a mistaken setting starts the wrong role. Each
  compose service names its role explicitly.

## More Information

- [`docs/architecture.md`, "Run lifecycle"](../architecture.md#run-lifecycle)
- [ADR-0011](0011-organise-application-code-by-feature.md) (how the code inside is organised)
