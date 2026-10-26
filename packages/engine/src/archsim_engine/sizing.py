"""Auto-sizing: the CPU, memory and storage each part needs for the design's own peak load.

An empty ``cpuCores``, ``memoryGib`` or ``storageGib`` is *auto*: before a run (and in
``analyze``), the part gets the size worked out here, so the CLI, the API and the web app all
simulate the same document the same way. A value set on the part always wins. The rules follow
what the engine does with each spec (docs/simulation-model.md, "Resource specs"), so an
auto-sized part is not the bottleneck at the peak it was sized for:

- CPU: the CPU work per second at the peak (from the static load estimate in ``load.py``) at a
  60% target, rounded up to a common size, at least 1 vCPU (below one core every request slows
  down), never more than the replica's handlers (workers, event loops, command threads) can use.
- Memory: what the engine's memory model needs (requests held, buffer cache, keys) plus 25%.
- Storage: the data per instance kept under 70% full.

A few ratios have no measured basis in the model (proxy throughput per vCPU, broker memory per
vCPU, database memory without a working set); their explanations say "illustrative".
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from archsim_engine.components.cache import KEY_MEMORY_SHARE
from archsim_engine.components.database import BUFFER_CACHE_SHARE
from archsim_engine.load import Json, estimate_load, js_num, mean_ms, num, peak_rps, round_half_up
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
MANAGED = {"aws_alb", "api_gateway", "cdn", "dynamodb", "sqs", "pubsub"}
"""Managed services you do not size yourself."""
MEMORY_HEADROOM = 1.25
"""Auto memory is what the engine's memory model needs, plus 25%."""
STORAGE_FILL = 0.7
"""Auto storage keeps the data under 70% full."""
MIN_STORAGE_GIB = 10.0
DB_GIB_PER_VCPU = 4
"""Illustrative: database memory per vCPU when no hot working set is set."""
PROXY_GIB_PER_VCPU = 1
"""Illustrative: load balancer memory per vCPU (a proxy keeps little state)."""
BROKER_GIB_PER_VCPU = 2
"""Illustrative: broker memory per vCPU for buffers and page cache."""
BACKLOG_S = 3600
"""Storage of a broker that deletes on ack: room for an hour of peak publishes."""


@dataclass
class PartSize:
    cpu_cores: float | None
    memory_gib: float | None
    storage_gib: float | None
    peak_rps: float = 0.0
    why: dict[str, str] = field(default_factory=dict)
    skipped: str | None = None

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
        return out


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


def _size_service(config: Any, cfg: Json, total: float) -> PartSize:
    baseline = num(cfg.get("memoryBaselineMb"))
    baseline = 256.0 if baseline is None else baseline
    per_req = num(cfg.get("memoryPerRequestMb"))
    per_req = 2.0 if per_req is None else per_req
    if cfg.get("platform") == "serverless":
        mb = baseline + per_req
        return PartSize(
            1.0,
            _mem_for(mb * MEMORY_HEADROOM / 1024, 0.5),
            None,
            why={
                "cpuCores": "One request per instance: 1 vCPU runs it at full speed",
                "memoryGib": f"{fmt(baseline)} MiB base + 1 request × {fmt(per_req)} MiB, {_headroom()}",
            },
        )
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
    cpu, why = _cpu_for(
        per_replica * ms / 1000,
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
    wanted = _mem_for(mb * MEMORY_HEADROOM / 1024, 0.5)
    mem = min(wanted, float(MAX_SERVICE_MEMORY_GIB))
    mem_why = (
        f"{fmt(baseline)} MiB base + {fmt(held)} requests held ({working} working, {fmt(queue)} "
        f"queued) × {fmt(per_req)} MiB = {_gib(mb / 1024)}, {_headroom()}"
    )
    if mem < wanted:
        refuse_at = math.floor((mem * 1024 - baseline) / max(per_req, 1e-9))
        mem_why = (
            f"{mem_why}; capped at {MAX_SERVICE_MEMORY_GIB} GiB, so past about {fmt(refuse_at)} "
            "requests held it refuses (out of memory)"
        )
    return PartSize(cpu, mem, None, why={"cpuCores": why, "memoryGib": mem_why})


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
    ws = num(cfg.get("workingSetGib"))
    if ws is not None:
        per = ws / shards
        mem = _mem_for(per / BUFFER_CACHE_SHARE, 2)
        mem_why = f"{_gib(per)} of hot data per instance in a buffer cache of {_pct(BUFFER_CACHE_SHARE)} of memory, so reads stay in memory"
    else:
        mem = _mem_for(DB_GIB_PER_VCPU * cpu, 2)
        mem_why = f"{DB_GIB_PER_VCPU} GiB per vCPU, a common database ratio (illustrative); set a hot working set to size the buffer cache"
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
    return PartSize(
        cpu, mem, disk, why={"cpuCores": why, "memoryGib": mem_why, "storageGib": storage_why}
    )


def _size_cache(config: Any, cfg: Json, total: float, workload: Json) -> PartSize:
    shards = max(1, int(num(cfg.get("shards")) or 1))
    executors = max(1, int(config.executors()))
    ms = mean_ms(cfg.get("serviceTimeMs"))
    per_shard = total / shards
    cpu, why = _cpu_for(
        per_shard * ms / 1000, f"{fmt(per_shard)} commands/s per node × {fmt(ms)} ms"
    )
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
    )


