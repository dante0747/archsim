"""Cloud prices: versioned snapshots of on-demand prices, and the machine shapes they price.

A design picks a price source with ``pricing: {provider, region, purchaseOption, families,
snapshotDate, snapshotSha256}``. ``generic`` (the default) is ArchSim's illustrative per-vCPU,
per-GiB and per-GiB-month rates, with no machine shapes: parts keep their computed sizes. Any
other provider needs a snapshot file (format ``archsim.prices/1``, see docs/pricing.md) in this
package's directory or in ``ARCHSIM_PRICES_DIR``. With a snapshot, auto-sizing snaps each part to
the cheapest instance type that covers its CPU and memory, and every cost line cites that SKU and
the snapshot date.

``snapshotDate`` (and ``snapshotSha256``, the hash of its content) pin one snapshot: exactly that
one is used, or none (a validation warning, generic rates). Unpinned, the newest snapshot for the
provider and region is used, and a run records which (``pinned``), so its input reproduces it.

Snapshots are data written by ``scripts/prices/`` from the providers' public price lists; nothing
here calls a network. This package ships no provider snapshot yet: which provider and region to
price first is the project owner's decision.
"""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

FORMAT = "archsim.prices/1"
DIR = Path(__file__).parent
PurchaseOption = Literal["on_demand"]
Category = Literal["compute", "database", "cache", "stream"]

# the illustrative rates of the "generic" provider (also cost.py's)
GENERIC_VCPU_HOUR = 0.037
GENERIC_GIB_HOUR = 0.0028
GENERIC_STORAGE_GIB_MONTH = 0.08

MANAGED_TECHNOLOGIES = frozenset({"aws_alb", "api_gateway", "cdn", "dynamodb", "sqs", "pubsub"})
"""Managed services: the provider sizes them, so they never run on an instance type."""


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class SkuModel(_M):
    sku: str = Field(min_length=1, max_length=80)
    family: str = Field(min_length=1, max_length=40)
    vcpu: float = Field(gt=0, le=1024)
    memory_gib: float = Field(alias="memoryGib", gt=0, le=32768)
    hourly: dict[str, float] = Field(description="Price per hour by purchase option")
    engines: list[str] = Field(
        default_factory=list, description="For databases, caches and streams: the engines it runs"
    )


class RatesModel(_M):
    storage_gib_month: float | None = Field(default=None, alias="storageGibMonth", ge=0)
    serverless_gb_second: float | None = Field(default=None, alias="serverlessGbSecond", ge=0)
    serverless_per_million_requests: float | None = Field(
        default=None, alias="serverlessPerMillionRequests", ge=0
    )
    queue_per_million_requests: float | None = Field(
        default=None, alias="queuePerMillionRequests", ge=0
    )
    load_balancer_hour: float | None = Field(default=None, alias="loadBalancerHour", ge=0)


class SourceModel(_M):
    name: str = Field(max_length=200)
    url: str = Field(max_length=500)


class SnapshotModel(_M):
    format: Literal["archsim.prices/1"]
    provider: str = Field(pattern=r"^[a-z0-9-]{1,40}$")
    region: str = Field(pattern=r"^[a-z0-9-]{1,40}$")
    currency: Literal["USD"] = "USD"
    snapshot_date: str = Field(alias="snapshotDate", pattern=r"^\d{4}-\d{2}-\d{2}$")
    sources: list[SourceModel] = Field(min_length=1)
    note: str | None = Field(default=None, max_length=500)
    compute: list[SkuModel] = Field(default_factory=list)
    databases: list[SkuModel] = Field(default_factory=list)
    caches: list[SkuModel] = Field(default_factory=list)
    streams: list[SkuModel] = Field(default_factory=list)
    rates: RatesModel = Field(default_factory=RatesModel)


@dataclass(frozen=True)
class Sku:
    sku: str
    family: str
    vcpu: float
    memory_gib: float
    hourly: dict[str, float]
    engines: tuple[str, ...] = ()

    def price(self, option: str) -> float | None:
        return self.hourly.get(option)


