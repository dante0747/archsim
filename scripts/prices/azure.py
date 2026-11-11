"""Refresh an Azure price snapshot (virtual machines) from the Azure Retail Prices API (public,
no account needed).

    uv run python scripts/prices/azure.py --region eastus

Reads pay-as-you-go Linux prices of the D v3-v6 (general purpose, 4 GiB per vCPU), E v3-v6
(memory optimised, 8 GiB per vCPU) and F v2 (compute optimised, 2 GiB per vCPU) series. The API
gives prices, not shapes: vCPU comes from the size name (Standard_D4s_v5 -> 4) and memory from
the series' published ratio, so other series (older D, low-memory "l" sizes, constrained
vCPU sizes) are skipped. Endpoint:
https://prices.azure.com/api/retail/prices (https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices).
Run by hand; ArchSim never calls it at runtime.
"""

from __future__ import annotations

import argparse
import json
import re
import urllib.parse
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from common import fetch, sku, write_snapshot

API = "https://prices.azure.com/api/retail/prices"
GIB_PER_VCPU = {"D": 4, "E": 8, "F": 2}
VERSIONS = {"D": {"3", "4", "5", "6"}, "E": {"3", "4", "5", "6"}, "F": {"2"}}
SIZE = re.compile(r"Standard_([DEF])(\d+)([a-z]*)_v(\d+)")


def vms(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Linux pay-as-you-go hourly prices of D, E and F series sizes (cheapest meter per size)."""
    best: dict[str, dict[str, Any]] = {}
    for it in items:
        name = str(it.get("armSkuName") or "")
        m = SIZE.fullmatch(name)
        if (
            not m
            or m[4] not in VERSIONS[m[1]]
            or "l" in m[3]
            or it.get("type") != "Consumption"
            or it.get("unitOfMeasure") != "1 Hour"
            or "Windows" in str(it.get("productName"))
            or re.search(r"Spot|Low Priority", str(it.get("skuName")) + str(it.get("meterName")))
        ):
            continue
        price = float(it.get("retailPrice") or 0)
        if price <= 0:
            continue
        vcpu = int(m[2])
        entry = sku(name, f"{m[1]}{m[3]}v{m[4]}", vcpu, vcpu * GIB_PER_VCPU[m[1]], price)
        if name not in best or price < best[name]["hourly"]["on_demand"]:
            best[name] = entry
    return list(best.values())


def _items(region: str) -> Iterable[dict[str, Any]]:
    query = f"serviceName eq 'Virtual Machines' and armRegionName eq '{region}' and priceType eq 'Consumption'"
    url: str | None = f"{API}?{urllib.parse.urlencode({'$filter': query})}"
    while url:
        page = json.loads(fetch(url))  # type: ignore[arg-type]
        yield from page.get("Items") or []
        url = page.get("NextPageLink")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--region", required=True, help="an ARM region name, e.g. eastus")
    ap.add_argument("--out", help="output file (default: among archsim-model's snapshots)")
    args = ap.parse_args()
    write_snapshot(
        {
            "provider": "azure",
            "region": args.region,
            "sources": [{"name": "Azure Retail Prices API", "url": API}],
            "note": "Memory per vCPU from each series' published ratio (D 4, E 8, F 2 GiB).",
            "compute": vms(_items(args.region)),
        },
        Path(args.out) if args.out else None,
    )


if __name__ == "__main__":
    main()
