"""Importing docker-compose files, Kubernetes manifests and Terraform plans: the import report,
provenance of imported numbers, and the round trip of ArchSim's own exports."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from archsim_engine import create_default_registry, load_document
from archsim_engine.export import export_document
from archsim_engine.importers import import_design
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "import"
TEMPLATES = sorted(EXAMPLES.glob("*.json"))
CLIENT_GROUPS = ("mobile_app", "web_client", "api_consumer")


def round_trip_case(path: Path) -> Any:
    """Exports carry only plain ``traffic_source`` clients (``roundtrip.NOT_DEPLOYED``), so a
    template with mobile, web or API-consumer client groups loses them on the way out. Known gap
    (docs/templates-review.md, follow-ups); strict, so this fails once exports keep them."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    if any(n["type"] in CLIENT_GROUPS for n in doc["architecture"]["nodes"]):
        reason = "exports drop mobile, web and API-consumer client groups"
        return pytest.param(path, marks=pytest.mark.xfail(strict=True, reason=reason))
    return path


def shape(doc: dict[str, Any]) -> tuple[dict[str, Any], set[tuple[Any, ...]]]:
    """Parts (type, effective technology, replicas, autoscaling) and links of a design."""
    nodes = {}
    for n in doc["architecture"]["nodes"]:
        cfg = {**REGISTRY.get(n["type"]).default_config(), **(n.get("config") or {})}
        nodes[n["id"]] = (
            n["type"],
            cfg.get("technology"),
            cfg.get("replicas") if n["type"] == "service" else None,
            (cfg.get("minReplicas"), cfg.get("maxReplicas")) if cfg.get("autoscaling") else None,
        )
    edges = {
        (
            e["source"],
            e["target"],
            e.get("sourcePort") or "out",
            tuple(sorted((e.get("config") or {}).get("requestTypes") or ["read", "write"])),
        )
        for e in doc["architecture"]["edges"]
    }
    return nodes, edges


@pytest.mark.parametrize("fmt", ["compose", "k8s"])
@pytest.mark.parametrize("path", [round_trip_case(p) for p in TEMPLATES], ids=lambda p: p.stem)
def test_every_template_round_trips(path: Path, fmt: str) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    text = export_document(doc, fmt, REGISTRY)
    back, report = import_design(fmt, text, REGISTRY, doc["metadata"]["name"])
    assert shape(back) == shape(doc)
    assert not [i for i in report.issues if i["severity"] == "error"]
    assert not report.guessed  # an ArchSim export states everything the import needs
    load_document(back)


@pytest.mark.parametrize("fmt", ["compose", "k8s"])
def test_a_stated_technology_picks_the_part_type_its_image_serves(fmt: str) -> None:
    """Redis Streams exports as the redis image, which is otherwise a cache."""
    doc = json.loads((EXAMPLES / "kafka-order-pipeline.json").read_text(encoding="utf-8"))
    for n in doc["architecture"]["nodes"]:
        if n["type"] == "message_queue":
            n["config"]["technology"] = "redis_streams"
    back, report = import_design(fmt, export_document(doc, fmt, REGISTRY), REGISTRY)
    assert shape(back) == shape(doc)
    assert not [i for i in report.issues if i["severity"] == "error"]


def test_compose_maps_infrastructure_and_lists_what_it_guessed() -> None:
    doc, report = import_design("compose", (FIXTURES / "voting-compose.yml").read_text(), REGISTRY)
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    assert nodes["db"]["type"] == "database" and nodes["db"]["config"]["technology"] == "postgresql"
    assert nodes["redis"]["type"] == "cache" and nodes["proxy"]["type"] == "load_balancer"
    assert nodes["vote"]["type"] == "service" and nodes["vote"]["config"]["replicas"] == 3
    links = {(e["source"], e["target"]) for e in doc["architecture"]["edges"]}
    assert ("result", "db") in links and ("clients", "proxy") in links
    # inferred links and the clients part are guesses, said so; build contexts are ignored
    assert any("link result -> db (from DATABASE_URL)" in g for g in report.guessed)
    assert any(g.startswith("a Clients part") for g in report.guessed)
    assert "vote.build" in report.ignored
    # imported numbers are assumed, with where they came from
    prov = {k: v for k, v in doc["provenance"]["nodes/vote/replicas"].items() if v is not None}
    assert prov == {
        "kind": "assumed",
        "source": "imported from docker-compose",
        "note": "deploy.replicas",
    }
    assert doc["provenance"]["nodes/db/maxConnections"]["note"] == "from the container command"
    assert doc["schemaVersion"] == "1.4"


