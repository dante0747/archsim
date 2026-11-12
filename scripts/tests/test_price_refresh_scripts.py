"""The price refresh scripts' parsers (scripts/prices/), on small hand-written samples in each
public API's layout. The AWS samples copy the rows that tripped the first run against the live
price lists (Outposts and Capacity Block rows, previous generations, MSK's own column names, free
and volume tiers, a trust store's hours); the tests themselves never use the network. Whether a
written snapshot is one the model reads is archsim-model's tests' to say: the refresh workflow
(.github/workflows/prices.yml) runs them on it."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "prices"
sys.path.insert(0, str(SCRIPTS))

import aws  # noqa: E402
import azure  # noqa: E402
import diff  # noqa: E402
import gcp  # noqa: E402
from common import dedupe, write_snapshot  # noqa: E402

AWS_HEAD = """"FormatVersion","v1.0"
"Disclaimer","sample"
"Publication Date","2026-10-01T00:00:00Z"
"Version","20261001000000"
"OfferCode","AmazonEC2"
"""
EC2_COLUMNS = (
    '"SKU","OfferTermCode","RateCode","TermType","PriceDescription","EffectiveDate",'
    '"StartingRange","EndingRange","Unit","PricePerUnit","Currency","Product Family",'
    '"Location Type","Instance Type","Current Generation","Instance Family","vCPU","Memory",'
    '"Tenancy","Operating System","License Model","Pre Installed S/W","CapacityStatus",'
    '"MarketOption","Volume API Name","usageType"\n'
)


def ec2_row(
    sku: str,
    price: str,
    it: str,
    vcpu: str = "2",
    mem: str = "8 GiB",
    *,
    term: str = "OnDemand",
    location: str = "AWS Region",
    current: str = "Yes",
    category: str = "General purpose",
    tenancy: str = "Shared",
    os: str = "Linux",
    market: str = "OnDemand",
) -> str:
    cells = [
        sku,
        "JRTCKXETXF",
        f"{sku}.r",
        term,
        "sample",
        "2026-10-01",
        "0",
        "Inf",
        "Hrs",
        price,
        "USD",
        "Compute Instance",
        location,
        it,
        current,
        category,
        vcpu,
        mem,
        tenancy,
        os,
        "No License required",
        "NA",
        "Used",
        market,
        "",
        "",
    ]
    return ",".join(f'"{c}"' for c in cells) + "\n"


def gp3_row(sku: str, price: str, location: str = "AWS Region") -> str:
    cells = [sku, "JRTCKXETXF", f"{sku}.r", "OnDemand", "gp3", "2026-10-01", "0", "Inf"]
    cells += ["GB-Mo", price, "USD", "Storage", location] + [""] * 11 + ["gp3", ""]
    return ",".join(f'"{c}"' for c in cells) + "\n"


def credit_row(sku: str, price: str, family: str, os: str = "Linux") -> str:
    """Surplus CPU credits of a burstable family (unlimited mode), per vCPU-hour."""
    cells = [sku, "JRTCKXETXF", f"{sku}.r", "OnDemand", "credits", "2026-10-01", "0", "Inf"]
    cells += ["vCPU-Hours", price, "USD", "CPU Credits", "AWS Region"] + [""] * 6
    cells += [os] + [""] * 5 + [f"CPUCredits:{family}"]
    return ",".join(f'"{c}"' for c in cells) + "\n"


EC2 = (
    AWS_HEAD
    + EC2_COLUMNS
    + ec2_row("A1", "0.1000000000", "x9.large")
    + ec2_row("A2", "0.2000000000", "x9.large", os="Windows")
    + ec2_row("A3", "0.3000000000", "x9.large", tenancy="Dedicated")
    + ec2_row("A4", "0.0600000000", "x9.large", term="Reserved")
    + ec2_row("A5", "0.4000000000", "x9.2xlarge", "8", "32 GiB")
    # rows that sit in a region's file but are not its on-demand price
    + ec2_row("A6", "0.0500000000", "x9.large", location="AWS Outposts")
    + ec2_row("A7", "0.0700000000", "x9.large", market="CapacityBlock")
    + ec2_row("A8", "0.0300000000", "o1.large", current="No")
    + ec2_row("A9", "0.9000000000", "g9.xlarge", "4", "16 GiB", category="GPU instance")
    + ec2_row("A10", "0.0400000000", "t9g.large")
    + ec2_row("A11", "0.0672000000", "t4g.large")
    + credit_row("A12", "0.0400000000", "t4g")
    + credit_row("A13", "0.0960000000", "t4g", os="Windows")
    + gp3_row("S1", "0.0900000000")
    + gp3_row("S2", "0.0300000000", "AWS Outposts")
)
RDS = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","Instance Type","vCPU","Memory","Database Engine","Deployment Option","usageType"
"R1","OnDemand","Hrs","0.2500000000","Database Instance","AWS Region","db.y9.large","2","16 GiB","PostgreSQL","Single-AZ",""
"R2","OnDemand","Hrs","0.5000000000","Database Instance","AWS Region","db.y9.large","2","16 GiB","PostgreSQL","Multi-AZ",""
"R3","OnDemand","Hrs","0.2400000000","Database Instance","AWS Region","db.y9.large","2","16 GiB","MySQL","Single-AZ",""
"R4","OnDemand","Hrs","0.2400000000","Database Instance","AWS Region","db.y9.large","2","16 GiB","MariaDB","Single-AZ",""
"R5","OnDemand","Hrs","0.1000000000","Database Instance","AWS Outposts","db.y9.large","2","16 GiB","PostgreSQL","Single-AZ",""
"R6","OnDemand","Hrs","0.0160000000","Database Instance","AWS Region","db.t4g.micro","2","1 GiB","PostgreSQL","Single-AZ",""
"R7","OnDemand","vCPU-Hours","0.0750000000","CPU Credits","AWS Region","","","","PostgreSQL","","CPUCredits:db.t4g"
"""
)
CACHE = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","Instance Type","vCPU","Memory","Cache Engine","usageType"
"C1","OnDemand","Hrs","0.1560000000","Cache Instance","AWS Region","cache.y9.large","2","6.38 GiB","Redis","NodeUsage:cache.y9.large"
"C2","OnDemand","Hrs","0.1560000000","Cache Instance","AWS Region","cache.y9.large","2","6.38 GiB","Memcached","NodeUsage:cache.y9.large"
"C3","OnDemand","Hrs","0.0600000000","Cache Instance","AWS Outposts","cache.y9.large","2","6.38 GiB","Redis","USE1-Outpost-NodeUsage:cache.y9.large"
"C4","OnDemand","Hrs","0.1250000000","Cache Instance","AWS Region","cache.y9.large","2","6.38 GiB","Redis","USE1-ExtendedSupportYr1_Yr2-NodeUsage:cache.y9.large"
"""
)
MSK = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Location Type","vCPU","usageType","Compute Family","Memory (GiB)"
"K1","OnDemand","hours","0.2100000000","AWS Region","2","USE1-Kafka.x9.large","x9.large","8"
"K2","OnDemand","hours","0.9900000000","AWS Region","","USE1-Kafka.unknown.large","unknown.large",""
"K3","OnDemand","hours","0.4080000000","AWS Region","2","USE1-Express.m7g.large","express.m7g.large","8"
"K4","OnDemand","hours","0.7500000000","AWS Region","","USE1-KafkaServerless-ClusterHours","",""
"K5","OnDemand","hours","0.0456000000","AWS Region","2","USE1-Kafka.t9.small","t9.small","2"
"""
)
LAMBDA = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Location Type","Group","StartingRange","usageType"
"L1","OnDemand","Lambda-GB-Second","0.0000000000","AWS Region","AWS-Lambda-Duration","0","Global-Lambda-GB-Second"
"L2","OnDemand","Lambda-GB-Second","0.0000166667","AWS Region","AWS-Lambda-Duration","0","Lambda-GB-Second"
"L3","OnDemand","Lambda-GB-Second","0.0000133334","AWS Region","AWS-Lambda-Duration","15000000000","Lambda-GB-Second"
"L4","OnDemand","Requests","0.0000002000","AWS Region","AWS-Lambda-Requests","0","Request"
"""
)
SQS = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Location Type","Queue Type","StartingRange"
"Q1","OnDemand","Requests","0.0000004000","AWS Region","Standard","0"
"Q2","OnDemand","Requests","0.0000002400","AWS Region","Standard","200000000000"
"Q3","OnDemand","Requests","0.0000005000","AWS Region","FIFO (first-in, first-out)","0"
"Q4","OnDemand","Requests","0.0000000000","AWS Region","Any","0"
"""
)
ELB = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Location Type","Product Family","usageType"
"E1","OnDemand","Hrs","0.0225000000","AWS Region","Load Balancer-Application","LoadBalancerUsage"
"E2","OnDemand","Hrs","0.0050000000","AWS Region","Load Balancer-Application","TS-LoadBalancerUsage"
"E3","OnDemand","LCU-Hrs","0.0080000000","AWS Region","Load Balancer-Application","LCUUsage"
"E4","OnDemand","Hrs","0.0225000000","AWS Outposts","Load Balancer-Application","Outposts-LoadBalancerUsage"
"E5","OnDemand","ReservedLCU-Hr","0.0060000000","AWS Region","Load Balancer-Application","ReservedLCUUsage"
"E6","OnDemand","Hrs","0.0225000000","AWS Region","Load Balancer-Network","LoadBalancerUsage"
"E7","OnDemand","LCU-Hrs","0.0060000000","AWS Region","Load Balancer-Network","LCUUsage"
"E8","OnDemand","Hrs","0.0125000000","AWS Region","Load Balancer-Gateway","LoadBalancerUsage"
"""
)
RDS_STORAGE = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","Volume Type","Database Engine","Deployment Option"
"V1","OnDemand","GB-Mo","0.1150000000","Database Storage","AWS Region","General Purpose-GP3","PostgreSQL","Single-AZ"
"V2","OnDemand","GB-Mo","0.1150000000","Database Storage","AWS Region","General Purpose-GP3","MySQL","Single-AZ"
"V3","OnDemand","GB-Mo","0.2300000000","Database Storage","AWS Region","General Purpose-GP3","PostgreSQL","Multi-AZ"
"V4","OnDemand","GB-Mo","0.1250000000","Database Storage","AWS Region","Provisioned IOPS","PostgreSQL","Single-AZ"
"""
)
DYNAMODB = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","Group","usageType","StartingRange"
"D1","OnDemand","ReadCapacityUnit-Hrs","0.0000000000","Provisioned IOPS","AWS Region","DDB-ReadUnits","ReadCapacityUnit-Hrs","0"
"D2","OnDemand","ReadCapacityUnit-Hrs","0.0001300000","Provisioned IOPS","AWS Region","DDB-ReadUnits","ReadCapacityUnit-Hrs","18600"
"D3","OnDemand","WriteCapacityUnit-Hrs","0.0000000000","Provisioned IOPS","AWS Region","DDB-WriteUnits","WriteCapacityUnit-Hrs","0"
"D4","OnDemand","WriteCapacityUnit-Hrs","0.0006500000","Provisioned IOPS","AWS Region","DDB-WriteUnits","WriteCapacityUnit-Hrs","18600"
"D5","OnDemand","ReadCapacityUnit-Hrs","0.0001600000","Provisioned IOPS","AWS Region","DDB-ReadUnitsIA","IA-ReadCapacityUnit-Hrs","0"
"D6","OnDemand","ReadRequestUnits","0.0000001250","Amazon DynamoDB PayPerRequest Throughput","AWS Region","DDB-ReadUnits","ReadRequestUnits","0"
"D7","OnDemand","WriteRequestUnits","0.0000006250","Amazon DynamoDB PayPerRequest Throughput","AWS Region","DDB-WriteUnits","WriteRequestUnits","0"
"D8","OnDemand","GB-Mo","0.0000000000","Database Storage","AWS Region","","TimedStorage-ByteHrs","0"
"D9","OnDemand","GB-Mo","0.2500000000","Database Storage","AWS Region","","TimedStorage-ByteHrs","25"
"D10","OnDemand","GB-Mo","0.1000000000","Database Storage","AWS Region","","IA-TimedStorage-ByteHrs","0"
"D11","OnDemand","GB-Mo","0.2000000000","Database Storage","AWS Region","","USE1-TimedPITRStorage-ByteHrs","0"
"""
)
API_GATEWAY = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","usageType","StartingRange"
"G1","OnDemand","Requests","0.0000035000","API Calls","AWS Region","USE1-ApiGatewayRequest","0"
"G2","OnDemand","Requests","0.0000028000","API Calls","AWS Region","USE1-ApiGatewayRequest","333000000"
"G3","OnDemand","Requests","0.0000010000","API Calls","AWS Region","USE1-ApiGatewayHttpRequest","0"
"G4","OnDemand","Messages","0.0000010000","WebSocket","AWS Region","USE1-ApiGatewayMessage","0"
"""
)
EKS = (
    AWS_HEAD
    + """"SKU","TermType","Unit","PricePerUnit","Product Family","Location Type","usageType"
"S1","OnDemand","Hours","0.1000000000","Compute","AWS Region","USE1-AmazonEKS-Hours:perCluster"
"S2","OnDemand","hours","0.5000000000","Compute","AWS Region","USE1-AmazonEKS-Hours:extendedSupport"
"S3","OnDemand","Hours","1.6500000000","Compute","AWS Region","USE1-AmazonEKS-Hours:XL-ProvisionedTier"
"S4","OnDemand","hours","0.0492700000","Compute","AWS Region","EKS-Auto:c7a.2xlarge-management-hours"
"""
)


