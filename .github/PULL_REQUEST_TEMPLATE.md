## What and why

<!-- What does this change, and why is it needed? Link related issues ("Fixes #123"). -->

## How it was tested

<!-- Commands run, new tests, manual checks (screenshots for UI changes). -->

## AI assistance

<!-- Did an AI assistant write or change part of this? Name it and say what it did, or write "None".
     See CONTRIBUTING.md#working-with-ai-assistants. -->

## Checklist

- [ ] One focused change, with commit messages as [Conventional Commits](https://github.com/dante0747/archsim/blob/main/CONTRIBUTING.md#pull-requests)
- [ ] Outside contributors: I know this can be merged only once I have signed the [Contributor License Agreement](https://github.com/dante0747/archsim/blob/main/CLA.md)
- [ ] I have read, understood and tested every line, including any an AI assistant wrote
- [ ] Java, the build or a migration changed: `./gradlew format` and `./gradlew build` pass
- [ ] `apps/web` changed: `npm run lint`, `npm run typecheck`, `npm test` and `npm run build` pass
- [ ] Docs updated (schema, API, modeling assumptions or UI workflow, if affected)
- [ ] **Simulation semantics changed?** If results can differ for the same document and seed:
      the engine's version (`EngineVersions.ENGINE`) bumped, each replay of the Python engine's
      recorded runs that no longer matches explained, the queueing-theory and reference tests
      still passing, `docs/simulation-model.md` updated
- [ ] **Schema changed?** `ArchitectureDocument.SCHEMA_VERSION` bumped, with an upgrade step and a test
- [ ] **Database changed?** A new Flyway migration in `archsim-app/src/main/resources/db/migration`
- [ ] No secrets, `.env` files or local databases committed
