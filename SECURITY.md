# Security Policy

## Supported versions

ArchSim is pre-1.0. Only the latest commit on `main` receives security fixes. There are no
long-term-support branches yet.

| Version          | Supported |
| ---------------- | --------- |
| `main` (0.6.x)   | yes       |
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

ArchSim runs in one of two modes, chosen with `ARCHSIM_AUTH_MODE`:

- **`none` (the default): local, single-user.** There is no sign-in: every request is the
  local owner of the `default` workspace, so anyone who can reach the API can read, change and
  delete everything. The API listens on `127.0.0.1` unless told otherwise
  (`--server.address`, `SERVER_ADDRESS`); keep it that way. The Docker Compose file binds ports
  to `127.0.0.1` for the same reason, and puts the engine service on a
  network only the API shares (see "Outbound requests" below).
- **`oauth`: hosted, for people other than whoever runs the server.** Sign-in with GitHub or
  Google, workspaces with roles, API tokens, CSRF protection, rate limits and an outbound block
  are on. The rest of this section describes that mode; the notes after it apply to both.

### Hosted mode (`ARCHSIM_AUTH_MODE=oauth`)

**What is protected.** Designs, runs and reports in each workspace; who belongs where; the
server's network position (it must not become a way into the network it runs in); its CPU
(simulations are expensive); sign-in sessions and API tokens.

**Who might attack.** Anyone on the internet (no account), a signed-in person reaching for
another workspace, a viewer trying to edit, a malicious site visited by a signed-in person
(CSRF), and someone holding a leaked token or cookie.

**Trust boundaries.** Browser → reverse proxy (TLS) → web server (Next.js, the only thing that
talks to the API) → API → database. API instances and workers trust each other through the
database. Keep the API's port unpublished: browsers, the CLI and CI reach it through the web
server at `https://<host>/api/v1`.

**How each threat is handled.**

- **Sign-in.** GitHub and Google OAuth 2 with PKCE (S256). The state and the PKCE verifier
  live in a short-lived (10 min) HttpOnly cookie signed with `ARCHSIM_SECRET_KEY`
  (HMAC-SHA256); the callback refuses a missing, expired, tampered or
  different state, and a stolen authorization code is useless without the verifier. After
  signing in the browser only ever lands on a path of this site (`next` cannot point
  elsewhere). Accounts are matched by the provider's stable id; a second provider is linked to
  an existing account only through an email both providers marked **verified**. Only a
  verified email in `ARCHSIM_ADMIN_EMAILS` makes a new account an owner of `default`. The
  provider's access token is discarded after reading the profile.
- **Sessions.** `__Host-archsim_session`: HttpOnly, Secure, SameSite=Lax, path `/`, no domain.
  It holds 256 random bits; the database stores only its SHA-256, so a database leak does not
  hand out live sessions. Sessions expire after `ARCHSIM_SESSION_DAYS` (30) and are revoked at
  once on every instance by signing out (or "sign out everywhere"). Disabled accounts are
  refused on their next request.
