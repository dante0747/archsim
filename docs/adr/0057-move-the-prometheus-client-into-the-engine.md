---
status: proposed
date: 2026-11-10
decision-makers: Majid Abarghooei (owner)
---

# 0057. Move the Prometheus client into the engine, for the app and the CLI

## Context and Problem Statement

`archsim calibrate prometheus --url` queries a Prometheus server, as the Python CLI's
`HttpPrometheus(url)` did. The Java client that does it lives in `archsim-app`
([ADR-0046](0046-call-prometheus-with-apache-httpclient-5.md)): `HttpPrometheus` and the classes
that pin each query to the address it checks, bound it, and word its failures as Python did.
That is about 1,700 lines, and 1,280 of tests. Of it, only the factory that applies the API's
outbound policy needs Spring. The CLI cannot depend on the app. Where does the client live, so
both use it?

## Decision Drivers

- The address pinning, the bounds and Python's wording are security-sensitive and tested at
  length; they should be written once.
- The CLI ships the engine, not the app.
- The engine stays a library without Spring or JDBC
  ([ADR-0012](0012-separate-model-and-engine-modules.md)).

## Considered Options

- Move the Spring-free client into `archsim-engine`, beside `PrometheusClient` and
  `RecordedPrometheus`; the app keeps its policy
- A client of the CLI's own over the JDK's `HttpClient`
- A module of its own, `archsim-prometheus`

## Decision Outcome

Chosen option: move it into the engine, because the client is written once with its tests, and the
engine already holds the interface it implements and the calibrations that call it.

- `io.archsim.engine.calibration.prometheus.http` holds the client. Public: `HttpPrometheus`
  (`of(PrometheusServer)`), `PrometheusServer` (the URL, the deadline, the size cap, the
  allowlist and whether every address must be public, with Python's default deadline and cap)
  and `PrometheusUrls` (whether a URL names an HTTP server, and whether an allowlist allows it,
  which the app's policy asks). The rest stays package-private.
- The app's `OutboundPolicy.serverFor(url)` builds a `PrometheusServer` from its outbound settings,
  refusing in the Python API's words, as `PrometheusServer.allowedBy` did: a hosted server calls
  only public addresses, and only listed hosts under an allowlist.
- The CLI runs on its user's own machine, for the server its user names, as the Python CLI's
  `HttpPrometheus(url)` did: the open policy, any host at any address (a Prometheus on
  `localhost` or a private network included), with Python's default deadline (30 s) and size cap
  (50 MiB). It has no allowlist: the Python CLI had none, and the API's `ARCHSIM_*` settings are
  the server's, not the CLI's. The address it checks is still the one it connects to, and it still
  follows no redirect.
- The engine depends on Apache HttpClient 5 at the version Spring Boot manages (5.6.5 with
  Boot 4.1.2), pinned in the catalog as SnakeYAML is
  ([ADR-0043](0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), so the engine
  and the CLI are built and tested against what the app runs. The app drops its own dependency
  on it. `checkBootManagedVersions`, part of the app's `check`, fails the build when a library
  pinned this way (HttpClient 5, SnakeYAML) is not at the version Boot's BOM manages, so an
  upgrade of Boot cannot leave the CLI on one version and the app on another unnoticed.
- This client is the engine's only HTTP client, and only a calibration from a live server uses
  it; an ArchUnit rule keeps HTTP clients out of the rest of the engine.
- HttpClient 5.6.5 brings SLF4J's API (1.7.36, from its own parent, not Boot's 2.x) into the
  engine, and so into the CLI, which has no SLF4J binding: SLF4J would print its warning about
  the missing binding on standard error at the first query, a line ADR-0055 does not allow. The
  CLI pins SLF4J at the version Boot manages and adds its no-op binding at run time, and its
  tests check that standard error holds nothing else.

### Consequences

- Good, because the pinning, the bounds and the wording are written and tested once, for both.
- Good, because the app's calibration package keeps only what is the API's: the endpoint, the
  policy, the replay pool.
- Bad, because the engine gains a dependency, whose version has to follow the one Boot manages:
  by hand, though the build says when.

### Confirmation

The client's tests moved with it (`HttpPrometheusTests`, `HttpPrometheusOutboundTests`,
`PrometheusUrlTests`, `PublicAddressesTests`), unchanged but for their package, with the same
tests (201 run, the same before the move and after); `OutboundPolicyTests` checks the app's
policy, and `archsim calibrate prometheus --url`'s tests (next) the CLI's open one. The build's
`checkBootManagedVersions` holds the pinned versions to Boot's, and `ArchitectureTests` keeps
Spring out of the engine and HTTP clients in this package.

## Pros and Cons of the Options

### Move it into the engine

- Good, because nothing is written twice.
- Bad, because the engine gains an HTTP library.

### A client of the CLI's own

- Good, because the engine gains no dependency.
- Bad, because the failures' wording and the bounds would be written twice and drift apart.

### A module of its own

- Good, because the engine gains no dependency.
- Bad, because a module for one client is more build for little gain: the engine is what both
  ship.

## More Information

- [ADR-0046](0046-call-prometheus-with-apache-httpclient-5.md): the client itself, which this
  moves.
