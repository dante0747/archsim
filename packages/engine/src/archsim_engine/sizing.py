"""Auto-sizing: the CPU, memory and storage each part needs for the design's own peak load.

An empty ``cpuCores``, ``memoryGib`` or ``storageGib`` is *auto*: before a run (and in
``analyze``), the part gets the size worked out here, so the CLI, the API and the web app all
simulate the same document the same way. A value set on the part always wins. The rules follow
what the engine does with each spec (docs/simulation-model.md, "Resource specs"), so an
auto-sized part is not the bottleneck at the peak it was sized for:

- CPU: the CPU work per second at the peak (from the static load estimate in ``load.py``, with
  each TTL cache's hit ratio estimated from its TTL and the key popularity) at a 60% target,
  rounded up to a common size, at least 1 vCPU (below one core every request slows down; the
  fraction shows only as the need, and as a Kubernetes CPU request in the export), never more
  than the replica's handlers (workers, event loops, command threads) or a database's
  connections (times the CPU share of a query) can use.
- Memory: what the engine's memory model needs (requests held, buffer cache, keys) plus 25%; a
  database's is its hot data in its buffer cache (at least 1 GiB); a serverless function gets
  1,769 MB per vCPU (AWS Lambda's coupling of CPU to memory).
- Storage: the data per instance kept under 70% full.
- With a price snapshot (``pricing`` names a provider), CPU and memory then snap to the cheapest
  instance type that covers them (``pricing.choose_machine``), so sizes are real machine shapes.
  A light part may get a burstable type (t4g) when it passes the credit check: its CPU at the
  workload's steady base load (``load.base_rps``) under 80% of the type's baseline, and at the
  peak under 60% of its vCPUs (``pricing.burstable``: auto, or never).
- Without one (generic rates), CPU and memory are rounded up together to a standard shape of 2,
  4 or 8 GiB per vCPU (the compute, general-purpose and memory-optimised ratios every large
  cloud sells), the cheapest at generic rates, so a part never asks for "4 vCPU, 0.5 GiB".
  Parts that do not run on their own machines (pods on a Kubernetes cluster, serverless
  functions, managed services) keep their computed size, with a snapshot or without.

Each part also records what it **needs** (``need``), beside the instance it gets: the CPU cores
busy at the peak and the cores that keeps at the 60% target, the memory it uses (a service: the
requests it typically holds, from the instant estimate's λ·W, and the most it can hold when every
queue is full; a cache: its keys; a database: its hot data in the buffer cache; plus the
operating system on a VM of its own) and the data it stores. ``cpuCores`` and ``memoryGib`` stay
the instance's real capacity, which is what the simulation runs on.

A few ratios have no measured basis in the model (proxy throughput per vCPU, broker memory per
vCPU, database memory without a working set); their explanations say "illustrative".
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from archsim_engine.components.cache import KEY_MEMORY_SHARE
from archsim_engine.components.database import BUFFER_CACHE_SHARE
from archsim_engine.load import (
    Json,
    base_rps,
    estimate_load,
    js_num,
    mean_ms,
    num,
    peak_rps,
    round_half_up,
    ttl_miss_shares,
)
from archsim_engine.pricing import (
    BURST_BASE_SHARE,
    BURST_PEAK_SHARE,
    GENERIC,
    GENERIC_GIB_HOUR,
    GENERIC_VCPU_HOUR,
    MANAGED_TECHNOLOGIES,
    Busy,
    PricingError,
    Snapshot,
    choose_machine,
    machine_kind,
    snapshot_for,
)
from archsim_engine.registry import ComponentRegistry

if TYPE_CHECKING:
    from archsim_engine.schema import ArchitectureDocument

TARGET_CPU = 0.6
"""CPU utilization a part is sized for at the peak."""
SPEC_KEYS = ("cpuCores", "memoryGib", "storageGib")

CPU_STEPS = [1, 2, 4, 8, 16, 32, 48, 64, 96, 128, 192, 256, 384, 512]
MEM_STEPS = [0.5, 1, 2, 4, 8, 16, 32, 64, 128, 256, 384, 512, 768, 1024, 1536, 2048]
DISK_STEPS = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000]
PROXY_RPS_PER_VCPU = 5000
"""Illustrative: requests per second an L7 proxy handles comfortably on one vCPU."""
BROKER_MSGS_PER_VCPU = 10000
"""Illustrative: messages per second a broker handles comfortably on one vCPU."""
LOG_RETENTION_S = 7 * 24 * 3600
"""Kafka's default retention (log.retention.hours = 168)."""
LOG_BROKERS = {"kafka", "redis_streams"}
MAX_SERVICE_MEMORY_GIB = 64
"""Largest auto memory for one service replica (a huge queue would otherwise ask for more)."""
MANAGED = MANAGED_TECHNOLOGIES
"""Managed services you do not size yourself."""
MEMORY_HEADROOM = 1.25
"""Auto memory is what the engine's memory model needs, plus 25%."""
STORAGE_FILL = 0.7
"""Auto storage keeps the data under 70% full."""
MIN_STORAGE_GIB = 10.0
DB_MIN_MEMORY_GIB = 1.0
"""Illustrative: the smallest auto memory of a database instance (the engine and its
connections)."""
DB_BYTES_PER_CONNECTION = 9_531_392
"""Memory per connection of a database instance: RDS for PostgreSQL sets max_connections to its
memory ÷ 9,531,392 bytes by default (a db.t4g.micro's 1 GiB: about 112 connections)."""
LAMBDA_MB_PER_VCPU = 1769
"""AWS Lambda gives a function one vCPU per 1,769 MB of memory (CPU scales with memory)."""
PROXY_GIB_PER_VCPU = 1
"""Illustrative: load balancer memory per vCPU (a proxy keeps little state)."""
BROKER_GIB_PER_VCPU = 2
"""Illustrative: broker memory per vCPU for buffers and page cache."""
BACKLOG_S = 3600
"""Storage of a broker that deletes on ack: room for an hour of peak publishes."""
GIB_PER_VCPU = (2, 4, 8)
"""Standard shapes without a price snapshot: 2, 4 or 8 GiB per vCPU (compute, general purpose,
memory optimised)."""
OS_MEMORY_GIB = 0.25
"""Illustrative: memory the operating system of a VM uses besides the part's own process."""
DB_HOT_SHARE = 0.25
"""Illustrative: share of a database's data that is read often, when no hot working set is set."""


