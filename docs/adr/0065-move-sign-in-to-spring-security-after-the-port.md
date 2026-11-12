---
status: accepted
date: 2026-11-12
decision-makers: Majid Abarghooei (owner)
---

# 0065. Move sign-in to Spring Security after the port, keeping its HTTP contract

## Context and Problem Statement

[ADR-0037](0037-sign-in-without-spring-security.md) signed people in with plain Spring MVC. While
the Python and Java APIs shared one database, each had to accept the other's session cookies,
CSRF tokens and state cookies, and answer with FastAPI's errors in FastAPI's order. Spring
Security would have had every part replaced to keep that. The owner accepted it on condition that
it is weighed again at stage 6 of the [port](../proposals/java-25-port.md), once the contract can
change. The Python API is now gone. Should sign-in move to Spring Security, and if so, how?

The code today: `access` (29 files, 940 lines of code) and `signin` (28 files, 1,020), about
1,960 lines, of which roughly 700 do sign-in itself. The rest is workspace roles, client
addresses, trusted proxies and accounts, which stay whatever is decided. `tokens` (210) manages
API tokens. `web` holds the security headers, CORS and the body-size limit.

## What must hold

The contract is now the web app and the people who hold API tokens, not a second API. The
black-box suite (`archsim-api-tests`) checks it.

- **API tokens:** `Authorization: Bearer asim_pat_…`, stored as their SHA-256, each read-only or
  not and maybe bound to a workspace. Tokens in use must keep working.
- **Browser sessions:** the `__Host-archsim_session` cookie (`HttpOnly`, `Secure`,
  `SameSite=Lax`) and the `sessions` table, which stores the secret's SHA-256, its expiry and
  where it was used. Signing out ends one session or all of them.
- **CSRF on cookie requests that change something:** the web app reads `__Host-archsim_csrf`
  (readable, `SameSite=Strict`) and sends it in `X-ArchSim-CSRF` (`apps/web/src/lib/session.ts`). An
  API token needs none.
- **Sign-in with GitHub or Google:** authorization code with PKCE `S256`, a `next` path checked
  to stay on the site, `/signin?error=<reason>` on failure, and GitHub's rule that the account's
  email is its verified primary one.
- **Without `ARCHSIM_AUTH_MODE=oauth`:** everyone is the local user, and nothing asks to sign in.
- **Answers:** `401` with `WWW-Authenticate: Bearer` and `{"detail": …}`, which the web app reads,
  and `403` for a missing or wrong CSRF header. Public endpoints need nothing and ignore an
  `Authorization` header. An unknown path is `404` whoever asks.

**Status (12 Nov 2026), API tokens:** a wrong API token is refused wherever it is sent, so for
a request that carries one, two sentences of "Answers" no longer hold: that public endpoints
ignore an `Authorization` header, and that an unknown path is `404` whoever asks. Spring
Security's bearer token filter answers a revoked, expired, mistyped or malformed `Bearer` token
with the `401` above (`WWW-Authenticate: Bearer`, the same `{"detail": …}`) before the request
reaches an endpoint, so a public endpoint and an unknown path answer it `401` too. A request with
no token, or with credentials of another kind (`Basic …`), still reaches the endpoint: public
endpoints need nothing, and an unknown path is `404`. Decided in slice 2 under the owner's
delegation, because the rule that a public endpoint ignores a token came from matching FastAPI,
which no longer binds; a token's holder is better told at once that it is wrong than treated as
anonymous, as GitHub's API does. The black-box suite's `TokensTests` checks both. With sign-in,
the resource server also answers its metadata (RFC 9728) at
`/.well-known/oauth-protected-resource/api/v1`, on the API's own address: it names the API and
the header as the one way to send a token.

## Decision Drivers

- Security code maintained and reviewed by Spring, where it fits: ADR-0007 chose Spring Boot
  partly for it.
- A reference for engineers: a Spring Boot app that signs people in is expected to use Spring
  Security, and code that doesn't is read with suspicion.
- The contract above, with the black-box suite as its check; tokens keep working.
- Each step small, test-first and releasable, as the port's stages were.

## Considered Options

- Stay with plain Spring MVC (ADR-0037 as it is)
- Spring Security for the protocol only: its OAuth2 client classes, no filter chain (ADR-0037's
  third option)
- Spring Security's filter chain for authentication, CSRF and OAuth2 login, authorizing per
  endpoint as today

## Decision Outcome

Chosen option: "the filter chain for authentication, CSRF and OAuth2 login", recommended as its
own short track after stage 6. ADR-0037's main reason, the second API, is gone. What is left to
write ourselves is small adapters to the tables and cookies we keep, and Spring then owns the
OAuth2 protocol, PKCE, the CSRF filter and the request firewall.

How, keeping the contract:

- **Authenticate in filters, authorize per endpoint.** `authorizeHttpRequests` permits every
  request. The filters only put who is asking in the `SecurityContext`, and a controller
  parameter (`Principal`, `Person`, read from the context) answers `401` when it needs someone
  and finds no one. So public endpoints, unknown paths (`404`) and body errors keep their order.