@dataclass(frozen=True)
class Snapshot:
    provider: str
    region: str | None
    snapshot_date: str | None
    sources: tuple[tuple[str, str], ...]
    currency: str = "USD"
    illustrative: bool = False
    note: str | None = None
    skus: dict[str, tuple[Sku, ...]] = field(default_factory=dict)
    rates: dict[str, float | None] = field(default_factory=dict)
    sha256: str | None = None
    """Hash of the snapshot's content (canonical JSON, so line endings do not change it)."""

    @property
    def label(self) -> str:
        if self.illustrative:
            return "generic (illustrative rates)"
        return f"{self.provider} {self.region} ({self.snapshot_date})"

    def has_shapes(self) -> bool:
        return any(self.skus.values())

    def cover(
        self,
        category: Category,
        cpu: float | None,
        memory_gib: float | None,
        option: str = "on_demand",
        engine: str | None = None,
        families: list[str] | tuple[str, ...] = (),
    ) -> Sku | None:
        """The cheapest SKU of ``category`` with at least ``cpu`` vCPU and ``memory_gib`` GiB (a
        database, cache or stream SKU must run ``engine``). ``families`` restrict a price list
        that has any of them (naming compute families leaves the database list alone). Ties go to
        the smaller machine, then the name. None when nothing fits."""
        items = self.skus.get(category) or ()
        if not any(s.family in families for s in items):
            families = ()
        fits = [
            s
            for s in items
            if s.vcpu >= (cpu or 0) - 1e-9
            and s.memory_gib >= (memory_gib or 0) - 1e-9
            and s.price(option) is not None
            and (not families or s.family in families)
            and (not engine or not s.engines or engine in s.engines)
        ]
        if not fits:
            return None
        return min(fits, key=lambda s: (s.price(option), s.vcpu, s.memory_gib, s.sku))

    def to_json(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "region": self.region,
            "snapshotDate": self.snapshot_date,
            "currency": self.currency,
            "illustrative": self.illustrative,
            "sources": [{"name": n, "url": u} for n, u in self.sources],
            "note": self.note,
            "sha256": self.sha256,
        }


@dataclass(frozen=True)
class Machine:
    """The instance type a part runs on in a snapshot. ``sku`` is None when no type covers the
    size; ``self_managed`` when the engine has no managed offering there (it runs on VMs)."""

    category: Category
    engine: str | None
    sku: Sku | None
    self_managed: bool = False


def machine_kind(node_type: str, cfg: dict[str, Any]) -> tuple[Category, str | None] | None:
    """Which price list a part's machines come from, or None when it is not billed per machine
    (clients, third-party APIs, services on Kubernetes or serverless, managed services)."""
    tech = cfg.get("technology") if isinstance(cfg.get("technology"), str) else None
    if tech in MANAGED_TECHNOLOGIES:
        return None
    if node_type == "service":
        return None if cfg.get("platform") in ("kubernetes", "serverless") else ("compute", None)
    if node_type == "database":
        return "database", tech or "postgresql"
    if node_type == "cache":
        return "cache", tech or "redis"
    if node_type == "message_queue":
        return ("stream", "kafka") if tech == "kafka" else ("compute", None)
    if node_type in ("load_balancer", "kubernetes_cluster"):
        return "compute", None
    return None


def choose_machine(
    snap: Snapshot,
    node_type: str,
    cfg: dict[str, Any],
    cpu: float | None,
    memory_gib: float | None,
    option: str = "on_demand",
    families: list[str] | tuple[str, ...] = (),
) -> Machine | None:
    """The cheapest instance type for a part of this size (None: not billed per machine, or the
    snapshot has no shapes). A database, cache or stream engine the snapshot has no managed
    type for runs on compute instances (self-managed)."""
    kind = machine_kind(node_type, cfg)
    if kind is None or not snap.has_shapes():
        return None
    category, engine = kind
    sku = snap.cover(category, cpu, memory_gib, option, engine, families)
    if (
        sku is None
        and category != "compute"
        and not any(engine in s.engines for s in snap.skus.get(category) or ())
    ):
        sku = snap.cover("compute", cpu, memory_gib, option, None, families)
        return Machine("compute", engine, sku, self_managed=True)
    return Machine(category, engine, sku)


