"""Refresh an AWS price snapshot from the AWS Price List bulk API (public, no account needed).

    uv run python scripts/prices/aws.py --region us-east-1 [--cache .cache/aws-prices]

Reads the region's CSV offer files (streamed: the EC2 file is several hundred MB) for EC2
(Linux, shared tenancy, on-demand), RDS (Single-AZ), ElastiCache, MSK brokers, EBS gp3 storage,
Lambda, SQS and Application Load Balancers, and writes
``packages/engine/src/archsim_engine/pricing/aws-<region>-<date>.json``.

Endpoints: https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/<offer>/current/region_index.json
(see https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-ppslong.html). Run by
hand; ArchSim never calls it at runtime.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import Any

from common import fetch, gib, sku, write_snapshot

BASE = "https://pricing.us-east-1.amazonaws.com"
OFFERS = ["AmazonEC2", "AmazonRDS", "AmazonElastiCache", "AmazonMSK", "AWSLambda", "AWSQueueService", "AWSELB"]
RDS_ENGINES = {"PostgreSQL": "postgresql", "MySQL": "mysql", "MariaDB": "mariadb"}
CACHE_ENGINES = {"Redis": "redis", "Valkey": "valkey", "Memcached": "memcached"}


def rows(lines: Iterable[str]) -> Iterator[dict[str, str]]:
    """Rows of an offer CSV, keyed by column name (the metadata lines before the header row,
    which starts with "SKU", are skipped)."""
    it = iter(lines)
    for line in it:
        if line.startswith('"SKU"') or line.startswith("SKU,"):
            header = next(csv.reader([line]))
            break
    else:
        raise ValueError("no header row (SKU, ...) found: the price list format may have changed")
    for rec in csv.reader(it):
        if len(rec) == len(header):
            yield dict(zip(header, rec, strict=True))


def _price(r: dict[str, str]) -> float | None:
    try:
        return float(r.get("PricePerUnit") or "")
    except ValueError:
        return None


def _on_demand(r: dict[str, str]) -> bool:
    return r.get("TermType") == "OnDemand"


def ec2(lines: Iterable[str]) -> tuple[list[dict[str, Any]], dict[str, tuple[float, float]], float | None]:
    """Linux, shared tenancy, no preinstalled software, on-demand instance-hours; and EBS gp3."""
    out, shapes, gp3 = [], {}, None
    for r in rows(lines):
        if not _on_demand(r):
            continue
        family = r.get("Product Family")
        if family == "Storage" and r.get("Volume API Name") == "gp3" and r.get("Unit") == "GB-Mo":
            gp3 = _price(r)
            continue
        if (
            family != "Compute Instance"
            or r.get("Operating System") != "Linux"
            or r.get("Tenancy") != "Shared"
            or r.get("Pre Installed S/W") not in ("NA", "")
            or r.get("License Model") not in ("No License required", "")
            or r.get("CapacityStatus") not in ("Used", "")
            or r.get("Unit") != "Hrs"
        ):
            continue
        it, vcpu, mem, price = r.get("Instance Type", ""), r.get("vCPU", ""), gib(r.get("Memory", "")), _price(r)
        if not it or not vcpu.isdigit() or mem is None or not price:
            continue
        shapes[it] = (float(vcpu), mem)
        out.append(sku(it, it.split(".")[0], float(vcpu), mem, price))
    return out, shapes, gp3


def rds(lines: Iterable[str]) -> list[dict[str, Any]]:
    out = []
    for r in rows(lines):
        engine = RDS_ENGINES.get(r.get("Database Engine", ""))
        if (
            not _on_demand(r)
            or r.get("Product Family") != "Database Instance"
            or engine is None
            or r.get("Deployment Option") != "Single-AZ"
            or r.get("Unit") != "Hrs"
        ):
            continue
        it, vcpu, mem, price = r.get("Instance Type", ""), r.get("vCPU", ""), gib(r.get("Memory", "")), _price(r)
        if it and vcpu.isdigit() and mem and price:
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine]))
    return out


def elasticache(lines: Iterable[str]) -> list[dict[str, Any]]:
    out = []
    for r in rows(lines):
        engine = CACHE_ENGINES.get(r.get("Cache Engine", ""))
        if not _on_demand(r) or r.get("Product Family") != "Cache Instance" or engine is None or r.get("Unit") != "Hrs":
            continue
        it, vcpu, mem, price = r.get("Instance Type", ""), r.get("vCPU", ""), gib(r.get("Memory", "")), _price(r)
        if it and vcpu.isdigit() and mem and price:
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine]))
    return out


def msk(lines: Iterable[str], shapes: dict[str, tuple[float, float]]) -> list[dict[str, Any]]:
    """Broker instance-hours; a broker kafka.m5.large has the shape of EC2 m5.large."""
    out = []
    for r in rows(lines):
        if not _on_demand(r) or r.get("Unit") != "Hrs":
            continue
        it = r.get("Instance Type") or r.get("Compute Family") or ""
        m = re.fullmatch(r"kafka\.(.+)", it)
        price = _price(r)
        if m and m[1] in shapes and price:
            vcpu, mem = shapes[m[1]]
            out.append(sku(it, m[1].split(".")[0], vcpu, mem, price, ["kafka"]))
    return out


def rates(lambda_lines: Iterable[str], sqs_lines: Iterable[str], elb_lines: Iterable[str]) -> dict[str, float]:
    out: dict[str, float] = {}
    for r in rows(lambda_lines):
        if not _on_demand(r) or (r.get("StartingRange") or "0") != "0":
            continue
        if r.get("Group") == "AWS-Lambda-Duration" and r.get("Unit") == "Lambda-GB-Second":
            out["serverlessGbSecond"] = _price(r) or 0.0
        elif r.get("Group") == "AWS-Lambda-Requests" and r.get("Unit") == "Requests":
            out["serverlessPerMillionRequests"] = round((_price(r) or 0.0) * 1e6, 6)
    for r in rows(sqs_lines):
        if _on_demand(r) and r.get("Queue Type") == "Standard" and r.get("Unit") == "Requests" and (_price(r) or 0) > 0:
            price = round((_price(r) or 0.0) * 1e6, 6)
            out["queuePerMillionRequests"] = min(out.get("queuePerMillionRequests", price), price)
    for r in rows(elb_lines):
        if (
            _on_demand(r)
            and r.get("Product Family") == "Load Balancer-Application"
            and "LoadBalancerUsage" in r.get("usageType", r.get("Usage Type", ""))
            and r.get("Unit") == "Hrs"
        ):
            out["loadBalancerHour"] = _price(r) or 0.0
    return out


def _csv(offer: str, region: str, cache: Path) -> Iterator[str]:
    index = json.loads(fetch(f"{BASE}/offers/v1.0/aws/{offer}/current/region_index.json"))  # type: ignore[arg-type]
    entry = (index.get("regions") or {}).get(region)
    if not entry:
        raise SystemExit(f"{offer} has no price list for region {region}")
    url = BASE + entry["currentVersionUrl"].replace(".json", ".csv")
    path = fetch(url, cache / f"{offer}-{region}.csv")
    with Path(path).open(encoding="utf-8", newline="") as f:  # type: ignore[arg-type]
        yield from f


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--region", required=True, help="e.g. us-east-1")
    ap.add_argument("--cache", default=".cache/aws-prices", help="where the downloaded CSVs are kept")
    ap.add_argument("--out", help="output file (default: the engine's pricing directory)")
    args = ap.parse_args()
    cache = Path(args.cache)
    compute, shapes, gp3 = ec2(_csv("AmazonEC2", args.region, cache))
    snapshot: dict[str, Any] = {
        "provider": "aws",
        "region": args.region,
        "sources": [{"name": "AWS Price List bulk API", "url": f"{BASE}/offers/v1.0/aws/index.json"}],
        "compute": compute,
        "databases": rds(_csv("AmazonRDS", args.region, cache)),
        "caches": elasticache(_csv("AmazonElastiCache", args.region, cache)),
        "streams": msk(_csv("AmazonMSK", args.region, cache), shapes),
        "rates": {
            **({"storageGibMonth": gp3} if gp3 is not None else {}),
            **rates(
                _csv("AWSLambda", args.region, cache),
                _csv("AWSQueueService", args.region, cache),
                _csv("AWSELB", args.region, cache),
            ),
        },
    }
    write_snapshot(snapshot, Path(args.out) if args.out else None)


if __name__ == "__main__":
    main()

