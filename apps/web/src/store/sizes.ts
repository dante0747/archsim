/**
 * Auto sizes of the parts in the editor: the engine's analysis of the design (POST /analyze),
 * refreshed together with validation. Nothing is computed in the browser.
 */
import type { Analysis, SizingEntry, SpecKey } from "@/lib/types";
import { useEditor } from "@/store/editor";

export const SPEC_KEYS: SpecKey[] = ["cpuCores", "memoryGib", "storageGib"];

/** The engine's sizing of one part (null before the first analysis or for parts without specs). */
export function useAutoSize(nodeId: string): SizingEntry | null {
  return useEditor((s) => s.analysis?.sizing.nodes[nodeId] ?? null);
}

/** The CPU utilization auto sizes aim for at the peak. */
export function useTargetCpu(): number | null {
  return useEditor((s) => s.analysis?.sizing.targetCpuUtilization ?? null);
}

/** "cpu|mem|storage" auto sizes of one part, for cheap equality in selectors. */
export function autoSizeKey(analysis: Analysis | null, nodeId: string): string {
  const a = analysis?.sizing.nodes[nodeId];
  if (!a || a.skipped) return "||";
  return SPEC_KEYS.map((k) => a.auto[k] ?? "").join("|");
}
