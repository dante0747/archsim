"""Refresh an AWS price snapshot from the AWS Price List bulk API (public, no account needed).

    uv run python scripts/prices/aws.py --region us-east-1 [--cache .cache/aws-prices]

Reads the region's CSV offer files (streamed: the EC2 file is several hundred MB) for EC2
(Linux, shared tenancy, on-demand), RDS (Single-AZ, and its gp3 storage), ElastiCache, MSK
brokers, EBS gp3 storage, Lambda, SQS, Application and Network Load Balancers (hours and capacity
units), DynamoDB (on-demand request units, provisioned capacity units and storage), API Gateway
(REST API requests) and the EKS control plane, and writes
``packages/engine/src/archsim_engine/pricing/aws-<region>-<date>.json``.

Only the region's own current-generation prices are kept: a region's price list also carries
Outposts and Local Zone rows (for hardware elsewhere, at other prices), Capacity Blocks and
extended-support surcharges, which must never be mistaken for the regional on-demand price.
GPU, machine-learning, FPGA and media types are left out (never the cheapest fit for the CPU and
memory a part needs), and burstable types (the t families) are marked ``burstable``, with the
baseline CPU AWS documents for them (``baselineCpu``; the price list does not carry it) and, where
they run in unlimited mode (EC2 and RDS; not ElastiCache), the price of surplus CPU credits per
vCPU-hour (``creditVcpuHour``).

Per-request and per-unit rates are each the region's first paid tier: not the free tier, and not
the cheaper tiers for very high volumes.

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
OFFERS = [
    "AmazonEC2",
    "AmazonRDS",
    "AmazonElastiCache",
    "AmazonMSK",
    "AWSLambda",
    "AWSQueueService",
    "AWSELB",
    "AmazonDynamoDB",
    "AmazonApiGateway",
    "AmazonEKS",
]
RDS_ENGINES = {"PostgreSQL": "postgresql", "MySQL": "mysql", "MariaDB": "mariadb"}
CACHE_ENGINES = {"Redis": "redis", "Valkey": "valkey", "Memcached": "memcached"}
ACCELERATED = {"GPU instance", "Machine Learning ASIC Instances", "FPGA Instances", "Media Accelerator Instances"}
"""EC2 instance categories ("Instance Family" column) left out of the snapshot."""
BURSTABLE = re.compile(r"(db\.|cache\.|kafka\.)?t\d")
"""Burstable types (t3, t4g, db.t4g, cache.t4g, kafka.t3): they run on CPU credits, not full cores."""
BASELINE_CPU = {"nano": 0.05, "micro": 0.10, "small": 0.20, "medium": 0.20, "large": 0.30, "xlarge": 0.40, "2xlarge": 0.40}
"""Baseline CPU per vCPU of the t3, t3a and t4g sizes (EC2, and the same sizes as db. and cache.
types), from AWS's burstable performance instance tables: a t4g.large's two vCPUs can each run at
30% for ever, and above that they spend CPU credits. Other burstable families (t2, newer ones) get
none, so they are never picked for a light part."""
BASELINE_FAMILIES = {"t3", "t3a", "t4g"}


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


def baseline_cpu(instance_type: str) -> float | None:
    """The documented baseline CPU per vCPU of a burstable type (t4g.large, db.t4g.micro,
    cache.t3.small), or None when it is not burstable or not in the table."""
    name = instance_type
    for prefix in ("db.", "cache.", "kafka."):
        name = name.removeprefix(prefix)
    family, _, size = name.partition(".")
    if not _burstable(instance_type) or family not in BASELINE_FAMILIES:
        return None
    return BASELINE_CPU.get(size)


def _credit_family(r: dict[str, str]) -> str:
    """The burstable family a "CPU Credits" row prices: "CPUCredits:t4g" -> t4g, "CPUCredits:db.t4g" -> t4g."""
    return _usage(r).partition(":")[2].removeprefix("db.").removeprefix("cache.")


def _with_credits(items: list[dict[str, Any]], credits: dict[Any, float], key: Any) -> list[dict[str, Any]]:
    for s in items:
        price = credits.get(key(s))
        if s.get("burstable") and price:
            s["creditVcpuHour"] = price
    return items


def ec2(lines: Iterable[str]) -> tuple[list[dict[str, Any]], dict[str, tuple[float, float]], float | None]:
    """Linux, shared tenancy, no preinstalled software, on-demand instance-hours; EBS gp3; and the
    price of surplus CPU credits of the burstable families (Linux, unlimited mode)."""
    out, shapes, gp3 = [], {}, None
    credits: dict[str, float] = {}
    for r in rows(lines):
        if not _on_demand(r):
            continue
        family = r.get("Product Family")
        if family == "CPU Credits" and r.get("Operating System") == "Linux" and r.get("Unit") == "vCPU-Hours":
            if _regional(r) and (_price(r) or 0) > 0:
                credits[_credit_family(r)] = _price(r) or 0.0
            continue
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
        out.append(sku(it, it.split(".")[0], float(vcpu), mem, price, burstable=_burstable(it), baseline_cpu=baseline_cpu(it)))
    return _with_credits(out, credits, lambda s: s["family"]), shapes, gp3


def rds(lines: Iterable[str]) -> list[dict[str, Any]]:
    """Single-AZ instance-hours per engine, and the price of surplus CPU credits of the db.t
    types (RDS runs them in unlimited mode)."""
    out = []
    credits: dict[tuple[str, str], float] = {}
    for r in rows(lines):
        engine = RDS_ENGINES.get(r.get("Database Engine", ""))
        if engine and _on_demand(r) and _regional(r) and r.get("Product Family") == "CPU Credits" and (_price(r) or 0) > 0:
            credits[(_credit_family(r), engine)] = _price(r) or 0.0
            continue
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
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine], _burstable(it), baseline_cpu(it)))
    return _with_credits(out, credits, lambda s: (s["family"], s["engines"][0]))


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
            out.append(sku(it, it.split(".")[1], float(vcpu), mem, price, [engine], _burstable(it), baseline_cpu(it)))
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
            out.append(sku(it, name.split(".")[0], shape[0], shape[1], price, ["kafka"], _burstable(it), baseline_cpu(it)))
    return out


def _first_paid_tier(r: dict[str, str]) -> bool:
    """The first priced tier: the free tier (price 0) and the cheaper high-volume tiers (which
    start past 0) are not what a typical design pays."""
    return _on_demand(r) and _regional(r) and (r.get("StartingRange") or "0") == "0" and (_price(r) or 0) > 0


def _usage(r: dict[str, str]) -> str:
    """The usage type without its region prefix ("USE1-", "EU-"): us-east-1 rows usually have
    none, other regions' rows do."""
    usage = r.get("usageType", r.get("Usage Type", ""))
    return re.sub(r"^(USE1|[A-Z]{2,3}\d?)-(?=[A-Z])", "", usage) if not usage.startswith("IA-") else usage


