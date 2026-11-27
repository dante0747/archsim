# Running ArchSim for a team

By default ArchSim is a local, single-user tool: no sign-in, every request is the owner of one
`default` workspace, and every port is bound to `127.0.0.1`. This page covers what changes when
more people, more machines or more runs are involved, and
[Hosting a public server](#hosting-a-public-server) takes one server from nothing to a site on the
internet with the repository's hosted kit, `deploy/`. Read [SECURITY.md](../SECURITY.md), the threat
model, before exposing a server.

## The images

Each release publishes two images to GitHub's container registry, for `linux/amd64` and
`linux/arm64`, tagged with its version (`latest` follows the newest release that is not a
pre-release):

| Image | What it runs |
| --- | --- |
| `ghcr.io/dante0747/archsim-app:<version>` | The API, the simulation workers and the database migration: one image, started as `api`, `worker`, `migrate` or `seed` |
| `ghcr.io/dante0747/archsim-web:<version>` | The web app, which calls the API at `ARCHSIM_API_URL` (default `http://api:8000`) |

`docker-compose.yml` and the hosted kit's `deploy/compose.yaml` run both at the version their
checkout names. Pin a version wherever you run them, so that every API instance and worker runs the same code. `.github/workflows/release.yml`
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
| `ARCHSIM_SANDBOXES` | `true` lets visitors try ArchSim without signing in, each in a sandbox deleted 24 hours later (default `false`; [SECURITY.md](../SECURITY.md) says what limits them) |
| `ARCHSIM_PRIVACY_URL` | Read by the web server: the address of your privacy notice, which the app then links from its footer and sign-in page ([Privacy](#13-privacy) says what to put in it) |

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

## How long the server keeps things

The API deletes what it no longer keeps when it starts and every hour after, in short batches that
any number of API instances share
([ADR-0111](adr/0111-prune-the-runs-past-a-projects-limit-and-expired-rows-every-hour.md)):

| What | Kept | Setting |
| --- | --- | --- |
| A project's runs | Its newest 200, the ones its list shows, and older runs still queued or running, signed off or commented on, with the rest of their seed group | `ARCHSIM_RUN_HISTORY_LIMIT` |
| A sign-in session | Until its person signs out, or it expires, 30 days after signing in | `ARCHSIM_SESSION_DAYS` |
| An invitation | Until it expires (7 days by default, 30 at most), accepted or not | |
| A share link's copy of the results | Until the link expires or is revoked; the link stays listed, expired | |
| The history of each workspace | 365 days | `ARCHSIM_HISTORY_DAYS` |

Rate-limit counters go within a minute of the end of their window, and sandboxes 24 hours after
they start. Designs, comments, sign-offs, accounts and workspaces stay until someone deletes them,
and API tokens, revoked and expired ones included, for as long as their person's account.
Before upgrading a server whose projects should keep more runs, raise `ARCHSIM_RUN_HISTORY_LIMIT`:
the first prune, as the API starts, deletes the runs past it. The hosting kit takes the table's
three settings from `hosted.env`, and `docker-compose.yml` from `.env.docker`.

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

## Hosting a public server

The repository's `deploy/` folder is a kit for one server on the internet
([ADR-0080](adr/0080-host-a-public-server-from-a-compose-kit.md)):

| File | What it does |
| --- | --- |
| [`compose.yaml`](../deploy/compose.yaml) | Runs a release's images with sign-in: PostgreSQL, the migration, the API, a worker, the web app, the way in and a nightly backup |
| [`hosted.env.example`](../deploy/hosted.env.example) | The settings: the way in, the domain, the secrets, the OAuth app, and sizes and limits you may change |
| [`Caddyfile`](../deploy/Caddyfile) | With [Caddy](https://caddyserver.com) as the way in: HTTPS for your domain, with a certificate Caddy gets from Let's Encrypt and renews itself |
| [`backup.sh`](../deploy/backup.sh) | The backup service's nightly `pg_dump`, the newest seven kept |
| [`update.sh`](../deploy/update.sh) | Moves the server to another release |

It does each step of [Before you expose a server](#before-you-expose-a-server): only the way in
takes connections from outside, browsers reach the API through the web server only, the database
sits on a network with no way out that only the API, the workers and the backup reach, and
calibration never calls out. Runs are smaller than a local install allows, for a public demo: at most
1,000,000 expected requests and 120 s (`ARCHSIM_MAX_REQUESTS`, `ARCHSIM_MAX_WALL_SECONDS`), more than
any template needs. Before you open it to the public, publish a privacy notice:
[Privacy](#13-privacy) lists what the server stores.

### 1. A server

Linux with 2 cores and 4 GB of memory or more, amd64 or arm64 (every image is built for both), and
20 GB of disk; Ubuntu 24.04 LTS is a good choice. The defaults fit that smallest size: the memory
limits add up to 3.3 GB, and one worker simulates two runs at once, with a 1 GB heap. On a bigger
server, give the JVMs more in `hosted.env` (a container's memory limit is its heap and 384 MB):

| Setting | 2 cores, 4 GB (default) | 4 cores, 8 GB or more |
| --- | --- | --- |
| `ARCHSIM_MAX_CONCURRENT_RUNS` (the worker's runs at once) | `2` | `3`, leaving a core to the rest |
| `ARCHSIM_WORKER_HEAP` | `1g` | `2g` |
| `ARCHSIM_WORKER_MEMORY` | `1408m` | `2432m` |
| `ARCHSIM_API_HEAP` | `512m` | `1g` |
| `ARCHSIM_API_MEMORY` | `896m` | `1408m` |

The API's heap also sets how many requests the engine's routes answer at once, each with a design
in memory: one on 512 MB with two cores, three on 1 GB with four (`ARCHSIM_MAX_ENGINE_REQUESTS`
in `hosted.env` overrides it; [ADR-0108](adr/0108-bound-the-engines-routes-by-slots-per-person-limits-and-a-sandbox-body-cap.md)).

The worker's heap on a big server is the one [archsim-app's README](../archsim-app/README.md#the-workers-heap)
asks: 512 MB a run and 256 MB besides. The default gives each run less, about 384 MB, because runs
here stop at 1,000,000 requests instead of 2,000,000, and a run's heap shrinks by about 128 MB a
million. More workers (`--scale worker=2`) need more cores still, each with its own heap.

Each API instance and each worker keeps at most ten connections to PostgreSQL
(`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`,
[ADR-0105](adr/0105-charge-every-rate-limit-before-its-transaction-begins.md)). A request holds one
at a time; in a burst, a request waits up to 10 s for one to come free, then fails. The kit's API
and worker keep 20 of PostgreSQL's 100, room for more workers and the nightly dump.

The disk holds the images, the database and its seven nightly dumps (step 11). The database's
largest rows are runs, each with its design and report, and a project keeps its newest 200
([How long the server keeps things](#how-long-the-server-keeps-things)).

### 2. Docker

Docker Engine 27 or later, with its Compose plugin, from Docker's own packages
([docs.docker.com](https://docs.docker.com/engine/install/ubuntu/) describes the apt repository; its
convenience script does the same):

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"   # sign out and in again; the docker group is as good as root
docker compose version
```

### 3. The way in: Caddy or a Cloudflare Tunnel

`COMPOSE_PROFILES` in `hosted.env` starts one of two:

| | `caddy` | `tunnel` |
| --- | --- | --- |
| What runs | Caddy, on ports 80 and 443 | `cloudflared`, which connects out to Cloudflare; no port is open |
| HTTPS | Caddy, with a certificate from Let's Encrypt | Cloudflare's edge |
| DNS | Anywhere: an `A` record to the server | The domain on Cloudflare; the tunnel makes its record |
| The visitor's address | Caddy writes the address it saw into `X-Forwarded-For` | Cloudflare appends the visitor's address to `X-Forwarded-For` |

Either way the web server takes the last entry of `X-Forwarded-For` (`ARCHSIM_TRUSTED_HOPS=1`) and
passes it to the API, which believes only the web server (`ARCHSIM_TRUSTED_PROXIES=web`), so rate
limits count each visitor, not the proxy. Whatever a visitor writes into the header themselves stays
to the left and is ignored. `cloudflared` passes the header on as Cloudflare wrote it, and Cloudflare
also sends the address alone as `CF-Connecting-IP`.

### 4. A firewall

Let in TCP 22 and, with Caddy, TCP 80 and 443 and UDP 443 (HTTP/3); nothing else. With the tunnel,
22 alone: `cloudflared` only connects out, to Cloudflare's port 7844, which most servers allow. Set
this in your provider's firewall, and on the server with ufw:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp   # with Caddy
sudo ufw allow 443      # with Caddy: TCP and UDP
sudo ufw enable
```

Docker opens the ports it publishes with rules of its own, ahead of ufw's; only Caddy publishes any.

On Oracle Cloud's Ubuntu images, leave ufw off: the image's own iptables rules (restored from
`/etc/iptables/rules.v4` by netfilter-persistent) reject everything but SSH, so insert `ACCEPT` rules
for 80 and 443 before their `REJECT` line (`sudo iptables -L INPUT --line-numbers` shows it) and save
them with `sudo netfilter-persistent save`; the VCN's security list needs the same ports. Oracle also
reclaims Always Free instances that stay idle, unless the account is pay-as-you-go.

### 5. DNS and HTTPS

**With Caddy**, point the name people will open, say `app.example.com`, at the server: an `A` record
for its IPv4 address, and an `AAAA` record only if it has a public IPv6 address. Caddy asks for the
certificate on the first start, so the records must resolve to the server by then
(`dig +short app.example.com`). On Cloudflare's DNS, leave the record **DNS only**: behind
Cloudflare's proxy, the SSL/TLS mode must be **Full (strict)** (Flexible loops between HTTP and
HTTPS), and Caddy would see Cloudflare's addresses instead of each visitor's. Use the tunnel instead.

**With a Cloudflare Tunnel**, the domain's DNS is on Cloudflare:

1. Delete any record the name already has (say `app.example.com`): the tunnel makes its own.
2. Cloudflare Zero Trust > Networks > Tunnels > **Create a tunnel**, of the Cloudflared kind; name it
   (`archsim`). Of the install command it shows, copy the token, the long string after `--token`,
   into `hosted.env` as `TUNNEL_TOKEN`. The kit runs `cloudflared` itself; install nothing.
3. Add a **public hostname**: subdomain `app`, domain `example.com`, service type `HTTP`, URL
   `web:3000`. The tunnel then sends `https://app.example.com` to the web server.
4. In the zone's SSL/TLS > Edge Certificates, turn on **Always Use HTTPS** and **HSTS** (Caddy sends
   HSTS itself; through the tunnel Cloudflare does). Leave the managed transform **Remove visitor IP
   headers** off: without `X-Forwarded-For`, every visitor would count as one.

### 6. A GitHub OAuth app

GitHub > Settings > Developer settings > OAuth Apps > New OAuth App, with:

- **Homepage URL:** `https://app.example.com`
- **Authorization callback URL:** `https://app.example.com/api/v1/auth/github/callback`

Then generate a client secret. A Google OAuth client works as well, or instead; its redirect URI is
`https://app.example.com/api/v1/auth/google/callback`.

### 7. The kit and its settings

Clone the kit alone (a partial clone with the `deploy` folder checked out) as the user that runs it,
then fill in `hosted.env`:

```bash
git clone --filter=blob:none --sparse https://github.com/dante0747/archsim.git ~/archsim
cd ~/archsim && git sparse-checkout set deploy
cd deploy
cp hosted.env.example hosted.env && chmod 600 hosted.env
openssl rand -hex 32      # POSTGRES_PASSWORD: hex, since it goes into a URL
openssl rand -base64 48   # ARCHSIM_SECRET_KEY
nano hosted.env
```

`main`'s kit runs the newest release's images; `./update.sh v<version>` (step 10) moves it to a
release's own kit. `hosted.env` needs the way in (`COMPOSE_PROFILES`, with `TUNNEL_TOKEN` for the
tunnel), the domain, the two secrets, the OAuth app's client id and secret, and your email in
`ARCHSIM_ADMIN_EMAILS`, so that your first sign-in owns the `default` workspace. Its optional
settings are the sizes above, the run limits, and the backups' hour and count. Git ignores
`hosted.env`; keep it on the server only.

### 8. Start

```bash
docker compose --env-file hosted.env up -d
docker compose --env-file hosted.env ps
```

Every Compose command for the kit takes `--env-file hosted.env`, which holds the settings Compose
fills in and the profile it starts. The first start pulls the images, migrates the database, and
starts the rest once the API answers; Caddy has its certificate, or the tunnel its connections, within
a minute. Each service starts again after a crash or a reboot.

To change the way in later, remove the old one, change `COMPOSE_PROFILES` (and the DNS), and start
again: `docker compose --env-file hosted.env rm --stop --force caddy` (or `cloudflared`), then
`up -d`.

### 9. Check

```bash
curl -sI https://app.example.com | head -1                 # HTTP/2 200
curl -s https://app.example.com/api/v1/health              # {"status":"ok","database":"ok"}
curl -s https://app.example.com/api/v1/auth/config         # {"mode":"oauth","providers":["github"]}
nc -zv -w 5 <server address> 5432                          # from elsewhere: refused or timed out
```

Then sign in with GitHub with the email in `ARCHSIM_ADMIN_EMAILS`, open a template and simulate it.
To see that rate limits count visitors and not the proxy, list the addresses the sign-in limit has
counted, a few minutes after signing in at most (ended windows are cleared); yours (over IPv6, its
/64) should be there, not a `172.` address of Docker's:

```bash
docker compose --env-file hosted.env exec backup psql -Atc "select key from rate_limits where key like 'ip:%'"
```

### 10. Upgrade to a new release

Take a backup (below), then move the kit to the release's tag:

```bash
cd ~/archsim/deploy
docker compose --env-file hosted.env exec backup archsim-backup now
./update.sh v1.6.0
```

`update.sh` checks the kit out at the tag, pulls its images, migrates the database and restarts what
changed, Caddy and the backup service too when the release changed their files. If the images cannot
be pulled, because the release's are not published yet, it goes back to the commit it was on and
restarts nothing. Going back to an older release works only if the newer one brought no migration
(its worker refuses a database past its own schema): otherwise restore the backup taken before the
upgrade.

The project's own demo deploys from GitHub Actions instead (`.github/workflows/deploy-demo.yml`): on
each published release, or by hand, it connects over SSH with a key whose only command is
`update.sh`. To set it up, make a key pair, put its public half in the server user's
`~/.ssh/authorized_keys` with a forced command, and give the workflow the rest:

```text
restrict,command="/home/<user>/archsim/deploy/update.sh" ssh-ed25519 AAAA... deploy-demo
```

| In GitHub | What it holds |
| --- | --- |
| Environment `demo`, secret `DEMO_SSH_KEY` | The key pair's private half (`ssh-keygen -t ed25519 -N '' -f deploy-demo`) |
| Environment `demo`, secret `DEMO_HOST` | `<user>@<server address>` (the address, not a name behind Cloudflare) |
| Environment `demo`, secret `DEMO_KNOWN_HOSTS` | `ssh-keyscan -t ed25519 <server address>`, checked against `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the server |
| Environment `demo`, deployment branches and tags | `main` and `v*` only, so that a run from another branch never gets the key |
| Repository variable `DEMO_ENABLED` | `true`; until then the workflow is skipped |

Publish a GitHub release once Release images has passed for its tag: the server pulls those images.

### 11. Backups and restore

The `backup` service dumps the database every night at 03:00 UTC into the `backups` volume and keeps
the newest seven (`ARCHSIM_BACKUP_HOUR`, `ARCHSIM_BACKUP_KEEP`). The volume is on the server's disk,
so copy dumps elsewhere too, or use your provider's volume backups.

The database and its seven dumps share that disk, and what the database stores is in each dump
too. Each workspace has a quota of stored bytes: a personal workspace 100 MB, a team 250 MB and a
sandbox 10 MB, with at most 100, 500 and 5 projects
([ADR-0109](adr/0109-give-each-workspace-a-project-cap-and-a-quota-of-stored-bytes.md)). A quota
bounds each workspace, not their number, so watch the disk as people join:

```bash
docker compose --env-file hosted.env exec backup psql -c "SELECT pg_size_pretty(pg_database_size('archsim'))"
df -h /
```

Dumps and restores:

```bash
docker compose --env-file hosted.env exec backup archsim-backup now                # one now
docker compose --env-file hosted.env exec backup ls -l /backups
docker compose --env-file hosted.env cp backup:/backups/archsim-2026-11-16T030000Z.dump .
```

To restore one, stop what writes to the database, restore, and start it again (a dump from elsewhere
goes in first with `docker compose --env-file hosted.env cp <file> backup:/backups/`):

```bash
docker compose --env-file hosted.env stop api worker web
docker compose --env-file hosted.env exec backup pg_restore --clean --if-exists --single-transaction \
  --dbname archsim /backups/archsim-2026-11-16T030000Z.dump
docker compose --env-file hosted.env start api worker web
```

### 12. Logs

```bash
docker compose --env-file hosted.env logs -f api worker   # or web, caddy, cloudflared, postgres, backup
```

Docker keeps five files of 10 MB for each container. Caddy logs its certificates, not each request;
a `log` line in the `Caddyfile` adds an access log, which holds visitors' addresses. Through the
tunnel, Cloudflare's dashboard has the traffic.

### 13. Privacy

A public server keeps personal data, so publish a privacy notice: what the server keeps, why, for
how long, and whom to ask about it. Set the web server's `ARCHSIM_PRIVACY_URL` to the notice's
address, and the app links to it. A server from this kit stores:

- **For each person who signs in:** the name, email and picture address their GitHub or Google
  account gives, that account's id (and GitHub user name), and what they make in their workspaces
  (designs, runs, comments, sign-offs, share links) until it is deleted: by them, or by the server
  for the runs past a project's newest 200 and an expired share link's copy
  ([How long the server keeps things](#how-long-the-server-keeps-things)).
- **For each sign-in session:** the IP address and browser (user agent) it started from, until
  the person signs out of it, or out everywhere, or about an hour after it expires.
- **For each invitation:** the email it was sent to, if it names one, until about an hour after it
  expires (7 days by default, 30 at most), accepted or not.
- **In each workspace's history:** every change to its projects, runs, reviews and members, who
  made it and from which IP address (only owners see the address), for 365 days, or until the
  workspace is deleted.
- **With sandboxes on** (`ARCHSIM_SANDBOXES=true`): a guest account with no name of its own and no
  email, its session's address and browser, and everything made in the sandbox, all deleted 24
  hours after the sandbox starts.
- **Rate-limit counters by IP address**, an IPv6 address's /64 rather than the address (sign-ins,
  sandbox starts, sandboxes' runs and public share views), deleted within a minute of the end of
  their window, which lasts an hour at most.
- **In the nightly backups:** all of the above as it was that night. The newest seven dumps are
  kept (`ARCHSIM_BACKUP_KEEP`), so whatever is deleted from the database, an expired sandbox
  included, stays in them for up to 7 days, and in any copies you keep elsewhere for as long as
  you keep those.
