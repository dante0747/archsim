# syntax=docker/dockerfile:1
# ArchSim engine service image: the simulation a calibration replays, for the Java API
# (ADR-0036, ADR-0045). It listens inside compose only. Build from the repository root:
#   docker build -f services/engine-service/Dockerfile -t archsim-engine-service .
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

RUN uv sync --frozen --no-dev --no-editable --package archsim-engine-service

RUN useradd --create-home --uid 10001 archsim

USER archsim

ENV PATH="/app/.venv/bin:${PATH}"

EXPOSE 8001

CMD ["archsim-engine-service", "--host", "0.0.0.0", "--port", "8001"]
