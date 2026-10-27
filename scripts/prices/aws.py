"""Refresh an AWS price snapshot from the AWS Price List bulk API (public, no account needed).

    uv run python scripts/prices/aws.py --region us-east-1 [--cache .cache/aws-prices]

Reads the region's CSV offer files (streamed: the EC2 file is several hundred MB) for EC2
(Linux, shared tenancy, on-demand), RDS (Single-AZ), ElastiCache, MSK brokers, EBS gp3 storage,
Lambda, SQS and Application Load Balancers, and writes
``packages/engine/src/archsim_engine/pricing/aws-<region>-<date>.json``.

Only the region's own current-generation prices are kept: a region's price list also carries
Outposts and Local Zone rows (for hardware elsewhere, at other prices), Capacity Blocks and
extended-support surcharges, which must never be mistaken for the regional on-demand price.
GPU, machine-learning, FPGA and media types are left out (never the cheapest fit for the CPU and
memory a part needs), and burstable types (the t families) are marked ``burstable``.

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
ACCELERATED = {"GPU instance", "Machine Learning ASIC Instances", "FPGA Instances", "Media Accelerator Instances"}
"""EC2 instance categories ("Instance Family" column) left out of the snapshot."""
BURSTABLE = re.compile(r"(db\.|cache\.|kafka\.)?t\d")
"""Burstable types (t3, t4g, db.t4g, cache.t4g, kafka.t3): they run on CPU credits, not full cores."""


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


def _regional(r: dict[str, str]) -> bool:
    """A price for the region itself, not an Outposts rack, Local Zone or Wavelength Zone (their
    rows can sit in the region's file, at other prices), and not a previous-generation type."""
    return r.get("Location Type", "") in ("AWS Region", "") and r.get("Current Generation", "") != "No"


def _burstable(instance_type: str) -> bool:
    return bool(BURSTABLE.match(instance_type))


def ec2(lines: Iterable[str]) -> tuple[list[dict[str, Any]], dict[str, tuple[float, float]], float | None]:
    """Linux, shared tenancy, no preinstalled software, on-demand instance-hours; and EBS gp3."""
    out, shapes, gp3 = [], {}, None
    for r in rows(lines):
        if not _on_demand(r):
            continue
        family = r.get("Product Family")
        if family == "Storage" and r.get("Volume API Name") == "gp3" and r.get("Unit") == "GB-Mo":
            if _regional(r) and (_price(r) or 0) > 0:
                if gp3 is not None and gp3 != _price(r):
                    raise ValueError(f"AmazonEC2: two gp3 prices, {gp3} and {_price(r)}; the price list format may have changed")
                gp3 = _price(r)
            continue
        if (
            family != "Compute Instance"
            or not _regional(r)
            or r.get("MarketOption", "") not in ("OnDemand", "")  # not Capacity Blocks
            or r.get("Instance Family", "") in ACCELERATED
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
        out.append(sku(it, it.split(".")[0], float(vcpu), mem, price, burstable=_burstable(it)))
    return out, shapes, gp3


def rds(lines: Iterable[str]) -> list[dict[str, Any]]:
    out = []
    for r in rows(lines):
        engine = RDS_ENGINES.get(r.get("Database Engine", ""))
        if (
            not _on_demand(r)
            or not _regional(r)
            or r.get("Product Family") != "Database Instance"
            or engine is None
            or r.get("Deployment Option") != "Single-AZ"
            or r.get("Unit") != "Hrs"
        ):
            continue
        it, vcpu, mem, price = r.get("Instance Type", ""), r.get("vCPU", ""), gib(r.get("Memory", "")), _price(r)
        if it and vcpu.isdigit() and mem and price:
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine], _burstable(it)))
    return out


def elasticache(lines: Iterable[str]) -> list[dict[str, Any]]:
    out = []
    for r in rows(lines):
        engine = CACHE_ENGINES.get(r.get("Cache Engine", ""))
        if (
            not _on_demand(r)
            or not _regional(r)
            or r.get("Product Family") != "Cache Instance"
            or engine is None
            or r.get("Unit") != "Hrs"
            or "ExtendedSupport" in r.get("usageType", "")  # a surcharge on top of the node price
        ):
            continue
        it, vcpu, mem, price = r.get("Instance Type", ""), r.get("vCPU", ""), gib(r.get("Memory", "")), _price(r)
        if it and vcpu.isdigit() and mem and price:
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine], _burstable(it)))
    return out


def msk(lines: Iterable[str], shapes: dict[str, tuple[float, float]]) -> list[dict[str, Any]]:
    """Standard broker instance-hours, named like AWS's broker types (kafka.m7g.large). The price
    list names the compute family (m7g.large) with the unit "hours"; the shape is the row's own,
    else the EC2 type's of the same name. Express brokers are another product (storage and
    throughput included) and are left out, as are serverless, connectivity and replicator hours."""
    out = []
    for r in rows(lines):
        if not _on_demand(r) or not _regional(r) or r.get("Unit", "").lower() not in ("hrs", "hours"):
            continue
        name = (r.get("Instance Type") or r.get("Compute Family") or "").removeprefix("kafka.")
        usage = r.get("usageType", "")
        if "." not in name or name.startswith("express.") or (usage and "Kafka." not in usage):
            continue
        vcpu, mem = r.get("vCPU", ""), gib(r.get("Memory (GiB)", ""))
        shape = (float(vcpu), mem) if vcpu.isdigit() and mem else shapes.get(name)
        price = _price(r)
        if shape and price:
            it = f"kafka.{name}"
            out.append(sku(it, name.split(".")[0], shape[0], shape[1], price, ["kafka"], _burstable(it)))
    return out


