# Prices and machine shapes

A design says where its prices come from:

```json
"pricing": {"provider": "generic", "region": null, "purchaseOption": "on_demand", "families": []}
```

With a snapshot, a design normally also pins its date (`"snapshotDate": "2026-10-27"`, see
[Pinning a snapshot](#pinning-a-snapshot)); the editor does that when you choose a snapshot.

**Templates and new projects** start with AWS us-east-1 prices, pinned to the newest AWS
snapshot ArchSim ships: `{"provider": "aws", "region": "us-east-1", "snapshotDate": "2026-10-27"}`.
The schema's default is still `generic`, so a document saved without `pricing` (every design
made before this) keeps its illustrative rates and its results; choose a snapshot under
**Prices** in Design to move it to real prices.

- **`generic`**: ArchSim's illustrative rates, $0.037 per vCPU-hour, $0.0028 per GiB-hour and
  $0.08 per GiB-month of storage, roughly a large cloud's general-purpose VMs. There are no
  machine shapes, so auto-sizing rounds each part's CPU and memory up together to the cheapest
  standard shape of 2, 4 or 8 GiB per vCPU (the compute, general-purpose and memory-optimised
  ratios every large cloud sells): a part that needs 4 vCPU and 0.5 GiB gets 4 vCPU and 8 GiB.
  Cost lines say "from size", and every dollar figure says "illustrative rates".
- **Any other provider** names a **price snapshot**: on-demand list prices of real instance
  types on a date, for a region. Auto-sizing then snaps each part to the cheapest instance type
  that covers the CPU and memory it needs (a value you set counts as the need), so parts run on
  shapes that exist; the inspector, the analysis and the report show the SKU, every cost line
  cites its SKU and the snapshot date, and every dollar figure names the snapshot ("AWS
  us-east-1 · Oct 2026").

**Burstable types** (AWS's t families: t3, t4g, db.t4g, cache.t4g, kafka.t3) run on CPU
credits: a t4g.micro sustains 10% of its two vCPUs. Auto-sizing aims at 60% CPU at the peak and
the engine simulates every vCPU at full speed, so snapping never picks a burstable type unless
the design names its family in `families` (for example `["t4g"]` for a dev environment).

Pods on a Kubernetes cluster and serverless functions are not machines of their own (their
machines are the cluster's nodes and the provider's), so they keep their computed size with or
without a snapshot.

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

A snapshot is a JSON file in `packages/engine/src/archsim_engine/pricing/` (shipped in the
engine's wheel) or in a directory named by `ARCHSIM_PRICES_DIR` (several are separated by the
path separator), in the format `archsim.prices/1`:

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

(The zeros above are placeholders, not prices.) A SKU may also carry `"burstable": true`. Every
snapshot has a content hash, the SHA-256 of its canonical JSON (sorted keys, no whitespace), so
reformatting a file or changing its line endings does not change it. `GET /api/v1/pricing`
lists every snapshot on the machine with its date, hash and whether it is the newest for its
provider and region.

### The AWS snapshot ArchSim ships

| | |
|---|---|
| File | `aws-us-east-1-2026-10-27.json` (about 270 KB) |
| Region | us-east-1 (US East, N. Virginia), USD, on-demand |
| Read on | 2026-10-27, by `scripts/prices/aws.py` |
| Source | [AWS Price List bulk API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-ppslong.html) (public, no account), offer files AmazonEC2 20261023221355, AmazonRDS 20261026214716, AmazonElastiCache 20261014221139, AmazonMSK 20261014170039, AWSLambda 20261022183959, AWSQueueService 20260911124607, AWSELB 20260911124544 (the snapshot's `note` repeats them) |
| Contents | 1,107 EC2 types (Linux, shared tenancy), 315 RDS Single-AZ types (PostgreSQL, MySQL, MariaDB), 188 ElastiCache types (Redis, Valkey, Memcached), 16 MSK broker types; EBS gp3 per GiB-month, Lambda per GB-second and per request, SQS standard per request and Application Load Balancer per hour |
| Left out | Outposts, Local Zone and Wavelength prices, Capacity Blocks, previous-generation types (AWS's own flag), GPU, machine-learning, FPGA and media types, Express MSK brokers, reserved and savings-plan terms, free tiers |

**Terms.** These are AWS's published list prices, copied as facts (instance type, shape, price
per hour) with their source and date. AWS's price list says of itself: "This pricing list is for
informational purposes only. All prices are subject to the additional terms included in the
pricing pages on http://aws.amazon.com." Use of AWS's sites is governed by the
[AWS Site Terms](https://aws.amazon.com/terms/), and AWS changes prices from time to time, so a
snapshot is a planning estimate for its date, not a quote: check
[aws.amazon.com/pricing](https://aws.amazon.com/pricing/) before buying. ArchSim is not
affiliated with or endorsed by Amazon Web Services.

**Checked by hand** against AWS's published on-demand prices for us-east-1: m7g.large $0.0816,
c6g.xlarge $0.136, m5.large $0.096 per hour; db.m5.large $0.178 (PostgreSQL) and $0.171 (MySQL);
cache.m7g.large $0.158 (Redis); kafka.m5.large $0.21; gp3 $0.08 per GiB-month; Lambda $0.20 per
million requests and $0.0000166667 per GB-second; SQS standard $0.40 per million requests; an
Application Load Balancer $0.0225 per hour.

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
ArchSim never calls them, or any price API, at runtime.

**Monthly AWS refresh.** `.github/workflows/prices.yml` runs on the 2nd of every month (and on
demand): it rebuilds the us-east-1 snapshot, runs the price tests, and opens a pull request
with the new file and a summary of what changed (`scripts/prices/diff.py`: types added, removed
and repriced, largest changes first). When no price changed it opens nothing. A person reviews
and merges it. Older snapshots stay in the package, so a design pinned to one reproduces.

After the merge, **new projects** pin the new snapshot (the API pins the newest AWS us-east-1
snapshot the engine ships). **Templates** keep the date they name, because a new snapshot can
snap parts to other types and so change results. To move them: set `pricing.snapshotDate` in
`examples/*.json` to the new date, then regenerate what depends on their results (as for any
change to simulated results): delete `packages/engine/tests/snapshots/check-regression.md` and
run `uv run pytest packages/engine/tests/test_check.py`, run
`uv run python scripts/export_web_fixtures.py`, copy `examples/cache-aside-starter.json` to
`apps/web/src/test/fixtures/starter.json`, and run the whole test suite.

By hand:

```bash
uv run python scripts/prices/aws.py --region us-east-1      # AWS Price List bulk API (CSV, streamed)
uv run python scripts/prices/diff.py packages/engine/src/archsim_engine/pricing/aws-us-east-1-<date>.json
uv run python scripts/prices/azure.py --region eastus       # Azure Retail Prices API
GOOGLE_API_KEY=... uv run python scripts/prices/gcp.py --region us-central1   # Cloud Billing Catalog API
```

- **AWS**: EC2 (Linux, shared tenancy, on-demand), RDS Single-AZ (PostgreSQL, MySQL, MariaDB),
  ElastiCache (Redis, Valkey, Memcached), MSK standard brokers, EBS gp3 storage, Lambda, SQS
  standard (both at their first paid tier) and Application Load Balancer hours. The EC2 file is
  about 300 MB; downloads are cached in `.cache/aws-prices` by AWS's file version. A region's
  file also lists Outposts and Local Zone prices, Capacity Blocks and extended-support
  surcharges; only the region's own on-demand rows are kept, and the same type at two prices
  stops the script rather than letting snapping pick the wrong one. Types that differ only in
  engine are stored once (an ElastiCache type costs the same for Redis and Memcached).
- **Azure**: virtual machines of the D v3-v6, E v3-v6 and F v2 series (Linux, pay-as-you-go).
  The API returns prices but not shapes, so memory comes from each series' published ratio.
- **GCP**: Compute Engine N2, N2D, E2 and C3 machine types, priced per vCPU-hour plus per
  GiB-hour of their family. The API needs an API key, read from `GOOGLE_API_KEY` and never
  written anywhere.

Their parsers are tested on small samples in each API's layout
(`packages/engine/tests/test_price_scripts.py`); the AWS samples include the rows that tripped
the first run against the live price lists. The AWS script has been run against the live lists
(the snapshot above); **the Azure and GCP scripts have not**, so their first real refresh should
be checked by hand (a few well-known prices against the provider's pricing page) before the
snapshot is committed.
