"""Kubernetes cluster: a pool of nodes that services on the Kubernetes platform run their pods on.

The cluster is not on the request path (it has no ports). Services with ``platform:
kubernetes`` join it (the one cluster in the design, or the one named in their ``cluster``
setting) and every replica becomes a pod:

* **Scheduling.** A pod requests the service's CPU and memory (or the cluster's default
  request when the service sets none). The scheduler binds it to a ready node with room for both,
  preferring the least allocated node (kube-scheduler's default scoring). When no node has room
  the pod stays **Pending**.
* **Cluster autoscaler.** Every ``scanIntervalS`` (10 s by default) it adds enough nodes for the
  Pending pods, up to ``maxNodes``; a node joins after ``nodeProvisionS``. Nodes empty for
  ``scaleDownUnneededS`` (10 minutes) are removed, down to ``minNodes``.
* **Start-up.** A pod bound to a node that has never run that service pulls the image first
  (``imagePullS``), then starts (the service's ``startupS``) before it takes traffic. Pods
  running when the simulation starts are ready.

Report: series ``nodes``, ``podsRunning``, ``podsPending``, ``cpuRequestedRatio``,
``memoryRequestedRatio``; summary peaks, node additions and how long pods waited.
"""

from __future__ import annotations

from collections import deque
from collections.abc import Generator
from dataclasses import dataclass, field
from typing import Any, Literal

import simpy
from pydantic import Field

from archsim_engine.explain import formula, num
from archsim_engine.issues import Issue, error, warning
from archsim_engine.metrics import Gauge, MeanAccumulator
from archsim_engine.registry import ComponentModel, GraphView, Preset, ui
from archsim_engine.runtime import Outcome, Request, RuntimeComponent, SimContext, rejected
from archsim_engine.schema import CamelModel, NodeSpec
from archsim_engine.technology import Profile, Trait

CLUSTER_TYPE = "kubernetes_cluster"
CA = {"clusterAutoscaler": True}
_K8S = "https://kubernetes.io/docs/"
_CA_FAQ = "https://github.com/kubernetes/autoscaler/blob/master/cluster-autoscaler/FAQ.md"

K8S_TRAITS = (
    Trait(
        "requests",
        "Pods are scheduled by their requests",
        "Each pod reserves the CPU and memory it requests on one node; the scheduler only binds it "
        "to a node with that much unreserved, and prefers the least allocated node.",
        _K8S + "concepts/scheduling-eviction/kube-scheduler/",
    ),
    Trait(
        "pending",
        "Pending pods",
        "When no node has room for a pod's requests it stays Pending: the replica the autoscaler "
        "asked for does not exist yet and takes no traffic.",
        _K8S + "concepts/workloads/pods/pod-lifecycle/",
    ),
    Trait(
        "cluster_autoscaler",
        "Cluster autoscaler",
        "Every 10 s the cluster autoscaler looks for Pending pods and adds nodes for them; a new "
        "node takes minutes to boot and join. Nodes left empty for 10 minutes are removed.",
        _CA_FAQ,
    ),
    Trait(
        "image_pull",
        "Image pulls on new nodes",
        "The first pod of a service on a node pulls its container image before starting; later "
        "pods on that node reuse the cached image.",
        _K8S + "concepts/containers/images/",
    ),
    Trait(
        "hpa",
        "Horizontal Pod Autoscaler",
        "The HPA re-evaluates every 15 s by default and asks for more pods; they still have to be "
        "scheduled, pulled and started before they help.",
        _K8S + "tasks/run-application/horizontal-pod-autoscale/",
    ),
)

# Upstream Kubernetes is core; the distributions behave the same here and differ only in their
# presets' (illustrative) node sizes and provisioning times.
CLUSTER_PROFILES: dict[str, Profile] = {
    tech: Profile({}, K8S_TRAITS, "core" if tech == "kubernetes" else "experimental")
    for tech in ("kubernetes", "eks", "gke", "aks", "k3s", "openshift")
}


