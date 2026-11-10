---
status: accepted
date: 2026-11-07
decision-makers: Majid Abarghooei (owner)
---

# 0046. Call Prometheus with Apache HttpClient 5, pinned to the address it checks

## Context and Problem Statement

With calibration in Java ([ADR-0045](0045-replay-calibrations-through-an-interim-simulate.md)),
the Java API calls the Prometheus server that a calibration request names: a server-side
request to an address a caller chooses. The frozen engine's client guards it. It looks the host
up once per query and connects to that address while TLS is checked against the name, so a name
cannot answer one address for the check and another for the call. On a hosted server every
address must be public. It follows no redirects, holds one deadline over connecting, the headers
and the body, and caps the answer's size. Which HTTP client lets Java do the same?

## Decision Drivers

- The address checked is the address connected to, while the `Host` header and TLS use the name.
- Every bound can be set: the deadline, the answer's size, header lines and count, redirects,
  retries, decompression, proxies.
- A library Spring Boot already manages, at the version its BOM sets.
- As little security-critical code of our own as possible.

## Considered Options

- Apache HttpClient 5
- The JDK's `java.net.http.HttpClient`, through Spring's `RestClient`
- Reactor Netty, through Spring's `WebClient`
- A client of our own on sockets

## Decision Outcome

Chosen option: "Apache HttpClient 5", at the version Spring Boot's BOM manages (5.6.5 with Boot
4.1.2), with no version of our own, because it is the only managed client that connects to an
address given with the request while it names the host for `Host` and TLS.

- `HttpPrometheus` looks the host up once per query, checks every address when the server is
  hosted (`PublicAddresses`, Python's `is_public`), and sends the request to an `HttpHost` that
  carries the first address and the name. HttpClient connects to that address without a lookup;
  the resolver it is given refuses every name, so a request without its address fails rather
  than resolving again. The request takes its authority from that target and its path as
  written: HttpClient would read a path that starts with `//` as another authority, and take
  the `Host` header and the TLS name from it.
- TLS is checked against the name, by the JDK's endpoint identification and HttpClient's own
  verifier. Names beyond ASCII are looked up and sent in their IDNA form, as Python sends them.
- `PinnedClients` turns off what HttpClient does by default and Python does not: redirects,
  retries, decompression, cookies, connection reuse, the user agent. It reads answers with the
  strict parser (HttpClient's own skips what comes before a status line) and Python's limits of
  64 KiB per header line and 100 headers, which the body's size cap does not count. It asks for
  the body as it is (`Accept-Encoding: identity`), as Python's `http.client` does.
- One deadline: a watchdog cancels the request when it passes, closing its connection, so a
  server that trickles bytes cannot hold a query longer.

**Status (10 Nov 2026):** the engine service and the API's client for it are deleted
([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)); the JDK client still serves the
sign-in calls.

### Consequences

- Good, because the check and the connection read one lookup, and HttpClient cannot look up
  again.
- Good, because each bound is set in one place and checked by a test.
- Bad, because the application carries one more library (HttpClient 5 and its two core jars,
  about 2.3 MB), for one feature.
- Bad, because HttpClient's defaults are lenient where Python's client is strict, and it reads
  a request's host from its target when it can; a later upgrade must keep `PinnedClients`'s
  settings and the request's authority, which the tests pin.

### Confirmation

`HttpPrometheusTests` port the Python engine's client tests (deadline, size cap, clean errors,
allowlist). `HttpPrometheusOutboundTests` port its outbound tests and add DNS rebinding, every
redirect status, special addresses (IPv6, IPv4-mapped, NAT64, link-local, cloud metadata,
loopback) and TLS checked against the name at the pinned address. The address and URL rules
were checked against Python 3.12 on 320,000 generated cases, without a difference.

## Pros and Cons of the Options

### The JDK's `java.net.http.HttpClient`

- Good, because it needs no library, and the API already uses it for the engine service.
- Bad, because it looks names up itself, with no resolver to give it: connecting to an address
  checked beforehand means sending to the address and turning off TLS's name check, then
  checking the certificate by hand.
- Bad, because it bounds neither header lines nor their count.

### Reactor Netty

- Good, because it has a resolver hook and fine-grained limits.
- Bad, because it brings a reactive stack and an event loop for a few queries a calibration makes
  one after another.

### A client of our own on sockets

- Good, because every byte would be ours to bound.
- Bad, because HTTP parsing, chunked bodies and TLS would be security-critical code we maintain.

## More Information

- The frozen engine's `calibration/prometheus.py` (`HttpPrometheus`, `is_public`,
  `host_allowed`) and its `test_calibration_outbound.py` and `test_calibration_hardening.py`.
- [ADR-0045](0045-replay-calibrations-through-an-interim-simulate.md): why the Java API calls
  Prometheus now.
