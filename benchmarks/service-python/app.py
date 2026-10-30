"""ArchSim benchmark service in Python: Flask on gunicorn's sync workers, each with its own
psycopg pool (the python-mixed held-out experiment).

It serves the same HTTP contract as benchmarks/service/server.js, so benchmarks/run.py and
benchmarks/k6/load.js drive it unchanged: GET /read?id=, POST /write?id=[&async=1], GET /health,
POST /reset and GET /stats (CPU per request, the handler's own time, each PostgreSQL call's time,
and garbage collection), added up over every worker process (shared_stats.py).

A sync worker serves one request at a time, so each worker's pool holds PG_POOL / WEB_WORKERS
connections (1 with the defaults: 10 connections in all, as on the other stacks).
"""

from __future__ import annotations

import os
import time
from collections.abc import Callable, Iterable
from typing import Any, TypeVar

import shared_stats
from flask import Flask, request
from psycopg_pool import ConnectionPool

T = TypeVar("T")

WORKERS = int(os.environ.get("WEB_WORKERS", "10"))
PER_WORKER = max(1, int(os.environ.get("PG_POOL", "10")) // WORKERS)
DSN = os.environ.get("DATABASE_URL", "postgresql://bench:bench@postgres:5432/bench")

# gunicorn imports this module in each worker after forking it (no preload): one slot each.
stats = shared_stats.Shared()
stats.claim()
_io = [0.0]  # time the current request spent in dependency calls (one request per process)
_pools: dict[str, Any] = {}


def pool(name: str) -> Any:
    """This worker's pool, opened on first use. ``async``: the same database, commits without
    waiting for the WAL flush (calibration only, as in server.js)."""
    p = _pools.get(name)
    if p is None:
        if name == "async":
            p = ConnectionPool(
                DSN,
                min_size=0,
                max_size=1,
                kwargs={"autocommit": True, "options": "-c synchronous_commit=off"},
                open=True,
            )
        else:
            p = ConnectionPool(
                DSN,
                min_size=PER_WORKER,
                max_size=PER_WORKER,
                kwargs={"autocommit": True},
                open=True,
            )
        _pools[name] = p
    return p


def timed(name: str, call: Callable[[], T]) -> T:
    t = time.perf_counter()
    try:
        return call()
    finally:
        ms = (time.perf_counter() - t) * 1000
        stats.record(name, ms)
        _io[0] += ms


app = Flask(__name__)


@app.get("/read")
def read() -> dict[str, Any]:
    key = int(request.args.get("id", 1))

    def query() -> Any:
        with pool("main").connection() as conn:
            return conn.execute("SELECT value FROM items WHERE id = %s", (key,)).fetchone()

    row = timed("db_read", query)
    return {"value": row[0] if row else None}


@app.post("/write")
def write() -> dict[str, Any]:
    key = int(request.args.get("id", 1))
    no_flush = request.args.get("async") == "1"

    def update() -> None:
        with pool("async" if no_flush else "main").connection() as conn:
            conn.execute("UPDATE items SET value = value + 1 WHERE id = %s", (key,))

    timed("db_write_async" if no_flush else "db_write", update)
    return {"ok": True}


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True}


@app.route("/reset", methods=["GET", "POST"])
def reset() -> dict[str, Any]:
    stats.reset()
    return {"ok": True}


@app.route("/stats", methods=["GET", "POST"])
def snapshot() -> dict[str, Any]:
    return stats.snapshot()


class Timed:
    """The handler's time for /read and /write, from gunicorn calling the app until the response
    is ready (Flask's routing and JSON included); ``own`` is that less the dependency calls."""

    def __init__(self, wsgi: Callable[..., Iterable[bytes]]) -> None:
        self.wsgi = wsgi

    def __call__(self, environ: dict[str, Any], start_response: Callable[..., Any]) -> Any:
        if environ.get("PATH_INFO") not in ("/read", "/write"):
            return self.wsgi(environ, start_response)
        _io[0] = 0.0
        status: list[str] = []

        def capture(s: str, headers: Any, exc_info: Any = None) -> Any:
            status.append(s)
            return start_response(s, headers, exc_info)

        t = time.perf_counter()
        body = self.wsgi(environ, capture)
        ms = (time.perf_counter() - t) * 1000
        if status and status[0].startswith("200"):
            stats.request(ms, ms - _io[0])
        return body


app.wsgi_app = Timed(app.wsgi_app)