def _only(prices: set[float], what: str) -> float | None:
    if len(prices) > 1:
        raise ValueError(f"{what}: several prices {sorted(prices)}; the price list format may have changed")
    return prices.pop() if prices else None


def _paid_tier(candidates: list[dict[str, str]], what: str) -> float | None:
    """The first priced tier of one rate: the lowest starting range with a price above zero (a
    free allowance can be a row of its own from 0, with the paid tier starting where it ends)."""
    priced = [(float(r.get("StartingRange") or 0), _price(r) or 0.0) for r in candidates if (_price(r) or 0) > 0]
    if not priced:
        return None
    start = min(s for s, _ in priced)
    return _only({p for s, p in priced if s == start}, what)


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
    # the load balancer-hour itself ("LoadBalancerUsage", "USW2-LoadBalancerUsage"), not a trust
    # store unit-hour ("TS-LoadBalancerUsage") or an Outposts one; and the capacity unit-hour
    # ("LCUUsage"), not a reserved one ("ReservedLCUUsage")
    wanted = {
        ("Load Balancer-Application", "LoadBalancerUsage", "Hrs"): "loadBalancerHour",
        ("Load Balancer-Application", "LCUUsage", "LCU-Hrs"): "loadBalancerLcuHour",
        ("Load Balancer-Network", "LoadBalancerUsage", "Hrs"): "networkLoadBalancerHour",
        ("Load Balancer-Network", "LCUUsage", "LCU-Hrs"): "networkLoadBalancerLcuHour",
    }
    found: dict[str, set[float]] = {}
    for r in rows(elb_lines):
        usage = r.get("usageType", r.get("Usage Type", "")).split("-")
        key = wanted.get((r.get("Product Family", ""), usage[-1], r.get("Unit", "")))
        if key and _on_demand(r) and _regional(r) and (_price(r) or 0) > 0 and not {"TS", "Outposts"} & set(usage):
            found.setdefault(key, set()).add(_price(r) or 0.0)
    for key, prices in found.items():
        price = _only(prices, f"AWSELB {key}")
        if price is not None:
            out[key] = price
    return out


