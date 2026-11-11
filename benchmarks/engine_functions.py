"""The engine's functions the harness calls, answered by the Java engine (ADR-0061).

The harness used to import them from the frozen Python engine; it now asks the Java engine,
which the golden files hold to the same answers. One ``archsim functions`` process per harness
process answers every call as a line of JSON, so a call costs no start of the JVM: the
machine-speed fit alone makes dozens. The process starts on the first call, from the CLI that
``./gradlew :archsim-cli:installDist`` installs, and ends with the harness.

The functions take the Python engine's arguments and give its results: ``expected_ms`` (a
distribution's mean), ``machine_speeds`` (``Machine.speed`` at each busy count),
``load_factors`` (``load_factor`` over ``curve_of`` a table), and the resource fits
``fit_load_curve``, ``extend_load_curve``, ``fit_memory`` and ``fit_cpu_share``, with
``min_curve_change`` and ``min_per_request_mb``. An input the engine refuses is a ValueError in
its words, as Python's was.
"""

from __future__ import annotations

import atexit
import functools
import json
import os
import subprocess
import threading
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CLI = (
    Path(__file__).resolve().parent.parent
    / "archsim-cli"
    / "build"
    / "install"
    / "archsim"
    / "bin"
    / ("archsim.bat" if os.name == "nt" else "archsim")
)
"""The Java engine's command, as ``./gradlew :archsim-cli:installDist`` installs it."""

_ERRORS: dict[str, type[Exception]] = {"ValueError": ValueError, "LookupError": LookupError}
"""The errors ``archsim functions`` answers, by name."""


def require_installed(cli: Path = CLI) -> None:
    """RuntimeError, saying how to install it, when the Java engine's command is not there."""
    if not cli.exists():
        raise RuntimeError(
            f"the Java engine is not installed at {cli}: run ./gradlew :archsim-cli:installDist"
        )


@dataclass(frozen=True)
class LoadCurve:
    """CPU per request against the rate one replica gets (``fit_load_curve``)."""

    points: list[tuple[float, float]]
    reference_ms: float
    measured: list[tuple[float, float]]
    error: float | None
    rows: list[dict[str, float]]
    """``serviceTimeByLoad`` as the engine writes it: ``table()``."""

    def table(self) -> list[dict[str, float]]:
        """``serviceTimeByLoad``: the factor at each rate, rounded as the engine sets it."""
        return [dict(row) for row in self.rows]


@dataclass(frozen=True)
class CurveExtension:
    """How CPU per request continues above a curve's top rate (``extend_load_curve``)."""

    top_rps: float
    top_ms: float
    busy: float
    slope: float
    error: float | None
    points: list[tuple[float, float]]
    reason: str

    def factors(self) -> list[tuple[float, float]]:
        """The points as multiples of the top rate's CPU per request."""
        return [(r, m / self.top_ms) for r, m in self.points]


@dataclass(frozen=True)
class MemoryFit:
    """A working set's fit to the request rate and requests held (``fit_memory``)."""

    baseline_mb: float
    per_rps_mb: float
    per_request_mb: float
    error: float | None
    points: int
    against: tuple[str, ...]
    notes: tuple[str, ...]

    def predict(self, rate: float = 0.0, held: float = 0.0) -> float:
        """Working set per replica (MiB) at ``rate`` req/s and ``held`` requests per replica."""
        return self.baseline_mb + self.per_rps_mb * rate + self.per_request_mb * held


@dataclass(frozen=True)
class CpuFit:
    """``cpuShare`` fitted to (req/s, cores) points (``fit_cpu_share``)."""

    share: float
    cpu_ms: float
    service_ms: float
    capped: bool
    error: float | None
    curve: LoadCurve | None
    points: int
    notes: tuple[str, ...]

    def predict(self, rate: float) -> float:
        """Cores the fitted model uses at ``rate`` req/s."""
        return rate * self.service_ms * self.share / 1000.0


