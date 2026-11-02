"""Price snapshots: parsing, hashes, listing, lookups and pinning, and covering a size."""

from __future__ import annotations

import json
from functools import cache
from typing import Any

from archsim_engine.pricing import (
    Busy,
    Sku,
    Snapshot,
    available,
    content_sha256,
    default_pricing,
    load_snapshot,
    machine_kind,
    parse_snapshot,
    snapshot_for,
    unknown_families,
)
from golden.adapters.support import Adapters
from golden.codec import Json, canonical, from_json, to_json

ADAPTERS = Adapters()


def snapshot_json(snap: Snapshot) -> Json:
    """A price snapshot in full: ``Snapshot.to_json()`` plus its instance types and rates."""
    return {
        **snap.to_json(),
        "skus": {category: [to_json(s) for s in items] for category, items in snap.skus.items()},
        "rates": dict(snap.rates),
    }


def snapshot_of(data: Json) -> Snapshot:
    return _snapshot(canonical(data))


@cache
def _snapshot(text: bytes) -> Snapshot:
    data = json.loads(text)
    return Snapshot(
        provider=data["provider"],
        region=data["region"],
        snapshot_date=data["snapshotDate"],
        sources=tuple((s["name"], s["url"]) for s in data["sources"]),
        currency=data["currency"],
        illustrative=data["illustrative"],
        note=data["note"],
        skus={c: tuple(from_json(Sku, s) for s in items) for c, items in data["skus"].items()},
        rates=data["rates"],
        sha256=data["sha256"],
    )


@ADAPTERS.add("archsim_engine.pricing.parse_snapshot")
def _parse_snapshot(i: Json) -> Any:
    return snapshot_json(parse_snapshot(i["data"]))


@ADAPTERS.add("archsim_engine.pricing.content_sha256")
def _content_sha256(i: Json) -> Any:
    return content_sha256(i["data"])


@ADAPTERS.add("archsim_engine.pricing.available")
def _available(i: Json) -> Any:
    return available(every_date=i["everyDate"])


@ADAPTERS.add("archsim_engine.pricing.default_pricing")
def _default_pricing(_: Json) -> Any:
    return default_pricing()


@ADAPTERS.add("archsim_engine.pricing.load_snapshot")
def _load_snapshot(i: Json) -> Any:
    return load_snapshot(i["provider"], i["region"], i["date"], i["sha256"]).to_json()


@ADAPTERS.add("archsim_engine.pricing.snapshot_for")
def _snapshot_for(i: Json) -> Any:
    return snapshot_for(i["pricing"]).to_json()


@ADAPTERS.add("archsim_engine.pricing.unknown_families")
def _unknown_families(i: Json) -> Any:
    return unknown_families(snapshot_of(i["snapshot"]), i["families"])


@ADAPTERS.add("archsim_engine.pricing.Snapshot.cover")
def _cover(i: Json) -> Any:
    busy = Busy(i["busy"]["peak"], i["busy"]["base"]) if i["busy"] else None
    return snapshot_of(i["snapshot"]).cover(
        i["category"],
        i["cpu"],
        i["memoryGib"],
        i["option"],
        i["engine"],
        i["families"],
        busy,
        i["burstable"],
    )


@ADAPTERS.add("archsim_engine.pricing.machine_kind")
def _machine_kind(i: Json) -> Any:
    return machine_kind(i["nodeType"], i["cfg"])
