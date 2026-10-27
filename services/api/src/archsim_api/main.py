"""FastAPI application factory."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from archsim_api.config import Settings, get_settings
from archsim_api.db import Base, Database
from archsim_api.identity import ensure_defaults
from archsim_api.jobs.worker import JobService, Worker, WorkerConfig
from archsim_api.migrations import upgrade_to_head
from archsim_api.routers import (
    auth,
    calibration,
    export,
    importing,
    meta,
    projects,
    run_groups,
    runs,
    tokens,
    workspaces,
)
from archsim_engine import ENGINE_VERSION, default_registry

API_PREFIX = "/api/v1"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    db = Database(settings.database_url)
    # Runs are queued in the database. By default this process also simulates them (one
    # command, no other service); with ARCHSIM_EMBEDDED_WORKER=false, `archsim-api worker`
    # processes do, and any number of API instances can share the database.
    worker = Worker(db, WorkerConfig.from_settings(settings)) if settings.embedded_worker else None
    jobs = JobService(db, worker)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if settings.auto_migrate:
            upgrade_to_head(settings.database_url)
        else:
            Base.metadata.create_all(db.engine, checkfirst=True)
            ensure_defaults(db)
        jobs.start()  # requeues runs a stopped worker left behind, then takes runs
        yield
        jobs.shutdown()  # gives back runs still in flight: requeued for the next worker
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

    hsts = bool(settings.public_url and settings.public_url.startswith("https://"))
    private_prefixes = tuple(
        f"{API_PREFIX}/{p}" for p in ("auth", "tokens", "workspaces", "invitations")
    )

    @app.middleware("http")
    async def security_headers(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Headers that keep API answers from being framed, sniffed, cached or leaked. The
        interactive docs load their script from a CDN, so they keep the browser's defaults."""
        response = await call_next(request)
        h = response.headers
        h.setdefault("X-Content-Type-Options", "nosniff")
        h.setdefault("X-Frame-Options", "DENY")
        h.setdefault("Referrer-Policy", "no-referrer")
        h.setdefault("Cross-Origin-Resource-Policy", "same-origin")
        h.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if not request.url.path.startswith(f"{API_PREFIX}/docs"):
            h.setdefault("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        if request.url.path.startswith(private_prefixes):
            h.setdefault("Cache-Control", "no-store")
        if hsts:
            h.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        return response

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

    for r in (
        meta.router,
        auth.router,
        workspaces.router,
        tokens.router,
        projects.router,
        runs.router,
        run_groups.router,
        calibration.router,
        export.router,
        importing.router,
    ):
        app.include_router(r, prefix=API_PREFIX)
    return app


def app_factory() -> FastAPI:  # used by `uvicorn --factory archsim_api.main:app_factory`
    return create_app()
