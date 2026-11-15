# Prices and machine shapes

A design says where its prices come from:

```json
"pricing": {"provider": "generic", "region": null, "purchaseOption": "on_demand", "families": [], "burstable": "auto"}
```

With a snapshot, a design normally also pins its date (`"snapshotDate": "2026-10-28"`, see
[Pinning a snapshot](#pinning-a-snapshot)); the editor does that when you choose a snapshot.

**Templates and new projects** start with AWS us-east-1 prices, pinned to the newest AWS
snapshot ArchSim ships: `{"provider": "aws", "region": "us-east-1", "snapshotDate": "2026-10-28"}`.
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
credits: each vCPU can use its baseline (a t4g.micro 10%, a t4g.large 30%) all the time, earns
credits below it and spends them above it. Auto-sizing picks a **t4g** type for a light part
when it passes the **credit check**: its CPU at the workload's steady base load (the rate
outside a burst, the low end of a ramp, a trace's median) stays under 80% of the type's
baseline, so it earns credits all month, and its CPU at the peak stays under 60% of the type's
vCPUs, the target auto-sizing aims at; the engine then simulates every vCPU at full speed, as
the credits allow. The explanation says so ("burstable: 0.02 cores busy at the base load stay
under 80% of its baseline..."). EC2 and RDS run t types in unlimited mode, so the risk is a
charge, not throttling: when a part on a burstable type would keep more than its baseline busy
at the base load (a type named on the part, or a run busier than the estimate), its cost line
adds the surplus CPU credits (t4g $0.04, db.t4g $0.075 per vCPU-hour above the baseline);
ElastiCache's t types have no surplus credits and are throttled to their baseline instead,
which the cost line notes. `pricing.burstable` is `auto` (the default) or `never` (many teams
ban t types in production). Naming a burstable family in `families` (`["t4g"]`) allows it
wherever it is the cheapest fit, credit check or not, without restricting the other lists.

Pods on a Kubernetes cluster and serverless functions are not machines of their own (their
machines are the cluster's nodes and the provider's), so they keep their computed size with or
without a snapshot.

`families` limits snapping to some instance families (for example `["m7g", "c7g", "r7g"]`). It
restricts a price list only when that list has one of the (non-burstable) families, so naming
compute families leaves the database and cache lists alone, and naming only `t4g` restricts
none (before engine 0.13 it forced every list onto t4g). A family the snapshot does not have restricts nothing
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
an instance type; the inspector shows no size price for them or for serverless functions, which
are billed per use. Storage is priced at the snapshot's block storage rate, a managed database's
at its own (RDS gp3). Not included: discounts, Multi-AZ, reserved instances and savings plans,
data transfer and NAT, free tiers, licences (the cost panel says so).

**Managed and per-request lines** (with a snapshot that has the rate), each listing what it
prices with its unit price (`items`, shown in the cost panel as "394.20 M requests × $3.50/M"):

| Part | Priced as |
|---|---|
| Application load balancer (`aws_alb`) | hours, plus capacity units: requests/s at the base load × 10 KB processed each (assumed: request and response; new connections and rule evaluations are assumed to need fewer), 1 LCU per GB an hour |
| API gateway | requests at the base load × `apiGatewayPerMillion` |
| Serverless function | invocations × `serverlessPerMillionRequests`, plus GB-seconds: invocations × its duration at the base load (in flight ÷ arrivals over the run's base-load time buckets; before a run, the instant estimate's time in the part) × its memory |
| SQS-style queue | three requests per message (send, receive, delete) × `queuePerMillionRequests` |
| DynamoDB | provisioned: RCU and WCU × hours; on demand: read and write request units at the base load (a read is half a unit per 4 KB when eventually consistent, a write one per KB); plus storage (`dataSizeGib`, else keys × item size) |
| Services on Kubernetes without a cluster in the design | implied nodes: each pod's share of the cheapest general node type that fits it at 90% allocatable (not burstable, HPC or bare-metal types), plus one managed control plane (`kubernetesControlPlaneHour`). A cluster in the design also pays for its control plane. |

Per-request lines are priced at the workload's steady **base** load as if it lasted the whole
month, unless the workload says how many hours a month it runs at its peak (below): after a run,
each part's rate measured while the offered load was at the base, scaled to it exactly, so
fan-out, cache misses and retries are kept but a burst's retries are not; before one, the static
load estimate at the base rate, so a design is priced before it runs. When no time bucket of the
run's window is at the base (a ramp that passes it during the warm-up), the rates are measured in
the buckets nearest it, so the failures and timeouts at the peak do not move them; the peak's are
too, when no bucket is at the peak.

### What a month is

A month is 730 hours: `workload.peakHoursPerMonth` of them at the pattern's peak (a burst's rate,
a ramp's high end, a trace's highest point), and the rest at the steady base load (a constant
pattern's rate, a burst's rate outside the burst, a ramp's low end, a trace's median)
([ADR-0074](adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)). The hours default to 0,
which bills the whole month at the base load, as before schema 1.8: say how long your peak lasts
(a sale day, an evening rush on every weekday) to bill it. A constant pattern has one rate, so the
hours change nothing there.

Each line is its base load's cost for the base's hours plus its peak's for the peak's: its
instances, its per-request items (their quantities are both loads' for their hours) and its
surplus CPU credits. Its `instances` stay the base's, beside `peakInstances`. Its basis says how
many run at the peak and for how long (`3 pods at the base load (autoscaling 3–14) and 12 pods at
the peak for 24 hours a month`), or, for a line whose count is the same at both loads but whose
cost is not (a per-request part, a load balancer's capacity units), ends `; at the peak for 24
hours a month`. A unit price that differs between the loads is the month's own: its cost over its
hours, or over its requests. The cost per million successful requests divides the month by both
loads' requests. `cost.peakHours` says how many hours the month billed at the peak (the
workload's, or 0 for a constant pattern, which has no peak), and `cost.hourly` has an hour at the
base load and an hour at the peak, whatever the hours.

An hour at the peak is priced as the base is: after a run, each part's rate measured while the
offered load was at the peak, scaled to it; before one, the static load estimate at the peak
rate; and a burstable type's surplus CPU credits for the cores it keeps busy at the peak, as if it
had no credits saved.

A part that scales is billed at each load the count it settles at once that load holds:

- **An autoscaled service**: the replicas its autoscaler wants there, as a Kubernetes HPA computes
  them, the work in busy replicas (the larger of its slots' and its CPU's utilization × its
  replicas) ÷ `targetUtilization`, rounded up, within `minReplicas`–`maxReplicas`. The busy
  replicas per request are what the run measured (before a run, what the instant estimate gives),
  times the part's requests at that load. A service that starts above its minimum is billed its
  minimum at a light base load, not the count it starts with.
- **A Kubernetes cluster with its autoscaler**: the nodes its pods at that load need, packed first
  fit, the largest first, as the cluster autoscaler packs them, within `minNodes`–`maxNodes`.
  Without its autoscaler, its `nodes`.
- **A serverless function**: per invocation; with no rate for it, its average concurrency in the
  run, scaled to the load.

Black Friday on Kubernetes (`examples/black-friday-kubernetes.json`, 300 → 1,500 req/s) bills 24
peak hours a month, one sale day. After a run, the month at the base load is $824.80, the
checkout on its 3 pods. An hour at the peak costs $0.7844 more for the HPA design: 9 more checkout
pods (12 in all) and the database's surplus CPU credits, $0.1044. So its month is $824.80 +
24 × $0.7844 = $843.63. The `fixed-pods` scenario keeps 3 pods, so its peak hours add only the
credits: $824.80 + 24 × $0.1044 = $827.31. `fixed-at-peak` runs 14 pods, the HPA's maximum, all
month: $1,434.02, so the autoscaler saves $590 a month.

**Checked by hand**, the serverless template (150 req/s with a burst to 1,500): DynamoDB with
100 RCU and 60 WCU provisioned is 100 × $0.00013 × 730 + 60 × $0.00065 × 730 = $37.96 a month;
the function at 150 invocations/s, 1,769 MB (1 vCPU) and about 40 ms each at the base load is
394.2 M invocations × $0.20/M + 394.2 M × 0.04 s × 1.7275 GB × $0.0000166667 ≈ $530 a month, and
the cost line agrees within a few percent (it uses the measured duration and rate). The Python
engine's `test_cost_managed.py` kept it within 10% until stage 5 of the Java port deleted that
engine; the Java cost estimate gives that engine's figures on every golden entry
([parity/README.md](../parity/README.md)).

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

A snapshot is a JSON file in `archsim-model/src/main/resources/io/archsim/model/pricing/snapshots/`
(shipped in the model's jar: its build lists every file of the folder) or in a directory named by
`ARCHSIM_PRICES_DIR` (several are separated by the path separator), in the
format `archsim.prices/1`:

```json
{
  "format": "archsim.prices/1",
  "provider": "aws", "region": "us-east-1", "currency": "USD", "snapshotDate": "2026-10-01",
  "sources": [{"name": "AWS Price List bulk API", "url": "https://pricing.us-east-1.amazonaws.com/..."}],
  "compute":   [{"sku": "m7g.large", "family": "m7g", "vcpu": 2, "memoryGib": 8, "hourly": {"on_demand": 0.0}},
                {"sku": "t4g.small", "family": "t4g", "vcpu": 2, "memoryGib": 2, "hourly": {"on_demand": 0.0},
                 "burstable": true, "baselineCpu": 0.2}],
  "databases": [{"sku": "db.r7g.large", "family": "r7g", "vcpu": 2, "memoryGib": 16, "hourly": {"on_demand": 0.0}, "engines": ["postgresql"]}],
  "caches":    [], "streams": [],
  "rates": {"storageGibMonth": 0.0, "serverlessGbSecond": 0.0, "serverlessPerMillionRequests": 0.0,
            "queuePerMillionRequests": 0.0, "loadBalancerHour": 0.0}
}
```

(The zeros above are placeholders, not prices.) A SKU may also carry `"burstable": true` and,
for a burstable type, `baselineCpu`: the share of each vCPU it can use all the time without
spending CPU credits (AWS publishes it in its instance tables, not in the price list), and
`creditVcpuHour`: the price of a vCPU-hour above the baseline when the type runs in unlimited
mode (EC2 t4g $0.04, RDS db.t4g $0.075). A burstable type without `creditVcpuHour` (ElastiCache's)
is throttled to its baseline once its credits run out.

Every rate is optional; older snapshots have fewer of them. The rates a snapshot can carry:

| Rate | Unit |
|---|---|
| `storageGibMonth` | block storage (EBS gp3), per GiB-month |
| `rdsStorageGibMonth` | managed database storage (RDS gp3, Single-AZ), per GiB-month |
| `serverlessGbSecond`, `serverlessPerMillionRequests` | functions (Lambda), per GB-second and per million invocations |
| `queuePerMillionRequests` | managed queues (SQS standard), per million requests |
| `loadBalancerHour`, `loadBalancerLcuHour` | an application load balancer per hour, and per capacity unit-hour (LCU) |
| `networkLoadBalancerHour`, `networkLoadBalancerLcuHour` | a network load balancer per hour, and per capacity unit-hour (NLCU) |
| `dynamodbReadPerMillion`, `dynamodbWritePerMillion` | DynamoDB on-demand, per million read and write request units |
| `dynamodbRcuHour`, `dynamodbWcuHour` | DynamoDB provisioned, per read and write capacity unit-hour |
| `dynamodbStorageGibMonth` | DynamoDB table storage, per GiB-month |
| `apiGatewayPerMillion` | an API gateway, per million requests (REST API, first tier) |
| `kubernetesControlPlaneHour` | a managed Kubernetes cluster's control plane (EKS), per hour |

Each per-request or per-unit rate is the first paid tier: not a free allowance, and not the
cheaper tiers for very high volumes.

Every snapshot has a content hash, the SHA-256 of its canonical JSON (sorted keys, no
whitespace), so reformatting a file or changing its line endings does not change it.
`GET /api/v1/pricing` lists every snapshot on the machine with its date, hash and whether it is
the newest for its provider and region.

### The AWS snapshots ArchSim ships

| | |
|---|---|
| File | `aws-us-east-1-2026-10-28.json` (about 260 KB), the newest; templates and new projects pin it |
| Region | us-east-1 (US East, N. Virginia), USD, on-demand |
| Read on | 2026-10-28, by `scripts/prices/aws.py` |
| Source | [AWS Price List bulk API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-ppslong.html) (public, no account), offer files AmazonEC2 20261028180352, AmazonRDS 20261026214716, AmazonElastiCache 20261028192450, AmazonMSK 20261014170039, AWSLambda 20261022183959, AWSQueueService 20260911124607, AWSELB 20260911124544, AmazonDynamoDB 20260911124422, AmazonApiGateway 20261013003044, AmazonEKS 20261027191639 (the snapshot's `note` repeats them) |
| Contents | 1,107 EC2 types (Linux, shared tenancy), 315 RDS Single-AZ types (PostgreSQL, MySQL, MariaDB), 188 ElastiCache types (Redis, Valkey, Memcached), 16 MSK broker types; the t3, t3a and t4g types with their baseline CPU and, on EC2 and RDS, their surplus-credit price; every rate in the table above |
| Left out | Outposts, Local Zone and Wavelength prices, Capacity Blocks, previous-generation types (AWS's own flag), GPU, machine-learning, FPGA and media types, Express MSK brokers, reserved and savings-plan terms, free tiers, DynamoDB's Infrequent Access table class, API Gateway HTTP APIs and WebSockets |

`aws-us-east-1-2026-10-27.json` is the first snapshot, kept unchanged because stored runs pin
its content hash: the same instance types and prices (no price changed between the two dates),
without baselines and with only the first five rates.

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
Application Load Balancer $0.0225 per hour and $0.008 per LCU-hour; DynamoDB $0.00013 per
RCU-hour, $0.00065 per WCU-hour, $0.125 and $0.625 per million on-demand read and write request
units and $0.25 per GiB-month; API Gateway REST APIs $3.50 per million requests; RDS gp3
$0.115 per GiB-month; the EKS control plane $0.10 per hour.

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

archsim-tools' price refresh (`prices`, run by Gradle from the repository's root) reads the
providers' public price lists and writes a snapshot. ArchSim never calls it, or any price API,
at runtime.

**Monthly AWS refresh.** `.github/workflows/prices.yml` rebuilds the us-east-1 snapshot among
the model's, runs the model's tests, and opens a pull request with the new file and a summary of
what changed (`prices diff`: types added, removed and repriced, largest changes first). When no
price changed it opens nothing. A person reviews and merges it. Older snapshots stay, so a
design pinned to one reproduces. Its monthly schedule (the 2nd) is off, and it runs
by hand only: the golden pricing entries and some sizing tests still expect 0.16.0's two
snapshots to be the only ones, so a new snapshot fails them until they stop assuming it
([ADR-0026](adr/0026-component-specs-and-price-snapshots-in-the-model.md)).

After the merge, **new projects** pin the new snapshot (the API pins the newest AWS us-east-1
snapshot the model ships). **Templates** keep the date they name, because a new snapshot can
snap parts to other types and so change results. To move them: set `pricing.snapshotDate` in
`examples/*.json` to the new date, then regenerate what depends on their results (as for any
change to simulated results): copy `examples/cache-aside-starter.json` to
`apps/web/src/test/fixtures/starter.json`, run `./gradlew :archsim-engine:generate` (which
analyzes that starter), and run the whole test suite.

By hand:

```bash
./gradlew :archsim-tools:run --args="prices aws --region us-east-1"     # AWS Price List bulk API (CSV, streamed)
./gradlew :archsim-tools:run --args="prices diff archsim-model/src/main/resources/io/archsim/model/pricing/snapshots/aws-us-east-1-<date>.json"
./gradlew :archsim-tools:run --args="prices azure --region eastus"      # Azure Retail Prices API
GOOGLE_API_KEY=... ./gradlew :archsim-tools:run --args="prices gcp --region us-central1"   # Cloud Billing Catalog API
```

- **AWS**: EC2 (Linux, shared tenancy, on-demand), RDS Single-AZ (PostgreSQL, MySQL, MariaDB)
  and its gp3 storage, ElastiCache (Redis, Valkey, Memcached), MSK standard brokers, EBS gp3
  storage, Lambda, SQS standard, Application and Network Load Balancers (hours and capacity
  units), DynamoDB (request units, capacity units, storage), API Gateway REST API requests and
  the EKS control plane, each rate at its first paid tier. Burstable types get their baseline CPU
  from a table in the refresh (AWS's instance tables; the price list does not have it). The EC2
  file is about 300 MB; downloads are cached in `.cache/aws-prices` by AWS's file version. A
  region's file also lists Outposts and Local Zone prices, Capacity Blocks, unused capacity
  reservations and extended-support surcharges; only the region's own on-demand instance hours
  are kept, and the same type at two prices stops the refresh rather than letting snapping pick
  the wrong one. Types that differ only in engine are stored once (an ElastiCache type costs the
  same for Redis and Memcached).
- **Azure**: virtual machines of the D v3-v6, E v3-v6 and F v2 series (Linux, pay-as-you-go).
  The API returns prices but not shapes, so memory comes from each series' published ratio.
- **GCP**: Compute Engine N2, N2D, E2 and C3 machine types, priced per vCPU-hour plus per
  GiB-hour of their family. The API needs an API key, read from `GOOGLE_API_KEY` and never
  written anywhere.

Their parsers are tested on small samples in each API's layout (archsim-tools' `prices` tests,
which compare them with what the Python scripts they port wrote); the AWS samples include the
rows that tripped the first run against the live price lists. The AWS refresh has been run
against the live lists (the snapshot above, by its Python version); **the Azure and GCP
refreshes have not**, so their first real refresh should be checked by hand (a few well-known
prices against the provider's pricing page) before the snapshot is committed.
