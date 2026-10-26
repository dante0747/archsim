# Prices and machine shapes

A design says where its prices come from:

```json
"pricing": {"provider": "generic", "region": null, "purchaseOption": "on_demand", "families": []}
```

- **`generic`** (the default): ArchSim's illustrative rates, $0.037 per vCPU-hour, $0.0028 per
  GiB-hour and $0.08 per GiB-month of storage, roughly a large cloud's general-purpose VMs.
  There are no machine shapes, so auto-sizing keeps the sizes it computes (a part can be
  "4 vCPU, 0.5 GiB"), and cost lines say "from size".
- **Any other provider** names a **price snapshot**: on-demand list prices of real instance
  types on a date, for a region. Auto-sizing then snaps each part to the cheapest instance type
  that covers the CPU and memory it needs (a value you set counts as the need), so parts run on
  shapes that exist; the inspector, the analysis and the report show the SKU, and every cost
  line cites its SKU and the snapshot date.

`families` limits snapping to some instance families (for example `["m7g", "c7g", "r7g"]`). It
restricts a price list only when that list has one of the families, so naming compute families
leaves the database and cache lists alone. `purchaseOption` is on-demand only: savings plans and
reserved terms are not modeled yet.

What snaps to what: services on VMs or Docker, self-hosted load balancers and Kubernetes node
pools use compute instances; databases use the snapshot's managed database types for their
engine (PostgreSQL, MySQL...), caches its cache types (Redis, Valkey, Memcached) and Kafka its
broker types. An engine the snapshot has no managed type for (Cassandra, MongoDB...) runs on
compute instances and the cost line says "self-managed". Services on Kubernetes are priced
through their cluster's nodes; serverless services, managed queues (SQS-style, Pub/Sub-style)
and managed load balancers use the snapshot's per-request and per-hour rates where it has them.
Storage is priced at the snapshot's block storage rate. Not included: discounts, data transfer,
load balancer capacity units, licences, multi-AZ or replica pricing beyond the instance count.

## Snapshots

ArchSim **ships no provider snapshot yet**: which provider and region to price first is a
decision for the project owner. A snapshot is a JSON file in
`packages/engine/src/archsim_engine/pricing/` (or a directory named by `ARCHSIM_PRICES_DIR`;
several are separated by the path separator), in the format `archsim.prices/1`:

```json
{
  "format": "archsim.prices/1",
  "provider": "aws", "region": "us-east-1", "currency": "USD", "snapshotDate": "2026-10-01",
  "sources": [{"name": "AWS Price List bulk API", "url": "https://pricing.us-east-1.amazonaws.com/..."}],
  "compute":   [{"sku": "m7g.large", "family": "m7g", "vcpu": 2, "memoryGib": 8, "hourly": {"on_demand": 0.0}}],
  "databases": [{"sku": "db.r7g.large", "family": "r7g", "vcpu": 2, "memoryGib": 16, "hourly": {"on_demand": 0.0}, "engines": ["postgresql"]}],
  "caches":    [], "streams": [],
  "rates": {"storageGibMonth": 0.0, "serverlessGbSecond": 0.0, "serverlessPerMillionRequests": 0.0,
            "queuePerMillionRequests": 0.0, "loadBalancerHour": 0.0}
}
```

(The zeros above are placeholders, not prices.) The newest snapshot per provider and region is
used; a design that names a snapshot this machine does not have gets a validation warning
(`pricing.snapshot_missing`) and is priced at generic rates.

## Refreshing snapshots

The scripts in `scripts/prices/` read the providers' public price lists and write a snapshot.
They are run by hand; ArchSim never calls them, or any price API, at runtime.

```bash
uv run python scripts/prices/aws.py --region us-east-1      # AWS Price List bulk API (CSV, streamed)
uv run python scripts/prices/azure.py --region eastus       # Azure Retail Prices API
GOOGLE_API_KEY=... uv run python scripts/prices/gcp.py --region us-central1   # Cloud Billing Catalog API
```

- **AWS**: EC2 (Linux, shared tenancy, on-demand), RDS Single-AZ (PostgreSQL, MySQL, MariaDB),
  ElastiCache (Redis, Valkey, Memcached), MSK brokers (shaped like the EC2 type they name), EBS
  gp3 storage, Lambda (first tier), SQS standard and Application Load Balancer hours.
- **Azure**: virtual machines of the D v3-v6, E v3-v6 and F v2 series (Linux, pay-as-you-go).
  The API returns prices but not shapes, so memory comes from each series' published ratio.
- **GCP**: Compute Engine N2, N2D, E2 and C3 machine types, priced per vCPU-hour plus per
  GiB-hour of their family. The API needs an API key, read from `GOOGLE_API_KEY` and never
  written anywhere.

Their parsers are tested on small samples written in each API's documented layout
(`packages/engine/tests/test_price_scripts.py`); **they have not been run against the live
price lists here**, so the first real refresh should be checked by hand (a few well-known prices
against the provider's pricing page) before the snapshot is committed.