GENERIC = Snapshot(
    provider="generic",
    region=None,
    snapshot_date=None,
    sources=(),
    illustrative=True,
    note="Illustrative on-demand rates, roughly a large cloud's general-purpose VMs; no machine shapes.",
    rates={
        "vcpuHour": GENERIC_VCPU_HOUR,
        "gibHour": GENERIC_GIB_HOUR,
        "storageGibMonth": GENERIC_STORAGE_GIB_MONTH,
    },
)


class PricingError(ValueError):
    pass


def content_sha256(data: dict[str, Any]) -> str:
    """Hash of a snapshot's JSON content: canonical (sorted keys, no whitespace), so the same
    prices give the same hash whatever the file's formatting or line endings."""
    text = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _from_model(m: SnapshotModel, sha256: str | None = None) -> Snapshot:
    def skus(items: list[SkuModel]) -> tuple[Sku, ...]:
        return tuple(
            Sku(s.sku, s.family, s.vcpu, s.memory_gib, dict(s.hourly), tuple(s.engines))
            for s in items
        )

    return Snapshot(
        provider=m.provider,
        region=m.region,
        snapshot_date=m.snapshot_date,
        sources=tuple((s.name, s.url) for s in m.sources),
        currency=m.currency,
        note=m.note,
        skus={
            "compute": skus(m.compute),
            "database": skus(m.databases),
            "cache": skus(m.caches),
            "stream": skus(m.streams),
        },
        rates={
            "storageGibMonth": m.rates.storage_gib_month,
            "serverlessGbSecond": m.rates.serverless_gb_second,
            "serverlessPerMillionRequests": m.rates.serverless_per_million_requests,
            "queuePerMillionRequests": m.rates.queue_per_million_requests,
            "loadBalancerHour": m.rates.load_balancer_hour,
        },
        sha256=sha256,
    )


def parse_snapshot(data: dict[str, Any]) -> Snapshot:
    """A snapshot from its JSON (``archsim.prices/1``); raises ``PricingError`` with the reason."""
    try:
        return _from_model(SnapshotModel.model_validate(data), content_sha256(data))
    except ValidationError as exc:
        first = exc.errors()[0]
        where = ".".join(str(p) for p in first["loc"])
        raise PricingError(f"not a valid price snapshot ({where}: {first['msg']})") from None


def snapshot_dirs() -> list[Path]:
    extra = os.environ.get("ARCHSIM_PRICES_DIR")
    return [Path(p) for p in extra.split(os.pathsep) if p] + [DIR] if extra else [DIR]


@lru_cache(maxsize=64)
def _load(path: str, mtime: float) -> Snapshot:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return parse_snapshot(data)


def _snapshots() -> list[tuple[Snapshot, Path]]:
    out = []
    for d in snapshot_dirs():
        for p in sorted(d.glob("*.json")) if d.is_dir() else []:
            try:
                out.append((_load(str(p), p.stat().st_mtime), p))
            except (OSError, ValueError):
                continue  # not a snapshot (or unreadable): not offered
    return out


def available(every_date: bool = False) -> list[dict[str, Any]]:
    """Snapshots that can be chosen: the newest per provider and region, or (``every_date``)
    every snapshot, each marked ``newest`` or not. Sorted by provider and region, newest first."""
    found: dict[tuple[str, str, str, str], tuple[Snapshot, Path]] = {}
    for snap, path in _snapshots():
        key = (snap.provider, snap.region or "", snap.snapshot_date or "", snap.sha256 or "")
        found.setdefault(key, (snap, path))  # the same content in two directories: listed once
    newest: dict[tuple[str, str], str] = {}
    for provider, region, date, _ in found:
        if date > newest.get((provider, region), ""):
            newest[(provider, region)] = date
    ordered = sorted(found, key=lambda k: k[3])
    ordered = sorted(ordered, key=lambda k: k[2], reverse=True)
    ordered = sorted(ordered, key=lambda k: (k[0], k[1]))
    out = []
    for key in ordered:
        s, p = found[key]
        is_newest = newest.get((key[0], key[1]), "") == key[2]
        if is_newest or every_date:
            out.append(
                {
                    "provider": s.provider,
                    "region": s.region or "",
                    "date": s.snapshot_date or "",
                    "file": p.name,
                    "sha256": s.sha256 or "",
                    "newest": is_newest,
                }
            )
    return out