def _size_balancer(total: float) -> PartSize:
    cpu = min(512.0, _step_up(max(1.0, total / PROXY_RPS_PER_VCPU), CPU_STEPS))
    return PartSize(
        cpu,
        _mem_for(PROXY_GIB_PER_VCPU * cpu, 1),
        None,
        why={
            "cpuCores": f"{fmt(total)} req/s at peak at about {fmt(PROXY_RPS_PER_VCPU)} req/s per vCPU (illustrative)",
            "memoryGib": f"{PROXY_GIB_PER_VCPU} GiB per vCPU: a proxy keeps little state (illustrative)",
        },
    )


def _size_queue(cfg: Json, total: float, workload: Json) -> PartSize:
    brokers = max(1, int(num(cfg.get("brokers")) or 1))
    cpu = min(512.0, _step_up(max(1.0, total / brokers / BROKER_MSGS_PER_VCPU), CPU_STEPS))
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


def auto_sizes(doc: Json, registry: ComponentRegistry) -> dict[str, PartSize]:
    """The auto size of every part that has resource specs, from a normalized document dict."""
    nodes: list[Json] = doc["architecture"]["nodes"]
    workload = doc["workload"]
    loads = estimate_load(doc, registry, ttl_miss_share=1.0)
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
        size: PartSize | None = None
        if n["type"] == "service":
            size = _size_service(config, cfg, total)
            size = _fit_node(size, config, clusters)
        elif n["type"] == "database":
            size = _size_database(config, cfg, read, write, workload)
        elif n["type"] == "cache":
            size = _size_cache(config, cfg, total, workload)
        elif n["type"] == "load_balancer":
            size = _size_balancer(total)
        elif n["type"] == "message_queue":
            size = _size_queue(cfg, total, workload)
        if size is None:
            continue
        # only the specs the part has
        out[n["id"]] = PartSize(
            size.cpu_cores if "cpuCores" in has else None,
            size.memory_gib if "memoryGib" in has else None,
            size.storage_gib if "storageGib" in has else None,
            total,
            why={k: v for k, v in size.why.items() if k in has},
        )
    return out


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
    return doc, sizes


def sizing_summary(
    document: ArchitectureDocument, resolved: ArchitectureDocument, sizes: dict[str, PartSize]
) -> Json:
    """Per part: the CPU, memory and storage it is simulated with and where each came from (set on
    the part, auto-sized, or a scenario override), its auto sizes with how they were worked out,
    and the illustrative price of the size used."""
    from archsim_engine.cost import size_price_per_hour

    raw = {n.id: n.config for n in document.architecture.nodes}
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
        entry: Json = {
            "specs": specs,
            "auto": {k: size.get(k) for k in SPEC_KEYS},
            "peakRps": round(size.peak_rps, 3),
            "why": dict(size.why),
            "pricePerHour": size_price_per_hour(
                num(n.config.get("cpuCores")),
                num(n.config.get("memoryGib")),
                num(n.config.get("storageGib")),
            ),
        }
        if size.skipped:
            entry["skipped"] = size.skipped
        nodes[n.id] = entry
    return {
        "auto": resolved.architecture.auto_size,
        "targetCpuUtilization": TARGET_CPU,
        "nodes": nodes,
    }
