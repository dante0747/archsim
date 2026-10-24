# Thin wrappers around the documented commands. On Windows without make, run the commands
# after each target directly.
.PHONY: setup api web dev-db seed test test-py test-web lint fmt typecheck

setup:            ## install Python and web dependencies
	uv sync
	cd apps/web && npm ci

api:              ## run the API on http://127.0.0.1:8000
	uv run archsim-api serve --reload

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
	uv run ruff check packages services
	uv run ruff format --check packages services
	cd apps/web && npm run lint

fmt:
	uv run ruff check --fix packages services
	uv run ruff format packages services

typecheck:
	uv run mypy packages/engine/src services/api/src
	cd apps/web && npm run typecheck
