"""gunicorn's settings for the python-mixed benchmark service: sync workers (one request at a
time each), as gunicorn ships them. ArchSim models the container as one replica with WEB_WORKERS
workers sharing its 2 CPUs."""

from __future__ import annotations

import os

import shared_stats

bind = "0.0.0.0:8080"
workers = int(os.environ.get("WEB_WORKERS", "10"))
worker_class = "sync"
backlog = 2048
timeout = 30
accesslog = None
errorlog = "-"
loglevel = "warning"


def on_starting(server: object) -> None:
    """In the master, before any worker starts: the statistics file the workers share."""
    shared_stats.create()
