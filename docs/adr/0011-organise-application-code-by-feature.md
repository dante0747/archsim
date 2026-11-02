---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0011. Organise the application's code by feature

## Context and Problem Statement

`archsim-app` serves the HTTP API and runs the workers. Most of what it does is:

- storing and serving projects, runs, users, workspaces and tokens;
- running the queue;
- handing designs to the engine.

The domain logic (validation, simulation, analysis, sizing, cost) lives in the framework-free
`archsim-model` and `archsim-engine` modules ([ADR-0012](0012-separate-model-and-engine-modules.md)).

How should the application's own code be organised?

## Considered Options

- By feature: one package per feature, holding its controller, service and repository classes
- Hexagonal (ports and adapters): a Spring-free core with web and persistence adapters behind
  interfaces
- By layer: `controllers/`, `services/` and `repositories/` at the top

## Decision Outcome

Chosen option: "By feature", because it's the mainstream way to organise a Spring application, and
everything about one feature sits in one package.

A hexagonal core would mostly wrap storage and the queue in interfaces and mapping code. The
logic that benefits from being free of the framework already is, in the model and engine modules.

```
io.archsim.app
  projects/   ProjectController, ProjectService, ProjectRepository
  runs/       RunController, RunService, RunQueue, RunRepository
  auth/
  tokens/
  shared/     what several features use
```

### Consequences

- Good, because a reader finds a feature's whole path, from HTTP to SQL, in one package.
- Good, because a feature can be changed or removed with little effect on the others.
- Bad, because the separation is a convention as well as a structure, so without checks a feature
  could reach into another's repository. ArchUnit rules guard the main boundaries.
- Bad, because if the application later gains substantial domain logic of its own, this decision
  should be revisited.

### Confirmation

ArchUnit tests in `archsim-app` fail the build when a controller uses a repository directly. Module
dependencies keep Spring and JDBC out of `archsim-model` and `archsim-engine`.

## More Information

- [ADR-0007](0007-spring-boot-4.md) (Spring Boot) and [ADR-0008](0008-jdbcclient-and-flyway.md)
  (repositories)
- ArchUnit: <https://www.archunit.org/>