class ClusterConfig(CamelModel):
    technology: Literal["kubernetes", "eks", "gke", "aks", "k3s", "openshift"] = Field(
        default="kubernetes",
        description="Kubernetes distribution or managed service. Presets fill in typical node sizes and provisioning times.",
        json_schema_extra=ui(label="Technology", group="Technology"),
    )
    nodes: int = Field(
        default=3,
        ge=1,
        le=1000,
        description="Nodes (worker VMs) running when the simulation starts.",
        json_schema_extra=ui(label="Nodes", group="Node pool", headline=True),
    )
    node_vcpu: float = Field(
        default=4.0,
        gt=0,
        le=512,
        description="vCPU per node.",
        json_schema_extra=ui(
            label="vCPU per node", unit="vCPU", group="Node pool", headline=True, step=1
        ),
    )
    node_memory_gib: float = Field(
        default=16.0,
        gt=0,
        le=24_576,
        description="Memory per node (GiB).",
        json_schema_extra=ui(label="Memory per node", unit="GiB", group="Node pool", headline=True),
    )
    allocatable_share: float = Field(
        default=0.9,
        gt=0,
        le=1,
        description=(
            "Share of each node's CPU and memory left for pods after the operating system, "
            "kubelet and system daemons reserve theirs."
        ),
        json_schema_extra=ui(
            label="Allocatable share", group="Node pool", assumption=True, step=0.05
        ),
    )
    cluster_autoscaler: bool = Field(
        default=True,
        description="Add nodes for Pending pods and remove nodes left empty (Kubernetes cluster autoscaler).",
        json_schema_extra=ui(label="Cluster autoscaler", group="Cluster autoscaler"),
    )
    min_nodes: int = Field(
        default=1,
        ge=1,
        le=1000,
        description="Fewest nodes the cluster autoscaler keeps.",
        json_schema_extra=ui(label="Min nodes", group="Cluster autoscaler", visible_when=CA),
    )
    max_nodes: int = Field(
        default=10,
        ge=1,
        le=1000,
        description="Most nodes the cluster autoscaler may run.",
        json_schema_extra=ui(label="Max nodes", group="Cluster autoscaler", visible_when=CA),
    )
    node_provision_s: float = Field(
        default=120.0,
        ge=0,
        le=3600,
        description="Time from the decision to add a node until it is ready for pods (VM boot, join, CNI) (s).",
        json_schema_extra=ui(
            label="Node provisioning time",
            unit="s",
            group="Cluster autoscaler",
            assumption=True,
            visible_when=CA,
        ),
    )
    scan_interval_s: float = Field(
        default=10.0,
        gt=0,
        le=600,
        description="How often the cluster autoscaler looks for Pending pods (s). The autoscaler's default is 10 s.",
        json_schema_extra=ui(
            label="Scan interval", unit="s", group="Cluster autoscaler", visible_when=CA
        ),
    )
    scale_down_unneeded_s: float = Field(
        default=600.0,
        ge=0,
        le=86_400,
        description="Time a node must stay empty before it is removed (s). The autoscaler's default is 10 minutes.",
        json_schema_extra=ui(
            label="Scale-down after empty", unit="s", group="Cluster autoscaler", visible_when=CA
        ),
    )
    image_pull_s: float = Field(
        default=8.0,
        ge=0,
        le=3600,
        description="Time to pull a service's container image on a node that has not run it yet (s).",
        json_schema_extra=ui(label="Image pull time", unit="s", group="Pods", assumption=True),
    )
    default_cpu_request: float = Field(
        default=0.5,
        gt=0,
        le=512,
        description="CPU request of pods whose service sets no CPU (vCPU).",
        json_schema_extra=ui(label="Default CPU request", unit="vCPU", group="Pods", step=0.25),
    )
    default_memory_request_gib: float = Field(
        default=0.5,
        gt=0,
        le=24_576,
        description="Memory request of pods whose service sets no memory (GiB).",
        json_schema_extra=ui(label="Default memory request", unit="GiB", group="Pods", step=0.25),
    )
    cost_per_hour: float = Field(
        default=0.19,
        ge=0,
        le=100_000,
        description="Price of one node per hour, in USD; services on the cluster are paid for through their nodes. Illustrative default.",
        json_schema_extra=ui(label="Cost per node-hour", unit="$/h", group="Cost", assumption=True),
    )

    def allocatable(self) -> tuple[float, float]:
        return (
            self.node_vcpu * self.allocatable_share,
            self.node_memory_gib * self.allocatable_share,
        )


