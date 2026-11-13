## What and why

<!-- What does this change, and why is it needed? Link related issues ("Fixes #123"). -->

## How it was tested

<!-- Commands run, new tests, manual checks (screenshots for UI changes). -->

## Checklist

- [ ] One focused change, with commit messages as [Conventional Commits](https://github.com/dante0747/archsim/blob/main/CONTRIBUTING.md#pull-requests)
- [ ] Outside contributors: I know this can be merged only once I have signed the Contributor License Agreement ([CONTRIBUTING.md](https://github.com/dante0747/archsim/blob/main/CONTRIBUTING.md#licensing-of-contributions))
- [ ] Java, the build or a migration changed: `./gradlew format` and `./gradlew build` pass
- [ ] `apps/web` changed: `npm run lint`, `npm run typecheck`, `npm test` and `npm run build` pass
- [ ] `benchmarks/` or `scripts/` changed: `uv run ruff check benchmarks scripts`, `uv run ruff format --check benchmarks scripts`, `uv run mypy benchmarks` and, after `./gradlew :archsim-cli:installDist`, `uv run pytest` pass
- [ ] Docs updated (schema, API, modeling assumptions or UI workflow, if affected)
- [ ] **Simulation semantics changed?** If results can differ for the same document and seed:
      the engine's version (`EngineVersions.ENGINE`) bumped, each replay of the Python engine's
      recorded runs that no longer matches explained, the queueing-theory and reference tests
      still passing, `docs/simulation-model.md` updated
- [ ] **Schema changed?** `ArchitectureDocument.SCHEMA_VERSION` bumped, with an upgrade step and a test
- [ ] **Database changed?** A new Flyway migration in `archsim-app/src/main/resources/db/migration`
- [ ] No secrets, `.env` files or local databases committed
