"""Refresh a GCP price snapshot (Compute Engine machine types) from the Cloud Billing Catalog API.

    GOOGLE_API_KEY=... uv run python scripts/prices/gcp.py --region us-central1

The API needs an API key (any Google Cloud project's; read from GOOGLE_API_KEY, never written
anywhere). Compute Engine bills N2, N2D, E2 and C3 machines per vCPU-hour and per GiB-hour, so
each machine type's price is vCPU x the core price + memory x the RAM price, for the standard
(4 GiB per vCPU), highmem (8) and highcpu (1; C3: 2) shapes in each family's predefined sizes.
Endpoint:
https://cloudbilling.googleapis.com/v1/services/6F81-5844-456A/skus
(https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list). Run by hand;
ArchSim never calls it at runtime.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.parse
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from common import fetch, sku, write_snapshot

API = "https://cloudbilling.googleapis.com/v1/services/6F81-5844-456A/skus"
FAMILIES = {"N2": "n2", "N2D": "n2d", "E2": "e2", "C3": "c3"}
SHAPES = {"standard": 4, "highmem": 8, "highcpu": 1}
N2_SIZES = [2, 4, 8, 16, 32, 48, 64, 80, 96]
C3_SIZES = [4, 8, 22, 44, 88, 176]
SIZES = {
    ("n2", "standard"): N2_SIZES,
    ("n2", "highmem"): N2_SIZES,
    ("n2", "highcpu"): N2_SIZES,
    ("n2d", "standard"): N2_SIZES,
    ("n2d", "highmem"): N2_SIZES,
    ("n2d", "highcpu"): N2_SIZES,
    ("e2", "standard"): [2, 4, 8, 16, 32],
    ("e2", "highmem"): [2, 4, 8, 16],
    ("e2", "highcpu"): [2, 4, 8, 16, 32],
    ("c3", "standard"): C3_SIZES,
    ("c3", "highmem"): C3_SIZES,
    ("c3", "highcpu"): C3_SIZES,
}
LINE = re.compile(r"^(N2D|N2|E2|C3) Instance (Core|Ram) running in ")


def _unit_price(item: dict[str, Any]) -> float | None:
    for info in item.get("pricingInfo") or []:
        for rate in (info.get("pricingExpression") or {}).get("tieredRates") or []:
            if float(rate.get("startUsageAmount") or 0) == 0:
                money = rate.get("unitPrice") or {}
                return int(money.get("units") or 0) + int(money.get("nanos") or 0) / 1e9
    return None


def unit_prices(items: Iterable[dict[str, Any]], region: str) -> dict[tuple[str, str], float]:
    """(family, "Core" | "Ram") -> on-demand price per vCPU-hour or GiB-hour in ``region``."""
    out: dict[tuple[str, str], float] = {}
    for it in items:
        cat = it.get("category") or {}
        m = LINE.match(str(it.get("description") or ""))
        if (
            not m
            or cat.get("usageType") != "OnDemand"
            or region not in (it.get("serviceRegions") or [])
        ):
            continue
        price = _unit_price(it)
        if price is not None:
            out[(m[1], m[2])] = price
    return out


def machines(prices: dict[tuple[str, str], float]) -> list[dict[str, Any]]:
    out = []
    for fam, prefix in FAMILIES.items():
        core, ram = prices.get((fam, "Core")), prices.get((fam, "Ram"))
        if core is None or ram is None:
            continue
        for shape, per in SHAPES.items():
            if fam == "C3" and shape == "highcpu":
                per = 2  # c3-highcpu has 2 GiB per vCPU
            for vcpu in SIZES[(prefix, shape)]:
                mem = vcpu * per
                out.append(sku(f"{prefix}-{shape}-{vcpu}", f"{prefix}-{shape}", vcpu, mem, vcpu * core + mem * ram))
    return out


def _items(key: str) -> Iterable[dict[str, Any]]:
    token = ""
    while True:
        params = {"key": key, "currencyCode": "USD", "pageSize": "5000"}
        if token:
            params["pageToken"] = token
        page = json.loads(fetch(f"{API}?{urllib.parse.urlencode(params)}"))  # type: ignore[arg-type]
        yield from page.get("skus") or []
        token = page.get("nextPageToken") or ""
        if not token:
            return


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--region", required=True, help="e.g. us-central1")
    ap.add_argument("--out", help="output file (default: the engine's pricing directory)")
    args = ap.parse_args()
    key = os.environ.get("GOOGLE_API_KEY")
    if not key:
        raise SystemExit("set GOOGLE_API_KEY (the Cloud Billing Catalog API needs one)")
    write_snapshot(
        {
            "provider": "gcp",
            "region": args.region,
            "sources": [{"name": "Cloud Billing Catalog API (Compute Engine)", "url": API}],
            "note": "Machine prices are vCPU x core price + GiB x RAM price per family.",
            "compute": machines(unit_prices(_items(key), args.region)),
        },
        Path(args.out) if args.out else None,
    )


if __name__ == "__main__":
    main()