def test_kubernetes_reads_autoscalers_ingresses_and_resources() -> None:
    doc, report = import_design("k8s", (FIXTURES / "shop-k8s.yaml").read_text(), REGISTRY)
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    checkout = nodes["checkout"]["config"]
    assert checkout["autoscaling"] is True and checkout["platform"] == "kubernetes"
    assert doc["provenance"]["nodes/checkout/maxReplicas"]["note"] == "HorizontalPodAutoscaler"
    assert nodes["shop-ingress"]["type"] == "load_balancer"
    assert nodes["orders-db"]["type"] == "database"
    # nothing in the manifests describes the nodes: the cluster part is a stated guess
    assert any("Kubernetes cluster part" in g for g in report.guessed)
    assert "ConfigMap shop-config" in report.ignored


def test_terraform_sizes_from_instance_types_and_suggests_links() -> None:
    doc, report = import_design(
        "terraform", (FIXTURES / "orders-aws-plan.json").read_text(), REGISTRY
    )
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    db = nodes["orders-db"]["config"]
    # db.r6g.large: 2 vCPU, memory-optimised (8 GiB per vCPU), from AWS's instance types
    assert (db["cpuCores"], db["memoryGib"]) == (2, 16)
    assert "db.r6g.large" in doc["provenance"]["nodes/orders-db/cpuCores"]["note"]
    # and it runs on that type: priced as it while it covers its CPU and memory (schema 1.4)
    assert db["instanceType"] == "db.r6g.large"
    assert db["readReplicas"] == 1
    # the only link Terraform states: the event source mapping (queue -> function)
    links = {(e["source"], e["target"]) for e in doc["architecture"]["edges"]}
    assert ("orders", "orders-fulfil") in links
    assert {"source": "orders-api", "target": "orders-db"} in [
        {k: s[k] for k in ("source", "target")} for s in report.suggestions
    ]
    # each resource is either mapped or ignored, not both
    mapped = " ".join(report.mapped)
    assert not [i for i in report.ignored if i.split(" ")[0] in mapped]
    assert any("aws_iam_role.lambda" in i for i in report.ignored)


def test_unreadable_input_says_what_is_expected() -> None:
    with pytest.raises(ValueError, match="services"):
        import_design("compose", "version: '3'\n", REGISTRY)
    with pytest.raises(ValueError, match="terraform show -json"):
        import_design("terraform", "resource aws_s3_bucket b {}", REGISTRY)
    with pytest.raises(ValueError, match="no Kubernetes objects"):
        import_design("k8s", "# nothing\n", REGISTRY)


def test_malformed_archsim_parts_are_skipped_not_trusted() -> None:
    text = (
        "services:\n  api:\n    image: your-org/api:latest\n"
        "x-archsim:\n  parts:\n    - {id: clients, type: database}\n    - not a part\n"
        "    - {id: users, type: traffic_source, label: Users, calls: api}\n"
    )
    doc, _ = import_design("compose", text, REGISTRY)
    types = {n["id"]: n["type"] for n in doc["architecture"]["nodes"]}
    assert types == {"api": "service", "users": "traffic_source"}
    assert {(e["source"], e["target"]) for e in doc["architecture"]["edges"]} == {("users", "api")}


# --- what images are, entry points, cache misses, memory, replicas, autoscalers, links ---


def _compose_one(image: str) -> str:
    return f'services:\n  x:\n    image: "{image}"\n'


