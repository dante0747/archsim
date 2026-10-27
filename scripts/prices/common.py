"""Shared pieces of the price refresh scripts: download with a local cache, and write a snapshot
in ArchSim's format (``archsim.prices/1``), validated by the engine before it is saved.

These scripts are run by hand to refresh the data files; ArchSim never calls them (or any price
API) at runtime. Downloads go only to the provider's public price endpoint named in each script.
"""

from __future__ import annotations

import datetime as dt
import json
import sys
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT_DIR = ROOT / "packages" / "engine" / "src" / "archsim_engine" / "pricing"
USER_AGENT = "archsim-price-refresh"


def fetch(url: str, cache: Path | None = None, timeout: float = 120.0) -> Path | bytes:
    """GET ``url``. With ``cache`` (a file path), stream it there once and reuse it."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if cache is not None:
        if cache.exists() and cache.stat().st_size > 0:
            return cache
        cache.parent.mkdir(parents=True, exist_ok=True)
        tmp = cache.with_suffix(cache.suffix + ".part")
        with urllib.request.urlopen(req, timeout=timeout) as r, tmp.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        tmp.replace(cache)
        return cache
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data: bytes = r.read()
        return data


def gib(text: str) -> float | None:
    """'8 GiB', '6.38 GiB', '16 GB', '1,024 GiB' -> GiB (GB in price lists means GiB)."""
    t = text.replace(",", "").strip().split(" ")[0]
    try:
        return float(t)
    except ValueError:
        return None


def sku(
    sku_id: str,
    family: str,
    vcpu: float,
    memory: float,
    hourly: float,
    engines: list[str] | None = None,
    burstable: bool = False,
) -> dict[str, Any]:
    out: dict[str, Any] = {
        "sku": sku_id,
        "family": family,
        "vcpu": vcpu,
        "memoryGib": round(memory, 3),
        "hourly": {"on_demand": round(hourly, 6)},
    }
    if engines:
        out["engines"] = engines
    if burstable:
        out["burstable"] = True
    return out


def dedupe(items: list[dict[str, Any]], what: str) -> list[dict[str, Any]]:
    """One entry per instance type, shape and price: entries that differ only in their engines
    are merged (an ElastiCache type costs the same for Redis and Memcached). The same type and
    engine at two prices means a filter let a row through that it should not (an Outposts or
    Local Zone price, a Capacity Block): that raises, naming the type, rather than letting
    snapping pick the cheaper price by accident."""
    merged: dict[tuple[Any, ...], dict[str, Any]] = {}
    seen: dict[tuple[str, str], float] = {}
    for s in items:
        price = s["hourly"]["on_demand"]
        for engine in s.get("engines") or [""]:
            first = seen.setdefault((s["sku"], engine), price)
            if first != price:
                on = f" ({engine})" if engine else ""
                raise ValueError(
                    f"{what}: {s['sku']}{on} has two prices, {first} and {price}; "
                    "the price list format may have changed"
                )
        key = (s["sku"], s["family"], s["vcpu"], s["memoryGib"], price)
        if key in merged:
            engines = set(merged[key].get("engines", [])) | set(s.get("engines", []))
            merged[key]["engines"] = sorted(engines)
        else:
            merged[key] = {**s, **({"engines": sorted(s["engines"])} if s.get("engines") else {})}
    return list(merged.values())


def write_snapshot(snapshot: dict[str, Any], out: Path | None) -> Path:
    """Validate with the engine's own parser, then write ``<provider>-<region>-<date>.json``."""
    sys.path.insert(0, str(ROOT / "packages" / "engine" / "src"))
    from archsim_engine.pricing import parse_snapshot

    snapshot.setdefault("format", "archsim.prices/1")
    snapshot.setdefault("currency", "USD")
    snapshot.setdefault("snapshotDate", dt.date.today().isoformat())
    for key in ("compute", "databases", "caches", "streams"):
        snapshot[key] = sorted(
            dedupe(snapshot.get(key, []), key),
            key=lambda s: (s["family"], s["vcpu"], s["memoryGib"], s["sku"], s.get("engines", [])),
        )
    parse_snapshot(snapshot)  # raises with the reason when something is off
    path = out or SNAPSHOT_DIR / f"{snapshot['provider']}-{snapshot['region']}-{snapshot['snapshotDate']}.json"
    path.write_text(json.dumps(snapshot, indent=1) + "\n", encoding="utf-8", newline="\n")
    counts = {k: len(snapshot.get(k, [])) for k in ("compute", "databases", "caches", "streams")}
    print(f"wrote {path} {counts}", file=sys.stderr)
    return path
