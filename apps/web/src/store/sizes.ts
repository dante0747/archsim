/**
 * Auto sizes of the parts in the editor: the engine's analysis of the design (POST /analyze),
 * refreshed together with validation. Nothing is computed in the browser.
 */
import type { Analysis, SizingEntry, SpecKey, VcpuBasis } from "@/lib/types";
import { useEditor } from "@/store/editor";

export const SPEC_KEYS: SpecKey[] = ["cpuCores", "memoryGib", "storageGib"];

const VCPU_BASIS: Record<VcpuBasis, string> = {
  part: "set on the part",
  design: "set on the design",
  wholeCoreFamily: "its instance family",
  serverless: "serverless",
  pod: "pods",
  default: "the default",
};

/** The sibling speed the engine counted a part's vCPUs at, and in a few words why: "0.6 (hyperthread
 * pairs, the default)", "1 (whole cores, its instance family)". Null before an analysis, and for a part
 * whose vCPUs it does not count (one vCPU, a design with a host, a part it does not size). */
export function countedVcpus(entry: SizingEntry | null | undefined): { value: number; text: string } | null {
  const vcpus = entry?.vcpus;
  if (!vcpus) return null;
  const kind = vcpus.siblingSpeed >= 1 ? "whole cores" : "hyperthread pairs";
  const speed = String(Number(vcpus.siblingSpeed.toFixed(2)));
  return { value: vcpus.siblingSpeed, text: `${speed} (${kind}, ${VCPU_BASIS[vcpus.basis] ?? vcpus.basis})` };
}

/** The engine's sizing of one part (null before the first analysis or for parts without specs). */
export function useAutoSize(nodeId: string): SizingEntry | null {
  return useEditor((s) => s.analysis?.sizing.nodes[nodeId] ?? null);
}

/** The CPU utilization auto sizes aim for at the peak. */
export function useTargetCpu(): number | null {
  return useEditor((s) => s.analysis?.sizing.targetCpuUtilization ?? null);
}

/** "cpu|mem" a part needs (CPU cores at the target, typical memory), for cheap equality in
 * selectors; "|" when the engine reports no need. */
export function needKey(analysis: Analysis | null, nodeId: string): string {
  const a = analysis?.sizing.nodes[nodeId];
  if (!a || a.skipped || !a.need) return "|";
  const mem = a.need.memoryGib ?? a.need.memoryGibMax;
  return `${a.need.cpuCores ?? ""}|${mem ?? ""}`;
}

/** "cpu|mem|storage" auto sizes of one part, for cheap equality in selectors. */
export function autoSizeKey(analysis: Analysis | null, nodeId: string): string {
  const a = analysis?.sizing.nodes[nodeId];
  if (!a || a.skipped) return "||";
  return SPEC_KEYS.map((k) => a.auto[k] ?? "").join("|");
}
