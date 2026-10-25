import { describe, expect, it } from "vitest";
import { clusterOf, membersOf, planCluster, podRequest, spreadPods, startingPods } from "./kubernetes";

const part = (id: string, type: string, config: Record<string, unknown> = {}) => ({ id, data: { type, label: id, config } });

describe("kubernetes helpers", () => {
  const k8s = part("k8s", "kubernetes_cluster", { nodes: 2, nodeVcpu: 4, nodeMemoryGib: 16, allocatableShare: 0.9 });

  it("finds a service's cluster the way the engine does", () => {
    const api = part("api", "service", { platform: "kubernetes" });
    expect(clusterOf(api, ["k8s"])).toBe("k8s");
    expect(clusterOf(api, ["a", "b"])).toBeNull(); // ambiguous
    expect(clusterOf(part("x", "service", { platform: "kubernetes", cluster: "b" }), ["a", "b"])).toBe("b");
    expect(clusterOf(part("x", "service", { platform: "kubernetes", cluster: "gone" }), ["a"])).toBeNull();
    expect(clusterOf(part("vm", "service", { platform: "vm" }), ["k8s"])).toBeNull();
    expect(membersOf([k8s, api, part("db", "database")], "k8s").map((p) => p.id)).toEqual(["api"]);
  });

  it("counts starting pods and their requests", () => {
    expect(startingPods({ replicas: 3 })).toBe(3);
    expect(startingPods({ replicas: 1, autoscaling: true, minReplicas: 2, maxReplicas: 5 })).toBe(2);
    expect(podRequest({ cpuCores: 1 }, { defaultCpuRequest: 0.5, defaultMemoryRequestGib: 0.25 })).toEqual({ cpu: 1, mem: 0.25 });
  });

  it("binds starting pods least-allocated first and leaves the rest Pending", () => {
    // 2 nodes x 3.6 allocatable vCPU fit 3 one-vCPU pods each
    const plan = planCluster(k8s.data.config, [part("api", "service", { platform: "kubernetes", replicas: 8, cpuCores: 1, memoryGib: 2 })]);
    expect(plan.podsPerNode).toEqual([3, 3]);
    expect(plan.pending).toBe(2);
    expect(plan.cpuRequested).toBe(6);
    expect(plan.cpuAllocatable).toBeCloseTo(7.2);
    expect(spreadPods(7, 3)).toEqual([3, 2, 2]);
  });
});