def lines(text: str) -> list[str]:
    return text.splitlines(keepends=True)


def test_aws_offer_csvs() -> None:
    compute, shapes, gp3 = aws.ec2(lines(EC2))
    # the region's own on-demand prices only: not Outposts, Capacity Blocks, previous
    # generations or GPU types; burstable types are kept and marked
    assert [(c["sku"], c["hourly"]["on_demand"], c.get("burstable", False)) for c in compute] == [
        ("x9.large", 0.1, False),
        ("x9.2xlarge", 0.4, False),
        ("t9g.large", 0.04, True),
        ("t4g.large", 0.0672, True),
    ]
    # AWS's baseline CPU per vCPU, from the script's table: only for the families it knows
    assert [c.get("baselineCpu") for c in compute] == [None, None, None, 0.3]
    # surplus CPU credits (unlimited mode) at the Linux price, on that family's types only
    assert [c.get("creditVcpuHour") for c in compute] == [None, None, None, 0.04]
    assert aws.baseline_cpu("db.t4g.micro") == 0.1 and aws.baseline_cpu("cache.t3.small") == 0.2
    assert aws.baseline_cpu("t2.micro") is None and aws.baseline_cpu("m7g.large") is None
    assert shapes["x9.large"] == (2.0, 8.0) and gp3 == 0.09
    dbs = aws.rds(lines(RDS))
    assert {(d["sku"], d["engines"][0], d["hourly"]["on_demand"]) for d in dbs} == {
        ("db.y9.large", "postgresql", 0.25),
        ("db.y9.large", "mysql", 0.24),
        ("db.y9.large", "mariadb", 0.24),
        ("db.t4g.micro", "postgresql", 0.016),
    }
    micro = next(d for d in dbs if d["sku"] == "db.t4g.micro")
    assert (micro["baselineCpu"], micro["creditVcpuHour"]) == (0.1, 0.075)
    caches = aws.elasticache(lines(CACHE))
    assert {(c["engines"][0], c["hourly"]["on_demand"]) for c in caches} == {
        ("redis", 0.156),
        ("memcached", 0.156),
    }
    # MSK names the broker by its compute family, with the unit "hours"; no Express or serverless
    streams = aws.msk(lines(MSK), shapes)
    assert [(s["sku"], s["vcpu"], s["memoryGib"], s.get("burstable", False)) for s in streams] == [
        ("kafka.x9.large", 2.0, 8.0, False),
        ("kafka.t9.small", 2.0, 2.0, True),
    ]
    # the first paid tier, not the free tier or the cheaper high-volume ones; the load
    # balancer-hour, not a trust store's; capacity units used, not reserved; no gateway LBs
    assert aws.rates(lines(LAMBDA), lines(SQS), lines(ELB)) == {
        "serverlessGbSecond": 0.0000166667,
        "serverlessPerMillionRequests": 0.2,
        "queuePerMillionRequests": 0.4,
        "loadBalancerHour": 0.0225,
        "loadBalancerLcuHour": 0.008,
        "networkLoadBalancerHour": 0.0225,
        "networkLoadBalancerLcuHour": 0.006,
    }
    # gp3 Single-AZ storage (not Multi-AZ or provisioned IOPS)
    assert aws.rds_storage(lines(RDS_STORAGE)) == {"rdsStorageGibMonth": 0.115}
    # the Standard table class; the paid tier past the free allowance; not PITR or IA storage
    assert aws.dynamodb(lines(DYNAMODB)) == {
        "dynamodbRcuHour": 0.00013,
        "dynamodbWcuHour": 0.00065,
        "dynamodbReadPerMillion": 0.125,
        "dynamodbWritePerMillion": 0.625,
        "dynamodbStorageGibMonth": 0.25,
    }
    # REST API requests' first tier, not HTTP APIs or WebSocket messages
    assert aws.api_gateway(lines(API_GATEWAY)) == {"apiGatewayPerMillion": 3.5}
    # the standard control plane, not extended support, provisioned tiers or Auto Mode
    assert aws.eks(lines(EKS)) == {"kubernetesControlPlaneHour": 0.1}
    with pytest.raises(ValueError, match="no header row"):
        list(aws.rows(["not a price list\n"]))