def _nodes(doc: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {n["id"]: n for n in doc["architecture"]["nodes"]}


def _links(doc: dict[str, Any]) -> set[tuple[str, str, str]]:
    return {(e["source"], e["target"], e["sourcePort"]) for e in doc["architecture"]["edges"]}


@pytest.mark.parametrize(
    "image",
    [
        "provectuslabs/kafka-ui:latest",
        "mongo-express:1.0",
        "prometheuscommunity/postgres-exporter:v0.15.0",
        "acme/postgres-migrations:3",
        "prom/mysqld-exporter:v0.15.1",
        "nginx/nginx-prometheus-exporter:1.3",
        "acme/nats-consumer:2",
        "ghcr.io/acme/server:1.0",
        "oliver006/redis_exporter:v1.62.0",
        "dpage/pgadmin4:8",
        "adminer:4",
        "phpmyadmin:5",
        "redis/redisinsight:latest",
        "tchiotludo/akhq:0.25.0",
        "rediscommander/redis-commander:latest",
    ],
)
def test_tools_and_lookalike_images_become_services(image: str) -> None:
    """Exporters, UIs, migrators and clients named after a technology are not that technology,
    and a repository called 'server' is not SQL Server."""
    doc, report = import_design("compose", _compose_one(image), REGISTRY)
    node = _nodes(doc)["x"]
    assert node["type"] == "service" and "technology" not in node["config"]
    assert any(g.startswith("x:") and image in g for g in report.guessed)


@pytest.mark.parametrize(
    ("image", "type_", "tech"),
    [
        ("postgres:16", "database", "postgresql"),
        ("postgis/postgis:16-3.4", "database", "postgresql"),
        ("timescale/timescaledb-ha:pg16", "database", "postgresql"),
        ("bitnami/postgresql:16", "database", "postgresql"),
        ("registry.local:5000/library/postgres:16", "database", "postgresql"),
        ("mcr.microsoft.com/mssql/server:2022-latest", "database", "sqlserver"),
        ("couchbase/server:community", "database", "couchbase"),
        ("docker.elastic.co/elasticsearch/elasticsearch:8.15.3", "database", "elasticsearch"),
        ("mongo@sha256:" + "a" * 64, "database", "mongodb"),
        ("redis/redis-stack-server:7.2.0-v10", "cache", "redis"),
        ("bitnami/redis:7.2", "cache", "redis"),
        ("confluentinc/cp-kafka:7.6.0", "message_queue", "kafka"),
        ("apache/kafka:3.8.0", "message_queue", "kafka"),
        ("rabbitmq:3-management", "message_queue", "rabbitmq"),
        ("nginx:1.27-alpine", "load_balancer", "nginx"),
        ("envoyproxy/envoy:v1.31-latest", "load_balancer", "envoy"),
    ],
)
def test_infrastructure_images_are_matched_by_full_name_and_listed_as_guesses(
    image: str, type_: str, tech: str
) -> None:
    doc, report = import_design("compose", _compose_one(image), REGISTRY)
    node = _nodes(doc)["x"]
    assert (node["type"], node["config"].get("technology")) == (type_, tech)
    # the technology came from the image name: a guess with its reason, not a mapping
    assert any(g.startswith("x:") and image in g and tech in g for g in report.guessed)
    assert not any(tech in m for m in report.mapped)


@pytest.mark.parametrize(
    ("fmt", "fixture"),
    [
        ("compose", "voting-compose.yml"),
        ("k8s", "shop-k8s.yaml"),
        ("terraform", "orders-aws-plan.json"),
    ],
)
def test_every_fixture_imports_to_a_design_that_validates_and_runs(fmt: str, fixture: str) -> None:
    from archsim_engine import run_simulation, validate_document

    text = (FIXTURES / fixture).read_text(encoding="utf-8")
    doc, report = import_design(fmt, text, REGISTRY)  # type: ignore[arg-type]
    assert not [i for i in report.issues if i["severity"] == "error"], report.issues
    result = validate_document(load_document(doc), REGISTRY)
    assert not [i for i in result.issues if i.severity == "error"]
    doc["workload"] = {"rps": 40, "durationS": 3, "warmupS": 1, "seed": 3}
    out = run_simulation(doc, registry=REGISTRY)
    assert out["summary"]["succeeded"] > 0
    # nothing links to or from the Kubernetes cluster part (services run on it by platform)
    clusters = {n["id"] for n in doc["architecture"]["nodes"] if n["type"] == "kubernetes_cluster"}
    assert not [e for e in doc["architecture"]["edges"] if {e["source"], e["target"]} & clusters]
    assert not [s for s in report.suggestions if {s["source"], s["target"]} & clusters]


K8S_TWO_APPS = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  template:
    spec:
      containers:
        - {name: web, image: "acme/web:1", resources: {requests: {cpu: 500m, memory: 512Mi}}}
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  replicas: 3
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: v1
kind: Service
metadata: {name: api-svc}
spec:
  selector: {app: nothing-matches}
  ports: [{port: 80}]
"""


def test_kubernetes_without_an_ingress_enters_through_one_part_never_the_cluster() -> None:
    doc, report = import_design("k8s", K8S_TWO_APPS, REGISTRY)
    nodes = _nodes(doc)
    clients = next(n for n in nodes.values() if n["type"] == "traffic_source")
    out = [e["target"] for e in doc["architecture"]["edges"] if e["source"] == clients["id"]]
    assert len(out) == 1 and nodes[out[0]]["type"] == "load_balancer"
    assert {e["target"] for e in doc["architecture"]["edges"] if e["source"] == out[0]} == {
        "web",
        "api",
    }
    assert any("load balancer" in g and out[0] in g for g in report.guessed)
    assert not [i for i in report.issues if i["severity"] == "error"], report.issues
    # a Service whose selector matches no workload is listed, not dropped silently
    assert any(i.startswith("Service api-svc") for i in report.ignored)


def test_kubernetes_keeps_real_requests_and_defaults_replicas_to_one() -> None:
    doc, _ = import_design("k8s", K8S_TWO_APPS, REGISTRY)
    web = _nodes(doc)["web"]["config"]
    # 500m / 512Mi are real requests here (no ArchSim export marker), not a placeholder
    assert (web["cpuCores"], web["memoryGib"]) == (0.5, 0.5)
    # no spec.replicas: Kubernetes runs one pod (ArchSim's own default is 2)
    assert web["replicas"] == 1
    assert "not set" in doc["provenance"]["nodes/web/replicas"]["note"]
    assert _nodes(doc)["api"]["config"]["replicas"] == 3


def test_kubernetes_skips_only_archsims_own_placeholder_requests() -> None:
    text = (
        "apiVersion: apps/v1\nkind: Deployment\n"
        "metadata: {name: api, annotations: {archsim.dev/node: api}}\n"
        "spec:\n  replicas: 2\n  template:\n    spec:\n      containers:\n"
        "        - name: api\n          image: your-org/api:latest\n"
        "          resources: {requests: {cpu: 500m, memory: 512Mi}, limits: {memory: 1Gi}}\n"
    )
    doc, _ = import_design("k8s", text, REGISTRY)
    cfg = _nodes(doc)["api"]["config"]
    assert "cpuCores" not in cfg and "memoryGib" not in cfg


def test_compose_without_replicas_runs_one_container() -> None:
    doc, _ = import_design("compose", "services:\n  api:\n    image: acme/api:1\n", REGISTRY)
    assert _nodes(doc)["api"]["config"]["replicas"] == 1
    assert "not set" in doc["provenance"]["nodes/api/replicas"]["note"]


HPA = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: api}
spec:
  scaleTargetRef: {kind: Deployment, name: api}
  minReplicas: 2
  maxReplicas: 9
  metrics:
    - type: Resource
      resource: {name: cpu, target: {type: Utilization, averageUtilization: 65}}
    - type: Resource
      resource: {name: memory, target: {type: Utilization, averageUtilization: 80}}
"""

HPA_V1 = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  replicas: 3
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: autoscaling/v1
kind: HorizontalPodAutoscaler
metadata: {name: api}
spec:
  scaleTargetRef: {kind: Deployment, name: api}
  minReplicas: 2
  maxReplicas: 6
  targetCPUUtilizationPercentage: 70
"""


def test_hpa_target_is_the_cpu_metric_in_v2_and_v1() -> None:
    doc, report = import_design("k8s", HPA, REGISTRY)
    cfg = _nodes(doc)["api"]["config"]
    assert cfg["targetUtilization"] == 0.65  # the memory metric does not overwrite it
    assert cfg["replicas"] == 2  # no spec.replicas: the autoscaler starts at its minimum
    assert any("memory" in i for i in report.ignored)
    doc, _ = import_design("k8s", HPA_V1, REGISTRY)
    cfg = _nodes(doc)["api"]["config"]
    assert (cfg["minReplicas"], cfg["maxReplicas"], cfg["targetUtilization"]) == (2, 6, 0.7)


def _res(type_: str, name: str, values: dict[str, Any]) -> dict[str, Any]:
    return {
        "address": f"{type_}.{name}",
        "mode": "managed",
        "type": type_,
        "name": name,
        "values": values,
    }


def _plan(resources: list[dict[str, Any]], config: list[dict[str, Any]]) -> str:
    return json.dumps(
        {
            "planned_values": {"root_module": {"resources": resources}},
            "configuration": {"root_module": {"resources": config}},
        }
    )


def test_terraform_links_the_queue_the_mapping_names_not_one_it_prefixes() -> None:
    fn = {"runtime": "python3.12", "memory_size": 512}
    resources = [
        _res("aws_sqs_queue", "orders", {"name": "orders"}),
        _res("aws_sqs_queue", "orders_dlq", {"name": "orders-dlq"}),
        _res("aws_lambda_function", "fulfil", {"function_name": "fulfil", **fn}),
        _res("aws_lambda_function", "redrive", {"function_name": "redrive", **fn}),
        _res("aws_lambda_event_source_mapping", "dlq", {}),
        _res("aws_lambda_event_source_mapping", "main", {}),
    ]

    def mapping(name: str, queue: str, function: str) -> dict[str, Any]:
        return {
            "address": f"aws_lambda_event_source_mapping.{name}",
            "expressions": {
                "event_source_arn": {"references": [f"{queue}.arn", queue]},
                "function_name": {"references": [f"{function}.arn", function]},
            },
        }

    config = [
        mapping("dlq", "aws_sqs_queue.orders_dlq", "aws_lambda_function.redrive"),
        mapping("main", "aws_sqs_queue.orders", "aws_lambda_function.fulfil"),
    ]
    doc, _ = import_design("terraform", _plan(resources, config), REGISTRY)
    links = {(s, t) for s, t, _ in _links(doc)}
    assert ("orders-dlq", "redrive") in links and ("orders", "fulfil") in links
    assert ("orders", "redrive") not in links


def test_terraform_lists_a_replica_without_its_source_and_fits_small_functions() -> None:
    resources = [
        _res(
            "aws_db_instance",
            "replica",
            {"identifier": "r", "engine": "postgres", "replicate_source_db": "gone"},
        ),
        _res(
            "aws_lambda_function",
            "api",
            {"function_name": "api", "runtime": "nodejs20.x", "memory_size": 128},
        ),
    ]
    _, report = import_design("terraform", _plan(resources, []), REGISTRY)
    assert any(i.startswith("aws_db_instance.replica") for i in report.ignored)
    # a 128 MB function is below ArchSim's default memory at idle: lowered, and said so
    assert not [i for i in report.issues if i["code"] == "service.memory_too_small"]
    assert any("memory at idle" in g for g in report.guessed)


def test_an_app_that_calls_a_cache_and_a_database_reads_the_database_on_a_miss() -> None:
    # cache-aside: the application calls both; Redis never calls PostgreSQL
    text = (
        "services:\n"
        "  api:\n    image: acme/api:1\n    ports: ['8080:8080']\n"
        "    environment:\n      CACHE: redis://cache:6379\n      DB: postgresql://db:5432/app\n"
        "  cache:\n    image: redis:7\n"
        "  db:\n    image: postgres:16\n"
    )
    doc, report = import_design("compose", text, REGISTRY)
    edges = {(e["source"], e["target"]): e for e in doc["architecture"]["edges"]}
    assert not any(e["sourcePort"] == "miss" for e in edges.values())
    assert edges[("api", "db")]["config"]["readsOnMiss"] is True
    assert not edges[("api", "cache")]["config"].get("readsOnMiss")
    assert any(g.startswith("api reads db only on a cache miss") for g in report.guessed)
    assert not [i for i in report.issues if i["severity"] == "error"], report.issues


def test_a_cache_whose_callers_use_no_database_misses_to_the_only_one() -> None:
    text = (
        "services:\n"
        "  api:\n    image: acme/api:1\n    ports: ['8080:8080']\n"
        "    environment:\n      CACHE: redis://cache:6379\n"
        "  cache:\n    image: redis:7\n"
        "  db:\n    image: postgres:16\n"
    )
    doc, report = import_design("compose", text, REGISTRY)
    edge = next(e for e in doc["architecture"]["edges"] if e["source"] == "cache")
    assert (edge["target"], edge["sourcePort"], edge["config"]["requestTypes"]) == (
        "db",
        "miss",
        ["read"],
    )
    assert any("cache -> db" in g and "only database" in g for g in report.guessed)
    assert not [i for i in report.issues if i["severity"] == "error"], report.issues


def test_a_memory_limit_below_the_idle_baseline_lowers_it_and_says_so() -> None:
    text = (
        "services:\n  api:\n    image: acme/api:1\n    deploy:\n"
        "      resources: {limits: {memory: 128M}}\n"
    )
    doc, report = import_design("compose", text, REGISTRY)
    cfg = _nodes(doc)["api"]["config"]
    assert cfg["memoryGib"] == 0.125 and cfg["memoryBaselineMb"] == 64
    assert "128 MiB" in doc["provenance"]["nodes/api/memoryBaselineMb"]["note"]
    assert any(g.startswith("api: memory at idle") for g in report.guessed)
    assert not [i for i in report.issues if i["severity"] == "error"], report.issues


@pytest.mark.parametrize(
    ("fmt", "text"),
    [
        ("compose", "services: ["),
        ("compose", "services:\n  a: just-a-string\n"),
        ("compose", "services:\n  a:\n    labels: 5\n"),
        ("compose", "services:\n  a:\n    deploy: [1]\n"),
        ("compose", "services:\n  a: {}\nx-external-dependencies: [1]\n"),
        ("k8s", "kind: Deployment\n"),
        ("k8s", "kind: Deployment\nmetadata: just-a-string\n"),
        ("k8s", "kind: Service\nmetadata: {name: s}\nspec: [1]\n"),
        ("k8s", "a: [\n"),
        ("terraform", "[]"),
        ("terraform", '{"values": {"root_module": {"resources": [{"type": "aws_lb"}]}}}'),
        ("terraform", '{"values": {"root_module": {"resources": [1]}}}'),
        pytest.param("terraform", "[" * 100_000 + "]" * 100_000, id="terraform-deep"),
    ],
)
def test_malformed_input_is_a_value_error_not_a_crash(fmt: str, text: str) -> None:
    with pytest.raises(ValueError):
        import_design(fmt, text, REGISTRY)  # type: ignore[arg-type]


@pytest.mark.parametrize("encoding", ["utf-16", "utf-8-sig", "utf-16-be"])
def test_cli_reads_plans_saved_by_windows_powershell(
    tmp_path: Path, encoding: str, capsys: pytest.CaptureFixture[str]
) -> None:
    """`terraform show -json > plan.json` in Windows PowerShell 5.1 writes UTF-16 with a byte
    order mark; PowerShell 7 and some editors write UTF-8 with one."""
    from archsim_engine.cli import main

    text = (FIXTURES / "orders-aws-plan.json").read_text(encoding="utf-8")
    src, out = tmp_path / "plan.json", tmp_path / "design.json"
    raw = text.encode(encoding)
    if encoding == "utf-16-be":
        raw = b"\xfe\xff" + raw
    src.write_bytes(raw)
    assert main(["import", "terraform", str(src), "-o", str(out)]) == 0
    assert json.loads(out.read_text(encoding="utf-8"))["architecture"]["nodes"]
    capsys.readouterr()
    bad = tmp_path / "bad.json"
    bad.write_bytes(b'{"x": "\xff\xfe\xfd"')
    assert main(["import", "terraform", str(bad)]) == 2
    assert "cannot import" in capsys.readouterr().err
