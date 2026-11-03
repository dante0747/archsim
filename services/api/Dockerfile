# syntax=docker/dockerfile:1
# ArchSim simulation worker image: simulates the runs the API (archsim-app) queues in PostgreSQL.
# Build from the repository root:
#   docker build -f services/api/Dockerfile -t archsim-worker .
# Run it under an init (docker run --init, init: true in compose) so killed simulation processes
# are reaped, with ARCHSIM_DATABASE_URL set to the API's database.
FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Workspace manifests first for better layer caching, then sources.
COPY pyproject.toml uv.lock ./
COPY packages/ packages/
COPY services/ services/
COPY examples/ examples/

RUN uv sync --frozen --no-dev --no-editable --package archsim-api

RUN useradd --create-home --uid 10001 archsim

USER archsim

ENV PATH="/app/.venv/bin:${PATH}" \
    ARCHSIM_EXAMPLES_DIR=/app/examples

CMD ["archsim-api", "worker"]
