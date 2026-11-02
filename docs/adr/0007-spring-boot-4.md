---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0007. Use Spring Boot 4 for the application

## Context and Problem Statement

The Java application replaces the FastAPI service and keeps its contract with the web app. It
covers:

- **The API:** the 69 endpoints under `/api/v1`, with the same JSON and cookies.
- **Sign-in:** GitHub and Google through OAuth 2.0, plus CSRF protection.
- **API tokens:** hashed, in a format that stays unchanged.
- **Protection:** rate limits and the block on outbound calls.

It also runs the simulation workers ([ADR-0014](0014-one-deployable-application-with-roles.md)).

Which framework should it use?

## Considered Options

- Spring Boot 4 (Spring Framework 7, Jackson 3)
- Quarkus

## Decision Outcome

Chosen option: "Spring Boot 4", because:

- it is the most common choice for Java services and has the most documentation, which matters for
  a codebase meant to be a reference;
- Spring Security's OAuth 2.0 client, CSRF support and token authentication cover today's sign-in
  behaviour with standard components;
- springdoc can generate the OpenAPI document that is checked against the web app's client.

Request handling uses virtual threads.

### Consequences

- Good, because the security-sensitive parts (sign-in, sessions, CSRF) use widely reviewed
  framework code instead of hand-written code.
- Good, because most Java developers can find their way around a Spring Boot application.
- Bad, because start-up is slower and memory use higher than Quarkus, and native images are
  harder. That matters little for a long-running service.
- Bad, because the application code depends on Spring's annotations and conventions. The design
  model and the engine stay free of Spring ([ADR-0012](0012-separate-model-and-engine-modules.md)),
  so the domain logic doesn't.

### Confirmation

Every API test is written as an HTTP test that can run against any base URL. That suite, and the
Playwright end-to-end tests, must pass against the Java API before the FastAPI code is removed
(stage 2 of [ADR-0005](0005-port-to-java-25-in-stages.md)).

## More Information

- [The port proposal](../proposals/java-25-port.md), stage 2
- Data access: [ADR-0008](0008-jdbcclient-and-flyway.md). Code layout:
  [ADR-0011](0011-organise-application-code-by-feature.md).
