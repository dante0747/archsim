# Thin wrappers around the documented commands. On Windows without make, run the commands
# after each target directly.
.PHONY: setup api worker web dev-db seed test test-py test-web lint fmt typecheck benchmark

setup:            ## install Python and web dependencies
	uv sync
	cd apps/web && npm ci

api:              ## run the API on http://127.0.0.1:8000
	uv run archsim-api serve --reload

worker:           ## run another simulation worker (share a PostgreSQL database with the API)
	uv run archsim-api worker

web:              ## run the web app on http://localhost:3000
	cd apps/web && npm run dev

dev-db:           ## start PostgreSQL only (then set ARCHSIM_DATABASE_URL, see .env.example)
	docker compose up -d postgres

seed:             ## migrate the database and create one project per example
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
	uv run mypy packages/engine/src services/api/src packages/mcp/src
	cd apps/web && npm run typecheck
	uv run mypy benchmarks

benchmark:        ## accuracy benchmarks (Docker; about 1.5 hours, machine otherwise idle), then rewrite docs/validation.md and accuracy.json
	uv run python benchmarks/run.py all
	uv run python benchmarks/report.py --json
