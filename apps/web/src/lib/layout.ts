/** Automatic left-to-right layout with ELK's layered algorithm. */
import ELK from "elkjs/lib/elk.bundled.js";
import type { ArchEdge, ArchNode } from "./document";

const elk = new ELK();

export const NODE_WIDTH = 248;
export const NODE_HEIGHT = 128;

export async function layoutGraph(
  nodes: ArchNode[],
  edges: ArchEdge[],
): Promise<Record<string, { x: number; y: number }>> {
  const result = await elk.layout({
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "RIGHT",
      "elk.spacing.nodeNode": "56",
      "elk.layered.spacing.nodeNodeBetweenLayers": "150",
      "elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
      "elk.layered.crossingMinimization.strategy": "LAYER_SWEEP",
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: n.measured?.width ?? NODE_WIDTH,
      height: n.measured?.height ?? NODE_HEIGHT,
    })),
    edges: edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  });
  const out: Record<string, { x: number; y: number }> = {};
  for (const c of result.children ?? []) out[c.id] = { x: c.x ?? 0, y: c.y ?? 0 };
  return out;
}
