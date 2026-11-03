---
status: proposed
date: 2026-11-03
decision-makers: Majid Abarghooei (owner)
---

# 0037. Sign people in with plain Spring MVC, not Spring Security

## Context and Problem Statement

Slice 3 of stage 2 ports sign-in: GitHub and Google OAuth with PKCE, browser sessions, CSRF
tokens and personal API tokens. The [port's plan](../proposals/java-25-port.md) named Spring
Security's OAuth2 client for it, and [ADR-0007](0007-spring-boot-4.md) chose Spring Boot partly
because Spring Security's OAuth client, CSRF support and token authentication would cover today's
sign-in, with security code the framework's maintainers review. While both APIs share one
database, a person signed in through one must stay signed in through the other. That means the
Java API has to keep each of these exactly as the Python API has it:

- the session cookie `__Host-archsim_session`, stored as the SHA-256 of its secret in `sessions`;
- the CSRF token, an HMAC of the session's id under a key derived from `ARCHSIM_SECRET_KEY`, sent
  in `X-ArchSim-CSRF` and in the `__Host-archsim_csrf` cookie;
- the signed state cookie `__Host-archsim_oauth`, which carries the state and the PKCE verifier;
- the answers: `401` with `WWW-Authenticate: Bearer` and FastAPI's error body, `403` for a
  missing CSRF header, and redirects to `/signin?error=<reason>`.

Who is asking is decided per endpoint. Public endpoints (`/health`, `/examples`, `/components`)
need nothing, and an unknown path answers Starlette's `404` whoever asks.

How should the Java API sign people in?

## Decision Drivers

- Sessions, tokens and CSRF tokens one API makes work in the other while both run.
- The HTTP contract stays as it is ([ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md)):
  statuses, bodies, headers, cookies, and which error wins when several apply.
- Little security code of our own, and only standard primitives in it.
- Code an engineer can read in one sitting.

## Considered Options

- Spring Security, with its OAuth2 client, filter chain and CSRF filter, adapted to the contract
- Plain Spring MVC: argument resolvers for who is asking, and the OAuth code exchange on
  `RestClient`
- Plain Spring MVC, with Spring Security's OAuth2 client classes as a library (no filter chain)
- Spring Security's filter chain with filters of our own, and no OAuth2 client

## Decision Outcome

Chosen option: "plain Spring MVC", because each part of Spring Security that applies would have
to be replaced to keep the contract, and what is left is about 150 lines of OAuth over HTTP.

- **Who is asking** is a controller parameter. `Principal` (in `access`) comes from
  `PrincipalResolver`: the local user without sign-in; otherwise an API token from
  `Authorization: Bearer asim_pat_…`, else the session in the cookie, else `401`. A cookie
  request that changes something must carry the CSRF header (`403`). A `Person` parameter is a
  principal that is not an API token, for managing tokens and workspaces. It is checked before
  the body is read, so the errors come in the same order as in FastAPI. Public endpoints take no
  such parameter, so they need nothing.
- **Secrets** are made with `SecureRandom` (256 bits) and stored as their SHA-256. CSRF tokens
  and the state cookie use HMAC-SHA256, under keys derived from the secret key one per purpose.
  Comparisons use `MessageDigest.isEqual`. These are the Python API's formats exactly, and the
  tests check values the Python API made.
- **OAuth** (`signin`): the authorization code flow with PKCE `S256`. The state and verifier are
  kept in the signed cookie for ten minutes. The code is exchanged with the verifier
  (`client_secret_post`) on a `RestClient` with 10-second timeouts. The provider's access token
  reads the profile and is then dropped.
- `ARCHSIM_AUTH_MODE=oauth` without a secret key, a public URL or a provider stops the
  application from starting, with the Python API's messages.

### Consequences

- Good, because a person moves between the two APIs without signing in again, and the black-box
  suite's sign-in tests pass against both.
- Good, because the whole sign-in is in two packages (`access`, `signin`) of small classes, with
  no filter chain or framework configuration to find.
- Bad, because it is security code we maintain, where ADR-0007 counted on Spring's. It is kept
  small (the OAuth exchange is about 150 lines), uses only JDK primitives, and is tested against a
  stand-in GitHub that checks PKCE and the client's credentials. The owner should weigh this
  reversal of ADR-0007's expectation.
- Bad, because adding a provider means writing its profile mapping, as it does in Python today.

### Confirmation

`CsrfTests`, `SigningKeysTests` and `StateCookiesTests` check values the Python API produced.
`HostedPrincipalTests`, `SignInTests` and `TokensTests` run the application on PostgreSQL against
the stand-in GitHub. The black-box suite's `OAuthTests` and `TokensTests` run against both APIs.

## Pros and Cons of the Options

### Spring Security, adapted

- Good, because the OAuth2 client, PKCE and the CSRF filter are maintained by Spring.
- Bad, because a repository for authorization requests in a cookie, a security context read from
  `sessions`, a CSRF token repository with the HMAC tokens, entry points and failure handlers for
  each error answer, and a token filter would all be ours anyway.
- Bad, because the filter chain runs before Spring MVC. Unknown paths, `405`s and body errors
  would be decided in a different order than FastAPI decides them.

### Spring Security's OAuth2 client classes as a library

`OAuth2AuthorizationRequest` with `OAuth2AuthorizationRequestCustomizers.withPkce()` builds the
sign-in page's address, and `RestClientAuthorizationCodeTokenResponseClient` exchanges the code,
without a filter chain.

- Good, because the protocol's two steps (about 60 of the 150 lines) would be Spring's.
- Bad, because the customizer makes its own verifier, which then has to be taken from the request's
  attributes into the state cookie that both APIs read, and the exchange takes a
  `ClientRegistration` built per provider for one call.
- Bad, because reading the account, the larger part (GitHub's `/user` and `/user/emails`, the
  verified email rule), is not covered either way.

### The filter chain with our own filters

- Good, because `@PreAuthorize` and the security context would be available.
- Bad, because the filters would do what the resolvers do, with the ordering problem of the
  previous option, and the endpoints need nothing that the context would add.

## More Information

- [ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md): the HTTP contract this keeps.
- This departs from the port's plan, which named Spring Security's OAuth2 client for sign-in, and
  from what [ADR-0007](0007-spring-boot-4.md) expected of Spring Security.
