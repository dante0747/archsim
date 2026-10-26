"""Terraform -> design, from ``terraform show -json`` (a plan or a state).

Resources that map reliably become parts: databases (engine and instance class become the
technology and real CPU and memory), caches, serverless functions, queues and streams, load
balancers and gateways, CDNs, Kubernetes clusters and node pools, container services, on AWS,
GCP and Azure. Infrastructure code rarely says who calls whom, so links are only added where the
code states them (a Lambda event source mapping); everything else becomes a "connect these?"
suggestion in the import report.
"""

from __future__ import annotations

import json
import re
from typing import Any

from archsim_engine.importers.common import DesignBuilder, ImportReport
from archsim_engine.importers.compose import _add_clients
from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry

Json = dict[str, Any]

# AWS sizes (the part after the dot) -> vCPU; GiB per vCPU by family letter. From the AWS
# instance type documentation (general purpose m: 4, compute c: 2, memory r: 8, x: 16).
AWS_SIZE_VCPU = {
    "medium": 1,
    "large": 2,
    "xlarge": 4,
    "2xlarge": 8,
    "4xlarge": 16,
    "8xlarge": 32,
    "12xlarge": 48,
    "16xlarge": 64,
    "24xlarge": 96,
}
AWS_GIB_PER_VCPU = {"m": 4, "c": 2, "r": 8, "x": 16, "z": 8}
AWS_BURSTABLE = {
    "micro": (2, 1),
    "small": (2, 2),
    "medium": (2, 4),
    "large": (2, 8),
    "xlarge": (4, 16),
    "2xlarge": (8, 32),
}
DOCS = {
    "aws": "https://aws.amazon.com/ec2/instance-types/",
    "gcp": "https://cloud.google.com/compute/docs/general-purpose-machines",
    "azure": "https://learn.microsoft.com/en-us/azure/virtual-machines/sizes",
}


def aws_size(instance_class: str | None) -> tuple[float, float] | None:
    """(vCPU, GiB) of an AWS instance class: db.r6g.large, cache.m7g.xlarge, kafka.m5.large."""
    if not instance_class:
        return None
    parts = instance_class.split(".")
    if len(parts) < 2:
        return None
    family, size = parts[-2], parts[-1]
    if family.startswith("t"):
        return AWS_BURSTABLE.get(size)
    vcpu = AWS_SIZE_VCPU.get(size)
    per = AWS_GIB_PER_VCPU.get(family[:1])
    return (vcpu, vcpu * per) if vcpu and per else None


def gcp_size(machine: str | None) -> tuple[float, float] | None:
    """e2-standard-4, n2-highmem-8, db-custom-2-7680, db-n1-standard-2."""
    if not machine:
        return None
    if m := re.fullmatch(r"db-custom-(\d+)-(\d+)", machine):
        return int(m[1]), round(int(m[2]) / 1024, 2)
    if m := re.fullmatch(r"(?:db-)?[a-z0-9]+-(standard|highmem|highcpu)-(\d+)", machine):
        per: float = {"standard": 4, "highmem": 8, "highcpu": 1}[m[1]]
        if machine.startswith("db-n1"):
            per = {"standard": 3.75, "highmem": 6.5, "highcpu": 0.9}[m[1]]
        return int(m[2]), round(int(m[2]) * per, 2)
    return None


def azure_size(sku: str | None) -> tuple[float, float] | None:
    """Standard_D4s_v5, GP_Standard_D2ds_v4, Standard_E8s_v5, Standard_F4s_v2: series whose
    memory per vCPU is fixed (D v3-v6 4 GiB, E v3-v6 8 GiB, F v2 2 GiB). Older series and
    low-memory ("l") or constrained-vCPU sizes return None (sized by hand)."""
    if not sku:
        return None
    m = re.search(r"Standard_([DEF])(\d+)([a-z]*)_v(\d+)$", sku)
    versions = {"D": {"3", "4", "5", "6"}, "E": {"3", "4", "5", "6"}, "F": {"2"}}
    if not m or m[4] not in versions[m[1]] or "l" in m[3]:
        return None
    per = {"D": 4, "E": 8, "F": 2}[m[1]]
    return int(m[2]), int(m[2]) * per