@dataclass
class PartSize:
    cpu_cores: float | None
    memory_gib: float | None
    storage_gib: float | None
    peak_rps: float = 0.0
    why: dict[str, str] = field(default_factory=dict)
    skipped: str | None = None
    sku: str | None = None
    """The instance type the size snapped to (with a price snapshot)."""
    need: dict[str, float] = field(default_factory=dict)
    """What the part needs, before rounding to an instance: ``busyCores`` (CPU busy at the peak),
    ``cpuCores`` (that at the target utilization), ``memoryGib`` and ``memoryGibMax`` (a service:
    the most it holds when every queue is full), ``storageGib`` (the data it stores)."""
    memory_model: tuple[float, float, float] | None = None
    """A service's memory as (fixed GiB, GiB per request held, pools): its typical memory is
    fixed + per request × the requests one pool holds (λ·W, from the instant estimate)."""
    smallest: bool = False
    """Its CPU need is below the smallest size it can get (1 vCPU, or the smallest instance type
    of its kind): a lightly used part cannot shrink further."""

    def busy(self) -> Busy | None:
        """CPU cores one instance keeps busy at the peak and at the base load (None: unknown)."""
        peak, base = self.need.get("busyCores"), self.need.get("baseBusyCores")
        return Busy(peak, base) if peak is not None and base is not None else None

    def get(self, key: str) -> float | None:
        return {
            "cpuCores": self.cpu_cores,
            "memoryGib": self.memory_gib,
            "storageGib": self.storage_gib,
        }[key]

    def to_json(self) -> Json:
        out: Json = {
            "cpuCores": self.cpu_cores,
            "memoryGib": self.memory_gib,
            "storageGib": self.storage_gib,
            "peakRps": round(self.peak_rps, 3),
            "why": self.why,
        }
        if self.skipped:
            out["skipped"] = self.skipped
        if self.sku:
            out["sku"] = self.sku
        if self.need:
            out["need"] = rounded_need(self.need)
        if self.smallest:
            out["smallest"] = True
        return out


def rounded_need(need: dict[str, float]) -> dict[str, float]:
    return {k: round(v, 4) for k, v in need.items()}


def _step_up(x: float, steps: list[float] | list[int]) -> float:
    for s in steps:
        if s >= x - 1e-9:
            return float(s)
    last = float(steps[-1])
    return math.ceil(x / last) * last


def fmt(v: float) -> str:
    """Short number for explanations: 1,234 · 12.3 · 0.42."""
    if not math.isfinite(v):
        return "–"
    if abs(v) >= 100:
        return f"{int(round_half_up(v)):,}"
    if abs(v) >= 10:
        return js_num(round_half_up(v, 1))
    return js_num(round_half_up(v, 2))


def _gib(v: float) -> str:
    return f"{int(round_half_up(v * 1024))} MiB" if v < 1 else f"{fmt(v)} GiB"


def _pct(v: float) -> str:
    return f"{int(round_half_up(v * 100))}%"


def _headroom() -> str:
    return f"+{_pct(MEMORY_HEADROOM - 1)} headroom"


def _fill() -> str:
    return f"kept under {_pct(STORAGE_FILL)} full"


def _cpu_for(busy: float, intro: str) -> tuple[float, str]:
    cpu = min(512.0, _step_up(max(busy / TARGET_CPU, 0.0), CPU_STEPS))
    return cpu, (
        f"{intro} = {fmt(busy)} cores busy; at a {_pct(TARGET_CPU)} target that is "
        f"{fmt(busy / TARGET_CPU)} → {js_num(cpu)} vCPU"
    )


def _cap_cpu(cpu: float, why: str, handlers: int, reason: str) -> tuple[float, str]:
    """More cores than handlers would idle: cap at the handler count (exactly, not a smaller
    common size, which would make the CPU the bottleneck below the handlers)."""
    if handlers >= 1 and cpu > handlers:
        return float(handlers), f"{why}; capped at {handlers} because {reason}"
    return cpu, why


def _mem_for(need: float, floor: float) -> float:
    return min(24576.0, _step_up(max(need, floor), MEM_STEPS))


def _disk_for(need: float) -> float:
    return min(1_000_000.0, _step_up(max(need, MIN_STORAGE_GIB), DISK_STEPS))


