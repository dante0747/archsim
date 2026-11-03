# Thin wrappers around the documented commands. On Windows without make, run the commands
# after each target directly.
.PHONY: setup hooks image engine api worker web dev-db seed test test-py test-web lint fmt typecheck benchmark

setup:            ## install Python and web dependencies, and the commit-message hook
	uv sync
	cd apps/web && npm ci
	git config core.hooksPath .githooks

hooks:            ## check commit messages against Conventional Commits on every commit
	git config core.hooksPath .githooks

image:            ## build the Java API's image for docker compose (Jib, into the local Docker)
	./gradlew :archsim-app:jibDockerBuild

engine:           ## run the engine service the API calls, on http://127.0.0.1:8001
	uv run archsim-engine-service

api:              ## build and run the API on http://127.0.0.1:8000 (needs PostgreSQL: make dev-db)
	./gradlew :archsim-app:bootJar
	java -jar archsim-app/build/libs/archsim-app.jar api

worker:           ## run a simulation worker on the API's database
	uv run archsim-api worker

web:              ## run the web app on http://localhost:3000
	cd apps/web && npm run dev

dev-db:           ## start PostgreSQL only, where the API and the workers look by default
	docker compose up -d postgres

seed:             ## create one project per example in the default workspace
	uv run archsim-api seed

test: test-py test-web

test-py:
	uv run pytest

test-web:
	cd apps/web && npm test

lint:
	uv run ruff check packages services benchmarks
	uv run ruff format --check packages services benchmarks
	cd apps/web && npm run lint

fmt:
	uv run ruff check --fix packages services benchmarks
	uv run ruff format packages services benchmarks

typecheck:
	uv run mypy packages/engine/src services/api/src packages/mcp/src services/engine-service/src
	cd apps/web && npm run typecheck
	uv run mypy benchmarks

benchmark:        ## accuracy benchmarks (Docker; about 1.5 hours, machine otherwise idle), then rewrite docs/validation.md and accuracy.json
	uv run python benchmarks/run.py all
	uv run python benchmarks/report.py --json
