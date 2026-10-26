# Prices and machine shapes

A design says where its prices come from:

```json
"pricing": {"provider": "generic", "region": null, "purchaseOption": "on_demand", "families": []}
```

With a snapshot, a design normally also pins its date (`"snapshotDate": "2026-10-01"`, see
[Pinning a snapshot](#pinning-a-snapshot)); the editor does that when you choose a snapshot.

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
leaves the database and cache lists alone. A family the snapshot does not have restricts nothing
and gets a validation warning (`pricing.unknown_family`). `purchaseOption` is on-demand only:
savings plans and reserved terms are not modeled yet.

What snaps to what: services on VMs or Docker, self-hosted load balancers and Kubernetes node
pools use compute instances; databases use the snapshot's managed database types for their
engine (PostgreSQL, MySQL...), caches its cache types (Redis, Valkey, Memcached) and Kafka its
broker types. An engine the snapshot has no managed type for (Cassandra, MongoDB...) runs on
compute instances and the cost line says "self-managed". Services on Kubernetes are priced
through their cluster's nodes; serverless services, managed queues (SQS-style, Pub/Sub-style)
and managed load balancers (ALB-style) use the snapshot's per-request and per-hour rates where it
has them. Managed services (ALB-, SQS-, Pub/Sub-, DynamoDB-style, CDN, API gateways) never snap to
an instance type. API gateways are billed per request by the providers, a rate snapshots do not
carry yet, so they keep an illustrative price and their cost line says so. Storage is priced at
the snapshot's block storage rate. Not included: discounts, data transfer, load balancer capacity
units, licences, multi-AZ or replica pricing beyond the instance count.

## Which price a part gets

Each cost line has a `priceSource`; the first that applies wins:

1. **`set`**: a price you set on the part (`costPerHour`). A value counts as yours when it differs
   from the catalog default and from the illustrative price of every preset of the part's
   technology, or when its provenance (`nodes/<id>/costPerHour`) is `measured` or `assumed`. It
   wins over everything, the snapshot's instance types and managed rates included.
2. **`snapshot`**: with a snapshot, the instance type the part snaps to (with `sku` and
   `snapshotDate`), or the snapshot's rate for a managed load balancer, queue or serverless
   function.
3. **`preset`**: the illustrative price a technology preset put on the part (for example
   $0.17/h for Java/Spring). Presets' prices are illustrative, so a snapshot reprices them; with
   generic rates, or when the snapshot cannot price the part, they are used as they are. The
   inspector shows them as the preset's illustrative price, never as yours.
4. **`size`**: the part's CPU, memory and storage at the generic illustrative rates. Whenever a
   line is priced this way the estimate's notes say so, also next to snapshot lines.
5. **`default`**: the catalog default.

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

(The zeros above are placeholders, not prices.) Every snapshot has a content hash, the SHA-256
of its canonical JSON (sorted keys, no whitespace), so reformatting a file or changing its line
endings does not change it. `GET /api/v1/pricing` lists every snapshot on the machine with its
date, hash and whether it is the newest for its provider and region.

## Pinning a snapshot

Snapping changes the CPU and memory a part is simulated with, so which snapshot is used changes
results, not just prices. A design therefore pins one:

- **`snapshotDate`** (`YYYY-MM-DD`): exactly the snapshot of that date for the provider and region
  is used, never a newer one. The editor fills it in when you choose a snapshot; the inspector
  lists older snapshots only for designs pinned to them.
- **`snapshotSha256`** (optional, with `snapshotDate`): the snapshot's content hash, so changed
  prices under the same date are not used either.
- **Unpinned** (`snapshotDate` empty): the newest snapshot for the provider (in `region` when set)
  is used, as before.

Either way the engine resolves the snapshot once per analysis or run, and the run's input
records it: `input.pricing` carries the `region`, `snapshotDate` and `snapshotSha256` actually
used (and `cost.pricing.sha256` repeats the hash). A run's input is therefore a pinned document:
running it again, after newer snapshots were added, gives the same result digest. A pinned
snapshot this machine does not have (or whose content differs) is never swapped for another: the
design gets a validation warning (`pricing.snapshot_missing`, naming the snapshots it has) and is
priced at generic rates without snapping, and its run's input has no `snapshotSha256`. An
unpinned design whose provider has no snapshot here gets the same warning and generic rates.

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