def cluster_for(service_config: Any, cluster_ids: list[str]) -> str | None:
    """The cluster a service runs on: the one it names, else the only cluster in the design."""
    if getattr(service_config, "platform", None) != "kubernetes" or not cluster_ids:
        return None
    named = getattr(service_config, "cluster", None)
    if named:
        return named if named in cluster_ids else None
    return cluster_ids[0] if len(cluster_ids) == 1 else None


def pod_request(service_config: Any, cluster: ClusterConfig) -> tuple[float, float]:
    cpu = (
        service_config.cpu_cores
        if service_config.cpu_cores is not None
        else cluster.default_cpu_request
    )
    mem = (
        service_config.memory_gib
        if service_config.memory_gib is not None
        else cluster.default_memory_request_gib
    )
    return float(cpu), float(mem)


@dataclass
class K8sNode:
    id: int
    cpu: float
    mem: float
    used_cpu: float = 0.0
    used_mem: float = 0.0
    pods: int = 0
    images: set[str] = field(default_factory=set)
    empty_since: float | None = 0.0

    def fits(self, cpu: float, mem: float) -> bool:
        return self.cpu - self.used_cpu >= cpu - 1e-9 and self.mem - self.used_mem >= mem - 1e-9

    def free_score(self) -> float:
        return ((self.cpu - self.used_cpu) / self.cpu + (self.mem - self.used_mem) / self.mem) / 2.0


@dataclass
class PodRequest:
    service_id: str
    cpu: float
    mem: float
    event: simpy.Event
    requested_at: float


