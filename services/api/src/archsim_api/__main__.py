"""``archsim-api`` command: serve, worker, migrate, seed."""

from __future__ import annotations

import argparse
import json
import logging
import signal
import threading
from pathlib import Path
from types import FrameType


def cmd_serve(args: argparse.Namespace) -> int:
    import copy

    import uvicorn
    from uvicorn.config import LOGGING_CONFIG

    # uvicorn's logging plus ArchSim's own lines (runs taken, finished, given back), also in the
    # server process that --reload starts
    log_config = copy.deepcopy(LOGGING_CONFIG)
    log_config["loggers"]["archsim"] = {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    uvicorn.run(
        "archsim_api.main:app_factory",
        factory=True,
        host=args.host,
        port=args.port,
        reload=args.reload,
        log_config=log_config,
    )
    return 0


def _slots(text: str) -> int:
    value = int(text)
    if not 1 <= value <= 32:
        raise argparse.ArgumentTypeError("--slots must be between 1 and 32")
    return value


def cmd_worker(args: argparse.Namespace) -> int:
    """Simulate queued runs until SIGTERM or Ctrl+C, then give back the runs in flight."""
    from archsim_api.config import get_settings
    from archsim_api.db import Database
    from archsim_api.jobs.worker import Worker, WorkerConfig
    from archsim_api.migrations import upgrade_to_head

    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    log = logging.getLogger("archsim.worker")
    settings = get_settings()
    if settings.auto_migrate:
        upgrade_to_head(settings.database_url)
    db = Database(settings.database_url)
    worker = Worker(db, WorkerConfig.from_settings(settings, slots=args.slots))
    stop = threading.Event()

    def on_signal(signum: int, _frame: FrameType | None) -> None:
        log.info("%s received: finishing up", signal.Signals(signum).name)
        stop.set()

    for name in ("SIGTERM", "SIGINT", "SIGBREAK"):  # SIGBREAK: Ctrl+Break on Windows
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), on_signal)
    worker.start()
    try:
        while not stop.wait(0.5):  # short waits, so signals are handled promptly on Windows
            pass
    finally:
        worker.stop()
        db.engine.dispose()
    return 0


def cmd_migrate(args: argparse.Namespace) -> int:
    from archsim_api.config import get_settings
    from archsim_api.migrations import upgrade_to_head

    url = get_settings().database_url
    upgrade_to_head(url)
    print(f"database at {url.split('@')[-1]} is at the latest schema")
    return 0


def cmd_seed(args: argparse.Namespace) -> int:
    """Create one project per example file unless a project with that name exists."""
    from sqlalchemy import select

    from archsim_api.config import get_settings
    from archsim_api.db import Database, Project
    from archsim_api.migrations import upgrade_to_head
    from archsim_engine import load_document

    settings = get_settings()
    upgrade_to_head(settings.database_url)
    db = Database(settings.database_url)
    created = 0
    with db.session() as s:
        existing = set(s.execute(select(Project.name)).scalars())
        for f in sorted(Path(settings.examples_dir).glob("*.json")):
            doc = load_document(json.loads(f.read_text(encoding="utf-8"))).to_json_dict()
            name = doc["metadata"]["name"]
            if name in existing:
                continue
            s.add(
                Project(
                    name=name,
                    description=doc["metadata"].get("description"),
                    document=doc,
                    schema_version=doc["schemaVersion"],
                )
            )
            created += 1
        s.commit()
    print(f"seeded {created} example project(s)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="archsim-api")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("serve", help="run the HTTP API")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--reload", action="store_true")
    p.set_defaults(fn=cmd_serve)
    w = sub.add_parser(
        "worker",
        help="simulate queued runs (scale these for several hosts; set "
        "ARCHSIM_EMBEDDED_WORKER=false on the API instances)",
    )
    w.add_argument(
        "--slots",
        type=_slots,
        default=None,
        help="runs simulated at once (default: ARCHSIM_MAX_CONCURRENT_RUNS, which defaults to "
        "the CPU count - 1)",
    )
    w.set_defaults(fn=cmd_worker)
    sub.add_parser(
        "migrate",
        help="apply database migrations, then exit (safe to run from several processes at once "
        "on PostgreSQL: they take turns)",
    ).set_defaults(fn=cmd_migrate)
    sub.add_parser("seed", help="create example projects").set_defaults(fn=cmd_seed)
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
