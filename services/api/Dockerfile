# syntax=docker/dockerfile:1
# ArchSim API image. Build from the repository root:
#   docker build -f services/api/Dockerfile -t archsim-api .
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

RUN useradd --create-home --uid 10001 archsim \
    && mkdir -p /app/data \
    && chown archsim /app/data

USER archsim

ENV PATH="/app/.venv/bin:${PATH}" \
    ARCHSIM_EXAMPLES_DIR=/app/examples \
    ARCHSIM_DATABASE_URL=sqlite:////app/data/archsim.db

EXPOSE 8000

CMD ["archsim-api", "serve", "--host", "0.0.0.0", "--port", "8000"]