# resources read in the second pass, attached to parts made from other resources
ATTACHED = {
    "aws_rds_cluster_instance",
    "aws_eks_node_group",
    "google_container_node_pool",
    "aws_lambda_event_source_mapping",
}

DB_ENGINES = [
    (r"aurora-postgres|^postgres", "postgresql"),
    (r"aurora-mysql|^aurora$|^mysql", "mysql"),
    (r"^mariadb", "mariadb"),
    (r"^sqlserver", "sqlserver"),
    (r"^oracle", "oracle"),
    (r"^POSTGRES", "postgresql"),
    (r"^MYSQL", "mysql"),
    (r"^SQLSERVER", "sqlserver"),
]
RUNTIMES = [
    (r"^nodejs", "nodejs"),
    (r"^python", "python"),
    (r"^java", "java"),
    (r"^go|provided", "go"),
    (r"^dotnet", "dotnet"),
    (r"^ruby", "ruby"),
]


def _first(v: Any) -> Json:
    if isinstance(v, list):
        return v[0] if v and isinstance(v[0], dict) else {}
    return v if isinstance(v, dict) else {}


def _match(rules: list[tuple[str, str]], value: str | None) -> str | None:
    return next((tech for pattern, tech in rules if value and re.search(pattern, value)), None)


def _resources(module: Json, prefix: str = "") -> list[Json]:
    out = [r for r in module.get("resources") or [] if r.get("mode", "managed") == "managed"]
    for child in module.get("child_modules") or []:
        out += _resources(child)
    return out


def _config_refs(plan: Json) -> dict[str, dict[str, list[str]]]:
    """address -> {attribute: [referenced addresses]} from the plan's configuration section."""
    out: dict[str, dict[str, list[str]]] = {}

    def walk(module: Json, prefix: str) -> None:
        for r in module.get("resources") or []:
            addr = f"{prefix}{r['address']}" if prefix else r["address"]
            refs = {}
            for attr, expr in (r.get("expressions") or {}).items():
                if isinstance(expr, dict) and expr.get("references"):
                    refs[attr] = list(expr["references"])
            out[addr] = refs
        for name, call in (module.get("module_calls") or {}).items():
            walk(call.get("module") or {}, f"{prefix}module.{name}.")

    walk((plan.get("configuration") or {}).get("root_module") or {}, "")
    return out


