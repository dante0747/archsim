# syntax=docker/dockerfile:1
# The archsim-check action's image (ADR-0060): ArchSim's Java CLI, built from this repository by
# the job that runs the action and never published. Build it from the repository's root:
#
#   docker build --file .github/actions/archsim-check/Dockerfile --tag archsim-check .
#
# Dockerfile.dockerignore keeps the context to what the build reads.

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /archsim
COPY . .
# the CLI alone: no tests, which the repository's own CI runs
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --quiet :archsim-cli:installDist

FROM eclipse-temurin:25-jre-noble
# git reads a design's baseline at a ref (archsim check --baseline-ref)
RUN apt-get update \
    && apt-get install --yes --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /archsim/archsim-cli/build/install/archsim /opt/archsim
# not root, as the app's image (Jib's 10001); the action runs it as the runner's user
USER 10001:10001
ENTRYPOINT ["/opt/archsim/bin/archsim"]
