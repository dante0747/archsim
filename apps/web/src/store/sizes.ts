/**
 * Auto sizes of the parts in the editor, computed once per change of the design (not per part
 * and not when a part is only moved).
 */
import { toDocument } from "@/lib/document";
import { type PartSize, autoSizes } from "@/lib/sizing";
import { useEditor } from "@/store/editor";

type EditorState = ReturnType<typeof useEditor.getState>;

let memo: { data: unknown[]; edges: unknown; workload: unknown; catalog: unknown; sizes: Map<string, PartSize> } | null = null;

export function editorSizes(s: EditorState): Map<string, PartSize> {
  // moving a part gives it a new node object but keeps its data, so only data changes count
  const data = s.nodes.map((n) => n.data);
  if (
    memo &&
    memo.edges === s.edges &&
    memo.workload === s.workload &&
    memo.catalog === s.catalog &&
    memo.data.length === data.length &&
    memo.data.every((d, i) => d === data[i])
  ) {
    return memo.sizes;
  }
  const sizes = autoSizes(toDocument(s), s.catalog);
  memo = { data, edges: s.edges, workload: s.workload, catalog: s.catalog, sizes };
  return sizes;
}

/** The auto size of one part (null for parts without resource specs). */
export function useAutoSize(nodeId: string): PartSize | null {
  return useEditor((s) => editorSizes(s).get(nodeId) ?? null);
}
