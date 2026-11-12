## What and why

<!-- What does this change and why is it needed? Link related issues. -->

## How it was tested

<!-- Commands run, new tests, manual checks (screenshots for UI changes). -->

## Checklist

- [ ] `uv run ruff check benchmarks scripts` and `uv run ruff format --check benchmarks scripts` pass
- [ ] `uv run mypy benchmarks` passes
- [ ] `./gradlew :archsim-cli:installDist`, then `uv run pytest` passes
- [ ] Web: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` pass (if `apps/web` changed)
- [ ] Docs updated (schema, API, modeling assumptions or UI workflow, if affected)
- [ ] **Simulation semantics changed?** If results can differ for the same document and seed:
      the engine's version (`EngineVersions.ENGINE`) bumped, each replay of the Python engine's
      recorded runs that no longer matches explained, the queueing-theory and reference tests
      still passing, `docs/simulation-model.md` updated
- [ ] **Schema changed?** `ArchitectureDocument.SCHEMA_VERSION` bumped with an upgrade step and a test
- [ ] **Database changed?** New Flyway migration added (`archsim-app/src/main/resources/db/migration`)
- [ ] No secrets, `.env` files or local databases committed
