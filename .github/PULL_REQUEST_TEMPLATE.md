## What and why

<!-- What does this change and why is it needed? Link related issues. -->

## How it was tested

<!-- Commands run, new tests, manual checks (screenshots for UI changes). -->

## Checklist

- [ ] `uv run ruff check packages services` and `uv run ruff format --check packages services` pass
- [ ] `uv run mypy packages/engine/src services/api/src packages/mcp/src` passes
- [ ] `uv run pytest` passes
- [ ] Web: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` pass (if `apps/web` changed)
- [ ] Docs updated (schema, API, modeling assumptions or UI workflow, if affected)
- [ ] **Simulation semantics changed?** If results can differ for the same document and seed:
      the engine's version (`EngineVersions.ENGINE`) bumped, reference benchmark reviewed,
      `docs/simulation-model.md` updated
- [ ] **Schema changed?** `SCHEMA_VERSION` bumped with a migration and a test
- [ ] **Database changed?** New Flyway migration added (`archsim-app/src/main/resources/db/migration`)
- [ ] No secrets, `.env` files or local databases committed