- **API tokens:** the resource server's opaque-token support, with an `OpaqueTokenIntrospector`
  over the `tokens` table, applied only to requests that carry `Authorization`. An
  `AuthenticationEntryPoint` writes the `401` the web app reads.
- **Sessions:** a `SecurityContextRepository` over the `sessions` table. It keeps the hashed
  secret, the expiry and "sign out everywhere". It replaces neither the table nor the cookie, and
  Spring Session is not used: it stores session ids as they are.
- **CSRF:** Spring's `CsrfFilter` with `CookieCsrfTokenRepository` (readable cookie, named
  `__Host-archsim_csrf`, header `X-ArchSim-CSRF`) and the plain `CsrfTokenRequestAttributeHandler`,
  so the header carries the cookie's value as the web app sends it (the default handler's masked
  tokens are for pages that render the token), required only of requests a session cookie
  authenticates. The HMAC token (`Csrf`, `SigningKeys`' CSRF key) goes. The web app's code
  doesn't change, and a signed-in browser gets a new token at its next `/auth/me`.
- **OAuth2 login:** `oauth2Login` with GitHub and Google registrations, PKCE for both, and an
  `AuthorizationRequestRepository` that keeps the request in the signed `__Host-archsim_oauth`
  cookie, so any instance can finish a sign-in another started. A `DefaultOAuth2UserService`
  extension holds GitHub's verified-email rule. Success and failure handlers start the session
  and redirect to `next` or to `/signin?error=<reason>`. `OAuthClient` (the code exchange)
  goes.
- **Security headers:** Spring Security's `headers()` writes the ones `SecurityHeadersFilter`
  writes today, which then goes.
- **Local mode:** an authentication filter that gives every request the local user, in place of
  the token and session filters.

Plan, each slice test-first with the black-box suite unchanged unless the slice says otherwise:

1. `spring-boot-starter-security` with a chain that permits everything and authenticates no one;
   the resolvers still decide. The security headers move into the chain. Nothing in the suite
   changes.
2. API tokens through the introspector and the entry point; `Principal` read from the context
   for tokens.
3. Sessions through the context repository; `Principal` read from the context for sessions; the
   resolvers keep only workspace roles.
4. CSRF through `CsrfFilter`. The contract changes here: the token's format. The suite checks
   CSRF by behaviour (a change without the header is refused), so it should pass as it is; the
   app's `CsrfTests`, which check the HMAC tokens the Python API made, go with that code.
5. OAuth2 login. The suite's `OAuthTests` run against the stand-in GitHub, which must speak what
   Spring's client sends (`client_secret_basic` by default; set `client_secret_post` as today).
6. Delete what is left of the hand-written sign-in, and update ADR-0037 to `superseded by
   ADR-0065`, with the app's README and `docs/api.md`.

### Consequences

- Good, because the OAuth2 protocol, PKCE, CSRF and the request firewall (encoded slashes, path
  traversal) are Spring's code, kept up to date with Spring Boot's.
- Good, because a Spring engineer finds sign-in where they look for it, and workspace roles
  could later use method security.
- Good, because tokens and sessions stay where they are, as SHA-256 hashes, and nobody loses a
  token.
- Bad, because it is about six slices of security work on code that works and is tested: a
  week or two of sessions, with its own reviews.
- Bad, because some adapters stay ours (the context repository, the introspector, the signed
  state cookie, GitHub's email rule, the entry point), about a third of today's sign-in code.
- Bad, because the CSRF token's format changes, and anyone signed in when slice 4 ships gets a
  new token, which the web app fetches by itself.
- Bad, because Spring Security's defaults (sessions, redirects to a login page, `403` pages) must
  each be switched off, or they change answers the suite checks.

### Confirmation

The black-box suite (`OAuthTests`, `TokensTests` and the rest) passes after each slice, against
the same stand-in GitHub, and the app's `HostedPrincipalTests` and `SignInTests` keep their
checks; `CsrfTests` and `StateCookiesTests` change with the code they test. An ArchUnit rule keeps
controllers away from `SecurityContextHolder`: they read who is asking from a parameter.

## Pros and Cons of the Options

### Stay with plain Spring MVC

- Good, because nothing changes, and the code is small, uses only JDK primitives and is tested
  against a stand-in provider.
- Bad, because the OAuth2 exchange, PKCE, the CSRF scheme and their upkeep stay ours, in a
  project whose reason for Spring Boot included Spring Security.
- Bad, because it reads as hand-rolled security to the engineers this codebase is meant to
  teach.

### Spring Security's OAuth2 client classes only

- Good, because the protocol's two steps would be Spring's, with no filter chain to tune.
- Bad, because it saves about 100 lines and leaves sessions, tokens and CSRF hand-written: most of
  the cost, little of the gain.

## More Information

- [ADR-0037](0037-sign-in-without-spring-security.md): the decision this would supersede.
- [ADR-0007](0007-spring-boot-4.md): Spring Boot, chosen partly for Spring Security.
- [ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md): the HTTP contract kept in
  Spring MVC.
- Not decided here: whether this track runs before or after the repository is published.
