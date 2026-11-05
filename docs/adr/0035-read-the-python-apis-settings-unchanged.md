---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0035. Read the Python API's `ARCHSIM_*` settings unchanged

## Context and Problem Statement

The Python API is configured by `ARCHSIM_*` environment variables (`ARCHSIM_DATABASE_URL`,
`ARCHSIM_CORS_ORIGINS`, `ARCHSIM_MAX_BODY_BYTES`, …), set in `docker-compose.yml`, its `.env` file
and deployment manifests. Stage 2's switch should change the image a service runs, not its
configuration. The values are written for Python: the database URL is a SQLAlchemy URL
(`postgresql+psycopg://user:password@host:5432/archsim`) and lists are JSON arrays. How does
archsim-app take its settings?

## Decision Drivers

- The switch in compose changes the image and command, not the environment.
- One typed, validated place for the settings, not `@Value` strings spread over the code.
- Spring Boot's own settings (`server.port`, `spring.datasource.hikari.*`) stay available.

## Considered Options

- One `@ConfigurationProperties("archsim")` record, bound from `ARCHSIM_*` by Spring Boot's
  relaxed binding, with converters for the Python formats
- New `SPRING_*` / `archsim.*` names, and change compose and the deployments
- Read `System.getenv` in a hand-written settings class

## Decision Outcome

Chosen option: "one `@ConfigurationProperties` record bound from `ARCHSIM_*`", because the
existing environment configures the Java API as it is.

- `ArchsimProperties` is a validated record under the prefix `archsim`, so `ARCHSIM_MAX_BODY_BYTES`
  binds to `maxBodyBytes` with no mapping code. Its bounds are the Python API's, and so are its
  defaults, except two: the database defaults to PostgreSQL on this machine, as SQLite is gone
  ([ADR-0016](0016-drop-sqlite-development-mode.md)), and the examples are looked for from the
  working directory up.
- `DatabaseUrl` turns the SQLAlchemy URL (any `postgresql+driver` scheme, credentials
  percent-decoded, query kept) into a JDBC URL and credentials; a JDBC URL is used as it is.
  `DataSourceConfiguration` builds Hikari from it, so Spring's `spring.datasource.url` is not used.
- A list the Python API reads as JSON, `ARCHSIM_CORS_ORIGINS='["http://localhost:3000"]'`, is a
  small value type (`CorsOrigins`) the binder builds with its `valueOf(String)`. A converter from
  `String` to `List<String>` would not do: Spring Boot splits a list setting on commas first, so
  the JSON array would come apart. The lists Python reads comma-separated (`NoDecode`) can stay
  `List<String>`.
- The role is the first argument (`archsim-app api`, `archsim-app migrate`), as the Python
  entry points are ([ADR-0014](0014-one-deployable-application-with-roles.md)).

### Consequences

- Good, because the environment compose and deployments give the API carries over unchanged.
- Bad, because the Python API also read a `.env` file in its working directory by itself
  (pydantic-settings); Spring Boot does not. Compose's own `.env` and `env_file` still apply.
- Good, because a wrong value fails at start with the setting's name, not at first use.
- Bad, because the SQLAlchemy URL format outlives the Python API. Accepting only `jdbc:` URLs
  must wait until stage 4: until then the Python workers read the same `ARCHSIM_DATABASE_URL`
  (compose gives both the same one).

### Confirmation

`ArchsimPropertiesBindingTests` bind `ARCHSIM_*` variables in the Python formats, and
`DatabaseUrlTests` and `CorsOriginsTests` parse them; the compose switch (stage 2's last slice) keeps the environment as it is.

## More Information

- The Python API's settings, as they were when it was deleted:
  `git show 5a557ca^:services/api/src/archsim_api/config.py`. The file now holds only the
  workers' settings.
