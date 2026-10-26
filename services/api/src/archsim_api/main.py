"""FastAPI application factory."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from archsim_api.config import Settings, get_settings
from archsim_api.db import Base, Database
from archsim_api.jobs import ThreadJobRunner
from archsim_api.migrations import upgrade_to_head
from archsim_api.routers import calibration, meta, projects, runs
from archsim_engine import ENGINE_VERSION, SimulationLimits, default_registry

log = logging.getLogger("archsim.api")

API_PREFIX = "/api/v1"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    db = Database(settings.database_url)
    jobs = ThreadJobRunner(
        db,
        SimulationLimits(
            max_requests=settings.max_requests, max_wall_seconds=settings.max_wall_seconds
        ),
        settings.max_concurrent_runs,
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if settings.auto_migrate:
            upgrade_to_head(settings.database_url)
        else:
            Base.metadata.create_all(db.engine, checkfirst=True)
        interrupted = jobs.recover_interrupted()
        if interrupted:
            log.warning("marked %d interrupted run(s) as failed", interrupted)
        yield
        jobs.shutdown()
        db.engine.dispose()

    app = FastAPI(
        title="ArchSim API",
        version=ENGINE_VERSION,
        description=(
            "Design software architectures and simulate their behaviour with a discrete-event "
            "engine. Results are model estimates driven by stated assumptions, not production "
            "guarantees."
        ),
        openapi_url=f"{API_PREFIX}/openapi.json",
        docs_url=f"{API_PREFIX}/docs",
        redoc_url=None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.db = db
    app.state.jobs = jobs
    app.state.registry = default_registry()

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.middleware("http")
    async def limit_body_size(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > settings.max_body_bytes:
            return JSONResponse(
                {"detail": {"message": "Request body too large", "issues": []}}, status_code=413
            )
        if (
            length is None
            and request.method in ("POST", "PUT", "PATCH")
            and "chunked" in request.headers.get("transfer-encoding", "").lower()
        ):
            # Chunked bodies have no declared size, so the limit above cannot be enforced.
            return JSONResponse(
                {"detail": {"message": "Content-Length is required", "issues": []}},
                status_code=411,
            )
        return await call_next(request)

    for r in (meta.router, projects.router, runs.router, calibration.router):
        app.include_router(r, prefix=API_PREFIX)
    return app


def app_factory() -> FastAPI:  # used by `uvicorn --factory archsim_api.main:app_factory`
    return create_app()
