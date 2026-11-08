"""Write the simulation's random draws for the Java engine, or check the committed file.

The Java kernel draws the same random numbers as the Python engine: each stream is Python's
Mersenne Twister, seeded as ``runtime.derive_rng`` seeds it, and sampled with Python 3.12's own
algorithms. This script asks the frozen engine (0.16.0) and Python's ``random`` for:

- ``streams``: the first ``random()`` draws of ``derive_rng(seed, stream)`` for seeds at the
  edges of their range and stream names the engine uses (one non-ASCII);
- ``seeds``: the first draws of ``random.Random(n)`` for integers whose seeding key is one word,
  two words, or zero;
- ``bits``: ``getrandbits(k)`` for every k from 1 to 32, and ``randrange(n)`` for small, odd and
  large ``n`` (rejection sampling draws again when a value is too big);
- ``samplers``: ``uniform``, ``expovariate``, ``normalvariate`` and ``lognormvariate`` on a few
  parameters, interleaved on one generator so the draws each consumes are checked too;
- ``distributions``: each distribution of the model sampled as the engine samples it
  (``Distribution.sample``), including the 10-minute cap, a lognormal with no spread and an
  empirical table read at its ends;
- ``zipf``: ``engine.ZipfSampler`` on a few key counts and skews.

Floats are written as Python's ``float.hex()`` so the Java tests can compare them bit for bit.
The draws that go through ``log`` or ``exp`` (``expovariate``, ``lognormvariate``, the exponential
and lognormal distributions) are marked ``throughLibm``: Java's ``StrictMath`` and the C library
Python calls may round them differently in the last place, so the tests compare those within a
tolerance.

``--check`` compares the text exactly, so it holds on the platform that wrote the file (Python
3.12 on Windows, its C runtime's ``log`` and ``exp``): a CPython on glibc may differ in the last
bit of those draws. It is a manual step for that reason, not a CI one.

Usage:
    uv run python scripts/export_random_cases.py           # write the cases
    uv run python scripts/export_random_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Any

from pydantic import TypeAdapter

from archsim_engine.distributions import Distribution
from archsim_engine.engine import MAX_SEED, ZipfSampler
from archsim_engine.runtime import derive_rng

GENERATOR = "scripts/export_random_cases.py (archsim_engine 0.16.0, Python 3.12)"
THROUGH_LIBM_SAMPLERS = {"expovariate", "lognormvariate"}
THROUGH_LIBM_KINDS = {"exponential", "lognormal"}
OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/random/cases.json"
)
Json = dict[str, Any]
DRAWS = 12

STREAM_SEEDS = [0, 1, 7, 42, 20261108, MAX_SEED]
STREAM_NAMES = [
    "arrivals",
    "arrivals:thinning",
    "mix",
    "keys",
    "node:db:service",
    "edge:e1:latency",
    "node:café-дб:flush",
]
INTEGER_SEEDS = [0, 1, 5489, 2**32 - 1, 2**32, 2**53 + 1, 2**64 - 1]
RANGES = [1, 2, 3, 5, 7, 10, 100, 1000, 65537, 2**30 + 1, 2**31 - 1]
DISTRIBUTIONS: list[Json] = [
    {"kind": "constant", "value": 12.5},
    {"kind": "constant", "value": 0},
    {"kind": "exponential", "mean": 20},
    {"kind": "exponential", "mean": 600000},
    {"kind": "uniform", "min": 5, "max": 15},
    {"kind": "uniform", "min": 3, "max": 3},
    {"kind": "lognormal", "median": 10, "p95": 40},
    {"kind": "lognormal", "median": 8, "p95": 8},
    {"kind": "lognormal", "median": 300000, "p95": 600000},
    {"kind": "empirical", "quantiles": [[0, 1], [0.5, 4], [0.9, 10], [1, 50]]},
    {"kind": "empirical", "quantiles": [[0, 0], [1, 0]]},
]
ZIPF = [(1, 1.0), (5, 0.0), (100, 0.99), (10000, 1.2), (1000000, 0.8)]


def hexes(values: list[float]) -> list[str]:
    """Floats as Python writes them in hex, exactly."""
    return [float(v).hex() for v in values]


def streams() -> list[Json]:
    """The first draws of each derived stream."""
    return [
        {"seed": seed, "stream": name, "random": hexes([rng.random() for _ in range(DRAWS)])}
        for seed in STREAM_SEEDS
        for name in STREAM_NAMES
        for rng in [derive_rng(seed, name)]
    ]


def seeds() -> list[Json]:
    """The first 32-bit outputs and ``random()`` draws of generators seeded with integers."""
    out = []
    for seed in INTEGER_SEEDS:
        words = random.Random(seed)
        draws = random.Random(seed)
        out.append(
            {
                "seed": str(seed),
                "words": [words.getrandbits(32) for _ in range(DRAWS)],
                "random": hexes([draws.random() for _ in range(DRAWS)]),
            }
        )
    return out


def bits() -> Json:
    """``getrandbits`` for every width up to 32 and ``randrange`` on one generator each."""
    rng = random.Random(20261108)
    widths = [{"k": k, "value": rng.getrandbits(k)} for k in range(1, 33) for _ in range(3)]
    rng = random.Random(20261109)
    ranges = [{"n": n, "value": rng.randrange(n)} for n in RANGES for _ in range(6)]
    return {"seed": 20261108, "getrandbits": widths, "rangeSeed": 20261109, "randrange": ranges}


def samplers() -> Json:
    """Each sampler in turn on one generator, so a sampler that consumes the wrong number of
    draws shifts every later value."""
    rng = random.Random(42)
    calls: list[Json] = []
    plan: list[tuple[str, tuple[float, ...]]] = [
        ("uniform", (0.0, 1.0)),
        ("uniform", (5.0, 15.0)),
        ("uniform", (3.0, 3.0)),
        ("expovariate", (0.05,)),
        ("expovariate", (1.0,)),
        ("expovariate", (2500.0,)),
        ("normalvariate", (0.0, 1.0)),
        ("normalvariate", (2.302585092994046, 0.8428871226205566)),
        ("lognormvariate", (2.302585092994046, 0.8428871226205566)),
        ("lognormvariate", (0.0, 3.0)),
    ]
    for _ in range(25):
        for name, args in plan:
            value = getattr(rng, name)(*args)
            calls.append(
                {
                    "sampler": name,
                    "args": hexes(list(args)),
                    "value": float(value).hex(),
                    "throughLibm": name in THROUGH_LIBM_SAMPLERS,
                }
            )
    return {"seed": 42, "calls": calls}


def distributions() -> list[Json]:
    """The model's distributions sampled as the engine samples them."""
    adapter: TypeAdapter[Any] = TypeAdapter(Distribution)
    out = []
    for raw in DISTRIBUTIONS:
        dist = adapter.validate_python(raw)
        rng = derive_rng(7, "node:svc:service")
        out.append(
            {
                "distribution": raw,
                "samples": hexes([dist.sample(rng) for _ in range(40)]),
                "throughLibm": raw["kind"] in THROUGH_LIBM_KINDS,
            }
        )
    return out


def zipf() -> list[Json]:
    """Key ranks drawn by the engine's Zipf sampler."""
    out = []
    for n, s in ZIPF:
        sampler = ZipfSampler(n, s, derive_rng(7, "keys"))
        out.append({"keys": n, "skew": s, "ranks": [sampler.sample() for _ in range(60)]})
    return out


def export() -> str:
    """The cases file's text."""
    cases = {
        "generator": GENERATOR,
        "streams": streams(),
        "seeds": seeds(),
        "bits": bits(),
        "samplers": samplers(),
        "distributions": distributions(),
        "zipf": zipf(),
    }
    return json.dumps(cases, indent=1, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_random_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's random draws")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
