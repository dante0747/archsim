---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0107. Without sign-in, answer only this machine's own clients

## Context and Problem Statement

Without sign-in (`ARCHSIM_AUTH_MODE=none`, the tool's default), whoever reaches the API owns
everything in it. The API listens on `127.0.0.1`, and compose binds the web app's port there too,
so only this machine reaches them. A web page the developer opens runs on this machine too,
though. Nothing stopped one:
CSRF protection is off without sign-in, a foreign `Origin` was served, and a body was read as
JSON whatever its type. A page could point its own name at `127.0.0.1` (DNS rebinding) and then
read and change every design, at the API's port or through the web app's proxy, as if it were the
web app. A page of another site could also post a body without a type, which needs no preflight,
to change state or make `/calibrate` query hosts on the developer's network.

How should the API tell its own clients from a page of another site, without sign-in?

## Decision Drivers

- The tool's own clients keep working with nothing to set up: the web app, which calls the API
  from its server, scripts and `curl`, and the API's docs page. The CLI and the MCP server run
  the engine in their own process and do not call the API.
- A page of another site can neither read nor change anything, at the API's port or through the
  web app.
- No credential in the local mode, which has no sign-in to give one.
- The hosted mode stays as it is: it has sessions, CSRF protection and CORS already.

## Considered Options

- Check the host, the page's origin for a change, and the body's type, in one filter.
- Spring Security's host allow-list (`StrictHttpFirewall#setAllowedHostnames`), plus checks of
  the origin and the type.
- A secret the local API prints at start, sent with every request.
- Leave it to the developer, and document the risk.

## Decision Outcome

Chosen option: "check the host, the page's origin for a change, and the body's type, in one
filter", because each check closes one way in, together they ask nothing of the tool's own
clients, and one filter answers each refusal in the API's error shape, naming the setting that
changes it. A filter does it, only without sign-in (`LocalRequestFilter`).

- **A request must be addressed to this machine.** That means `localhost`, an address in
  `127.0.0.0/8` written in dotted form, `[::1]`, or a name `ARCHSIM_ALLOWED_HOSTS` lists. Compose
  lists `api`, the name the web container calls it by. Any other host gets `403`: after
  rebinding, the browser still sends the page's own name.
- **The web app's proxy passes on what the browser said.** It sends the host the browser
  addressed (`X-ArchSim-Client-Host`) and the page's origin (`X-ArchSim-Client-Origin`), always
  from the browser's own request, and the filter checks them as it checks a browser that calls
  the API itself. A rebound page then gets the same `403` through the web app.
- **A change from a page of another site is refused.** That is a `POST`, `PUT`, `PATCH` or
  `DELETE` whose origin, direct or passed on, is neither the page the request was addressed to
  (the same host and port) nor one `ARCHSIM_CORS_ORIGINS` lists, read as the CORS filter reads
  it. It gets `403`. A listed `*` lets every page read, never change. A request without an origin
  comes from no page and passes: a script, `curl`, or the proxy's own call for one. Browsers send
  an origin with every cross-site change, `null` included, which is refused.
- **A body must be JSON.** A body that is not `application/json` or an `application/*+json`
  type gets `415`, with `Accept: application/json`. That includes a body with no type, the one
  cross-site body a page can send without a preflight. A page can send JSON to another site only
  after a CORS preflight, which the API never grants a foreign origin.

The refusals are `403`, not the `400` a host allow-list in the firewall answers. The request is
understood, and is refused for where it comes from. The firewall's `400` would also say only
"Bad Request", where the filter names `ARCHSIM_ALLOWED_HOSTS`.

### Consequences

- Good, because a page of another site can neither read nor change anything, nor aim `/calibrate`
  at the developer's network, at the API's port or through the web app.
- Good, because the tool's own clients work as before, with nothing to configure for the usual
  setups.
- Bad, because pages of the origins `ARCHSIM_CORS_ORIGINS` lists, by default
  `http://localhost:3000`, the port many development servers use, can still read and change
  everything, as the setting intends.
- Bad, because serving the local mode under another name (a reverse proxy, another container name,
  a name on the local network) needs `ARCHSIM_ALLOWED_HOSTS`. Until it is set, requests get `403`
  with a message that names the setting.
- Bad, because a client that sends a body without a JSON type now gets `415`.

### Confirmation

`LocalRequestFilterTests` checks each rule with the API's error bodies:
- a host, direct or passed on, that is neither this machine's nor listed;
- a change from another site, from `null`, from the page addressed and from a listed origin
  written in capitals;
- bodies with no type, text types and `+json` types;
- that with sign-in everything passes.

`AllowedHostsTests`, the binding tests and `ComposeFileTests` pin the names.
`LocalRequestsTests` (black-box, without sign-in) checks a host that is neither this machine's
nor listed, and one the setting lists. It also checks a host passed on for a page, a change from
another site and from `null`, and bodies with no type and of another type. The web app's
`proxy.test.ts` checks that the proxy passes on the browser's host and origin, never the
browser's own headers of those names.

## Pros and Cons of the Options

### Spring Security's host allow-list

- Good, because the application already declares the firewall, and it refuses a host before any
  filter runs.
- Bad, because it answers `400` with "Bad Request" and no reason. The origin and type checks, and
  the host the proxy passes on, would still need a filter, so the rule would live in two places.

### A secret the local API prints at start

- Good, because only a client that has the secret is served, whatever its address or origin.
- Bad, because the web app's server, scripts and the docs page must each be given it, and the
  local mode exists to need nothing.

### Leave it to the developer

- Good, because nothing changes.
- Bad, because the tool's default mode stays open to any page its user visits.

## More Information

- [SECURITY.md](../../SECURITY.md) describes the local mode's protections.
- [ADR-0065](0065-move-sign-in-to-spring-security-after-the-port.md): sign-in, CSRF protection
  and CORS in the hosted mode, which this leaves as they are.
