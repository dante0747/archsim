"""Kubernetes clusters: pods scheduled by requests, Pending pods, the cluster autoscaler."""

from __future__ import annotations

from typing import Any

from archsim_engine import default_registry, load_document, run_simulation, validate_document
from engine_builders import doc, edge, node, node_by_id

CONST = lambda v: {"kind": "constant", "value": v}  # noqa: E731


def api(id_: str = "api", **cfg: Any) -> dict[str, Any]:
    base = dict(
        platform="kubernetes",
        autoscaling=True,
        replicas=2,
        minReplicas=2,
        maxReplicas=20,
        cpuCores=1,
        memoryGib=2,
        workersPerReplica=8,
        serviceTimeMs=CONST(20),
        scaleIntervalS=10,
        startupS=5,
        queueTimeoutMs=500,
    )
    base.update(cfg)
    return node(id_, "service", **base)


def cluster(**cfg: Any) -> dict[str, Any]:
    base = dict(nodes=2, nodeVcpu=4, nodeMemoryGib=16, nodeProvisionS=60, maxNodes=6, imagePullS=8)
    base.update(cfg)
    return node("k8s", "kubernetes_cluster", label="prod", **base)


def ramp_doc(
    *nodes: dict[str, Any], end_rps: float = 2500, duration: float = 180
) -> dict[str, Any]:
    return doc(
        [node("clients", "traffic_source", timeoutMs=10_000), *nodes],
        [edge("c-a", "clients", "api")],
        rps=100,
        durationS=duration,
        warmupS=5,
        pattern={"kind": "ramp", "startRps": 100, "endRps": end_rps},
    )


def issues(d: dict[str, Any]) -> set[str]:
    return {i.code for i in validate_document(load_document(d), default_registry()).issues}


def test_pods_wait_pending_until_the_cluster_autoscaler_adds_nodes() -> None:
    r = run_simulation(ramp_doc(cluster(), api()))
    c = node_by_id(r, "k8s")
    s = c["summary"]
    assert s["podsPendingPeak"] > 0
    assert s["counters"]["nodeAdds"] >= 1
    assert s["nodesPeak"] > 2
    # the first new node arrives only after the 60 s provisioning delay
    assert s["maxPendingS"] >= 60
    nodes = [v for v in c["timeseries"]["nodes"] if v is not None]
    assert nodes[0] == 2 and max(nodes) > 2
    assert {"nodes", "podsRunning", "podsPending", "cpuRequestedRatio"} <= set(c["timeseries"])
    a = node_by_id(r, "api")
    assert {"podsReady", "podsStarting", "podsPending"} <= set(a["timeseries"])
    kinds = {f["kind"] for f in r["findings"]}
    assert "pods_pending" in kinds
    assert not any(f["kind"] == "bottleneck" and f["nodeId"] == "k8s" for f in r["findings"])
    assert c["status"] in ("hot", "saturated")
    assert {e["id"] for e in c["explain"]} >= {"allocatable", "time_to_capacity"}
    assert "pods_per_node" in {e["id"] for e in a["explain"]}


def test_without_the_cluster_autoscaler_pods_stay_pending() -> None:
    r = run_simulation(ramp_doc(cluster(clusterAutoscaler=False), api()))
    s = node_by_id(r, "k8s")["summary"]
    assert s["nodesPeak"] == 2
    assert s["podsPendingEnd"] > 0
    assert s["maxPendingS"] > 30  # pods that never got a node still count as waiting
    assert node_by_id(r, "k8s")["status"] == "saturated"
    # 2 nodes x 3.6 allocatable vCPU fit 6 one-vCPU pods
    assert node_by_id(r, "api")["summary"]["replicasPeak"] <= 6


def test_more_headroom_means_less_waiting() -> None:
    tight = run_simulation(ramp_doc(cluster(), api()))
    roomy = run_simulation(ramp_doc(cluster(nodes=6, minNodes=1), api()))
    assert (
        node_by_id(roomy, "k8s")["summary"]["podsPendingPeak"]
        < node_by_id(tight, "k8s")["summary"]["podsPendingPeak"]
    )
    assert roomy["summary"]["successRate"] > tight["summary"]["successRate"]