def dynamodb(lines: Iterable[str]) -> dict[str, float]:
    """The Standard table class (not Infrequent Access): on-demand read and write request units
    (per million), provisioned read and write capacity unit-hours, and table storage per GB-month."""
    wanted = {
        ("DDB-ReadUnits", "ReadRequestUnits"): "dynamodbReadPerMillion",
        ("DDB-WriteUnits", "WriteRequestUnits"): "dynamodbWritePerMillion",
        ("DDB-ReadUnits", "ReadCapacityUnit-Hrs"): "dynamodbRcuHour",
        ("DDB-WriteUnits", "WriteCapacityUnit-Hrs"): "dynamodbWcuHour",
    }
    found: dict[str, list[dict[str, str]]] = {}
    for r in rows(lines):
        if not _on_demand(r) or not _regional(r):
            continue
        key = wanted.get((r.get("Group", ""), r.get("Unit", "")))
        if key is None and r.get("Product Family") == "Database Storage" and _usage(r) == "TimedStorage-ByteHrs" and r.get("Unit") == "GB-Mo":
            key = "dynamodbStorageGibMonth"
        if key:
            found.setdefault(key, []).append(r)
    out: dict[str, float] = {}
    for key, candidates in found.items():
        price = _paid_tier(candidates, f"AmazonDynamoDB {key}")
        if price is not None:
            out[key] = round(price * 1e6, 6) if key.endswith("PerMillion") else price
    return out


def api_gateway(lines: Iterable[str]) -> dict[str, float]:
    """REST API requests, first tier (per million). HTTP APIs and WebSocket messages are other,
    cheaper products, and a gateway cache is billed per hour on top; none of them is kept."""
    candidates = [
        r
        for r in rows(lines)
        if _on_demand(r) and _regional(r) and r.get("Product Family") == "API Calls" and _usage(r) == "ApiGatewayRequest" and r.get("Unit") == "Requests"
    ]
    price = _paid_tier(candidates, "AmazonApiGateway REST API requests")
    return {"apiGatewayPerMillion": round(price * 1e6, 6)} if price is not None else {}


def eks(lines: Iterable[str]) -> dict[str, float]:
    """The EKS control plane per cluster-hour on standard support (not extended support, the
    provisioned control-plane tiers, Auto Mode or Fargate)."""
    prices = {
        _price(r) or 0.0
        for r in rows(lines)
        if _on_demand(r) and _regional(r) and _usage(r) == "AmazonEKS-Hours:perCluster" and (_price(r) or 0) > 0
    }
    price = _only(prices, "AmazonEKS cluster-hour")
    return {"kubernetesControlPlaneHour": price} if price is not None else {}


def rds_storage(lines: Iterable[str]) -> dict[str, float]:
    """gp3 storage of a Single-AZ RDS instance per GB-month (the engines share one price)."""
    prices = {
        _price(r) or 0.0
        for r in rows(lines)
        if _on_demand(r)
        and _regional(r)
        and r.get("Product Family") == "Database Storage"
        and r.get("Volume Type") == "General Purpose-GP3"
        and r.get("Deployment Option") == "Single-AZ"
        and r.get("Unit") == "GB-Mo"
        and (_price(r) or 0) > 0
    }
    price = _only(prices, "AmazonRDS gp3 storage")
    return {"rdsStorageGibMonth": price} if price is not None else {}


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
            **rds_storage(lines_of(files["AmazonRDS"])),
            **dynamodb(lines_of(files["AmazonDynamoDB"])),
            **api_gateway(lines_of(files["AmazonApiGateway"])),
            **eks(lines_of(files["AmazonEKS"])),
        },
    }
    write_snapshot(snapshot, Path(args.out) if args.out else None)


if __name__ == "__main__":
    main()

