---
status: accepted
date: 2026-11-11
decision-makers: Majid Abarghooei (owner)
---

# 0060. Run the archsim-check action's CLI in an image the job builds

## Context and Problem Statement

The `archsim-check` action (`.github/actions/archsim-check`) is ArchSim's SLO gate for pull
requests: it checks a design against its objectives and a baseline at a git ref, posts one sticky
comment and fails the job as asked. It set up uv and ran the Python CLI from the ArchSim checkout
GitHub downloads with the action. Stage 5 of the [Java port](../proposals/java-25-port.md) deletes
the Python engine, and its plan has the action run the Java CLI from a container image. The
repository is private and nothing of it is published: no image in a registry. The check reads a
baseline through `git` (`--baseline-ref`). Where is the image built, and how?

## Decision Drivers

- Nothing is published: the job that runs the action builds what it runs, from the ArchSim
  checkout the action comes from (or `archsim-directory`).
- The action needs as little from the caller's runner as it can, and leaves the caller's job as
  it was: no Java on its `PATH` afterwards.
- `git` in the image, for `--baseline-ref`.
- The action runs the CLI the repository builds and tests, built the same way.
- A job's minutes: the repository runs on a limited allowance.

## Considered Options

- A multi-stage Dockerfile beside the action: a JDK stage builds the CLI with the Gradle wrapper,
  a JRE stage with git runs it
- A Docker container action (`runs.using: docker`, `image: Dockerfile`), which the runner builds
- Build the CLI on the runner (`actions/setup-java`, Gradle's cache) and copy it into a JRE image
- The CLI's image built by Jib as a tar (`jibBuildTar`) and loaded with `docker load`
- An image published to a registry

## Decision Outcome

Chosen option: the multi-stage Dockerfile, because it needs nothing on the runner but Docker,
leaves the caller's job untouched, and builds the CLI exactly as the repository's build does.

- `.github/actions/archsim-check/Dockerfile` builds from the ArchSim checkout's root:
  `eclipse-temurin:25-jdk-noble` runs `./gradlew :archsim-cli:installDist`, and
  `eclipse-temurin:25-jre-noble` with `git` from Ubuntu's packages holds the installed CLI as its
  entry point, for a user that is not root. `Dockerfile.dockerignore` keeps the context to what
  the build reads.
- The action builds the image (its log in a collapsed group), and runs `archsim check` in it by
  the image's ID, so another job on the same Docker cannot swap it: as the runner's user, who owns
  the checkout, so `git` trusts it and the files written are the job's; with the workspace
  mounted read-only and a folder of the action's own to write in; without a network, which the
  check does not use. The image is removed once the check has run; BuildKit's cache keeps the
  next build short.

  **Since 12 Nov 2026** the image has a tag of the check's own (`archsim-check:<a UUID>`) and is
  run and removed by it, not by its ID: two jobs on one Docker that build the same commit get the
  same image, and removing it by its ID could take it from the other job before its run. Removing
  a tag removes the image only once no other tag names it.
- A runner that is not Linux, an image that does not build, or a container Docker cannot run (its
  own exit codes, 125 to 127, or a kill's) is a check that could not run: exit code 2, an error in
  the log, and a comment that says which, as a check that fails to start was.
- Its inputs, outputs, comment, artifact and exit codes are unchanged.

### Consequences

- Good, because a Linux runner with Docker (GitHub's) is all the action needs, and the caller's
  job keeps its own tools.
- Good, because the check runs isolated: no network, the workspace read-only, and no other file
  of the job but the action's own folder to write in.
- Bad, because the action now needs a Linux runner with Docker, where the Python action ran
  wherever uv did, macOS and Windows included.
- Bad, because every job builds the image. Measured on this repository's Docker (8 CPUs): 8.2
  minutes cold, most of it downloading Gradle and the dependencies; 2.1 minutes with the
  dependencies cached; about 1 minute with Gradle's build cache warm. A GitHub-hosted runner
  starts cold, so a job spends a few minutes more than the Python action's uv set-up did. If that
  matters, the build can be cached in the Actions cache (BuildKit's `gha` cache, with the
  dependencies in a layer of their own), or the plan's native binary can replace the JVM: a
  decision of its own.

### Confirmation

`scripts/tests/test_archsim_check_action.py` runs the check step's own script with a stand-in for
`docker`: the image built from the right checkout, the CLI's arguments, the container's options, and
the exit code, outputs and comment of each outcome. The `archsim-check` CI job builds the image and
runs the action on a template whenever the CLI, the engine, the model, the Gradle build or the
action change. The action's check step also ran in WSL's Ubuntu with Docker, as a runner runs it
(Linux paths, the runner's user, a clone with its history): the CI job's own case, a scenario that
misses its objectives, a ref that cannot be read, a design that is not there, the knee, another
checkout and a folder that is not one, each with its exit code, comment and files. Its comment is
the Python action's, but for the engine it names.

## Pros and Cons of the Options

### A multi-stage Dockerfile

- Good, because it is self-contained: Docker builds and runs everything.
- Bad, because nothing is cached between jobs unless the Actions cache is added.

### A Docker container action

- Good, because GitHub builds and runs it with no script of the action's.
- Bad, because the runner builds it with the action's own folder as the context, out of reach of
  the Gradle build three levels up.
- Bad, because it is built when the job is set up, before an input is read: `archsim-directory`
  could not name the checkout to build.
- Bad, because it cannot upload the artifact or post the comment itself, and it runs as root, so
  the files it writes in the workspace are root's.

### The CLI built on the runner

- Good, because Gradle's cache (`gradle/actions/setup-gradle`) makes a warm build about a minute.
- Bad, because `setup-java` puts a JDK on the caller's `PATH` for the rest of its job.
- Bad, because the action then needs Java, Gradle's cache and Docker on the runner.

### Jib's tar

- Good, because the repository already builds the app's image with Jib.
- Bad, because Jib's base image has no `git`: a base with it would need a Dockerfile anyway, and
  Jib would need Java on the runner too.

### A published image

- Good, because a job would pull it in seconds.
- Bad, because the repository is private and publishes nothing.

## More Information

- [ADR-0055](0055-the-java-clis-output-contract.md): the CLI's words and exit codes, which the
  action reads.
- [docs/ci.md](../ci.md): how to use the action.