def test_starting_pods_that_do_not_fit_start_pending() -> None:
    d = ramp_doc(
        cluster(nodes=1, clusterAutoscaler=False),
        api(autoscaling=False, replicas=6),
        end_rps=100,
        duration=30,
    )
    assert "kubernetes.starts_pending" in issues(d)
    r = run_simulation(d)
    a = node_by_id(r, "api")
    # one node of 3.6 vCPU runs 3 of the 6 one-vCPU pods
    assert a["summary"]["replicasEnd"] == 3
    assert node_by_id(r, "k8s")["summary"]["podsPendingEnd"] == 3


def test_a_pod_bigger_than_a_node_is_an_error() -> None:
    d = ramp_doc(cluster(nodeVcpu=2), api(cpuCores=4), duration=20)
    assert "kubernetes.pod_too_big" in issues(d)


def test_services_share_a_node_pool_and_a_starved_one_answers_503() -> None:
    d = doc(
        [
            node("clients", "traffic_source", timeoutMs=10_000),
            cluster(nodes=1, clusterAutoscaler=False),
            node("lb", "load_balancer"),
            # the batch service takes the whole node; the API's pods cannot be scheduled
            api("batch", autoscaling=False, replicas=3, cpuCores=1),
            api("api", autoscaling=False, replicas=2, cpuCores=1),
        ],
        [edge("c-lb", "clients", "lb"), edge("lb-a", "lb", "api")],
        rps=50,
        durationS=20,
        warmupS=2,
    )
    r = run_simulation(d)
    assert node_by_id(r, "api")["summary"]["replicasEnd"] == 0
    reasons = {row["reason"] for row in r["summary"]["failureBreakdown"]}
    assert "no_ready_pods" in reasons
    assert r["summary"]["successRate"] == 0


def test_services_pick_their_cluster_when_there_are_several() -> None:
    second = node("k8s2", "kubernetes_cluster", label="batch", nodes=1)
    d = ramp_doc(cluster(), second, api(), duration=20)
    assert "service.cluster_ambiguous" in issues(d)
    d2 = ramp_doc(cluster(), second, api(cluster="k8s2"), duration=20)
    codes = issues(d2)
    assert "service.cluster_ambiguous" not in codes
    assert "kubernetes.no_services" in codes  # the first cluster now runs nothing
    r = run_simulation(d2)
    assert node_by_id(r, "api")["summary"]["cluster"] == "k8s2"
    bad = ramp_doc(cluster(), api(cluster="nope"), duration=20)
    assert "service.cluster_missing" in issues(bad)


def test_services_off_kubernetes_ignore_the_cluster_and_runs_are_deterministic() -> None:
    d = ramp_doc(cluster(), api(platform="docker"), duration=30, end_rps=300)
    r = run_simulation(d)
    assert "cluster" not in node_by_id(r, "api")["summary"]
    assert node_by_id(r, "k8s")["summary"]["podsPeak"] == 0
    k = ramp_doc(cluster(), api(), duration=60)
    assert (
        run_simulation(k)["provenance"]["resultDigest"]
        == run_simulation(k)["provenance"]["resultDigest"]
    )


def test_pending_pods_are_deleted_when_the_hpa_scales_back_down() -> None:
    d = doc(
        [
            node("clients", "traffic_source", timeoutMs=10_000),
            cluster(clusterAutoscaler=False),
            api(),
        ],
        [edge("c-a", "clients", "api")],
        rps=100,
        durationS=240,
        warmupS=5,
        pattern={"kind": "burst", "burstRps": 2500, "startS": 20, "durationS": 60},
    )
    r = run_simulation(d)
    s = node_by_id(r, "k8s")["summary"]
    assert s["podsPendingPeak"] > 0
    assert s["counters"]["podsCancelled"] >= 1
    assert s["podsPendingEnd"] == 0
    # with no Pending pods left the HPA can scale the ready ones down again
    assert (
        node_by_id(r, "api")["summary"]["replicasEnd"]
        < node_by_id(r, "api")["summary"]["replicasPeak"]
    )
