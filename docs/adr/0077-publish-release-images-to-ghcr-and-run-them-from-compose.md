---
status: accepted
date: 2026-11-15
decision-makers: Majid Abarghooei (owner)
---

# 0077. Publish release images to GHCR, and run them from `docker-compose.yml`

## Context and Problem Statement

The quick start is two commands: `./gradlew :archsim-app:jibDockerBuild`, then
`docker compose up --build`. The first needs a JDK on the machine, because `docker-compose.yml`
runs `archsim-app:local` with `pull_policy: never`: compose never pulls or builds the app's image,
and Jib builds it into the local Docker
([ADR-0014](0014-one-deployable-application-with-roles.md)). The web image is built by compose from
`apps/web/Dockerfile`, which takes a few minutes. Nothing is published, as
[ADR-0060](0060-run-the-check-action-in-an-image-the-job-builds.md) noted for the check action:
the repository was private.

ArchSim is about to be released and made public, as 1.0.0
([ADR-0076](0076-version-archsim-apart-from-its-engine.md)). Someone trying it should need only
Docker: `docker compose up` should start PostgreSQL, the migration, the API, a worker and the web
app. People working on ArchSim still need to run the stack on what they have just built. Where
do the images come from, and how does compose choose between a release and a local build?

## Decision Drivers

- `docker compose up` with Docker alone: no JDK, no Node.js, no build.
- What compose runs is a release, named by its version, so a user knows what they run.
- Developers keep a one-command path to the stack on images built from their checkout.
- No credentials to manage: the workflow uses its own token only.
- Arm machines (Apple silicon, Graviton) run the images natively.
- Free while the repository is public: GitHub's hosted runners and its container registry.

## Considered Options

- (a) A release workflow publishes both images to GHCR on a `v*` tag; `docker-compose.yml` runs
  them at the release's version; `docker-compose.local.yml` switches compose to local builds
- (b) The same images, with compose choosing through variables
  (`ARCHSIM_APP_IMAGE=archsim-app:local`) instead of an override file
- (c) Compose builds everything from source: a Dockerfile for the app (a JDK stage running
  Gradle), and the web app's
- (d) Docker Hub instead of GHCR

## Decision Outcome

Chosen option: (a), because a user needs only Docker, compose names exactly which release it runs,
and a developer's local images can never be mistaken for a release's.

- **`.github/workflows/release.yml`**, on a `v*` tag, first checks the tag. It must be on main,
  and archsim-tools' `CheckRelease` (run from its source, as `CheckCommits` is,
  [ADR-0072](0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md))
  checks that it names the version `gradle.properties` says, and that `docker-compose.yml` runs
  both images from the workflow's registry at that version. `apps/web/package.json` repeats the
  version, which the web app's own tests check. Then it publishes:
  - `ghcr.io/dante0747/archsim-app:<version>` with Jib (`./gradlew :archsim-app:jib`, Jib's
    `jib.to.image` and `jib.from.platforms` system properties), for `linux/amd64` and
    `linux/arm64`. Jib needs no Docker and no emulation: it puts the app's layers on each
    platform's Temurin image.
  - `ghcr.io/dante0747/archsim-web:<version>` from `apps/web/Dockerfile` with Buildx, for the same
    two platforms, the arm64 one under QEMU.
  - `latest` too, for a release that is not a pre-release.

  Its jobs have `contents: read` and `packages: write`, and the job's `GITHUB_TOKEN` is the only
  credential. The registry, `ghcr.io/dante0747`, is written once in the workflow, as compose and
  the tests write it: if the repository moves to another owner, the check fails until compose
  follows, rather than publishing where compose does not look. Both images carry the OCI labels
  for their title, description, version, license and source. The source label links each package
  to the repository.
- **`docker-compose.yml`** runs those images, at the version `gradle.properties` names. It has no
  `build:` section and no `pull_policy: never`, so `docker compose up` pulls what it lacks.
- **`docker-compose.local.yml`** is an override for developers. It runs `archsim-app:local`
  (`pull_policy: never`, built by `make image`, `./gradlew :archsim-app:jibDockerBuild`) and builds
  `archsim-web:local` from `apps/web/Dockerfile`:
  `docker compose -f docker-compose.yml -f docker-compose.local.yml up --build`, or `make up-local`.
  Local images have names of their own, so a local build never takes a release's name.
- archsim-app's tests hold `docker-compose.yml`'s images to ArchSim's version, and
  `docker-compose.local.yml`'s to the local builds.