def _size_function(cfg: Json, baseline: float, per_req: float) -> PartSize:
    """A serverless function runs one request per instance. Its CPU comes with its memory (AWS
    Lambda: 1 vCPU per 1,769 MB): a full vCPU, so the request runs at full speed, takes 1,769 MB;
    memory set on the part sets the CPU instead."""
    mb = baseline + per_req
    full = LAMBDA_MB_PER_VCPU / 1024  # GiB that come with one vCPU
    coupling = f"a function gets 1 vCPU per {LAMBDA_MB_PER_VCPU:,} MB of memory (AWS Lambda)"
    set_cpu, set_mem = num(cfg.get("cpuCores")), num(cfg.get("memoryGib"))
    if set_mem is not None and set_cpu is None:
        cpu = min(1.0, set_mem / full)
        cpu_why = f"{js_num(set_mem)} GiB of memory: {coupling}, so {fmt(cpu)} vCPU" + (
            "; below 1 its work runs slower" if cpu < 1 else ""
        )
    else:
        cpu = 1.0
        cpu_why = "One request per instance: 1 vCPU runs it at full speed"
    vcpu = set_cpu if set_cpu is not None else cpu
    own = _mem_for(mb * MEMORY_HEADROOM / 1024, 0.125)
    coupled = round(min(1.0, vcpu) * full, 6)
    mem = max(own, coupled)
    mem_why = f"{fmt(baseline)} MiB base + 1 request × {fmt(per_req)} MiB, {_headroom()}"
    if coupled >= own:
        mem_why += f"; {coupling}, so {js_num(min(1.0, vcpu))} vCPU takes {int(round_half_up(coupled * 1024)):,} MB"
    return PartSize(
        cpu,
        mem,
        None,
        why={"cpuCores": cpu_why, "memoryGib": mem_why},
        need={"cpuCores": 1.0, "memoryGib": mb / 1024, "memoryGibMax": mb / 1024},
    )


def _size_service(config: Any, cfg: Json, total: float, on_vm: bool = False) -> PartSize:
    baseline = num(cfg.get("memoryBaselineMb"))
    baseline = 256.0 if baseline is None else baseline
    per_req = num(cfg.get("memoryPerRequestMb"))
    per_req = 2.0 if per_req is None else per_req
    if cfg.get("platform") == "serverless":
        return _size_function(cfg, baseline, per_req)
    autoscaling = cfg.get("autoscaling") is True
    replicas_set = num(cfg.get("replicas")) or 1.0
    if autoscaling:
        replicas = max(1.0, max(num(cfg.get("maxReplicas")) or 1.0, replicas_set))
    else:
        replicas = max(1.0, replicas_set)
    event_loop = bool(config.event_loop())
    handlers = int(config.slots_per_replica())
    ms = mean_ms(cfg.get("serviceTimeMs"))
    per_replica = total / replicas
    busy = per_replica * ms / 1000
    cpu, why = _cpu_for(
        busy,
        f"{fmt(per_replica)} req/s per replica at peak ({fmt(total)} over {js_num(replicas)}"
        f"{' at full scale' if autoscaling else ''}) × {fmt(ms)} ms of CPU each",
    )
    what = "event loop" if event_loop else "worker"
    cpu, why = _cap_cpu(
        cpu,
        why,
        handlers,
        f"{handlers} {what}{'' if handlers == 1 else 's'} cannot use more cores (add workers or replicas)",
    )
    # memory for every request a replica can hold (working and a full queue), so it never refuses
    # for memory before its queue is full; a huge queue is capped at a large replica. An event
    # loop holds many requests in flight while they wait on dependencies: count its workers.
    workers = max(1, int(num(cfg.get("workersPerReplica")) or 1))
    processes = max(1, int(num(cfg.get("processesPerReplica")) or 1))
    working = workers * processes if event_loop else handlers
    queue = max(0.0, num(cfg.get("queueCapacity")) or 0.0)
    held = working + queue
    mb = baseline + held * per_req
    os = OS_MEMORY_GIB if on_vm else 0.0
    wanted = _mem_for((mb / 1024 + os) * MEMORY_HEADROOM, 0.5)
    mem = min(wanted, float(MAX_SERVICE_MEMORY_GIB))
    need = {
        "busyCores": busy,
        "cpuCores": busy / TARGET_CPU,
        "memoryGibMax": mb / 1024 + os,
    }
    memory_model = (baseline / 1024 + os, per_req / 1024, replicas)
    on_os = f" + {_gib(os)} for the operating system" if os else ""
    mem_why = (
        f"{fmt(baseline)} MiB base + {fmt(held)} requests held ({working} working, {fmt(queue)} "
        f"queued) × {fmt(per_req)} MiB = {_gib(mb / 1024)}{on_os}, {_headroom()}"
    )
    if mem < wanted:
        refuse_at = math.floor((mem * 1024 - baseline) / max(per_req, 1e-9))
        mem_why = (
            f"{mem_why}; capped at {MAX_SERVICE_MEMORY_GIB} GiB, so past about {fmt(refuse_at)} "
            "requests held it refuses (out of memory)"
        )
    return PartSize(
        cpu,
        mem,
        None,
        why={"cpuCores": why, "memoryGib": mem_why},
        need=need,
        memory_model=memory_model,
    )


