# Running ArchSim for a team

By default ArchSim is a local, single-user tool: no sign-in, every request is the owner of one
`default` workspace, and every port is bound to `127.0.0.1`. This page covers what changes when
more people, more machines or more runs are involved. A hosted stack and a step-by-step deploy guide
are still to come; until then, read [SECURITY.md](../SECURITY.md), the threat model, before exposing
a server.

## The images

Each release publishes two images to GitHub's container registry, for `linux/amd64` and
`linux/arm64`, tagged with its version (`latest` follows the newest release that is not a
pre-release):

| Image | What it runs |
| --- | --- |
| `ghcr.io/dante0747/archsim-app:<version>` | The API, the simulation workers and the database migration: one image, started as `api`, `worker`, `migrate` or `seed` |
| `ghcr.io/dante0747/archsim-web:<version>` | The web app, which calls the API at `ARCHSIM_API_URL` (default `http://api:8000`) |

`docker-compose.yml` runs both at the version its checkout names. Pin a version wherever you run
them, so that every API instance and worker runs the same code. `.github/workflows/release.yml`
builds them from the release's tag
([ADR-0077](adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)).

## Several API instances and workers

Runs wait in a queue in the database, and workers simulate them. To share the work, start more
workers against the same PostgreSQL:

- with Compose: `docker compose up --scale worker=3`;
- elsewhere: `java -jar archsim-app.jar worker` on any machine that reaches the database, with the
  heap [archsim-app's README](../archsim-app/README.md#the-workers-heap) asks for and
  `-XX:+ExitOnOutOfMemoryError`.

A run survives a restart (it is queued again), and cancel works from any instance. A worker's runs
share its heap: a run that exhausts it stops the worker, whose runs are then requeued. Any number of
API instances can share the database too; run the `migrate` role once
(`java -jar archsim-app.jar migrate`, the Compose `migrate` service) and set
`ARCHSIM_AUTO_MIGRATE=false` on the API instances. Hosts need synchronised clocks (NTP), because
leases compare their times. Details and limits:
[api.md](api.md#background-runs-and-several-instances).

## Sign-in, teams and API tokens

To let other people in, run the API with `ARCHSIM_AUTH_MODE=oauth`, a secret key, the public address
and a GitHub or Google OAuth app:

| Variable | What it is |
| --- | --- |
| `ARCHSIM_AUTH_MODE` | `oauth` (the default, `none`, is local use only) |
| `ARCHSIM_SECRET_KEY` | 32 or more random characters, the same on every API instance |
| `ARCHSIM_PUBLIC_URL` | The address people open, for example `https://archsim.example.com` |
| `ARCHSIM_GITHUB_CLIENT_ID`, `ARCHSIM_GITHUB_CLIENT_SECRET` | A GitHub OAuth app whose callback URL is `<public url>/api/v1/auth/github/callback` |
| `ARCHSIM_GOOGLE_CLIENT_ID`, `ARCHSIM_GOOGLE_CLIENT_SECRET` | A Google OAuth client whose callback URL is `<public url>/api/v1/auth/google/callback` |
| `ARCHSIM_ADMIN_EMAILS` | Verified emails whose first sign-in makes them an owner of `default` |

Every variable, with the optional ones (GitHub Enterprise, session length, trusted proxies, the
outbound policy, rate limits), is described in [.env.example](../.env.example). With Docker Compose,
put them in `.env.docker` (git ignores it): the API and worker containers read it. Then:

- people sign in with GitHub or Google and get a personal workspace; whoever's verified email is in
  `ARCHSIM_ADMIN_EMAILS` also owns `default`;
- the person menu at the top right switches workspaces, and **Settings** creates team workspaces,
  invites people as viewers, editors or owners, and makes **API tokens** for the CLI, CI and MCP
  (`Authorization: Bearer asim_pat_...` to `https://<host>/api/v1`);
- runs per hour and runs at once are limited per workspace (429 with `Retry-After`), and the server
  calls a Prometheus only on an allowlist of public hosts.

The endpoints are in [api.md](api.md#sign-in-workspaces-and-tokens).

## Before you expose a server

- Use PostgreSQL with a password of your own: the Compose file's `archsim` / `archsim` is a local
  development default.
- Serve HTTPS from a reverse proxy in front of the web server, and keep the API's port private:
  browsers, the CLI and CI reach the API through the web server at `https://<host>/api/v1`.
- Tell each side about the proxy: `ARCHSIM_TRUSTED_HOPS` on the web server (1 behind one reverse
  proxy) and `ARCHSIM_TRUSTED_PROXIES` on the API (the web server's address), so rate limits see
  each visitor's own address.
- Keep the database reachable only by the API instances and workers: whoever can write to it can
  queue, cancel or change runs.
- Leave the outbound policy closed unless calibration needs a Prometheus server, and then list its
  host in `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS`.

[SECURITY.md](../SECURITY.md) explains each of these, and what remains unprotected.