def load_snapshot(
    provider: str = "generic",
    region: str | None = None,
    date: str | None = None,
    sha256: str | None = None,
) -> Snapshot:
    """The newest snapshot for ``provider`` and ``region`` (``generic`` needs none); with
    ``date`` (and ``sha256``) exactly that snapshot, never another. Raises ``PricingError``
    naming what this machine has when there is none."""
    if provider == "generic":
        return GENERIC
    best: Snapshot | None = None
    for snap, _ in _snapshots():
        matches = (
            snap.provider == provider
            and (region is None or snap.region == region)
            and (date is None or snap.snapshot_date == date)
            and (sha256 is None or snap.sha256 == sha256)
        )
        if matches and (best is None or (snap.snapshot_date or "") > (best.snapshot_date or "")):
            best = snap
    if best is not None:
        return best
    wanted = f"no price snapshot for {provider} {region or ''}".rstrip()
    if date is not None:
        wanted += f" of {date}"
    if sha256 is not None:
        wanted += f" with content {sha256[:12]}"
    same = [
        a
        for a in available(every_date=True)
        if a["provider"] == provider and (region is None or a["region"] == region)
    ]
    if (date is not None or sha256 is not None) and same:
        have = ", ".join(f"{a['region']} {a['date']} ({a['sha256'][:12]})" for a in same)
        raise PricingError(
            f"{wanted} (this machine has {provider} {have}); a pinned snapshot is never "
            "swapped for another."
        )
    have = ", ".join(f"{a['provider']} {a['region']}" for a in available()) or "none"
    raise PricingError(f"{wanted} (available: generic; snapshots: {have}).")


def snapshot_for(pricing: dict[str, Any] | None) -> Snapshot:
    """The snapshot a document's ``pricing`` names (generic when unset): the pinned one when it
    has ``snapshotDate`` (and ``snapshotSha256``), else the newest for its provider and region."""
    p = pricing or {}
    return load_snapshot(
        str(p.get("provider") or "generic"),
        p.get("region"),
        p.get("snapshotDate"),
        p.get("snapshotSha256"),
    )


def pinned(pricing: dict[str, Any] | None) -> dict[str, Any] | None:
    """``pricing`` with the snapshot it resolves to written in (``region``, ``snapshotDate``,
    ``snapshotSha256``), so a run's input names exactly the prices it used and re-running that
    input reproduces it. Unchanged for generic prices, or when no snapshot is found (validation
    warns, and generic rates are used)."""
    p = pricing or {}
    if str(p.get("provider") or "generic") == "generic":
        return pricing
    try:
        snap = snapshot_for(p)
    except PricingError:
        return pricing
    return {
        **p,
        "region": snap.region,
        "snapshotDate": snap.snapshot_date,
        "snapshotSha256": snap.sha256,
    }


def unknown_families(snap: Snapshot, families: list[str] | tuple[str, ...]) -> list[str]:
    """``families`` that are in none of the snapshot's price lists (they restrict nothing)."""
    known = {s.family for items in snap.skus.values() for s in items}
    return [f for f in families if f not in known]


__all__ = [
    "FORMAT",
    "GENERIC",
    "MANAGED_TECHNOLOGIES",
    "Machine",
    "PricingError",
    "Sku",
    "Snapshot",
    "available",
    "choose_machine",
    "content_sha256",
    "load_snapshot",
    "machine_kind",
    "parse_snapshot",
    "pinned",
    "snapshot_for",
    "unknown_families",
]