- **CSRF.** Every request that changes something with the session cookie must carry
  `X-ArchSim-CSRF`, an HMAC of the session derived from `ARCHSIM_SECRET_KEY`. Another site can
  make a browser send the cookie but cannot read the token (it is in `__Host-archsim_csrf`,
  readable only by this site's pages, and returned by `GET /auth/me`). SameSite=Lax is a second
  layer. Bearer-token requests need no CSRF token (browsers never add them on their own).
- **API tokens.** `asim_pat_` plus 256 random bits, shown once, stored as SHA-256. A token acts
  as its owner; `read` tokens are viewers everywhere; a token can be limited to one workspace
  and can expire. Tokens cannot create tokens, workspaces or invitations, or accept
  invitations, so a leaked token cannot widen its own access. Revocation is immediate.
- **Authorization.** Every project and run belongs to a workspace; access needs a membership
  (`viewer` < `editor` < `owner`). Someone without a role in a workspace gets **404** for
  anything in it, exactly like something that does not exist, so ids reveal nothing; a role too
  weak for an action gets 403. Opening a template only ever reuses your own unedited copy.
  Invitations are single-use links (256-bit tokens stored as hashes) that expire in at most 30
  days and can be bound to a verified email; a workspace always keeps an owner.
- **Abuse and cost.** Counters in the database (shared by every instance) limit runs per hour
  per workspace and per person, sign-in attempts per address, and token, workspace,
  invitation and calibration creation; over a limit the API answers **429** with
  `Retry-After`. Each workspace kind has a profile: runs at once (enforced by the queue across
  all workers, which also keeps workspaces fair), runs per hour, the largest run, the
  calibration replay's wall clock (20 s) and, for sandboxes, the number of projects.
- **Share links.** `/r/<slug>` pages are public to anyone with the link: the slug is 128
  random bits, and the page is a frozen copy of one run's report made when the link was
  created, without request traces and with telemetry addresses (a calibration's Prometheus URL)
  hidden. Editors make and revoke links; revoking deletes the copy and the page answers 404 at
  once (the web server never caches it), as do expired and unknown links. Pages are `noindex`,
  the public endpoint is limited per address (60 a minute) and needs no sign-in, and "Open a
  copy" needs editor access to a workspace of your own.
- **Outbound requests (SSRF).** `ARCHSIM_OUTBOUND` decides whether `POST /calibrate` may call a
  Prometheus server: `off` by default when hosted (recorded responses and OTLP traces still
  work), `allowlist` when `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS` is set, and always `off` for
  sandbox workspaces. With `allowlist`, the host is resolved once, and every address it resolves
  to must be public by the frozen engine's rules: Python's `ipaddress.is_global` (no loopback,
  private, link-local and metadata, CGNAT or unique-local address), and no multicast, reserved or
  unspecified address; an IPv4 address inside IPv6 (mapped or NAT64) is judged as the one it
  carries, and deprecated site-local IPv6 is refused too. The connection goes to that checked
  address (no second lookup an attacker could change), and TLS and the `Host` header still use
  the host name. An IP address written in the allowlist itself is trusted as written (for a
  Prometheus on a private network the operator chose). The API makes the call itself
  ([ADR-0046](docs/adr/0046-call-prometheus-with-apache-httpclient-5.md)): no redirects,
  retries, proxies, cookies, decompression or reused connections, one deadline from connecting
  to the answer's last byte, and caps on the answer's size, the length of a header line and the
  number of headers. A calibration's replay is simulated by the internal engine service, within
  the replay's limits; the service calls nothing, but it checks no caller, so only the API may
  reach it: Compose gives it a network that it shares with the API alone.
- **Client addresses.** The web server sends the visitor's address as `X-ArchSim-Client-IP`,
  taken from `X-Forwarded-For` at the depth `ARCHSIM_TRUSTED_HOPS` says (1 behind one reverse
  proxy; 0 sends nothing, since the header would be the client's own word). The API believes it
  only from `ARCHSIM_TRUSTED_PROXIES` (the web server's address or Compose service name);
  anyone else is limited by the address they connect from.
- **Response headers.** API answers carry `nosniff`, `DENY` framing, `no-referrer`,
  `Cross-Origin-Resource-Policy: same-origin`, a `default-src 'none'` Content-Security-Policy
  (except the interactive docs page, which loads Swagger UI from a CDN), `no-store` on
  identity endpoints and HSTS when `ARCHSIM_PUBLIC_URL` is https. The web app sends its own CSP
  (self only), framing and referrer headers.
- **Secrets.** `ARCHSIM_SECRET_KEY` (32+ characters, the same on every API instance) and the
  OAuth client secrets come from the environment only. Rotating the key signs everyone out of
  pending sign-ins and invalidates CSRF tokens (people reload); sessions themselves stay valid.

**What remains.** Resolving a host name is not covered by the per-query deadline (the
system's resolver timeout applies). A workspace owner can read every design in it. Provider
accounts are trusted for the emails they verify. Denial of service by a signed-in person is
bounded by the limits above, not prevented.

### Both modes

- **No code execution over the API.** Documents are data: they are parsed strictly, unknown
  fields refused (the API's document model, and the engine's Pydantic models with
  `extra="forbid"`), and component types must already be registered in the engine. There is no
  endpoint that evaluates expressions, templates or user code.
- **Plugins are local only.** Third-party component models are loaded from Python packages
  installed in the engine's environment (the engine service and the workers) via the
  `archsim.components` entry point group, and only when `ARCHSIM_ENABLE_PLUGINS=1`. Plugins run
  with those processes' privileges, so install only packages you trust. Plugins can never be
  uploaded through the API.
- **Bounded resource use.** Each request body is capped (`ARCHSIM_MAX_BODY_BYTES`, default
  5 MB). Documents are limited to 200 nodes, 1,000 edges and 50 scenarios. Every simulation is
  bounded by an expected-request limit (`ARCHSIM_MAX_REQUESTS`, default 2,000,000) checked
  before the run starts and a wall-clock limit (`ARCHSIM_MAX_WALL_SECONDS`, default 300 s)
  checked while it runs, then enforced 30 s later by killing the run's process. Retries are
  capped at 5 attempts per call and queues at 100,000 entries per pool. Each worker simulates at
  most `ARCHSIM_MAX_CONCURRENT_RUNS` runs at once (default: CPU count - 1), each in its own
  process; extra runs wait in the queue.
- **Several instances and workers.** API instances and `archsim-api worker` processes trust each
  other through the database: whoever can write to it can queue, cancel or change runs, so keep
  it reachable only by them. Simulation processes run with their worker's privileges and end
  with it: they exit when its pipe closes, and get SIGKILL on Linux (`PR_SET_PDEATHSIG`) or are
  killed by a Windows Job Object when it dies. In containers, run workers under an init
  (`init: true` in Compose) so killed processes are reaped. Hosts sharing a database need
  synchronised clocks; a host whose clock runs ahead can make other workers' leases look
  expired.
- **Outbound requests from calibration.** `POST /calibrate` queries the Prometheus URL a
  request names (besides sign-in's calls to GitHub and Google, the only outbound request the
  API makes). Without sign-in any host is allowed, for local use
  (`ARCHSIM_OUTBOUND=open`; `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS` still narrows it when set);
  hosted mode is `off` or `allowlist` as described above. Each query has one deadline
  (`ARCHSIM_PROMETHEUS_TIMEOUT_S`, 30 s, body included) and a size cap (`ARCHSIM_PROMETHEUS_MAX_BYTES`, 50 MiB); redirects are
  not followed, and errors never repeat what the server answered. The mapping is always text:
  the API never opens a file a request names. The replay run inside the request is bounded by
  `ARCHSIM_CALIBRATE_MAX_WALL_SECONDS` (60 s) and `ARCHSIM_MAX_REQUESTS`.
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
