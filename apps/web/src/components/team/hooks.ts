"use client";

import { useCallback, useMemo } from "react";
import { type LiveParts, type PartName, type Thread, threadsOf } from "@/lib/team";
import type { AnchorKind } from "@/lib/team-types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useTeam } from "@/store/team";
import { useUi } from "@/store/ui";

/** Node and edge ids on the canvas now. Recomputed only when parts are added or removed. */
export function useLiveParts(): LiveParts {
  const nodeKey = useEditor((s) => s.nodes.map((n) => n.id).join("\n"));
  const edgeKey = useEditor((s) => s.edges.map((e) => e.id).join("\n"));
  return useMemo(
    () => ({ nodes: new Set(nodeKey ? nodeKey.split("\n") : []), edges: new Set(edgeKey ? edgeKey.split("\n") : []) }),
    [nodeKey, edgeKey],
  );
}

/** The project's comments as threads, detached judged against the design as drawn now. */
export function useThreads(): Thread[] {
  const comments = useTeam((s) => s.comments);
  const live = useLiveParts();
  return useMemo(() => threadsOf(comments, live), [comments, live]);
}

/** Open threads on one part (for the badges on the canvas). */
export function useOpenCount(kind: AnchorKind, anchorId: string): number {
  return useTeam((s) => s.comments.reduce((n, c) => n + (!c.parentId && !c.resolvedAt && c.anchorKind === kind && c.anchorId === anchorId ? 1 : 0), 0));
}

/** Names parts by their labels on the canvas ("Cache", "API → Database"), runs by label. */
export function usePartName(): PartName {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const runs = useRuns((s) => s.runs);
  return useCallback(
    (kind, id) => {
      if (kind === "project") return "The design";
      if (kind === "run") return `Run · ${runs.find((r) => r.id === id)?.label ?? id?.slice(0, 8) ?? "?"}`;
      if (kind === "node") return nodes.find((n) => n.id === id)?.data.label ?? id ?? "?";
      const e = edges.find((x) => x.id === id);
      if (!e) return `Link ${id ?? "?"}`;
      const label = (nid: string) => nodes.find((n) => n.id === nid)?.data.label ?? nid;
      return `${label(e.source)} → ${label(e.target)}`;
    },
    [nodes, edges, runs],
  );
}

/** Select a part on the canvas and show it in the side panel (Design workspace). */
export function showPart(kind: AnchorKind, id: string | null): void {
  if (!id || (kind !== "node" && kind !== "edge")) return;
  useUi.getState().setMode("build");
  const editor = useEditor.getState();
  editor.select(kind === "node" ? { nodeId: id } : { edgeId: id });
  editor.setRightTab("inspect");
  window.setTimeout(() => document.getElementById("comments-section")?.scrollIntoView({ block: "nearest", behavior: "smooth" }), 50);
}