def test_one_entry_per_type_and_price() -> None:
    def entry(engine: str, price: float) -> dict:
        return {
            "sku": "db.y9.large",
            "family": "y9",
            "vcpu": 2.0,
            "memoryGib": 16.0,
            "hourly": {"on_demand": price},
            "engines": [engine],
        }

    merged = dedupe(
        [entry("postgresql", 0.25), entry("mysql", 0.24), entry("mariadb", 0.24)], "databases"
    )
    assert sorted((m["hourly"]["on_demand"], m["engines"]) for m in merged) == [
        (0.24, ["mariadb", "mysql"]),
        (0.25, ["postgresql"]),
    ]
    # the same type and engine at two prices: a row the filters should have dropped
    with pytest.raises(ValueError, match=r"db\.y9\.large \(postgresql\) has two prices"):
        dedupe([entry("postgresql", 0.25), entry("postgresql", 0.1)], "databases")


def test_price_diff_summary() -> None:
    def vm(sku: str, vcpu: int, price: float) -> dict:
        return {
            "sku": sku,
            "family": sku.split(".")[0],
            "vcpu": vcpu,
            "memoryGib": vcpu * 4,
            "hourly": {"on_demand": price},
        }

    old = {
        "provider": "aws",
        "region": "sample-1",
        "snapshotDate": "2026-10-01",
        "sources": [{"name": "sample", "url": "https://example.com"}],
        "compute": [vm("x9.large", 2, 0.1), vm("x9.xlarge", 4, 0.2)],
        "rates": {"storageGibMonth": 0.08},
    }
    same, changed = diff.summarize(old, {**old, "snapshotDate": "2026-11-01"})
    assert not changed and "No prices changed" in same
    new = {
        **old,
        "snapshotDate": "2026-11-01",
        "compute": [vm("x9.large", 2, 0.11), vm("y9.large", 2, 0.09)],
        "rates": {"storageGibMonth": 0.07},
    }
    text, changed = diff.summarize(old, new)
    assert changed
    assert "| EC2 / compute | 1 | 1 | 1 |" in text
    assert "| x9.large | $0.1 | $0.11 | +10.0% |" in text
    assert "| storageGibMonth | 0.08 | 0.07 |" in text
    assert "y9.large (new)" in text and "x9.xlarge (gone)" in text


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


def test_a_written_snapshot_is_the_snapshot_format_with_its_types_in_order(tmp_path: Path) -> None:
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

    written = json.loads(path.read_text(encoding="utf-8"))
    assert (written["format"], written["currency"]) == ("archsim.prices/1", "USD")
    assert [s["sku"] for s in written["compute"]] == sorted(
        (s["sku"] for s in compute),
        key=lambda sku: next(
            (c["family"], c["vcpu"], c["memoryGib"], c["sku"]) for c in compute if c["sku"] == sku
        ),
    )
    assert {"x9.large", "t9g.large"} <= {s["sku"] for s in written["compute"]}
    assert written["rates"] == {"storageGibMonth": gp3}
    assert all(written[key] == [] for key in ("databases", "caches", "streams"))