def _first_paid_tier(r: dict[str, str]) -> bool:
    """The first priced tier: the free tier (price 0) and the cheaper high-volume tiers (which
    start past 0) are not what a typical design pays."""
    return _on_demand(r) and _regional(r) and (r.get("StartingRange") or "0") == "0" and (_price(r) or 0) > 0


def rates(lambda_lines: Iterable[str], sqs_lines: Iterable[str], elb_lines: Iterable[str]) -> dict[str, float]:
    out: dict[str, float] = {}
    for r in rows(lambda_lines):
        if not _first_paid_tier(r):
            continue
        if r.get("Group") == "AWS-Lambda-Duration" and r.get("Unit") == "Lambda-GB-Second":
            out["serverlessGbSecond"] = _price(r) or 0.0
        elif r.get("Group") == "AWS-Lambda-Requests" and r.get("Unit") == "Requests":
            out["serverlessPerMillionRequests"] = round((_price(r) or 0.0) * 1e6, 6)
    for r in rows(sqs_lines):
        if _first_paid_tier(r) and r.get("Queue Type") == "Standard" and r.get("Unit") == "Requests":
            out["queuePerMillionRequests"] = round((_price(r) or 0.0) * 1e6, 6)
    lb_hours = set()
    for r in rows(elb_lines):
        # the load balancer-hour itself ("LoadBalancerUsage", "USW2-LoadBalancerUsage"), not a
        # trust store unit-hour ("TS-LoadBalancerUsage") or an Outposts one
        usage = r.get("usageType", r.get("Usage Type", "")).split("-")
        if (
            _on_demand(r)
            and _regional(r)
            and (_price(r) or 0) > 0
            and r.get("Product Family") == "Load Balancer-Application"
            and usage[-1] == "LoadBalancerUsage"
            and not {"TS", "Outposts"} & set(usage)
            and r.get("Unit") == "Hrs"
        ):
            lb_hours.add(_price(r) or 0.0)
    if len(lb_hours) > 1:
        raise ValueError(f"AWSELB: several load balancer-hour prices {sorted(lb_hours)}; the price list format may have changed")
    if lb_hours:
        out["loadBalancerHour"] = lb_hours.pop()
    return out


def download(offer: str, region: str, cache: Path) -> Path:
    """The region's current CSV of ``offer``, cached by its version (a newer version is fetched)."""
    index = json.loads(fetch(f"{BASE}/offers/v1.0/aws/{offer}/current/region_index.json"))  # type: ignore[arg-type]
    entry = (index.get("regions") or {}).get(region)
    if not entry:
        raise SystemExit(f"{offer} has no price list for region {region}")
    version_url = entry["currentVersionUrl"]  # /offers/v1.0/aws/<offer>/<version>/<region>/index.json
    version = next((p for p in version_url.split("/") if p.isdigit()), "current")
    return Path(fetch(BASE + version_url.replace(".json", ".csv"), cache / f"{offer}-{region}-{version}.csv"))  # type: ignore[arg-type]


def lines_of(path: Path) -> Iterator[str]:
    with path.open(encoding="utf-8", newline="") as f:
        yield from f


def header(path: Path) -> dict[str, str]:
    """The metadata before an offer CSV's column header: FormatVersion, Disclaimer, Publication
    Date, Version, OfferCode."""
    out: dict[str, str] = {}
    for rec in csv.reader(lines_of(path)):
        if not rec or rec[0] == "SKU" or len(out) >= 20:
            break
        if len(rec) >= 2:
            out[rec[0]] = rec[1]
    return out


def provenance(headers: dict[str, dict[str, str]]) -> str:
    """The snapshot's note: which version of each offer file it was read from, and AWS's own
    disclaimer about the price list."""
    versions = ", ".join(f"{offer} {h.get('Version', '?')}" for offer, h in headers.items())
    disclaimer = next((h["Disclaimer"] for h in headers.values() if h.get("Disclaimer")), "")
    return f"Offer file versions: {versions}. AWS: {disclaimer}"[:500]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--region", required=True, help="e.g. us-east-1")
    ap.add_argument("--cache", default=".cache/aws-prices", help="where the downloaded CSVs are kept")
    ap.add_argument("--out", help="output file (default: the engine's pricing directory)")
    args = ap.parse_args()
    cache = Path(args.cache)
    files = {offer: download(offer, args.region, cache) for offer in OFFERS}
    compute, shapes, gp3 = ec2(lines_of(files["AmazonEC2"]))
    snapshot: dict[str, Any] = {
        "provider": "aws",
        "region": args.region,
        "sources": [{"name": "AWS Price List bulk API", "url": f"{BASE}/offers/v1.0/aws/index.json"}],
        "note": provenance({offer: header(path) for offer, path in files.items()}),
        "compute": compute,
        "databases": rds(lines_of(files["AmazonRDS"])),
        "caches": elasticache(lines_of(files["AmazonElastiCache"])),
        "streams": msk(lines_of(files["AmazonMSK"]), shapes),
        "rates": {
            **({"storageGibMonth": gp3} if gp3 is not None else {}),
            **rates(lines_of(files["AWSLambda"]), lines_of(files["AWSQueueService"]), lines_of(files["AWSELB"])),
        },
    }
    write_snapshot(snapshot, Path(args.out) if args.out else None)


if __name__ == "__main__":
    main()

