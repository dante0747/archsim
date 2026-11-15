---
status: accepted
date: 2026-11-15
decision-makers: Majid Abarghooei (owner)
---

# 0076. Version ArchSim as a product, apart from its engine, in `gradle.properties`

## Context and Problem Statement

Until now the only version ArchSim showed was the engine's, `EngineVersions.ENGINE` (1.1.1).
`archsim --version` printed `archsim, engine 1.1.1`, the MCP server's `serverInfo.version` was the
engine's ([ADR-0058](0058-serve-mcp-with-the-official-mcp-java-sdk.md)), and `GET /meta` named
the engine, schema and report versions. The Gradle projects had no version (`unspecified`), and
the web app's `package.json` said 0.2.0, which nothing read.

The engine's version has one job: it moves only when what a simulation means changes, so that a
saved run is compared fairly with a new one ([CONTRIBUTING.md](../../CONTRIBUTING.md), "Changing
simulation semantics"). A release moves for other reasons too, such as a new page, a fix in the API
or a new template. The first public release, 1.0.0, needs a version of its own for its tag, its
images and its release notes. Where is that version said, and how does the code learn it?

## Decision Drivers

- Said once: a release changes one number, and nothing can disagree with it unnoticed.
- The build's artifacts (jars, distributions, images) and the running code know the same number.
- It works wherever the code runs: tests, the IDE through Gradle, the CLI's distribution, the
  app's image, and Docker builds whose context has no `.git`.
- The engine's version stays as it is, with its own meaning.

## Considered Options

- (a) Gradle's `version` in `gradle.properties`, which the build writes into a resource that
  `ArchsimVersion` reads
- (b) A Java constant, as `EngineVersions.ENGINE` is
- (c) The jars' manifests (`Implementation-Version`), read through `Package`
- (d) A Java source file the build generates, holding a compile-time constant
- (e) The git tag, read by the build (`git describe`)

## Decision Outcome

Chosen option: (a), because it says the version once, where Gradle and its plugins already look
for it, and the code reads the same number in every place it runs.

- `gradle.properties` holds `version=1.0.0`, every module's version. The jars and distributions
  are named with it (`archsim-engine-1.0.0.jar`). The app's jar keeps its fixed name,
  `archsim-app.jar`.
- archsim-engine's build writes it into `io/archsim/engine/version/archsim.properties`, and
  `ArchsimVersion.PRODUCT` reads it. The class sits beside `EngineVersions` because archsim-engine
  is the one module that the app, the CLI and the MCP server all depend on. It refuses anything
  but a semantic version, so a build that copied the file without writing the version into it
  (`${version}`), or one with no version set (Gradle's `unspecified`), fails when the class
  loads, and says why.
- `archsim --version` prints `archsim 1.0.0, engine 1.1.1`, through a picocli version provider,
  since `PRODUCT` is not a compile-time constant.
- The MCP server's `serverInfo.version` is ArchSim's version. The handshake names the server, and
  the server is a product, not an engine.
- `GET /meta` adds `version` beside `engineVersion`.
- The web app's `package.json` and its lock file say the same version, for npm. The web app's
  own tests (`src/test/package-version.test.ts`) hold them to `gradle.properties`, so a
  dependency update, which changes the lock file, runs the web jobs, not the Java one; a change
  to `gradle.properties` runs the web job besides the Java ones.
- A release is tagged `v` followed by the version. Its commit sets `version` in
  `gradle.properties`, and `npm version <version> --no-git-tag-version` in `apps/web` sets the
  package's. Between releases, builds from `main` keep the last release's number.

### Consequences

- Good, because a release changes one line, plus the web package's, which a test holds to it.
- Good, because a user, an agent or an operator can tell the product release from the engine:
  `archsim 1.0.0, engine 1.1.1` says which ArchSim is installed, and which engine made its runs.
- Good, because the engine's version keeps its meaning. A release that changes no simulation
  leaves it alone.
- Bad, because the jars' names and the engine's jar now carry the product's version. The
  benchmark harness's `EngineBuild` hash, which covers the engine jar's name and content,
  changes at every release, even when the engine has not changed. Runs record the engine's
  version, not that hash, so comparisons are not affected.
- Bad, because `ArchsimVersion` is read at run time from a resource. A build that bypasses
  Gradle's resource processing (an IDE's own builder, say) fails when the class loads.
- Neutral: the version on `main` between releases is not a pre-release (`1.1.0-SNAPSHOT`).
  `ArchsimVersion` accepts a pre-release suffix, if one is wanted later.

### Confirmation

`ArchsimVersionTests` (archsim-engine) checks that the code reads `gradle.properties`' version,
and that anything but a semantic version is refused. The web app's `package-version.test.ts`
checks that `package.json` and its lock file say the same as `gradle.properties`. `ArchsimTests`
(archsim-cli) checks `--version` on the command and its subcommands.
`McpSessionTests` checks the MCP handshake. `MetaServiceTests` and `MetaControllerTests` check
`/meta`, and the black-box `MetaTests` checks it over HTTP.

## Pros and Cons of the Options

### (b) A Java constant

- Good, because it is a compile-time constant, usable in picocli's `version` attribute.
- Bad, because Gradle, the jars and the images would not know it, unless the build parsed a Java
  file to learn it.

### (c) The jars' manifests

- Good, because it is Java's standard place for an implementation's version.
- Bad, because it is absent when a class runs from a folder of classes, as a module's own tests
  do, so the tests could not check it.

### (d) A generated Java source file

- Good, because a compile-time constant needs no version provider.
- Bad, because generated code then falls under the formatter, Checkstyle and Error Prone, or has
  to be kept out of them, and a source-generating task is more build code than a filtered
  resource.

### (e) The git tag

- Good, because the tag would be the only record of the version.
- Bad, because a build needs git and the repository's history to learn its own version. The
  check action's image and the app's Docker builds have no `.git` in their context, and a
  source archive has none either.

## More Information

- [ADR-0058](0058-serve-mcp-with-the-official-mcp-java-sdk.md): its `serverInfo.version` was the
  engine's; this ADR makes it ArchSim's.
- [ADR-0077](0077-publish-release-images-to-ghcr-and-run-them-from-compose.md) publishes the
  images tagged with this version, which `docker-compose.yml` runs.
- `EngineVersions` (archsim-engine) for the engine's and the reports' versions.
