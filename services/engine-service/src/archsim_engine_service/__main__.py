"""``archsim-engine-service``: serve the simulation a calibration replays on an internal port
(ADR-0045)."""

from __future__ import annotations

import argparse


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="archsim-engine-service",
        description="Serve the replays of calibrations for the Java API (internal only).",
    )
    parser.add_argument(
        "--host", default="127.0.0.1", help="address to listen on (default: 127.0.0.1)"
    )
    parser.add_argument("--port", type=int, default=8001, help="port to listen on (default: 8001)")
    args = parser.parse_args(argv)

    import uvicorn

    uvicorn.run(
        "archsim_engine_service.app:create_app",
        factory=True,
        host=args.host,
        port=args.port,
        access_log=False,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