class Functions:
    """One ``archsim functions`` process, answering calls in turn."""

    def __init__(self, cli: Path = CLI) -> None:
        require_installed(cli)
        self._process = subprocess.Popen(
            [str(cli), "functions"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        self._lock = threading.Lock()

    def call(self, function: str, **inputs: Any) -> Any:
        """The output of ``function`` for ``inputs``; ValueError for an input it refuses,
        LookupError for a function it does not have."""
        request = json.dumps({"function": function, "input": inputs}) + "\n"
        assert self._process.stdin is not None and self._process.stdout is not None
        with self._lock:
            self._process.stdin.write(request)
            self._process.stdin.flush()
            line = self._process.stdout.readline()
        if not line:
            raise RuntimeError(
                f"archsim functions ended (exit {self._process.wait(timeout=30)}) on {function}: "
                "its standard error, above, says why"
            )
        answer: dict[str, Any] = json.loads(line)
        if "error" in answer:
            error = answer["error"]
            raise _ERRORS.get(error["type"], RuntimeError)(error["message"])
        return answer["output"]

    def close(self) -> None:
        """Ends the process: its input ends, and it exits."""
        if self._process.stdin is not None and not self._process.stdin.closed:
            self._process.stdin.close()
        self._process.wait(timeout=30)


_functions: Functions | None = None
_owner: int | None = None


def functions() -> Functions:
    """This process's ``archsim functions``, started on the first call. A forked worker starts
    its own: two processes never share one's pipes."""
    global _functions, _owner
    if _functions is None or _owner != os.getpid():
        _functions, _owner = Functions(), os.getpid()
        atexit.register(_functions.close)
    return _functions


def expected_ms(distribution: Mapping[str, Any]) -> float:
    """The mean of a model input's distribution as the engine samples it (ms)."""
    return float(functions().call("expected", distribution=dict(distribution)))


def machine_speeds(machine: Mapping[str, float], busy: Sequence[float]) -> list[float]:
    """``Machine.speed`` at each busy count: the mean speed of a busy thread when that many want
    the CPU, on a machine of ``cores``, ``threadsPerCore``, ``siblingSpeed`` and
    ``allCoreClock``, with no other load."""
    return [
        float(s) for s in functions().call("machine_speed", machine=dict(machine), busy=list(busy))
    ]


def load_factors(table: Sequence[Mapping[str, Any]], rates: Sequence[float]) -> list[float]:
    """A ``serviceTimeByLoad`` table's factor at each rate (one replica's req/s): interpolated in
    log(rate) between its points, the nearest point's outside them, 1 without points; points
    without a positive rate and factor are left out."""
    return [
        float(f)
        for f in functions().call(
            "load_factor", table=[dict(row) for row in table], rps=list(rates)
        )
    ]


def fit_load_curve(
    points: Sequence[tuple[float, float]], reference_ms: float | None = None, max_bins: int = 6
) -> LoadCurve | None:
    """The never-rising curve of CPU per request against the rate, or None."""
    out = functions().call(
        "fit_load_curve",
        points=[list(p) for p in points],
        referenceMs=reference_ms,
        maxBins=max_bins,
    )
    return _curve(out) if out is not None else None


def extend_load_curve(
    passes: Sequence[tuple[float, float]], cores: float = 1.0
) -> CurveExtension | None:
    """How CPU per request continues above a staircase's top, or None."""
    out = functions().call("extend_load_curve", passes=[list(p) for p in passes], cores=cores)
    if out is None:
        return None
    return CurveExtension(
        top_rps=out["topRps"],
        top_ms=out["topMs"],
        busy=out["busy"],
        slope=out["slope"],
        error=out["error"],
        points=_pairs(out["points"]),
        reason=out["reason"],
    )


def fit_memory(
    working_set_mb: Sequence[float],
    rate: Sequence[float] | None = None,
    held: Sequence[float] | None = None,
) -> MemoryFit | None:
    """The working set's fit by non-negative least squares, or None; ValueError without
    ``rate`` or ``held``."""
    out = functions().call(
        "fit_memory",
        workingSetMb=list(working_set_mb),
        rate=list(rate) if rate is not None else None,
        held=list(held) if held is not None else None,
    )
    if out is None:
        return None
    return MemoryFit(
        baseline_mb=out["baselineMb"],
        per_rps_mb=out["perRpsMb"],
        per_request_mb=out["perRequestMb"],
        error=out["error"],
        points=out["points"],
        against=tuple(out["against"]),
        notes=tuple(out["notes"]),
    )


def fit_cpu_share(
    points: Sequence[tuple[float, float]], service_ms: float, replicas: float = 1.0
) -> CpuFit | None:
    """``cpuShare`` from (req/s, cores) points and the mean service time (ms), or None."""
    out = functions().call(
        "fit_cpu_share", points=[list(p) for p in points], serviceMs=service_ms, replicas=replicas
    )
    if out is None:
        return None
    return CpuFit(
        share=out["share"],
        cpu_ms=out["cpuMs"],
        service_ms=out["serviceMs"],
        capped=out["capped"],
        error=out["error"],
        curve=_curve(out["curve"]) if out["curve"] is not None else None,
        points=out["points"],
        notes=tuple(out["notes"]),
    )


def min_curve_change() -> float:
    """The share a fitted curve's factors must span to be set."""
    return float(_constants()["minCurveChange"])


def min_per_request_mb() -> float:
    """The smallest memory per request held calibration sets (MiB)."""
    return float(_constants()["minPerRequestMb"])


@functools.cache
def _constants() -> dict[str, Any]:
    constants: dict[str, Any] = functions().call("constants")
    return constants


def _pairs(points: Sequence[Mapping[str, float]]) -> list[tuple[float, float]]:
    return [(p["rate"], p["value"]) for p in points]


def _curve(out: Mapping[str, Any]) -> LoadCurve:
    return LoadCurve(
        points=_pairs(out["points"]),
        reference_ms=out["referenceMs"],
        measured=_pairs(out["measured"]),
        error=out["error"],
        rows=list(out["table"]),
    )