def import_terraform(
    text: str, registry: ComponentRegistry, name: str | None = None
) -> tuple[Json, ImportReport]:
    try:
        plan = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(
            f"not JSON: run `terraform show -json plan.out` (or state) first ({exc})"
        ) from None
    root = ((plan.get("planned_values") or plan.get("values") or {}).get("root_module")) or {}
    resources = _resources(root)
    if not resources:
        raise ValueError(
            "no resources found (expected `terraform show -json` output with planned_values or values)"
        )
    report = ImportReport("Terraform")
    b = DesignBuilder(registry, name or "Imported from Terraform", "a Terraform plan")
    by_address: dict[str, str] = {}
    clusters: dict[str, str] = {}  # rds/eks cluster id or name -> part id

    def add(r: Json, type_: str, config: Json, label: str | None = None, what: str = "") -> str:
        v = r.get("values") or {}
        base = str(
            v.get("name")
            or v.get("identifier")
            or v.get("cluster_id")
            or v.get("function_name")
            or r.get("name")
        )
        node_id = b.unique_id(base)
        notes = config.pop("_notes", {})
        b.add(node_id, type_, label or base, {k: x for k, x in config.items() if x is not None})
        for key, text in notes.items():
            b.note(node_id, key, text)
        by_address[r["address"]] = node_id
        report.mapped.append(
            f"{r['address']} -> {registry.get(type_).display_name}{f' ({what})' if what else ''}"
        )
        return node_id

    def sized(
        config: Json, size: tuple[float, float] | None, instance: str | None, cloud: str
    ) -> None:
        if size:
            config["cpuCores"], config["memoryGib"] = size
            from_type = f"from instance type {instance} ({DOCS[cloud]})"
            config["_notes"] = {"cpuCores": from_type, "memoryGib": from_type}
        elif instance:
            report.guessed.append(
                f"instance type '{instance}' is not in the size table ({DOCS[cloud]}): set CPU and memory yourself"
            )

    for r in resources:
        t, v = r["type"], r.get("values") or {}
        if t == "aws_db_instance":
            if v.get("replicate_source_db"):
                continue  # counted on its source below
            cfg: Json = {
                "technology": _match(DB_ENGINES, v.get("engine")) or "generic",
                "storageGib": v.get("allocated_storage"),
            }
            sized(cfg, aws_size(v.get("instance_class")), v.get("instance_class"), "aws")
            add(r, "database", cfg, what=f"{v.get('engine')} {v.get('instance_class')}")
        elif t == "aws_rds_cluster":
            cid = add(
                r,
                "database",
                {"technology": _match(DB_ENGINES, v.get("engine")) or "generic"},
                what=str(v.get("engine")),
            )
            clusters[str(v.get("cluster_identifier") or r["name"])] = cid
        elif t in ("aws_elasticache_cluster", "aws_elasticache_replication_group"):
            engine = str(v.get("engine") or "redis")
            cfg = {
                "technology": "memcached"
                if engine == "memcached"
                else "valkey"
                if engine == "valkey"
                else "redis"
            }
            shards = v.get("num_node_groups")
            if isinstance(shards, int) and shards > 1:
                cfg["shards"] = shards
            sized(cfg, aws_size(v.get("node_type")), v.get("node_type"), "aws")
            add(r, "cache", cfg, what=f"{engine} {v.get('node_type')}")
        elif t == "aws_lambda_function":
            mem = v.get("memory_size")
            cfg = {
                "platform": "serverless",
                "technology": _match(RUNTIMES, v.get("runtime")) or "generic",
                "memoryGib": round(mem / 1024, 3) if isinstance(mem, int | float) else None,
                "concurrencyLimit": v.get("reserved_concurrent_executions")
                if (v.get("reserved_concurrent_executions") or 0) > 0
                else None,
            }
            add(r, "service", cfg, what=f"Lambda {v.get('runtime')}")
        elif t == "aws_sqs_queue":
            cfg = {
                "technology": "sqs",
                "fifo": bool(v.get("fifo_queue")) or None,
                "visibilityTimeoutS": v.get("visibility_timeout_seconds"),
            }
            add(r, "message_queue", cfg, what="SQS")
        elif t == "aws_msk_cluster":
            info = _first(v.get("broker_node_group_info"))
            cfg = {"technology": "kafka", "brokers": v.get("number_of_broker_nodes")}
            sized(cfg, aws_size(info.get("instance_type")), info.get("instance_type"), "aws")
            add(r, "message_queue", cfg, what=f"MSK {info.get('instance_type')}")
        elif t in ("aws_lb", "aws_alb"):
            app = v.get("load_balancer_type", "application") == "application"
            add(
                r,
                "load_balancer",
                {"technology": "aws_alb" if app else "generic"},
                what="ALB" if app else "NLB (generic: no ALB idle timeout)",
            )
        elif t in ("aws_api_gateway_rest_api", "aws_apigatewayv2_api"):
            add(r, "load_balancer", {"technology": "api_gateway"}, what="API Gateway")
        elif t == "aws_cloudfront_distribution":
            add(r, "cache", {"technology": "cdn"}, label="CloudFront", what="CDN")
        elif t == "aws_dynamodb_table":
            prov = v.get("billing_mode") == "PROVISIONED"
            cfg = {
                "technology": "dynamodb",
                "capacityMode": "provisioned" if prov else "on_demand",
                "readCapacityUnits": v.get("read_capacity") if prov else None,
                "writeCapacityUnits": v.get("write_capacity") if prov else None,
            }
            add(r, "database", cfg, what="DynamoDB")
        elif t == "aws_eks_cluster":
            clusters[str(v.get("name") or r["name"])] = add(
                r, "kubernetes_cluster", {"technology": "eks"}, what="EKS"
            )
        elif t == "aws_ecs_service":
            add(
                r,
                "service",
                {"platform": "docker", "replicas": v.get("desired_count")},
                what="ECS service",
            )
        elif t == "google_sql_database_instance":
            tier = _first(v.get("settings")).get("tier")
            cfg = {"technology": _match(DB_ENGINES, v.get("database_version")) or "generic"}
            sized(cfg, gcp_size(tier), tier, "gcp")
            add(r, "database", cfg, what=f"Cloud SQL {v.get('database_version')} {tier}")
        elif t == "google_redis_instance":
            add(
                r,
                "cache",
                {"technology": "redis", "memoryGib": v.get("memory_size_gb")},
                what="Memorystore",
            )
        elif t in ("google_pubsub_topic", "google_pubsub_subscription"):
            if t == "google_pubsub_topic":
                add(r, "message_queue", {"technology": "pubsub"}, what="Pub/Sub")
        elif t == "google_container_cluster":
            clusters[str(v.get("name") or r["name"])] = add(
                r, "kubernetes_cluster", {"technology": "gke"}, what="GKE"
            )
        elif t in ("google_cloud_run_v2_service", "google_cloud_run_service"):
            add(r, "service", {"platform": "serverless"}, what="Cloud Run")
        elif t in ("azurerm_postgresql_flexible_server", "azurerm_mysql_flexible_server"):
            cfg = {
                "technology": "postgresql" if "postgresql" in t else "mysql",
                "storageGib": round(v["storage_mb"] / 1024) if v.get("storage_mb") else None,
            }
            sized(cfg, azure_size(v.get("sku_name")), v.get("sku_name"), "azure")
            add(r, "database", cfg, what=f"Azure flexible server {v.get('sku_name')}")
        elif t == "azurerm_redis_cache":
            add(r, "cache", {"technology": "redis"}, what="Azure Cache for Redis")
        elif t == "azurerm_kubernetes_cluster":
            pool = _first(v.get("default_node_pool"))
            cfg = {"technology": "aks", "nodes": pool.get("node_count") or pool.get("min_count")}
            size = azure_size(pool.get("vm_size"))
            if size:
                cfg["nodeVcpu"], cfg["nodeMemoryGib"] = size
                pool_type = f"from VM size {pool.get('vm_size')} ({DOCS['azure']})"
                cfg["_notes"] = {"nodeVcpu": pool_type, "nodeMemoryGib": pool_type}
            if pool.get("enable_auto_scaling") or pool.get("auto_scaling_enabled"):
                cfg.update(
                    clusterAutoscaler=True,
                    minNodes=pool.get("min_count"),
                    maxNodes=pool.get("max_count"),
                )
            clusters[str(v.get("name") or r["name"])] = add(
                r, "kubernetes_cluster", cfg, what=f"AKS {pool.get('vm_size')}"
            )
        elif t in ("azurerm_linux_function_app", "azurerm_windows_function_app"):
            add(r, "service", {"platform": "serverless"}, what="Azure Functions")
        elif t in ("azurerm_servicebus_queue", "azurerm_servicebus_topic"):
            add(r, "message_queue", {}, what="Service Bus (generic queue)")
        elif t == "azurerm_application_gateway":
            add(r, "load_balancer", {}, what="Application Gateway (generic)")
        elif t not in ATTACHED:
            report.ignored.append(f"{r['address']} ({t})")

    # second pass: things that attach to parts created above
    for r in resources:
        t, v = r["type"], r.get("values") or {}
        if t == "aws_db_instance" and v.get("replicate_source_db"):
            src = next(
                (
                    n
                    for a, n in by_address.items()
                    if a.endswith(str(v["replicate_source_db"]))
                    or (b.node(n) or {}).get("label") == v["replicate_source_db"]
                ),
                None,
            )
            if src:
                cfg = b.node(src)["config"]  # type: ignore[index]
                cfg["readReplicas"] = int(cfg.get("readReplicas", 0)) + 1
                report.mapped.append(f"{r['address']} -> a read replica of {src}")
        elif t == "aws_rds_cluster_instance":
            cluster_id = clusters.get(str(v.get("cluster_identifier")))
            if cluster_id:
                cid = cluster_id
                cfg = b.node(cid)["config"]  # type: ignore[index]
                cfg["_instances"] = int(cfg.get("_instances", 0)) + 1
                if "cpuCores" not in cfg:
                    size = aws_size(v.get("instance_class"))
                    if size:
                        cfg["cpuCores"], cfg["memoryGib"] = size
                        for key in ("cpuCores", "memoryGib"):
                            b.note(
                                cid,
                                key,
                                f"from instance type {v.get('instance_class')} ({DOCS['aws']})",
                            )
                report.mapped.append(f"{r['address']} -> an instance of {cid}")
        elif t in ("aws_eks_node_group", "google_container_node_pool"):
            cluster = clusters.get(str(v.get("cluster_name") or v.get("cluster")))
            if cluster is None:
                report.ignored.append(f"{r['address']} (its cluster is not in the plan)")
                continue
            cfg = b.node(cluster)["config"]  # type: ignore[index]
            if t == "aws_eks_node_group":
                sc = _first(v.get("scaling_config"))
                size = aws_size((v.get("instance_types") or [None])[0])
                cfg.update(
                    nodes=sc.get("desired_size"),
                    minNodes=sc.get("min_size"),
                    maxNodes=sc.get("max_size"),
                    clusterAutoscaler=True,
                )
            else:
                auto = _first(v.get("autoscaling"))
                size = gcp_size(_first(v.get("node_config")).get("machine_type"))
                cfg.update(
                    nodes=v.get("node_count") or v.get("initial_node_count"),
                    minNodes=auto.get("min_node_count"),
                    maxNodes=auto.get("max_node_count"),
                )
            if size:
                cfg["nodeVcpu"], cfg["nodeMemoryGib"] = size
                for key in ("nodeVcpu", "nodeMemoryGib"):
                    b.note(cluster, key, "from the node pool's machine type")
            for k in [k for k, x in cfg.items() if x is None]:
                del cfg[k]
            report.mapped.append(f"{r['address']} -> the node pool of {cluster}")
    for n in b.nodes:
        inst = n["config"].pop("_instances", None)
        if inst and inst > 1:
            n["config"]["readReplicas"] = inst - 1
            b.note(
                n["id"],
                "readReplicas",
                f"guessed: {inst} cluster instances as a writer and readers",
            )
            report.guessed.append(
                f"{n['id']}: {inst} cluster instances read as a writer and {inst - 1} readers"
            )

    # links the code states: Lambda event source mappings (queue/stream -> function)
    refs = _config_refs(plan)
    for r in resources:
        if r["type"] != "aws_lambda_event_source_mapping":
            continue
        rr = refs.get(r["address"], {})
        src = next(
            (
                by_address[a]
                for ref in rr.get("event_source_arn", [])
                for a in by_address
                if ref.startswith(a)
            ),
            None,
        )
        fn = next(
            (
                by_address[a]
                for ref in rr.get("function_name", [])
                for a in by_address
                if ref.startswith(a)
            ),
            None,
        )
        if src and fn and b.link(src, fn):
            report.mapped.append(
                f"{r['address']} -> link {src} -> {fn} (the function consumes the queue)"
            )
        else:
            report.ignored.append(f"{r['address']} (its queue or function is not in the plan)")

    # everything else: suggestions, not guesses
    kinds = {n["id"]: kind_of(n["type"], registry) for n in b.nodes}
    callers = [n for n, k in kinds.items() if k == "compute"]
    stores = [n for n, k in kinds.items() if k in ("database", "cache", "messaging")]
    linked = {(e["source"], e["target"]) for e in b.edges}
    for c in callers:
        for s in stores:
            if (c, s) not in linked and (s, c) not in linked:
                report.suggestions.append(
                    {
                        "source": c,
                        "target": s,
                        "reason": "Terraform does not say whether it calls it",
                    }
                )
    entries = [
        n
        for n, k in kinds.items()
        if k == "network"
        or (k == "cache" and ((b.node(n) or {}).get("config") or {}).get("technology") == "cdn")
    ]
    for e in entries:
        for c in callers:
            report.suggestions.append(
                {
                    "source": e,
                    "target": c,
                    "reason": "which services does the load balancer route to?",
                }
            )
    if not callers and stores:
        report.guessed.append(
            "no compute found (EC2/ECS/EKS workloads, functions): add the services that use these stores"
        )
    _add_clients(b, report, entries, registry)
    doc = b.document(lambda t: kind_of(t, registry))
    return doc, report
