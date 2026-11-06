"""Write the exports' extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) export each template, and the templates do not reach every
branch of ``export``: managed services (a CDN, a managed load balancer, an API gateway, a managed
queue), a part of a type the registry does not know, a technology the exporters have no image
for, a third party whose technology is empty, settings of the wrong kind (a technology or a platform that is not a string, specs that are
booleans), a design that does not auto-size, a part whose CPU is set but not its memory, two
links to one part, a link to a part the design does not have, ids that clash as DNS or Mermaid
names, strings YAML would read as something else (``yes``, ``~``, ``a: b``, a number, a trailing
newline), numbers JavaScript writes differently from Python (``1e+21``, ``-0.0``), nested lists and
empty maps in a client's settings, and text outside ASCII in the Kubernetes ConfigMap. This script
asks the frozen engine (0.16.0) to export a design for each such case, as ``export_document`` does
it for the API (loaded, auto-sized, then written), and writes the designs with the texts to
archsim-engine's test resources, where ``ExportCasesTests`` checks the Java against them as the
golden files are checked.

Usage:
    uv run python scripts/export_export_cases.py           # write the cases
    uv run python scripts/export_export_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library may differ in the last bit of ``exp`` or ``log``, which the auto sizes use.
It is a manual step for that reason, not a CI one.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine.components import create_default_registry
from archsim_engine.export import FORMATS, _auto_sized, export_document
from archsim_engine.export.compose import to_docker_compose
from archsim_engine.export.k8s import to_kubernetes
from archsim_engine.export.mermaid import to_mermaid

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/export/cases.json"
)
REGISTRY = create_default_registry(include_plugins=False)
CONST10 = {"kind": "constant", "value": 10}
Json = dict[str, Any]


def design(nodes: list[Json], edges: list[Json], name: str = "case", **more: Any) -> Json:
    workload = {"rps": 100, "readRatio": 0.8, "durationS": 60, **more.pop("workload", {})}
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": name},
        "architecture": {"nodes": nodes, "edges": edges, **more},
        "workload": workload,
    }


def link(source: str, target: str, port: str | None = None, **config: Any) -> Json:
    out: Json = {"id": f"{source}-{target}-{port or 'out'}", "source": source, "target": target}
    if port is not None:
        out["sourcePort"] = port
    out["config"] = config
    return out


def node(node_id: str, node_type: str, label: str | None = None, **config: Any) -> Json:
    out: Json = {"id": node_id, "type": node_type, "config": config}
    if label is not None:
        out["label"] = label
    return out


def clients(node_id: str = "clients", **config: Any) -> Json:
    return node(node_id, "traffic_source", "Shoppers", **config)


def service(node_id: str, label: str | None = None, **config: Any) -> Json:
    return node(node_id, "service", label, **{"serviceTimeMs": CONST10, **config})


def every_kind() -> Json:
    """A part of every kind the exporters deploy, each with the settings they write."""
    return design(
        [
            clients(),
            node("edge", "load_balancer", "Edge", technology="nginx", rateLimitRps=500),
            service(
                "api",
                "Orders API",
                technology="java",
                platform="kubernetes",
                autoscaling=True,
                minReplicas=2,
                maxReplicas=6,
                targetUtilization=0.7,
                scaleIntervalS=20,
            ),
            node("cache", "cache", "Hot keys", technology="redis"),
            node(
                "orders",
                "database",
                "Orders DB",
                technology="postgresql",
                shards=2,
                readReplicas=1,
                maxConnections=200,
                storageGib=50,
            ),
            node(
                "events",
                "message_queue",
                "Events",
                technology="kafka",
                partitions=12,
                consumers=3,
                brokers=3,
            ),
            service("worker", "Worker", technology="go", replicas=2),
            node("ledger", "database", "Ledger", technology="mysql", maxConnections=300),
            node("billing", "database", "Billing", technology="mariadb"),
            node("pay", "external_api", 'Pay "Co"', technology="payments"),
        ],
        [
            link("clients", "edge"),
            link("edge", "api"),
            link("api", "cache", requestTypes=["read"]),
            link("api", "orders", readsOnMiss=True, timeoutMs=250),
            link("cache", "orders", "miss", timeoutMs=100),
            link("api", "events", requestTypes=["write"], continueOnFailure=True),
            link("events", "worker"),
            link("worker", "ledger"),
            link("worker", "billing", requestTypes=["write"]),
            link("api", "pay", timeoutMs=2000),
        ],
        name="Every kind",
    )


def managed_and_unknown() -> Json:
    """Managed services, a type the registry does not know, technologies without an image and
    settings of the wrong kind."""
    return design(
        [
            clients(),
            node("cdn", "cache", "CDN", technology="cdn"),
            node("alb", "load_balancer", "ALB", technology="aws_alb"),
            node("gateway", "load_balancer", "Gateway", technology="api_gateway"),
            service("api", "API", technology=5, platform=7, cpuCores=True, memoryGib=2),
            node("topics", "message_queue", "Topics", technology="pubsub"),
            node("thing", "custom_thing", "Custom", technology="widget", replicas=3),
            node("store", "database", "Store", technology="generic"),
            node("legacy", "database", "Legacy", technology="foxpro", storageGib=12.5),
            node("memo", "cache", "Memo", technology="memcached", cpuCores=0.5),
            node("search", "database", "Search", technology="opensearch", replicationFactor=3),
            node("mail", "external_api", "Mail", technology=""),
        ],
        [
            link("clients", "cdn"),
            link("cdn", "alb", "miss"),
            link("alb", "gateway"),
            link("gateway", "api"),
            link("api", "topics", requestTypes=["write"]),
            link("api", "thing"),
            link("api", "store"),
            link("api", "store", requestTypes=["write"]),
            link("api", "legacy"),
            link("api", "memo"),
            link("api", "search"),
            link("api", "clients"),
            link("topics", "thing"),
            link("api", "mail"),
        ],
        name="Managed & unknown",
    )


def serverless() -> Json:
    """A serverless function, a service with fractional specs, one whose CPU is set, and one
    whose target utilization is a percentage of more than 21 digits."""
    return design(
        [
            clients(),
            service(
                "fn",
                "Thumbnailer",
                platform="serverless",
                concurrencyLimit=200,
                workersPerReplica=4,
                replicas=2,
                technology="python",
            ),
            service("small", "Small", cpuCores=0.5, memoryGib=1.5),
            service("sized", "Sized", cpuCores=2, technology="nodejs"),
            service("bare", "Bare", replicas=0, autoscaling=True, minReplicas=3),
            service("huge", "Huge", autoscaling=True, maxReplicas=2, targetUtilization=1e19),
            node("db", "database", "DB", technology="cockroachdb", storageGib=7.2),
        ],
        [
            link("clients", "fn"),
            link("fn", "small"),
            link("small", "sized"),
            link("sized", "bare"),
            link("sized", "huge"),
            link("bare", "db"),
            link("fn", "db", requestTypes=["write"]),
        ],
        name="Serverless",
    )


def cluster() -> Json:
    """Services on a Kubernetes cluster, which nothing deploys, with text outside ASCII."""
    return design(
        [
            clients("phones", platform="mobile"),
            node(
                "k8s",
                "kubernetes_cluster",
                "Clúster — prod",
                technology="eks",
                nodes=3,
                nodeVcpu=4,
                nodeMemoryGib=16,
            ),
            service("api", "Café API", technology="java", platform="kubernetes", cluster="k8s"),
            node("db", "database", "Base de données", technology="yugabytedb"),
        ],
        [link("phones", "api"), link("api", "db"), link("phones", "k8s")],
        name="Café — résumé",
    )


def yaml_strings() -> Json:
    """Strings YAML would read as something else, and numbers JavaScript writes differently."""
    odd = {
        "yes": [],
        "No": {},
        "nested": [[], {}, [1, 2.5, [True, None]], {"a": None, "b": {}}, [[]], "x"],
        "big": 1e21,
        "bigger": 12345678901234567890123,
        "small": 1e-05,
        "negativeZero": -0.0,
        "whole": 2.0,
        "colon:": "a: b",
        "ends": "edge:",
        "number": "123",
        "dot": ".hidden",
        "empty": "",
        "tilde": "~",
        "space key": "two words",
        "accent": "naïve",
        "quote": 'say "hi"',
        "backslash": "a\\b",
        "tab": "a\tb",
        "control": "\u0001",
        "newline": "ok\n",
        "at": "user@host",
        "slash": "/var/data",
        "Y": "n",
    }
    return design(
        [
            node("clients", "traffic_source", "On", **odd),
            node("off", "external_api", "off", technology="On"),
            node("tilde", "external_api", "~"),
            node("colon", "external_api", "edge:"),
            node("pair", "external_api", "a: b"),
            node("digits", "external_api", "123"),
            node("blank", "external_api", ""),
            node("trailing", "external_api", "ok\n"),
            node("dotted", "external_api", "v1.2/x@y:z-w"),
        ],
        [
            link("clients", "off"),
            link("clients", "tilde"),
            link("clients", "colon"),
            link("clients", "pair"),
            link("clients", "digits"),
            link("clients", "blank"),
            link("clients", "trailing"),
            link("clients", "dotted"),
        ],
        name="YES",
    )


def clashing_ids() -> Json:
    """Ids that clash as DNS names and as Mermaid ids, and quotes in labels and the name."""
    return design(
        [
            clients("1st"),
            service("a-b", 'Say "hi"', technology="generic"),
            service("a_b", "A B"),
            service("a.b", "A.B"),
            service("API.Gateway_v2", "Gateway v2"),
            node("db", "database", "Postgres primary", technology="postgresql"),
            node("q", "message_queue", "Jobs", technology="rabbitmq"),
        ],
        [
            link("1st", "API.Gateway_v2"),
            link("API.Gateway_v2", "a-b", requestTypes=["write"]),
            link("API.Gateway_v2", "a_b", requestTypes=["read"], timeoutMs=1500.5),
            link("API.Gateway_v2", "a.b"),
            link("a-b", "db"),
            link("a_b", "q"),
            link("a.b", "db", "miss"),
        ],
        name='The "quoted" name',
    )


def not_auto_sized() -> Json:
    """A design that does not auto-size its parts: nothing is filled in."""
    return design(
        [
            clients(),
            service("api", "API", technology="rust"),
            node("db", "database", "DB", technology="mongodb", shards=3),
            node("cache", "cache", "Cache", technology="valkey"),
            node("bus", "message_queue", "Bus", technology="nats"),
            node("stream", "message_queue", "Stream", technology="redis_streams"),
            node("table", "database", "Table", technology="dynamodb"),
        ],
        [
            link("clients", "api"),
            link("api", "db"),
            link("api", "cache"),
            link("api", "bus"),
            link("bus", "stream"),
            link("api", "table"),
        ],
        name="!!!",
        autoSize=False,
    )


def no_clients() -> Json:
    """No clients, so no modelled load; a long name cut to 40 characters; every proxy."""
    return design(
        [
            node("envoy", "load_balancer", "Envoy", technology="envoy"),
            node("haproxy", "load_balancer", "HAProxy", technology="haproxy"),
            node("traefik", "load_balancer", "Traefik", technology="traefik"),
            node("kong", "load_balancer", "Kong", technology="kong", rateLimitRps=0.5),
            node("varnish", "cache", "Varnish", technology="varnish"),
            service("api", "API", technology="dotnet"),
            node("sql", "database", "SQL", technology="sqlserver"),
            node("ora", "database", "Ora", technology="oracle"),
            node("cass", "database", "Cass", technology="cassandra"),
            node("scylla", "database", "Scylla", technology="scylladb"),
            node("couch", "database", "Couch", technology="couchbase"),
            node("es", "database", "ES", technology="elasticsearch"),
        ],
        [
            link("envoy", "haproxy"),
            link("haproxy", "traefik"),
            link("traefik", "kong"),
            link("kong", "varnish"),
            link("varnish", "api", "miss"),
            link("api", "sql"),
            link("api", "ora"),
            link("api", "cass"),
            link("api", "scylla"),
            link("api", "couch"),
            link("api", "es"),
        ],
        name="A very long design name that goes on and on past forty characters",
    )


DESIGNS = {
    "everyKind": every_kind,
    "managedAndUnknown": managed_and_unknown,
    "serverless": serverless,
    "cluster": cluster,
    "yamlStrings": yaml_strings,
    "clashingIds": clashing_ids,
    "notAutoSized": not_auto_sized,
    "noClients": no_clients,
}


DANGLING = {"managedAndUnknown": [link("api", "nowhere")]}
"""Links to parts the design does not have, added after auto-sizing: the engine's estimate
raises ``KeyError`` on them (the Java estimate does not), so only the writers see them."""


def writer_cases(name: str, auto: Json, needs: dict[str, float]) -> list[Json]:
    """The three writers on the auto-sized design, with its dangling links added."""
    doc = copy.deepcopy(auto)
    doc["architecture"]["edges"] += DANGLING.get(name, [])
    calls = [
        (
            "to_docker_compose",
            "compose",
            {"doc": doc},
            to_docker_compose(copy.deepcopy(doc), REGISTRY),
        ),
        (
            "to_kubernetes",
            "k8s",
            {"doc": doc, "needs": needs},
            to_kubernetes(copy.deepcopy(doc), REGISTRY, needs),
        ),
        ("to_mermaid", "mermaid", {"doc": doc}, to_mermaid(copy.deepcopy(doc), REGISTRY)),
    ]
    return [
        {
            "name": f"{name} {writer}",
            "fn": f"archsim_engine.export.{module}.{writer}",
            "input": given,
            "output": text,
        }
        for writer, module, given, text in calls
    ]


def cases() -> list[Json]:
    out = []
    for name, make in DESIGNS.items():
        document = make()
        auto, needs = _auto_sized(copy.deepcopy(document), REGISTRY)
        out.append(
            {
                "name": f"{name} _auto_sized",
                "fn": "archsim_engine.export._auto_sized",
                "input": {"document": document},
                "output": {"document": auto, "needs": needs},
            }
        )
        if name in DANGLING:
            out += writer_cases(name, auto, needs)
        for fmt in FORMATS:
            given = {"document": document, "fmt": fmt, "autoSize": True}
            out.append(
                {
                    "name": f"{name} export_document[{fmt}]",
                    "fn": "archsim_engine.export.export_document",
                    "input": given,
                    "output": export_document(copy.deepcopy(document), fmt, REGISTRY),
                }
            )
    return out


def render() -> str:
    return json.dumps(cases(), indent=1, ensure_ascii=False, allow_nan=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args()
    text = render()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} differs from a fresh export", file=sys.stderr)
            return 1
        print(f"{OUT} is up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