class ClusterRuntime(RuntimeComponent):
    def __init__(self, ctx: SimContext, node: NodeSpec, config: ClusterConfig) -> None:
        super().__init__(ctx, node, config, None)
        tl = ctx.tl
        self.alloc_cpu, self.alloc_mem = config.allocatable()
        self.nodes: list[K8sNode] = [
            K8sNode(i, self.alloc_cpu, self.alloc_mem) for i in range(config.nodes)
        ]
        self.next_id = config.nodes
        self.pending: deque[PodRequest] = deque()
        self.provisioning = 0
        self.g_nodes = Gauge(tl)
        self.g_pending = Gauge(tl)
        self.g_running = Gauge(tl)
        self.g_cpu_req = Gauge(tl)
        self.g_mem_req = Gauge(tl)
        self.g_cpu_alloc = Gauge(tl)
        self.g_mem_alloc = Gauge(tl)
        self.g_provisioning = Gauge(tl)
        self.g_nodes.add(config.nodes, 0.0)
        self.g_cpu_alloc.add(config.nodes * self.alloc_cpu, 0.0)  # type: ignore[arg-type]
        self.g_mem_alloc.add(config.nodes * self.alloc_mem, 0.0)  # type: ignore[arg-type]
        self.pending_wait = MeanAccumulator(tl)
        self.max_wait = 0.0
        self.services: set[str] = set()
        self._t_end: float | None = None
        if config.cluster_autoscaler:
            ctx.env.process(self._autoscaler())

    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:  # pragma: no cover
        # not on the request path (no ports); kept for the interface
        return rejected("not_routable", self.id)
        yield

    # -- scheduling -------------------------------------------------------------------------
    def request_pod(self, service_id: str, cpu: float, mem: float) -> simpy.Event:
        """Ask for a pod; the event succeeds with its node once the pod is bound."""
        env = self.env
        self.services.add(service_id)
        req = PodRequest(service_id, cpu, mem, env.event(), env.now)
        node = self._fit(cpu, mem)
        if node is not None:
            self._bind(node, req)
        else:
            self.pending.append(req)
            self.g_pending.add(1, env.now)
            self.m.count("podsQueued", env.now)
        return req.event

    def _fit(self, cpu: float, mem: float) -> K8sNode | None:
        best: K8sNode | None = None
        for n in self.nodes:
            if n.fits(cpu, mem) and (best is None or n.free_score() > best.free_score()):
                best = n
        return best

    def _bind(self, node: K8sNode, req: PodRequest) -> None:
        now = self.env.now
        node.used_cpu += req.cpu
        node.used_mem += req.mem
        node.pods += 1
        node.empty_since = None
        self.g_running.add(1, now)
        self.g_cpu_req.add(req.cpu, now)  # type: ignore[arg-type]
        self.g_mem_req.add(req.mem, now)  # type: ignore[arg-type]
        wait = now - req.requested_at
        if wait > 0:
            self.pending_wait.record(now, wait)
            self.max_wait = max(self.max_wait, wait)
        self.m.count("podsScheduled", now)
        req.event.succeed(node)

    def release_pod(self, node: K8sNode, cpu: float, mem: float) -> None:
        now = self.env.now
        node.used_cpu = max(0.0, node.used_cpu - cpu)
        node.used_mem = max(0.0, node.used_mem - mem)
        node.pods = max(0, node.pods - 1)
        if node.pods == 0:
            node.empty_since = now
        self.g_running.add(-1, now)
        self.g_cpu_req.add(-cpu, now)  # type: ignore[arg-type]
        self.g_mem_req.add(-mem, now)  # type: ignore[arg-type]
        self._schedule_pending()

    def cancel_pending(self, service_id: str, count: int) -> int:
        """Delete up to ``count`` of a service's Pending pods, newest first (as a ReplicaSet
        scaled down deletes its unscheduled pods first). Their events succeed with None."""
        if count <= 0 or not self.pending:
            return 0
        now = self.env.now
        keep: deque[PodRequest] = deque()
        cancelled: list[PodRequest] = []
        for req in reversed(self.pending):
            if len(cancelled) < count and req.service_id == service_id:
                cancelled.append(req)
            else:
                keep.appendleft(req)
        self.pending = keep
        for req in cancelled:
            self.max_wait = max(self.max_wait, now - req.requested_at)
            self.g_pending.add(-1, now)
            self.m.count("podsCancelled", now)
            req.event.succeed(None)
        return len(cancelled)

    def _schedule_pending(self) -> None:
        if not self.pending:
            return
        still: deque[PodRequest] = deque()
        now = self.env.now
        while self.pending:
            req = self.pending.popleft()
            node = self._fit(req.cpu, req.mem)
            if node is None:
                still.append(req)
            else:
                self.g_pending.add(-1, now)
                self._bind(node, req)
        self.pending = still

    def mark_image(self, node: K8sNode, service_id: str) -> None:
        node.images.add(service_id)

    def pull_ms(self, node: K8sNode, service_id: str) -> float:
        """Image pull time for a pod of ``service_id`` on ``node`` (0 once the image is cached)."""
        if service_id in node.images:
            return 0.0
        node.images.add(service_id)
        return self.config.image_pull_s * 1000.0

    # -- cluster autoscaler -------------------------------------------------------------------
    def _nodes_needed(self) -> int:
        """New empty nodes the Pending pods need (first-fit, largest first)."""
        bins: list[list[float]] = []
        for req in sorted(self.pending, key=lambda r: (r.cpu, r.mem), reverse=True):
            for b in bins:
                if b[0] >= req.cpu - 1e-9 and b[1] >= req.mem - 1e-9:
                    b[0] -= req.cpu
                    b[1] -= req.mem
                    break
            else:
                if req.cpu <= self.alloc_cpu + 1e-9 and req.mem <= self.alloc_mem + 1e-9:
                    bins.append([self.alloc_cpu - req.cpu, self.alloc_mem - req.mem])
        return len(bins)

    def _autoscaler(self) -> Generator[simpy.Event, Any, None]:
        env = self.env
        cfg: ClusterConfig = self.config
        while True:
            yield env.timeout(cfg.scan_interval_s * 1000.0)
            now = env.now
            if self.pending:
                room = cfg.max_nodes - len(self.nodes) - self.provisioning
                add = min(room, self._nodes_needed() - self.provisioning)
                for _ in range(max(0, add)):
                    self.provisioning += 1
                    self.g_provisioning.add(1, now)
                    self.m.count("nodeAdds", now)
                    env.process(self._provision())
            elif len(self.nodes) + self.provisioning > cfg.min_nodes:
                for n in list(self.nodes):
                    if len(self.nodes) <= cfg.min_nodes:
                        break
                    if (
                        n.pods == 0
                        and n.empty_since is not None
                        and now - n.empty_since >= cfg.scale_down_unneeded_s * 1000.0
                    ):
                        self.nodes.remove(n)
                        self.g_nodes.add(-1, now)
                        self.g_cpu_alloc.add(-self.alloc_cpu, now)  # type: ignore[arg-type]
                        self.g_mem_alloc.add(-self.alloc_mem, now)  # type: ignore[arg-type]
                        self.m.count("nodeRemovals", now)

    def _provision(self) -> Generator[simpy.Event, Any, None]:
        cfg: ClusterConfig = self.config
        if cfg.node_provision_s > 0:
            yield self.env.timeout(cfg.node_provision_s * 1000.0)
        now = self.env.now
        self.provisioning -= 1
        self.g_provisioning.add(-1, now)
        self.nodes.append(K8sNode(self.next_id, self.alloc_cpu, self.alloc_mem, empty_since=now))
        self.next_id += 1
        self.g_nodes.add(1, now)
        self.g_cpu_alloc.add(self.alloc_cpu, now)  # type: ignore[arg-type]
        self.g_mem_alloc.add(self.alloc_mem, now)  # type: ignore[arg-type]
        self._schedule_pending()

    # -- report ---------------------------------------------------------------------------------
    def background_busy(self) -> bool:
        return False

    def finalize(self, t_end: float) -> None:
        super().finalize(t_end)
        for req in self.pending:  # still waiting: their wait so far counts too
            self.max_wait = max(self.max_wait, t_end - req.requested_at)
        for g in (
            self.g_nodes,
            self.g_pending,
            self.g_running,
            self.g_cpu_req,
            self.g_mem_req,
            self.g_cpu_alloc,
            self.g_mem_alloc,
            self.g_provisioning,
        ):
            g.finalize(t_end)
        self._t_end = t_end

    def _mean(self, g: Gauge) -> list[float | None]:
        b = self.ctx.tl.bucket_ms
        out: list[float | None] = []
        for i, a in enumerate(g.area):
            covered = b if self._t_end is None else min(b, self._t_end - i * b)
            out.append(a / covered if covered > 0 else None)
        return out

    def extra_timeseries(self) -> dict[str, list[float | None]]:
        def ratio(num_g: Gauge, den_g: Gauge) -> list[float | None]:
            return [n / d if d else None for n, d in zip(num_g.area, den_g.area, strict=True)]

        return {
            "nodes": self._mean(self.g_nodes),
            "podsRunning": self._mean(self.g_running),
            "podsPending": self._mean(self.g_pending),
            "nodesProvisioning": self._mean(self.g_provisioning),
            "cpuRequestedRatio": ratio(self.g_cpu_req, self.g_cpu_alloc),
            "memoryRequestedRatio": ratio(self.g_mem_req, self.g_mem_alloc),
        }

    def extra_summary(self) -> dict[str, Any]:
        cpu_alloc = self.g_cpu_alloc.window_mean()
        mem_alloc = self.g_mem_alloc.window_mean()
        mean_wait = self.pending_wait.window_mean()
        return {
            "nodesMean": round(self.g_nodes.window_mean(), 3),
            "nodesPeak": max(self.g_nodes.maxes) if self.g_nodes.maxes else len(self.nodes),
            "nodesEnd": len(self.nodes),
            "podsPeak": max(self.g_running.maxes) if self.g_running.maxes else 0,
            "podsPendingPeak": max(self.g_pending.maxes) if self.g_pending.maxes else 0,
            "podsPendingEnd": len(self.pending),
            "meanPendingS": round(mean_wait / 1000.0, 3) if mean_wait is not None else None,
            "maxPendingS": round(self.max_wait / 1000.0, 3),
            "cpuRequestedRatio": round(self.g_cpu_req.window_mean() / cpu_alloc, 4)
            if cpu_alloc
            else None,
            "memoryRequestedRatio": round(self.g_mem_req.window_mean() / mem_alloc, 4)
            if mem_alloc
            else None,
            "allocatableVcpuPerNode": round(self.alloc_cpu, 3),
            "allocatableGibPerNode": round(self.alloc_mem, 3),
        }

    def behaviour(self) -> list[dict[str, Any]]:
        cfg: ClusterConfig = self.config
        return [
            t.to_dict()
            for t in K8S_TRAITS
            if t.id != "cluster_autoscaler" or cfg.cluster_autoscaler
        ]

    def explain(self, summary: dict[str, Any]) -> list[dict[str, Any]]:
        cfg: ClusterConfig = self.config
        out = [
            formula(
                "allocatable",
                "Allocatable per node",
                "allocatable = node size × allocatable share",
                f"{num(cfg.node_vcpu)} vCPU and {num(cfg.node_memory_gib)} GiB × {num(cfg.allocatable_share, 2)}",
                self.alloc_cpu,
                "vCPU",
                note=f"and {num(self.alloc_mem, 2)} GiB of memory for pods on each node.",
                source=_K8S + "tasks/administer-cluster/reserve-compute-resources/",
                metric="cpuRequestedRatio",
            )
        ]
        delay = cfg.node_provision_s + cfg.image_pull_s
        out.append(
            formula(
                "time_to_capacity",
                "Time until a Pending pod helps",
                "wait ≈ scan interval + node provisioning + image pull + pod start-up",
                f"up to {num(cfg.scan_interval_s)} + {num(cfg.node_provision_s)} + {num(cfg.image_pull_s)} s + the service's start-up",
                delay + cfg.scan_interval_s / 2,
                "s",
                note=f"Measured: pods waited {num(summary.get('meanPendingS'), 2)} s on average for a node (longest {num(summary.get('maxPendingS'), 2)} s).",
                source=_CA_FAQ,
                metric="podsPending",
            )
        )
        return out


