---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0034. Keep the FastAPI API's HTTP contract in Spring MVC

## Context and Problem Statement

Stage 2 replaced the FastAPI API with archsim-app while the web app, the CLI, scripts and the
black-box suite kept calling it ([ADR-0019](0019-black-box-api-tests-against-any-base-url.md)).
Spring MVC answers differently by default: `/error` bodies with `timestamp`, `status` and `path`
for a missing route or method, a different 413, no security headers, controllers at the root.
How much of FastAPI's behaviour should the Java API reproduce, and where does that code live?

## Decision Drivers

- Clients keep working: what a client read from the Python API it reads from the Java one.
- One place for each cross-cutting rule, not repeated in every controller.
- Features don't know about the contract's framing: a service throws, the web layer answers.

## Considered Options

- Reproduce the contract in the `web` package: an exception handler, two servlet filters and a
  path prefix
- Spring's defaults, and change the clients
- RFC 9457 problem details (`application/problem+json`), and change the clients

## Decision Outcome

Chosen option: "reproduce the contract in the `web` package", because clients kept working
unchanged and each rule is written once.

- **Error bodies.** A refusal is `{"detail": {"message": "...", "issues": [...]}}`, `issues`
  being the design's validation issues as the model writes them (`ErrorBody`). A feature throws
  `ApiException` with a status, message and issues; `ApiExceptionHandler` writes the body.
- **What Spring refuses on its own** keeps Spring's status and headers and gets Starlette's body:
  a route that does not exist is `404 {"detail": "Not Found"}`, a method a route does not take
  is `405 {"detail": "Method Not Allowed"}` with `Allow: GET, HEAD`, and so on
  (`ApiExceptionHandler` extends `ResponseEntityExceptionHandler`). A failure is a logged `500`
  with Starlette's plain-text `Internal Server Error`. Errors the servlet container handles reach
  `ApiErrorController`, which answers them the same way, and `/error` itself is a route that does
  not exist. Tomcat decodes `%2F` in a path, as Starlette does.
- **Request bodies.** An endpoint reads its body as a record with the model's codec (`@ApiBody`),
  which reports what does not fit as pydantic does; the answer is FastAPI's
  `422 {"detail": [{"type", "loc", "msg", "input"}]}`, which the web app shows.
- **Content.** Answers are JSON whatever the request's `Accept` says, as FastAPI's are.
- **CORS** with Starlette's settings (any method and header, preflight cached 600 s) and its
  leniency: a request from an origin that is not listed is served without CORS headers rather
  than refused, because requests the web server passes on carry the site's origin.
- **Security headers** on every response (`SecurityHeadersFilter`): `nosniff`, `DENY` framing,
  `no-referrer`, same-origin resource policy, a permissions policy, a content-security policy
  except on the interactive docs, `no-store` on the private routes (`auth`, `tokens`,
  `workspaces`, `invitations`) and HSTS when `ARCHSIM_PUBLIC_URL` is `https`.
- **Body size** (`BodySizeFilter`), before anything reads the body: `413` over
  `ARCHSIM_MAX_BODY_BYTES`, `411` for a chunked body on `POST`, `PUT` or `PATCH`, with the error
  body.
- **Paths.** Every controller of `io.archsim.app` is served under `/api/v1` (`WebConfiguration`); the
  OpenAPI document and interactive docs are at `/api/v1/openapi.json` and `/api/v1/docs`.
- **JSON** field names are the Python API's camelCase; dates, numbers and `null`s are written as
  it writes them, endpoint by endpoint, and the black-box suite checks them. Numbers are compared
  by value, as the golden files compare them: where Python writes a whole number as `600` and
  Java as `600.0`, or the other way round, clients read the same number.

Where the Python API had a bug, the Java API does not copy it. It also differed from FastAPI in
24 accepted ways.
[archsim-app's README](../../archsim-app/README.md#differences-from-the-python-api) lists both.
Among the differences: a trailing slash was `404` where Starlette redirected (`307`);
a body that didn't fit was `422` before access was checked, where FastAPI answered `404` or `403`
first; numbers came back spelled as Java writes them (`5.0E-4` for `0.0005`). The web app, the
black-box suite and the Playwright tests passed with them.

### Consequences

- Good, because the web app, CLI and black-box suite ran against either API unchanged, apart
  from the accepted differences.
- Good, because a feature only throws `ApiException`; its controller has no error handling.
- Bad, because Spring's own error page and problem-details support go unused, and a new kind of
  framework exception needs a mapping in `ApiExceptionHandler` to keep the contract.

### Confirmation

`WebBehaviourTests` check the 404, 405, 411, 413 and 422 bodies, the headers, `Accept` and CORS;
`ServedApplicationTests` check the container's answers on a real Tomcat; the controller tests check
each body; the black-box suite runs against the Java API in CI, as it ran against the Python
one until stage 2 ([ADR-0019](0019-black-box-api-tests-against-any-base-url.md)).

## Pros and Cons of the Options

### Spring's defaults, and change the clients

- Good, because there is no contract code.
- Bad, because the web app and every script would change in the same release as the API, which
  stage 2 must avoid.

### RFC 9457 problem details

- Good, because it is a standard Spring supports.
- Bad, because it changes every error body clients read; it can be adopted in `/api/v2`.

## More Information

- [ADR-0011](0011-organise-application-code-by-feature.md): the `web` package is shared
  infrastructure, not a feature.
- [ADR-0019](0019-black-box-api-tests-against-any-base-url.md): the black-box suite.
