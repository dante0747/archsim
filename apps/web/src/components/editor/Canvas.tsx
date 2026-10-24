"use client";

import {
  Background,
  BackgroundVariant,
  type Connection,
  Controls,
  type FinalConnectionState,
  type IsValidConnection,
  MarkerType,
  MiniMap,
  ReactFlow,
  useNodesInitialized,
  useReactFlow,
} from "@xyflow/react";
import { MousePointerClick } from "lucide-react";
import { useCallback, useEffect, useRef } from "react";
import { toast } from "sonner";
import { ArchEdgeView } from "@/components/editor/ArchEdgeView";
import { ArchNodeView } from "@/components/editor/ArchNodeView";
import { useIsDark } from "@/components/ui/theme-toggle";
import type { ArchEdge, ArchNode } from "@/lib/document";
import { checkConnection } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

export const DND_MIME = "application/x-archsim-component";

const nodeTypes = { arch: ArchNodeView };
const edgeTypes = { arch: ArchEdgeView };
const defaultEdgeOptions = {
  type: "arch",
  markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: "var(--ink-3)" },
};

function OverlayLegend() {
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId));
  const setOverlay = useRuns((s) => s.setOverlay);
  const statuses = ["ok", "warm", "hot", "saturated"] as const;
  return (
    <div className="absolute left-3 top-3 z-10 max-w-[calc(100%-24px)] rounded-lg border border-line bg-surface/95 px-3 py-2 text-[11px] shadow-sm">
      <div className="flex items-center gap-2">
        <span className="text-ink-3">Showing results of</span>
        <span className="truncate font-medium text-ink">{run?.label ?? "a run"}</span>
        {run && <span className="text-ink-3">· seed {run.seed}</span>}
        <button type="button" className="ml-1 text-accent hover:underline" onClick={() => setOverlay(false)}>
          hide
        </button>
      </div>
      <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5" aria-label="Status legend">
        {statuses.map((s) => (
          <li key={s} className="flex items-center gap-1 text-ink-2" title={NODE_STATUS[s].description}>
            <span className="size-2 rounded-full" style={{ background: NODE_STATUS[s].color }} aria-hidden />
            {NODE_STATUS[s].label}
          </li>
        ))}
      </ul>
    </div>
  );
}

export function Canvas() {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const onNodesChange = useEditor((s) => s.onNodesChange);
  const onEdgesChange = useEditor((s) => s.onEdgesChange);
  const connect = useEditor((s) => s.connect);
  const addNode = useEditor((s) => s.addNode);
  const setRightTab = useEditor((s) => s.setRightTab);
  const layoutVersion = useEditor((s) => s.layoutVersion);
  const overlay = useRuns((s) => s.overlay);
  const activeReport = useRuns((s) => (s.activeRunId ? s.reports[s.activeRunId] : null));
  const rf = useReactFlow<ArchNode, ArchEdge>();
  const dark = useIsDark();
  const firstLayout = useRef(true);
  const nodesInitialized = useNodesInitialized();
  const didInitialFit = useRef(false);

  // Fit the whole graph once node sizes are known (unless a saved viewport exists).
  useEffect(() => {
    if (!nodesInitialized || didInitialFit.current) return;
    didInitialFit.current = true;
    void rf.fitView({ padding: 0.15, maxZoom: 1 });
  }, [nodesInitialized, rf]);

  useEffect(() => {
    if (firstLayout.current) {
      firstLayout.current = false;
      return;
    }
    const t = setTimeout(() => void rf.fitView({ padding: 0.2, duration: 300 }), 50);
    return () => clearTimeout(t);
  }, [layoutVersion, rf]);

  const isValidConnection: IsValidConnection<ArchEdge> = useCallback(
    (c) => {
      const s = useEditor.getState();
      return checkConnection(c, s.nodes, s.edges, s.catalog).ok;
    },
    [],
  );

  const onConnect = useCallback(
    (c: Connection) => {
      const res = connect(c);
      if (!res.ok) toast.error("Cannot link these components", { description: res.reason });
    },
    [connect],
  );

  const onConnectEnd = useCallback((_: MouseEvent | TouchEvent, state: FinalConnectionState) => {
    if (state.isValid || !state.fromNode || !state.toNode) return;
    const s = useEditor.getState();
    const fromIsSource = state.fromHandle?.type === "source";
    const conn = fromIsSource
      ? {
          source: state.fromNode.id,
          sourceHandle: state.fromHandle?.id ?? null,
          target: state.toNode.id,
          targetHandle: state.toHandle?.id ?? null,
        }
      : {
          source: state.toNode.id,
          sourceHandle: state.toHandle?.id ?? null,
          target: state.fromNode.id,
          targetHandle: state.fromHandle?.id ?? null,
        };
    const check = checkConnection(conn, s.nodes, s.edges, s.catalog);
    if (!check.ok) toast.error("Cannot link these components", { description: check.reason });
  }, []);

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      const type = e.dataTransfer.getData(DND_MIME);
      if (!type) return;
      e.preventDefault();
      const p = rf.screenToFlowPosition({ x: e.clientX, y: e.clientY });
      addNode(type, { x: Math.round(p.x - 124), y: Math.round(p.y - 40) });
    },
    [rf, addNode],
  );

  const statusColor = (n: ArchNode) => {
    if (!overlay || !activeReport) return "var(--surface-3)";
    const nr = activeReport.nodes.find((x) => x.id === n.id);
    return nr ? NODE_STATUS[nr.status].color : "var(--surface-3)";
  };

  return (
    <div className="relative h-full w-full" onDragOver={(e) => e.preventDefault()} onDrop={onDrop}>
      <ReactFlow<ArchNode, ArchEdge>
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultEdgeOptions={defaultEdgeOptions}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onConnectEnd={onConnectEnd}
        isValidConnection={isValidConnection}
        onNodeClick={() => setRightTab("inspect")}
        onEdgeClick={() => setRightTab("inspect")}
        deleteKeyCode={["Backspace", "Delete"]}
        multiSelectionKeyCode={["Meta", "Control"]}
        selectionKeyCode="Shift"
        snapToGrid
        snapGrid={[8, 8]}
        minZoom={0.2}
        maxZoom={2}
        colorMode={dark ? "dark" : "light"}
        aria-label="Architecture canvas"
      >
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} />
        <Controls showInteractive={false} />
        <MiniMap
          pannable
          zoomable
          nodeColor={statusColor}
          nodeStrokeWidth={0}
          style={{ width: 160, height: 104 }}
          className="!hidden lg:!block"
        />
      </ReactFlow>
      {overlay && activeReport && nodes.length > 0 && <OverlayLegend />}
      {nodes.length === 0 && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-6">
          <div className="max-w-sm rounded-xl border border-dashed border-line-strong bg-surface/90 p-6 text-center">
            <MousePointerClick className="mx-auto mb-2 size-6 text-ink-3" />
            <p className="text-sm font-medium text-ink">Start drawing your system</p>
            <p className="mt-1 text-xs leading-relaxed text-ink-2">
              Drag a <b>Clients / Traffic</b> component from the palette, add services and data stores, then drag
              from a node&apos;s right handle to another node&apos;s left handle to link them.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