def _pool(
    tech: str, nodes: int, vcpu: float, mem: float, provision: float, cost: float, **extra: Any
) -> dict[str, Any]:
    return {
        "technology": tech,
        "nodes": nodes,
        "nodeVcpu": vcpu,
        "nodeMemoryGib": mem,
        "nodeProvisionS": provision,
        "costPerHour": cost,
        **extra,
    }


_ILL = "Node sizes, provisioning times and prices are illustrative."


class KubernetesClusterModel(ComponentModel):
    type = CLUSTER_TYPE
    display_name = "Kubernetes cluster"
    category = "compute"
    description = (
        "A pool of nodes for services on the Kubernetes platform. Every replica becomes a pod "
        "scheduled by its CPU and memory requests; pods wait as Pending when nodes are full until "
        "the cluster autoscaler adds a node, then pull their image and start."
    )
    icon = "boxes"
    config_model = ClusterConfig
    ports = ()
    technology_profiles = CLUSTER_PROFILES
    modeled = (
        "Nodes with allocatable CPU and memory; pods bound by their requests to the least allocated node that fits",
        "Pending pods when no node has room; scheduled as soon as room appears",
        "Cluster autoscaler: scan interval, node provisioning delay, max/min nodes, removal of empty nodes",
        "Image pull on the first pod of a service per node, then the service's start-up time",
        "Services join automatically (the only cluster) or by name; each replica is a pod",
    )
    not_modeled = (
        "CPU contention between pods beyond their requests (noisy neighbours), limits and throttling",
        "Pod priority and preemption, affinity, taints, topology spread",
        "Node failures, drains and rolling deployments",
        "Several node pools per cluster",
    )
    presets = (
        Preset(
            "kubernetes-cluster",
            "Kubernetes cluster",
            "kubernetes",
            "Cluster",
            "Three 4 vCPU / 16 GiB nodes with the cluster autoscaler (up to 10 nodes, 2 minutes per node). "
            + _ILL,
            _pool("kubernetes", 3, 4, 16, 120, 0.19, clusterAutoscaler=True, maxNodes=10),
            ("kubernetes", "cluster-autoscaler"),
        ),
        Preset(
            "eks-style",
            "EKS-style managed cluster",
            "eks",
            "Cluster",
            "Managed control plane with a node group of 4 vCPU / 16 GiB instances, about 2.5 minutes per new node. "
            + _ILL,
            _pool("eks", 3, 4, 16, 150, 0.192, clusterAutoscaler=True, maxNodes=12),
            ("kubernetes", "managed"),
        ),
        Preset(
            "gke-style",
            "GKE-style managed cluster",
            "gke",
            "Cluster",
            "Managed cluster with 4 vCPU / 16 GiB nodes and node auto-provisioning, about 1.5 minutes per new node. "
            + _ILL,
            _pool("gke", 3, 4, 16, 90, 0.17, clusterAutoscaler=True, maxNodes=12),
            ("kubernetes", "managed"),
        ),
        Preset(
            "aks-style",
            "AKS-style managed cluster",
            "aks",
            "Cluster",
            "Managed cluster with 4 vCPU / 16 GiB nodes, about 3 minutes per new node. " + _ILL,
            _pool("aks", 3, 4, 16, 180, 0.19, clusterAutoscaler=True, maxNodes=12),
            ("kubernetes", "managed"),
        ),
        Preset(
            "k3s",
            "k3s (small / edge)",
            "k3s",
            "Cluster",
            "Lightweight Kubernetes on two small nodes and no cluster autoscaler: what fits is what you get. "
            + _ILL,
            _pool("k3s", 2, 2, 4, 0, 0.04, clusterAutoscaler=False, allocatableShare=0.85),
            ("kubernetes", "edge"),
        ),
    )

    def validate(self, node: NodeSpec, config: Any, view: GraphView) -> list[Issue]:
        from archsim_engine.components.service import ServiceConfig  # service imports this module

        issues: list[Issue] = []
        name = node.label or node.id
        if config.cluster_autoscaler and not (config.min_nodes <= config.nodes <= config.max_nodes):
            issues.append(
                warning(
                    "kubernetes.nodes_outside_range",
                    f"{name} starts with {config.nodes} nodes, outside its autoscaling range "
                    f"{config.min_nodes}-{config.max_nodes}.",
                    node_id=node.id,
                    path="nodes",
                )
            )
        clusters = [n.id for n in view.nodes.values() if n.type == CLUSTER_TYPE]
        alloc_cpu, alloc_mem = config.allocatable()
        requested_cpu = 0.0
        requested_mem = 0.0
        members = 0
        for other in view.nodes.values():
            if other.type != "service":
                continue
            cfg = view.configs.get(other.id)
            if not isinstance(cfg, ServiceConfig) or cluster_for(cfg, clusters) != node.id:
                continue
            members += 1
            cpu, mem = pod_request(cfg, config)
            pods = (
                min(max(cfg.replicas, cfg.min_replicas), cfg.max_replicas)
                if cfg.autoscaling
                else cfg.replicas
            )
            requested_cpu += cpu * pods
            requested_mem += mem * pods
            if cpu > alloc_cpu + 1e-9 or mem > alloc_mem + 1e-9:
                issues.append(
                    error(
                        "kubernetes.pod_too_big",
                        f"Pods of {other.label or other.id} request {cpu:g} vCPU and {mem:g} GiB, more "
                        f"than one node of {name} can offer ({alloc_cpu:g} vCPU, {alloc_mem:g} GiB "
                        "allocatable), so they can never be scheduled.",
                        node_id=other.id,
                        path="cpuCores",
                        hint="Use bigger nodes or smaller pod requests.",
                    )
                )
        if members and (
            requested_cpu > alloc_cpu * config.nodes + 1e-9
            or requested_mem > alloc_mem * config.nodes + 1e-9
        ):
            issues.append(
                warning(
                    "kubernetes.starts_pending",
                    f"The starting pods on {name} request {requested_cpu:g} vCPU and {requested_mem:g} "
                    f"GiB but its {config.nodes} nodes offer {alloc_cpu * config.nodes:g} vCPU and "
                    f"{alloc_mem * config.nodes:g} GiB: some pods start Pending.",
                    node_id=node.id,
                    path="nodes",
                )
            )
        if not members:
            issues.append(
                warning(
                    "kubernetes.no_services",
                    f"No service runs on {name}. Set a service's platform to Kubernetes"
                    + (" and pick this cluster." if len(clusters) > 1 else "."),
                    node_id=node.id,
                )
            )
        return issues

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return ClusterRuntime(ctx, node, config)
