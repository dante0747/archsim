"""The price refresh scripts' parsers (scripts/prices/), on small hand-written samples in each
public API's documented layout. The scripts themselves are run by hand and never at runtime; they
have not been run against the live price lists in this repository's tests (no network)."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from archsim_engine.pricing import parse_snapshot

SCRIPTS = Path(__file__).resolve().parents[3] / "scripts" / "prices"
sys.path.insert(0, str(SCRIPTS))

import aws  # noqa: E402
import azure  # noqa: E402
import gcp  # noqa: E402
from common import write_snapshot  # noqa: E402

AWS_HEAD = """"FormatVersion","v1.0"
"Disclaimer","sample"
"Publication Date","2026-10-01T00:00:00Z"
"Version","20261001000000"
"OfferCode","AmazonEC2"
"""
EC2 = (
    AWS_HEAD
    + """"SKU","OfferTermCode","RateCode","TermType","PriceDescription","EffectiveDate","StartingRange","EndingRange","Unit","PricePerUnit","Currency","Product Family","Instance Type","vCPU","Memory","Tenancy","Operating System","License Model","Pre Installed S/W","CapacityStatus","Volume API Name"
"A1","JRTCKXETXF","A1.r","OnDemand","linux","2026-10-01","0","Inf","Hrs","0.1000000000","USD","Compute Instance","x9.large","2","8 GiB","Shared","Linux","No License required","NA","Used",""
"A2","JRTCKXETXF","A2.r","OnDemand","windows","2026-10-01","0","Inf","Hrs","0.2000000000","USD","Compute Instance","x9.large","2","8 GiB","Shared","Windows","No License required","NA","Used",""
"A3","JRTCKXETXF","A3.r","OnDemand","dedicated","2026-10-01","0","Inf","Hrs","0.3000000000","USD","Compute Instance","x9.large","2","8 GiB","Dedicated","Linux","No License required","NA","Used",""
"A4","4NA7Y494T4","A4.r","Reserved","1yr","2026-10-01","0","Inf","Hrs","0.0600000000","USD","Compute Instance","x9.large","2","8 GiB","Shared","Linux","No License required","NA","Used",""
"A5","JRTCKXETXF","A5.r","OnDemand","linux","2026-10-01","0","Inf","Hrs","0.4000000000","USD","Compute Instance","x9.2xlarge","8","32 GiB","Shared","Linux","No License required","NA","Used",""
"S1","JRTCKXETXF","S1.r","OnDemand","gp3","2026-10-01","0","Inf","GB-Mo","0.0900000000","USD","Storage","","","","","","","","","gp3"
"""
)
RDS = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Instance Type","vCPU","Memory","Database Engine","Deployment Option"
"R1","OnDemand","Hrs","0.2500000000","Database Instance","db.y9.large","2","16 GiB","PostgreSQL","Single-AZ"
"R2","OnDemand","Hrs","0.5000000000","Database Instance","db.y9.large","2","16 GiB","PostgreSQL","Multi-AZ"
"R3","OnDemand","Hrs","0.2400000000","Database Instance","db.y9.large","2","16 GiB","MySQL","Single-AZ"
"""
)
MSK = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Instance Type"
"K1","OnDemand","Hrs","0.2100000000","kafka.x9.large"
"K2","OnDemand","Hrs","0.9900000000","kafka.unknown.large"
"""
)


def lines(text: str) -> list[str]:
    return text.splitlines(keepends=True)


def test_aws_offer_csvs() -> None:
    compute, shapes, gp3 = aws.ec2(lines(EC2))
    assert [(c["sku"], c["hourly"]["on_demand"]) for c in compute] == [
        ("x9.large", 0.1),
        ("x9.2xlarge", 0.4),
    ]
    assert shapes["x9.large"] == (2.0, 8.0) and gp3 == 0.09
    dbs = aws.rds(lines(RDS))
    assert {(d["sku"], d["engines"][0], d["hourly"]["on_demand"]) for d in dbs} == {
        ("db.y9.large", "postgresql", 0.25),
        ("db.y9.large", "mysql", 0.24),
    }
    streams = aws.msk(lines(MSK), shapes)
    assert [(s["sku"], s["vcpu"], s["memoryGib"]) for s in streams] == [
        ("kafka.x9.large", 2.0, 8.0)
    ]
    with pytest.raises(ValueError, match="no header row"):
        list(aws.rows(["not a price list\n"]))


def test_azure_retail_prices() -> None:
    def item(name: str, price: float, **kw: str) -> dict:
        return {
            "armSkuName": name,
            "retailPrice": price,
            "type": "Consumption",
            "unitOfMeasure": "1 Hour",
            "productName": kw.get("product", "Virtual Machines Dsv5 Series"),
            "skuName": kw.get("sku", "D4s v5"),
            "meterName": kw.get("meter", "D4s v5"),
        }

    out = azure.vms(
        [
            item("Standard_D4s_v5", 0.2),
            item("Standard_D4s_v5", 0.4, product="Virtual Machines Dsv5 Series Windows"),
            item("Standard_D4s_v5", 0.05, sku="D4s v5 Spot"),
            item("Standard_E8s_v5", 0.5),
            item("Standard_D4_v2", 0.3),  # older series: memory ratio differs, skipped
            item("Standard_D4pls_v5", 0.15),  # low-memory size, skipped
        ]
    )
    assert {(o["sku"], o["vcpu"], o["memoryGib"], o["hourly"]["on_demand"]) for o in out} == {
        ("Standard_D4s_v5", 4, 16, 0.2),
        ("Standard_E8s_v5", 8, 64, 0.5),
    }


def test_gcp_billing_catalog() -> None:
    def item(desc: str, nanos: int, region: str = "us-central1") -> dict:
        return {
            "description": desc,
            "category": {"usageType": "OnDemand"},
            "serviceRegions": [region],
            "pricingInfo": [
                {
                    "pricingExpression": {
                        "tieredRates": [
                            {"startUsageAmount": 0, "unitPrice": {"units": "0", "nanos": nanos}}
                        ]
                    }
                }
            ],
        }

    prices = gcp.unit_prices(
        [
            item("N2 Instance Core running in Americas", 30_000_000),
            item("N2 Instance Ram running in Americas", 4_000_000),
            item("N2 Instance Core running in Europe", 99_000_000, region="europe-west1"),
        ],
        "us-central1",
    )
    assert prices == {("N2", "Core"): 0.03, ("N2", "Ram"): 0.004}
    machines = {m["sku"]: m for m in gcp.machines(prices)}
    m = machines["n2-standard-4"]
    assert (m["vcpu"], m["memoryGib"]) == (4, 16) and m["hourly"]["on_demand"] == pytest.approx(
        4 * 0.03 + 16 * 0.004
    )
    assert "e2-standard-4" not in machines  # no E2 prices in the sample


def test_written_snapshots_are_valid(tmp_path: Path) -> None:
    compute, _, gp3 = aws.ec2(lines(EC2))
    path = write_snapshot(
        {
            "provider": "aws",
            "region": "sample-1",
            "snapshotDate": "2026-10-01",
            "sources": [{"name": "sample", "url": "https://example.com"}],
            "compute": compute,
            "rates": {"storageGibMonth": gp3},
        },
        tmp_path / "out.json",
    )
    import json

    snap = parse_snapshot(json.loads(path.read_text(encoding="utf-8")))
    assert snap.cover("compute", 2, 4).sku == "x9.large"  # type: ignore[union-attr]