This changes [ADR-0014](0014-one-deployable-application-with-roles.md)'s set-up, where compose
never pulls or builds the app's image. The image is still the one deployable that is started as
`migrate`, `api`, `worker` or `seed`, built by Jib, but compose now pulls a published copy of it.
[ADR-0060](0060-run-the-check-action-in-an-image-the-job-builds.md)'s reason against a published
image ("the repository is private and publishes nothing") no longer holds for these two images.
The check action still builds its CLI's image from the checkout. Publishing that image as well is
a separate decision.

### Consequences

- Good, because the quick start is `git clone` and `docker compose up`, and the compose file
  needs nothing else from the checkout. It runs on its own, given `.env.docker` for sign-in.
- Good, because a user on arm64 pulls an arm64 image: no emulation at run time.
- Good, because the versions say which release runs. GHCR's tags can be pushed again, so it is a
  rule, not a guarantee, that a release's images never change: a release tag is never pushed
  twice, and a broken release is fixed by the next one.
- Bad, because `docker-compose.yml` on `main` runs the last release's images, not `main`'s code.
  A developer who changes the code and runs plain `docker compose up` runs the release, not their
  change. The compose file's header, the getting-started guide and `make up-local` say how to run
  local builds.
- Bad, because the release commit changes the version before the tag's workflow has published its
  images. Until the workflow finishes, `docker compose up` on that commit cannot pull them.
- Bad, because both stacks share one database (`archsim_pgdata`). A local build that carries a new
  migration moves it past the release, whose worker then refuses to start until the developer goes
  back to their build or starts afresh ([troubleshooting.md](../troubleshooting.md)). A separate
  compose project for local builds would avoid that, but running from source shares the database
  too, and a developer's existing data would stay behind in the release's stack.
- Bad, because the workflow cannot run until the repository is public: Actions starts no jobs on
  this private repository (billing). Its first run is the 1.0.0 release.
- Bad, because GHCR makes a new package private, even when a public repository's workflow
  publishes it. After the first release, the owner makes each package public, once, in its
  settings.
- Bad, because the arm64 web image builds under QEMU, for about ten minutes more. Native arm64
  runners, with a job per platform and a merged index, can replace it if that becomes a problem.

### Confirmation

- archsim-app's `ComposeFileTests`: every ArchSim service of `docker-compose.yml` runs a release
  image at `ArchsimVersion.PRODUCT`, no service builds, and `docker-compose.local.yml` runs the
  local builds. `WorkerCommandLineTests` keeps checking the worker's command line.
- archsim-tools' `CheckReleaseTests`: a tag that names another version, compose at another version
  or from another registry, or an image named only in a comment, each stops the release; a
  pre-release leaves `latest`. `ReleaseWorkflowTests`: the workflow runs the check, the checkout
  as it is releases to the workflow's registry, and the check runs from its source with the JDK
  alone. CI's rules run them when `release.yml` changes.
- Checked locally before the first release: the workflow's YAML, a Jib build of the app's image
  into Docker with Jib's `jib.to.image` and `jib.to.tags` properties, the web image's build for
  both platforms (the arm64 one served its home page under emulation), and the whole stack on the
  local images through `docker-compose.local.yml`: a run of the starter succeeded. The publish
  itself first runs at the 1.0.0 tag.

## Pros and Cons of the Options

### (b) Variables instead of an override file

- Good, because one file holds everything.
- Bad, because every developer sets two variables per shell (in PowerShell differently from POSIX
  shells) or in a `.env` file, and forgetting them silently runs the release.
- Bad, because the web service would need both `image:` and `build:`. Compose then builds the
  local web image under the release's name (`ghcr.io/...:1.0.0`), and the local build passes
  for the release afterwards.

### (c) Compose builds everything from source

- Good, because nothing is published and no registry is involved.
- Bad, because the first `docker compose up` builds Gradle's dependencies and the Java app inside
  Docker: minutes of downloads and compilation before anything starts, every time the cache is
  cold.
- Bad, because a second way of building the app's image (a Dockerfile) besides Jib's would have to
  be kept equal to it.

### (d) Docker Hub

- Good, because it is Docker's default registry, and its image names are shorter.
- Bad, because it needs an account, a token stored as a secret, and its rate limits on anonymous
  pulls. GHCR uses the repository's own token and permissions.

## More Information

- [ADR-0014](0014-one-deployable-application-with-roles.md): one image, started in a role.
- [ADR-0060](0060-run-the-check-action-in-an-image-the-job-builds.md): the check action's image,
  which stays built by the job that runs it.
- [ADR-0076](0076-version-archsim-apart-from-its-engine.md): the version the images are tagged
  with.
- [docs/deployment.md](../deployment.md) and [docs/getting-started.md](../getting-started.md).