def _size_database(config: Any, cfg: Json, read: float, write: float, workload: Json) -> PartSize:
    shards = max(1, int(num(cfg.get("shards")) or 1))
    rf = max(1, int(num(cfg.get("replicationFactor")) or 1))
    copies = 1 + max(0, int(num(cfg.get("readReplicas")) or 0))
    read_ms = mean_ms(cfg.get("readServiceTimeMs"))
    write_ms = mean_ms(cfg.get("writeServiceTimeMs"))
    share = num(cfg.get("cpuShare"))
    share = 0.5 if share is None else share
    if rf > 1:
        # a ring or replica set: every write goes to RF nodes, a read to the consistency count
        consistency = cfg.get("consistency")
        k = rf if consistency == "all" else (rf // 2 + 1 if consistency == "quorum" else 1)
        nodes = shards * max(rf, copies)
        busy = (read * read_ms * k + write * write_ms * rf) * share / 1000 / nodes
        intro = (
            f"{fmt(read)} reads/s × {k} replica{'' if k == 1 else 's'} × {fmt(read_ms)} ms + "
            f"{fmt(write)} writes/s × {rf} replicas × {fmt(write_ms)} ms, {_pct(share)} of it CPU, "
            f"over {nodes} nodes"
        )
    else:
        # the primary of each shard takes its share of the reads plus every write; a scattered
        # read (Elasticsearch searches every shard) reaches every shard
        scatter = 0.0
        if shards > 1:
            scatter = min(1.0, max(0.0, num(config.setting("scatter_reads", 0.0)) or 0.0))
        reads_per_shard = read * (scatter + (1 - scatter) / shards)
        busy = ((reads_per_shard / copies) * read_ms + (write / shards) * write_ms) * share / 1000
        scattered = ""
        if scatter > 0:
            what = (
                "every read searches all"
                if scatter >= 1
                else f"{_pct(scatter)} of reads search all"
            )
            scattered = f" ({what} {shards} shards)"
        intro = (
            f"Each primary: {fmt(reads_per_shard / copies)} reads/s{scattered} × {fmt(read_ms)} ms + "
            f"{fmt(write / shards)} writes/s × {fmt(write_ms)} ms, {_pct(share)} of it CPU"
        )
    cpu, why = _cpu_for(busy, intro)
    # each connection runs one query at a time, and only its CPU share needs a core: more cores
    # than that would idle (like a service's workers)
    conns = max(1, int(num(cfg.get("maxConnections")) or 1))
    usable = max(1, math.ceil(conns * share - 1e-9))
    cpu, why = _cap_cpu(
        cpu,
        why,
        usable,
        f"{conns} connection{'' if conns == 1 else 's'} × {_pct(share)} CPU keep at most "
        f"{usable} core{'' if usable == 1 else 's'} busy (add connections, read replicas or shards)",
    )
    ws = num(cfg.get("workingSetGib"))
    given = num(cfg.get("dataSizeGib"))
    item_kb = num(cfg.get("itemSizeKb"))
    item_kb = 1.0 if item_kb is None else item_kb
    data = given if given is not None else int(workload["keyCount"]) * item_kb * 1000 / 2**30
    per_instance = data / shards
    disk = _disk_for(per_instance / STORAGE_FILL)
    source = (
        f"{_gib(data)} of data"
        if given is not None
        else f"{fmt(int(workload['keyCount']))} keys × {fmt(item_kb)} KB ≈ {_gib(data)} (set the data size for a better estimate)"
    )
    storage_why = f"{source}{f' over {shards} shards' if shards > 1 else ''}, {_fill()} (at least {js_num(MIN_STORAGE_GIB)} GiB)"
    # memory: its hot data in a buffer cache of 75% of memory (the working set, else an
    # illustrative share of the data it stores)
    hot = ws / shards if ws is not None else DB_HOT_SHARE * per_instance
    # and enough for its connections (RDS's default max_connections formula)
    for_conns = conns * DB_BYTES_PER_CONNECTION / 2**30
    need_mem = max(hot / BUFFER_CACHE_SHARE, for_conns)
    mem = _mem_for(need_mem, DB_MIN_MEMORY_GIB)
    floor = (
        f", at least {_gib(for_conns)} for {conns} connections (RDS's default: "
        f"{DB_BYTES_PER_CONNECTION:,} bytes each) and {js_num(DB_MIN_MEMORY_GIB)} GiB in all"
    )
    if ws is not None:
        mem_why = (
            f"{_gib(hot)} of hot data per instance in a buffer cache of "
            f"{_pct(BUFFER_CACHE_SHARE)} of memory, so reads stay in memory{floor}"
        )
    else:
        mem_why = (
            f"{_gib(hot)} of hot data per instance ({_pct(DB_HOT_SHARE)} of the {_gib(per_instance)} "
            f"it stores, illustrative: set a hot working set for a better estimate) in a buffer "
            f"cache of {_pct(BUFFER_CACHE_SHARE)} of memory{floor}"
        )
    need = {
        "busyCores": busy,
        "cpuCores": busy / TARGET_CPU,
        "memoryGib": need_mem,
        "storageGib": per_instance,
    }
    return PartSize(
        cpu,
        mem,
        disk,
        why={"cpuCores": why, "memoryGib": mem_why, "storageGib": storage_why},
        need=need,
    )


def _size_cache(config: Any, cfg: Json, total: float, workload: Json) -> PartSize:
    shards = max(1, int(num(cfg.get("shards")) or 1))
    executors = max(1, int(config.executors()))
    ms = mean_ms(cfg.get("serviceTimeMs"))
    per_shard = total / shards
    busy = per_shard * ms / 1000
    cpu, why = _cpu_for(busy, f"{fmt(per_shard)} commands/s per node × {fmt(ms)} ms")
    cpu, why = _cap_cpu(
        cpu,
        why,
        executors,
        f"it runs {executors} command{'' if executors == 1 else 's'} at a time (add shards)",
    )
    item_kb = num(cfg.get("itemSizeKb"))
    item_kb = 1.0 if item_kb is None else item_kb
    keys = int(workload["keyCount"])
    data = keys * item_kb / shards / 1024 / 1024
    return PartSize(
        cpu,
        _mem_for(data / KEY_MEMORY_SHARE * MEMORY_HEADROOM, 0.5),
        None,
        why={
            "cpuCores": why,
            "memoryGib": (
                f"{fmt(keys)} keys × {fmt(item_kb)} KiB{f' over {shards} shards' if shards > 1 else ''} "
                f"= {_gib(data)} per node; {_pct(KEY_MEMORY_SHARE)} of memory holds keys, {_headroom()}"
            ),
        },
        need={
            "busyCores": busy,
            "cpuCores": busy / TARGET_CPU,
            "memoryGib": data / KEY_MEMORY_SHARE,
        },
    )


def _size_balancer(total: float) -> PartSize:
    cpu = min(512.0, _step_up(max(1.0, total / PROXY_RPS_PER_VCPU), CPU_STEPS))
    need_cpu = total / PROXY_RPS_PER_VCPU
    return PartSize(
        cpu,
        _mem_for(PROXY_GIB_PER_VCPU * cpu, 1),
        None,
        why={
            "cpuCores": f"{fmt(total)} req/s at peak at about {fmt(PROXY_RPS_PER_VCPU)} req/s per vCPU (illustrative)",
            "memoryGib": f"{PROXY_GIB_PER_VCPU} GiB per vCPU: a proxy keeps little state (illustrative)",
        },
        need={
            "busyCores": need_cpu * TARGET_CPU,
            "cpuCores": need_cpu,
            "memoryGib": PROXY_GIB_PER_VCPU * need_cpu + OS_MEMORY_GIB,
        },
    )


def _size_queue(cfg: Json, total: float, workload: Json, on_vm: bool = False) -> PartSize:
    brokers = max(1, int(num(cfg.get("brokers")) or 1))
    need_cpu = total / brokers / BROKER_MSGS_PER_VCPU
    cpu = min(512.0, _step_up(max(1.0, need_cpu), CPU_STEPS))
    msg_kb = num(cfg.get("messageSizeKb"))
    msg_kb = 1.0 if msg_kb is None else msg_kb
    log = str(cfg.get("technology")) in LOG_BROKERS
    peak = peak_rps(workload)
    # a log keeps messages for its retention at the usual rate; other brokers delete on ack and
    # only need room for a backlog (an hour of peak publishes)
    rate = total * (min(1.0, float(workload["rps"]) / peak) if peak > 0 else 1.0) if log else total
    seconds = LOG_RETENTION_S if log else BACKLOG_S
    copies = min(3, brokers) if log else 1
    stored = rate * msg_kb * 1024 * seconds * copies / brokers / 2**30
    over = f" over {brokers} brokers" if brokers > 1 else ""
    if log:
        storage_why = (
            f"{fmt(rate)} messages/s × {fmt(msg_kb)} KiB kept 7 days (Kafka's default retention)"
            f"{f' × {copies} copies' if copies > 1 else ''}{over} = {_gib(stored)}, {_fill()}"
        )
    else:
        storage_why = (
            f"An hour of peak publishes as a backlog buffer ({fmt(total)} messages/s × {fmt(msg_kb)} KiB"
            f"{over}) = {_gib(stored)}, {_fill()}"
        )
    return PartSize(
        cpu,
        _mem_for(BROKER_GIB_PER_VCPU * cpu, 2),
        _disk_for(stored / STORAGE_FILL),
        why={
            "cpuCores": f"{fmt(total / brokers)} messages/s per broker at peak at about {fmt(BROKER_MSGS_PER_VCPU)} per vCPU (illustrative)",
            "memoryGib": f"{BROKER_GIB_PER_VCPU} GiB per vCPU for buffers and page cache (illustrative)",
            "storageGib": storage_why,
        },
        need={
            "busyCores": need_cpu * TARGET_CPU,
            "cpuCores": need_cpu,
            "memoryGib": BROKER_GIB_PER_VCPU * need_cpu + (OS_MEMORY_GIB if on_vm else 0.0),
            "storageGib": stored,
        },
    )


def _clusters(
    nodes: list[Json], registry: ComponentRegistry
) -> dict[str, tuple[str, float, float]]:
    """Kubernetes clusters in the design: id -> (label, allocatable vCPU, allocatable GiB) per node."""
    out: dict[str, tuple[str, float, float]] = {}
    for n in nodes:
        if n["type"] != "kubernetes_cluster" or n["type"] not in registry:
            continue
        model = registry.get(n["type"])
        try:
            cfg = model.parse_config({**model.default_config(), **(n.get("config") or {})})
        except Exception:
            continue
        cpu, mem = cfg.allocatable()  # type: ignore[attr-defined]
        out[n["id"]] = (n.get("label") or n["id"], float(cpu), float(mem))
    return out


def _fit_node(
    size: PartSize, config: Any, clusters: dict[str, tuple[str, float, float]]
) -> PartSize:
    """A pod can never be bigger than a node: cap a Kubernetes service's size at what one node of
    its cluster can allocate."""
    from archsim_engine.components.kubernetes import cluster_for

    cid = cluster_for(config, list(clusters))
    if cid is None:
        return size
    name, alloc_cpu, alloc_mem = clusters[cid]
    if size.cpu_cores is not None and size.cpu_cores > alloc_cpu:
        size.why["cpuCores"] += (
            f"; capped at {fmt(alloc_cpu)}, what one node of {name} can allocate to a pod"
        )
        size.cpu_cores = alloc_cpu
    if size.memory_gib is not None and size.memory_gib > alloc_mem:
        size.why["memoryGib"] += (
            f"; capped at {fmt(alloc_mem)} GiB, what one node of {name} can allocate to a pod"
        )
        size.memory_gib = alloc_mem
    return size


def pricing_of(doc: Json) -> tuple[Snapshot, str, tuple[str, ...], str]:
    """The price snapshot, purchase option, families and burstable policy a document names
    (generic when its snapshot is not available here; validation says so)."""
    pricing = doc.get("pricing") or {}
    try:
        snap = snapshot_for(pricing)
    except PricingError:
        snap = GENERIC
    return (
        snap,
        str(pricing.get("purchaseOption") or "on_demand"),
        tuple(pricing.get("families") or ()),
        "never" if pricing.get("burstable") == "never" else "auto",
    )


def _snap(
    size: PartSize,
    node_type: str,
    cfg: Json,
    snap: Snapshot,
    option: str,
    families: tuple[str, ...],
    burstable: str = "auto",
) -> None:
    """Snap CPU and memory up to the cheapest instance type covering them (a value set on the
    part counts as the need for that spec); a burstable type when the part passes the credit
    check."""
    set_cpu, set_mem = num(cfg.get("cpuCores")), num(cfg.get("memoryGib"))
    cpu = set_cpu if set_cpu is not None else size.cpu_cores
    mem = set_mem if set_mem is not None else size.memory_gib
    if cpu is None and mem is None:
        return
    busy = size.busy()
    m = choose_machine(snap, node_type, cfg, cpu, mem, option, families, busy, burstable)
    if m is None:
        return
    if m.sku is None:
        note = (
            f"; no {m.category} type in {snap.label} has {fmt(cpu or 0)} vCPU and "
            f"{_gib(mem or 0)}: kept as computed"
        )
        for k in ("cpuCores", "memoryGib"):
            if k in size.why:
                size.why[k] += note
        return
    sku = m.sku
    shape = f"{sku.sku} ({js_num(sku.vcpu)} vCPU, {_gib(sku.memory_gib)})"
    named = cfg.get("instanceType")
    if m.named:
        how = f"{shape}, the instance type set on the part"
    else:
        how = (
            f"{shape}, the cheapest {'compute (self-managed) ' if m.self_managed else ''}type in "
            f"{snap.label} that covers it"
        )
        if isinstance(named, str) and named:
            how += f" ({named}, the type set on the part, is not in it or is too small)"
        if sku.burstable and sku.baseline_cpu is not None and busy is not None:
            how += (
                f"; burstable: {fmt(busy.base)} cores busy at the base load stay under "
                f"{_pct(BURST_BASE_SHARE)} of its baseline ({fmt(sku.baseline_cpu * sku.vcpu)} "
                f"cores, {_pct(sku.baseline_cpu)} of each vCPU), so it earns CPU credits, and "
                f"{fmt(busy.peak)} at the peak stay under {_pct(BURST_PEAK_SHARE)} of its vCPUs"
            )
    # the CPU need is below the smallest type of its kind: it cannot shrink further
    need_cpu = size.need.get("cpuCores")
    smallest = min(
        (
            x.vcpu
            for x in snap.skus.get(m.category) or ()
            if not m.engine or not x.engines or m.engine in x.engines
        ),
        default=sku.vcpu,
    )
    if (
        need_cpu is not None
        and need_cpu <= max(1.0, smallest) + 1e-9
        and sku.vcpu <= max(smallest, 2.0)
    ):
        size.smallest = True
        how += f" (it needs less CPU than the smallest {m.category} type has)"
    if size.cpu_cores is not None:
        size.why["cpuCores"] = size.why.get("cpuCores", "") + f"; snapped to {how}"
        size.cpu_cores = float(sku.vcpu)
    if size.memory_gib is not None:
        size.why["memoryGib"] = size.why.get("memoryGib", "") + f"; snapped to {how}"
        size.memory_gib = float(sku.memory_gib)
    size.sku = sku.sku


def standard_shape(
    cpu: float, memory_gib: float, set_cpu: float | None = None, set_memory: float | None = None
) -> tuple[float, float] | None:
    """The cheapest standard shape (at generic rates) with at least ``cpu`` vCPU and
    ``memory_gib`` GiB: a common vCPU count with 2, 4 or 8 GiB per vCPU. A spec set on the part
    is kept as it is (``set_cpu``, ``set_memory``). None when no standard shape fits."""
    best: tuple[float, float, float] | None = None
    for vcpu in [set_cpu] if set_cpu is not None else CPU_STEPS:
        if vcpu < cpu - 1e-9:
            continue
        for ratio in GIB_PER_VCPU:
            gib = vcpu * ratio
            if gib < memory_gib - 1e-9 or (set_memory is not None and abs(gib - set_memory) > 1e-9):
                continue
            key = (vcpu * GENERIC_VCPU_HOUR + gib * GENERIC_GIB_HOUR, float(vcpu), float(gib))
            if best is None or key < best:
                best = key
    return (best[1], best[2]) if best else None


def _shape(size: PartSize, node_type: str, cfg: Json) -> None:
    """Without machine shapes: round auto CPU and memory up together to a standard shape (a
    value set on the part counts as the need for that spec and is kept)."""
    if machine_kind(node_type, cfg) is None:
        return  # pods, serverless functions, managed services: not a machine of their own
    set_cpu, set_mem = num(cfg.get("cpuCores")), num(cfg.get("memoryGib"))
    cpu = set_cpu if set_cpu is not None else size.cpu_cores
    mem = set_mem if set_mem is not None else size.memory_gib
    if cpu is None or mem is None or (set_cpu is not None and set_mem is not None):
        return
    shape = standard_shape(cpu, mem, set_cpu, set_mem)
    if shape is None or shape == (cpu, mem):
        return
    vcpu, gib = shape
    how = (
        f"rounded up to a standard shape, {js_num(vcpu)} vCPU and {_gib(gib)} "
        f"({js_num(gib / vcpu)} GiB per vCPU)"
    )
    if set_cpu is None and size.cpu_cores is not None and vcpu != size.cpu_cores:
        size.why["cpuCores"] = size.why.get("cpuCores", "") + f"; {how}"
        size.cpu_cores = vcpu
    if set_mem is None and size.memory_gib is not None and gib != size.memory_gib:
        size.why["memoryGib"] = size.why.get("memoryGib", "") + f"; {how}"
        size.memory_gib = gib


def _size_part(
    n: Json,
    config: Any,
    cfg: Json,
    load: Any,
    workload: Json,
    clusters: dict[str, tuple[str, float, float]],
    on_vm: bool,
) -> PartSize | None:
    """The size of one part for one load (read and write requests per second)."""
    read, write = load.read, load.write
    total = read + write
    if n["type"] == "service":
        return _fit_node(_size_service(config, cfg, total, on_vm), config, clusters)
    if n["type"] == "database":
        return _size_database(config, cfg, read, write, workload)
    if n["type"] == "cache":
        return _size_cache(config, cfg, total, workload)
    if n["type"] == "load_balancer":
        return _size_balancer(total)
    if n["type"] == "message_queue":
        return _size_queue(cfg, total, workload, on_vm)
    return None


def auto_sizes(doc: Json, registry: ComponentRegistry) -> dict[str, PartSize]:
    """The auto size of every part that has resource specs, from a normalized document dict."""
    nodes: list[Json] = doc["architecture"]["nodes"]
    workload = doc["workload"]
    snap, option, families, burstable = pricing_of(doc)
    # a TTL cache's misses from its TTL and the key popularity (all of them when its load
    # cannot be estimated)
    loads = estimate_load(
        doc, registry, ttl_miss_share=1.0, miss_shares=ttl_miss_shares(doc, registry)
    )
    # and at the steady base load, for CPU credits (the hit ratios at that rate too)
    base = base_rps(workload)
    base_loads = estimate_load(
        doc,
        registry,
        ttl_miss_share=1.0,
        miss_shares=ttl_miss_shares(doc, registry, rate=base),
        rate=base,
    )
    clusters = _clusters(nodes, registry)
    out: dict[str, PartSize] = {}
    for n in nodes:
        if n["type"] not in registry:
            continue
        model = registry.get(n["type"])
        fields = {f.alias or name for name, f in model.config_model.model_fields.items()}
        has = [k for k in SPEC_KEYS if k in fields]
        if not has:
            continue
        cfg: Json = {**model.default_config(), **(n.get("config") or {})}
        try:
            config = model.parse_config(cfg)
        except Exception:
            continue
        load = loads.get(n["id"])
        read = load.read if load else 0.0
        write = load.write if load else 0.0
        total = read + write
        if str(cfg.get("technology")) in MANAGED:
            out[n["id"]] = PartSize(
                None, None, None, total, skipped="A managed service: the provider sizes it."
            )
            continue
        if load is None:
            out[n["id"]] = PartSize(
                None, None, None, total, skipped="Its load cannot be estimated before a run."
            )
            continue
        on_vm = (machine_kind(n["type"], cfg) or ("", None))[0] == "compute"
        size = _size_part(n, config, cfg, load, workload, clusters, on_vm)
        if size is None:
            continue
        at_base = base_loads.get(n["id"])
        base_size = (
            _size_part(n, config, cfg, at_base, workload, clusters, on_vm) if at_base else None
        )
        if base_size is not None and "busyCores" in base_size.need:
            size.need["baseBusyCores"] = base_size.need["busyCores"]
        if snap.has_shapes():
            _snap(size, n["type"], cfg, snap, option, families, burstable)
        else:
            _shape(size, n["type"], cfg)
            need_cpu = size.need.get("cpuCores")
            size.smallest = need_cpu is not None and need_cpu <= 1.0 + 1e-9
        # only the specs the part has
        out[n["id"]] = PartSize(
            size.cpu_cores if "cpuCores" in has else None,
            size.memory_gib if "memoryGib" in has else None,
            size.storage_gib if "storageGib" in has else None,
            total,
            why={k: v for k, v in size.why.items() if k in has},
            sku=size.sku,
            need={k: v for k, v in size.need.items() if _need_spec(k) in has},
            memory_model=size.memory_model if "memoryGib" in has else None,
            smallest=size.smallest,
        )
    return out


def _need_spec(key: str) -> str:
    """The spec a need belongs to: busyCores -> cpuCores, memoryGibMax -> memoryGib."""
    return {"busyCores": "cpuCores", "baseBusyCores": "cpuCores", "memoryGibMax": "memoryGib"}.get(
        key, key
    )


def base_busy(sizes: dict[str, PartSize]) -> dict[str, float]:
    """CPU cores one instance of each auto-sized part keeps busy at the steady base load."""
    return {
        nid: size.need["baseBusyCores"]
        for nid, size in sizes.items()
        if "baseBusyCores" in size.need
    }


def apply_auto_sizes(doc: Json, registry: ComponentRegistry) -> tuple[Json, dict[str, PartSize]]:
    """Fill every empty spec of a normalized document dict with its auto size (in place).

    Returns the document and the sizes (with which specs were filled, for the report)."""
    if not doc["architecture"].get("autoSize", True):
        return doc, {}
    sizes = auto_sizes(doc, registry)
    for n in doc["architecture"]["nodes"]:
        size = sizes.get(n["id"])
        if size is None:
            continue
        config = n.setdefault("config", {})
        for k in SPEC_KEYS:
            value = size.get(k)
            if value is not None and num(config.get(k)) is None:
                config[k] = value
        # the instance type chosen, so cost and a re-run of the run's input use the same one
        if size.sku and not config.get("instanceType"):
            config["instanceType"] = size.sku
    return doc, sizes


def _typical_memory(size: PartSize, row: Json | None) -> float | None:
    """A service's typical memory: its fixed memory plus the requests one replica holds on
    average at the peak (arrival rate times the time in the part, Little's law, from the estimate)."""
    if size.memory_model is None or row is None:
        return None
    rps, stay = row.get("arrivalRps"), row.get("meanResidenceMs")
    if not isinstance(rps, int | float) or not isinstance(stay, int | float):
        return None
    fixed_gib, per_request, pools = size.memory_model
    held = rps / max(1.0, pools) * stay / 1000
    return fixed_gib + held * per_request


def need_text(key: str, need: dict[str, float], value: float | None, holder: str) -> str | None:
    """One line: what the part needs against what its instance has, e.g. "needs 0.3 GiB (up to
    0.5 when queues are full) · c6g.xlarge has 8 GiB"."""
    has = (
        f" · {holder} has {_gib(value) if key != 'cpuCores' else js_num(value) + ' vCPU'}"
        if value is not None
        else ""
    )
    if key == "cpuCores" and "cpuCores" in need:
        busy = need.get("busyCores")
        why = (
            f" ({fmt(busy)} busy at the peak, at a {_pct(TARGET_CPU)} target)"
            if busy is not None
            else ""
        )
        return f"needs {fmt(need['cpuCores'])} vCPU{why}{has}"
    if key == "memoryGib":
        typical, most = need.get("memoryGib"), need.get("memoryGibMax")
        if typical is not None and most is not None and most > typical + 0.005:
            return f"needs {_gib(typical)} (up to {_gib(most)} when queues are full){has}"
        one = typical if typical is not None else most
        if one is not None:
            return f"needs {_gib(one)}{has}"
    if key == "storageGib" and "storageGib" in need:
        return f"stores {_gib(need['storageGib'])}{has}"
    return None


def _holder(node_type: str, cfg: Json) -> str:
    """What holds a part's resources when there is no instance type: a pod, a function, a node."""
    if node_type == "service":
        platform = cfg.get("platform")
        return {"kubernetes": "each pod", "serverless": "each function instance"}.get(
            str(platform), "each replica"
        )
    if node_type == "database":
        return "each instance"
    if node_type in ("cache", "load_balancer"):
        return "each node"
    if node_type == "message_queue":
        return "each broker"
    return "each instance"


def sizing_summary(
    document: ArchitectureDocument,
    resolved: ArchitectureDocument,
    sizes: dict[str, PartSize],
    registry: ComponentRegistry,
    estimate: Json | None = None,
) -> Json:
    """Per part: the CPU, memory and storage it is simulated with and where each came from (set on
    the part, auto-sized, or a scenario override), its auto sizes with how they were worked out,
    what it needs against what its instance has (``need``, ``needText``; a service's typical
    memory comes from ``estimate``, the instant estimate, when given), and the price of the size
    used (its instance type with a price snapshot)."""
    from archsim_engine.cost import quote

    snap, option, families, _ = pricing_of(resolved.to_json_dict())

    raw = {n.id: n.config for n in document.architecture.nodes}
    rows = {r["id"]: r for r in (estimate or {}).get("parts") or []}
    nodes: Json = {}
    for n in resolved.architecture.nodes:
        size = sizes.get(n.id)
        if size is None:
            continue
        specs: Json = {}
        for k in SPEC_KEYS:
            value = n.config.get(k)
            if value is None:
                continue
            given = raw.get(n.id, {}).get(k)
            source = (
                "set" if given is not None else ("auto" if size.get(k) == value else "scenario")
            )
            specs[k] = {"value": value, "source": source}
        priced = quote(
            {"id": n.id, "type": n.type, "config": n.config},
            registry,
            snap,
            option,
            families,
            size_only=True,
        )
        entry: Json = {
            "specs": specs,
            "auto": {k: size.get(k) for k in SPEC_KEYS},
            "peakRps": round(size.peak_rps, 3),
            "why": dict(size.why),
            "pricePerHour": priced.price,
            "sku": priced.sku,
        }
        need = dict(size.need)
        typical = _typical_memory(size, rows.get(n.id))
        if typical is not None:
            need["memoryGib"] = min(typical, need.get("memoryGibMax", typical))
        if need:
            entry["need"] = rounded_need(need)
            holder = priced.sku or _holder(n.type, n.config)
            texts = {
                k: t
                for k in SPEC_KEYS
                if k in specs and (t := need_text(k, need, specs[k]["value"], holder)) is not None
            }
            if texts:
                entry["needText"] = texts
        if size.smallest:
            entry["smallest"] = True
        if size.skipped:
            entry["skipped"] = size.skipped
        nodes[n.id] = entry
    return {
        "auto": resolved.architecture.auto_size,
        "targetCpuUtilization": TARGET_CPU,
        "nodes": nodes,
    }
