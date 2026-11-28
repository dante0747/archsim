---
status: accepted
date: 2026-11-16
decision-makers: Majid Abarghooei (owner)
---

# 0080. Host a public server from a Compose kit, behind Caddy or a Cloudflare Tunnel

## Context and Problem Statement

ArchSim is public, and its owner wants a live demo at `https://app.archsim.io` on one small Linux
server, as small as 2 cores and 4 GB, with the domain's DNS on Cloudflare. The pieces exist: release
images for amd64 and arm64
([ADR-0077](0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)), sign-in, workspace
limits and the outbound block ([SECURITY.md](../../SECURITY.md)), and a checklist in
[deployment.md](../deployment.md#before-you-expose-a-server). But `docker-compose.yml` is a local
stack: no sign-in, ports on `127.0.0.1`, the password `archsim`, no TLS and no backups, and the guide
said a hosted stack was still to come. What should a server run, how do visitors reach it, and how
does it go from an empty machine to a site, and from one release to the next?

## Decision Drivers

- Going live is: install Docker, clone the kit, fill in one file of settings, start it.
- Nothing but Docker and official images on the server; any provider, either processor.
- SECURITY.md's trust boundaries hold by construction, not by care: browser, the way in, web
  server, API, database.
- HTTPS without anyone renewing a certificate.
- Rate limits count each visitor's address, whichever proxy stands in front.
- The server's secrets stay in one file on the server, outside git.
- The release check that keeps `docker-compose.yml` at the release's version keeps the kit there too.
- Upgrades and backups a person can run in one command, and a deploy from CI that cannot do more.

## Considered Options

- (a) A Compose kit in `deploy/`: the release's images with sign-in, behind one way in that a
  Compose profile chooses, Caddy or a Cloudflare Tunnel, with a backup service, a script that moves
  the server to a release, and an optional SSH deploy workflow
- (b) A hosted profile or override file of `docker-compose.yml`
- (c) Kubernetes manifests or a Helm chart
- (d) A platform's own configuration (Fly.io, Render and the like)
- For the way in: Caddy, nginx with certbot, Traefik, or a Cloudflare Tunnel

## Decision Outcome

Chosen option: (a), with Caddy and a Cloudflare Tunnel as its two ways in, because one file of
settings and one command bring up the whole site on any server with Docker, the networks enforce the
trust boundaries, and neither way in leaves a certificate to renew.

- **`deploy/compose.yaml`** (project `archsim-hosted`, apart from the local stack's `archsim` and its
  `archsim_pgdata`) runs PostgreSQL 16 with a password from the settings and no published port, the
  migration once, the API with `ARCHSIM_AUTH_MODE=oauth`, auto-migration and outbound calls off and
  the web server as its only trusted proxy, a worker, the web app with `ARCHSIM_TRUSTED_HOPS=1`, the
  way in and a backup service. Its images are the release's, pinned to the version, as in
  `docker-compose.yml`. Runs are capped for a public demo at 1,000,000 expected requests and 120 s
  (against 2,000,000 and 300 locally), more than any template needs; the settings can change both.
  Every service restarts unless stopped, rotates its logs (five files of 10 MB) and has a memory
  limit.
- **Sized for 2 cores and 4 GB.** The limits add up to 3.3 GB: the worker simulates two runs at once
  in a 1 GB heap, the API has 512 MB, and a JVM's limit is its heap and 384 MB. That is less a run
  than [archsim-app's rule](../../archsim-app/README.md#the-workers-heap) (512 MB a run and 256 MB
  besides), which is for runs of up to 2,000,000 requests; a run's heap shrinks by about 128 MB a
  million. On 4 cores and 8 GB, the settings give the worker three runs and the rule's 2 GB, and the
  API 1 GB; the guide has the values.
- **Two ways in, one profile each.** `COMPOSE_PROFILES` in the settings starts one:
  - `caddy`: Caddy (the official image, pinned) publishes 80, 443 and 443/udp, the only service
    that publishes a port. `deploy/Caddyfile` serves `https://<domain>` with a certificate from
    Let's Encrypt, the redirect from HTTP, HTTP/3 and compression, and proxies everything to the web
    server. It adds HSTS, `nosniff`, framing and referrer headers only where an answer lacks them,
    so the app's own headers, its Content-Security-Policy among them, stand.
  - `tunnel`: `cloudflared` (the official image, pinned) runs `tunnel run` with the tunnel's token
    from the settings and connects out to Cloudflare, which serves HTTPS and sends the tunnel's
    public hostname to `http://web:3000`. The server publishes no port at all.
- **The visitor's address.** Either way the web server reads the last entry of `X-Forwarded-For`
  (`ARCHSIM_TRUSTED_HOPS=1`) and the API believes only the web server (`ARCHSIM_TRUSTED_PROXIES=web`).
  Caddy replaces the header with the address it saw. Through the tunnel, Cloudflare appends the
  visitor's address to whatever the visitor sent, and `cloudflared` passes the header on as it is,
  as [cloudflared#1426](https://github.com/cloudflare/cloudflared/issues/1426) shows; Cloudflare
  sends the same address alone as `CF-Connecting-IP`
  ([Cloudflare's headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/)).
  A forged entry stays on the left and is ignored.
- **Three networks** follow the trust boundaries: `edge` holds the way in and the web server, `app`
  the web server and the API, and `data`, `internal` (no way out), the database with the migration,
  the API, the workers and the backup. The API and the database are unreachable from the internet
  and from the way in. `edge` has IPv6 on, so that Docker forwards IPv6 visitors to Caddy with their
  own address instead of its proxy's.
- **`deploy/hosted.env`**, from `hosted.env.example`, holds the profile, the tunnel's token, the
  domain, the database password (`openssl rand -hex 32`, since it goes into a URL), the secret key,
  the OAuth app, the admin emails and the sizes. Git ignores it. Compose reads it with
  `--env-file hosted.env`, `COMPOSE_PROFILES` included, and gives each container only the variables
  it needs. It is not named `.env`, which Compose would read by itself: the repository keeps `.env*`
  names for a developer's local secrets, which its tooling and assistants never touch.
- **`deploy/backup.sh`** runs in the official PostgreSQL image: a `pg_dump` at 03:00 UTC every
  night, written under a temporary name and renamed when complete, the newest seven kept in a
  volume. `archsim-backup now` makes one on demand, and `pg_restore` from the same container
  restores one.
- **`deploy/update.sh`** moves a server's clone to a release's tag (or `main`), pulls its images and
  runs `up -d`, which migrates and restarts what changed, and recreates Caddy or the backup service
  when the release changed the file it mounts. When the images cannot be pulled, it goes back to the
  commit it was on.
- **`.github/workflows/deploy-demo.yml`** runs it for the project's demo, on a published release
  that is not a pre-release or by hand, with plain `ssh` and the secrets of a `demo` environment. On
  the server, the key's forced command is `update.sh`, which accepts only a tag or `main`, so the key
  can deploy and nothing else. The workflow has no token and is skipped unless the repository variable
  `DEMO_ENABLED` is `true`, as the CLA workflow is.
- archsim-tools' `CheckRelease` checks `deploy/compose.yaml` as it checks `docker-compose.yml`: a
  release whose kit runs another version, or has no kit, does not publish.

### Consequences

- Good, because a server goes live in a few commands, which
  [deployment.md](../deployment.md#hosting-a-public-server) lists from the firewall to a restore, on
  any provider.
- Good, because a mistake in the settings cannot open the API or the database: only Caddy has a port,
  and the database's network has no route out. Through the tunnel, the server has no web port at all,
  and its address can stay unknown.
- Good, because certificates are Caddy's or Cloudflare's job, renewed in the background.
- Bad, because it is one server: no failover, and a restart or an upgrade is a short outage. The
  backups live on its disk too, until someone copies them off.
- Bad, because every Compose command for the kit needs `--env-file hosted.env`, and changing the way
  in means removing the old one's container by hand.
- Bad, because through the tunnel the visitor's address is Cloudflare's word: anyone who could send
  requests to the web server other than through the tunnel could forge it. Nothing else can reach
  the web server, and turning on Cloudflare's "Remove visitor IP headers" would make every visitor
  count as one; the guide says to leave it off.
- Bad, because the workers receive the sign-in settings, OAuth secrets included: with sign-in on,
  the app checks them in every role, the worker's too. The workers sit on the network with no way
  out.
- Bad, because the images of Caddy and `cloudflared` are pinned by hand: Dependabot does not watch
  `deploy/`.
- Bad, because the IPv6 network needs Docker Engine 27 or later, and a Cloudflare proxy in front of
  Caddy would make the rate limits count Cloudflare's addresses (the guide says to use the tunnel
  instead).
- Bad, because the deploy user is in the `docker` group, which is as good as root. The forced
  command narrows what the workflow's key can do, not what that user can.

### Confirmation

- archsim-app's `ComposeFileTests`: `deploy/compose.yaml` runs both release images at
  `ArchsimVersion.PRODUCT` and builds nothing; only `caddy` publishes ports; `caddy` and
  `cloudflared` each start with a profile of their own; `edge` holds only them and the web server;
  PostgreSQL is only on `data`, which is internal; the API and the workers run with sign-in; and the
  API believes only the web server about visitors' addresses.
- archsim-tools' `CheckReleaseTests`: a kit at another version, or none, stops a release;
  `ReleaseWorkflowTests` runs the check on the checkout as it is. CI's `Rules` run the Java job when
  `deploy/compose.yaml` changes, and no job for the rest of `deploy/`.
- Brought up locally on 2026-11-16 with `ARCHSIM_DOMAIN=localhost` (Caddy's own certificate
  authority) and a dummy OAuth app, profile `caddy`: the web app and `/api/v1/health` answered
  through Caddy over HTTP/2, HTTP redirected to HTTPS, the sign-in redirect named the public
  callback, `GET /meta` reported the kit's limits, the API and PostgreSQL published no port and were
  unreachable from Caddy (and the database from the web server), the workers had no route out while
  the API reached GitHub, the API counted the address Caddy saw while spoofed `X-Forwarded-For` and
  `X-ArchSim-Client-IP` headers were ignored, and a dump was made, pruned to the newest and restored.
  With the default sizes, the worker simulated two runs of 950,000 requests at once (`queueing-lab`
  overloaded and `social-feed-hot-key`, scaled up) in 88 s; its memory peaked at 1.21 GB of its
  1.375 GB limit, the heap grown to its 1 GB. Profile `tunnel` started `cloudflared` on `edge` with
  the token from the settings and no port on the host; a real tunnel needs a Cloudflare account, so
  the path from Cloudflare to the web server is first checked on the demo server (the guide's check
  of the rate-limit keys).

## Pros and Cons of the Options

### (b) A hosted profile or override of `docker-compose.yml`

- Good, because one file describes both stacks.
- Bad, because the local stack's choices (ports on `127.0.0.1`, the `archsim` password, no sign-in,
  the shared `archsim_pgdata`) would be overridden rather than absent, and Compose merges lists
  such as `ports` instead of replacing them: a forgotten override would publish the API.

### (c) Kubernetes or Helm

- Good, because it scales past one machine and is what larger installs use.
- Bad, because one small server would first need a cluster (k3s or the like), an ingress, a
  certificate manager and a chart to maintain: far more than the demo needs.

### (d) A platform's configuration

- Good, because the platform handles TLS, restarts and sometimes the database.
- Bad, because it ties the kit to one provider and its prices, and most free tiers cannot run a
  JVM worker with a 1 GB heap beside the API's.

### The ways in

- Caddy gets and renews its certificates itself, with HTTP/2, HTTP/3 and the redirect in a few lines.
- nginx is familiar, but certbot is a second process with its own renewal timer and volume, and the
  configuration for TLS, HTTP/2, HTTP/3 and the redirect is several times longer.
- Traefik gets certificates itself too, but is configured through labels on each service or a file
  of its own, and reading labels needs the Docker socket, a privilege Caddy does not need.
- A Cloudflare Tunnel needs the domain on Cloudflare and makes Cloudflare part of the path, but the
  server opens no port, Cloudflare's edge serves TLS and absorbs floods, and no certificate lives on
  the server. Kept as the second way in rather than the only one, so that the kit runs without
  Cloudflare.

## More Information

- [ADR-0116](0116-give-the-hosting-kits-api-a-1-gb-heap.md): the API's heap, now 1 GB, which amends
  "Sized for 2 cores and 4 GB".
- [ADR-0014](0014-one-deployable-application-with-roles.md): one image, started as `migrate`, `api`
  or `worker`.
- [ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md): the workers and their heap.
- [ADR-0076](0076-version-archsim-apart-from-its-engine.md) and
  [ADR-0077](0077-publish-release-images-to-ghcr-and-run-them-from-compose.md): the version and the
  images the kit runs.
- [docs/deployment.md](../deployment.md#hosting-a-public-server), [docs/ci.md](../ci.md#release-images)
  and [SECURITY.md](../../SECURITY.md).
