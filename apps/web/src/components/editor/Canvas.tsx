"use client";

import {
  Background,
  BackgroundVariant,
  type Connection,
  type FinalConnectionState,
  type IsValidConnection,
  MarkerType,
  MiniMap,
  ReactFlow,
  useNodesInitialized,
  useReactFlow,
} from "@xyflow/react";
import { ArrowRight, Cable, MousePointerClick, Play } from "lucide-react";
import { useCallback, useEffect, useRef } from "react";
import { toast } from "sonner";
import { ArchEdgeView } from "@/components/editor/ArchEdgeView";
import { ArchNodeView, NODE_W } from "@/components/editor/ArchNodeView";
import { RunReportCard } from "@/components/editor/CanvasHud";
import { CanvasToolbar } from "@/components/editor/CanvasToolbar";
import { DND_MIME } from "@/components/editor/Palette";
import { useIsDark } from "@/components/ui/theme-toggle";
import type { ArchEdge, ArchNode } from "@/lib/document";
import { checkConnection } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

const nodeTypes = { arch: ArchNodeView };
const edgeTypes = { arch: ArchEdgeView };
// Keep components clear of the floating toolbar (bottom centre).
type Pad = { top: number; right: number; bottom: number; left: number };
const BASE_PAD: Pad = { top: 32, right: 32, bottom: 84, left: 32 };
const px = (p: Pad) => ({ top: `${p.top}px`, right: `${p.right}px`, bottom: `${p.bottom}px`, left: `${p.left}px` }) as const;
const FIT_PAD = px(BASE_PAD);

const defaultEdgeOptions = {
  type: "arch",
  markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12, color: "var(--wire)" },
};

function EmptyState() {
  const steps = [
    { icon: MousePointerClick, title: "add", text: "Drag a shape from the left rail, or click one. Start with Clients." },
    { icon: Cable, title: "link", text: "Drag from a square pin on the right of a shape to the next one." },
    { icon: Play, title: "run", text: "Press Run (Ctrl Enter) and watch queues, latency and bottlenecks." },
  ];
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-6">
      <div className="max-w-xl">
        <p className="font-mono text-xs text-ink-3">
          <span className="text-accent">{"//"}</span> empty canvas
        </p>
        <h2 className="mt-2 font-mono text-xl font-semibold tracking-tight text-ink">
          Sketch a system, then stress it<span className="caret text-accent">_</span>
        </h2>
        <ol className="mt-5 grid gap-2 sm:grid-cols-3">
          {steps.map((s, i) => (
            <li key={s.title} className="rounded-md border border-line bg-surface p-3">
              <div className="mb-1.5 flex items-center gap-2 font-mono text-[11px] text-ink-3">
                <span className="text-accent">{String(i + 1).padStart(2, "0")}</span>
                <span className="uppercase tracking-wider text-ink">{s.title}</span>
                <s.icon className="ml-auto size-3.5" aria-hidden />
              </div>
              <p className="text-xs leading-relaxed text-ink-2">{s.text}</p>
            </li>
          ))}
        </ol>
        <p className="mt-3 flex items-center gap-1 font-mono text-[11px] text-ink-3">
          or press <kbd className="rounded-sm border border-line px-1">Ctrl K</kbd> for commands, or open an example from the projects page
          <ArrowRight className="size-3" />
        </p>
      </div>
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
  const activeRunId = useRuns((s) => s.activeRunId);
  const drawerOpen = useRuns((s) => s.drawerOpen);
  const rf = useReactFlow<ArchNode, ArchEdge>();
  const wrapRef = useRef<HTMLDivElement>(null);
  const dark = useIsDark();
  const firstLayout = useRef(true);
  const nodesInitialized = useNodesInitialized();
  const didInitialFit = useRef(false);

  // Fit the whole graph once node sizes are known.
  useEffect(() => {
    if (!nodesInitialized || didInitialFit.current) return;
    didInitialFit.current = true;
    void rf.fitView({ padding: FIT_PAD, maxZoom: 1 });
  }, [nodesInitialized, rf]);

  useEffect(() => {
    if (firstLayout.current) {
      firstLayout.current = false;
      return;
    }
    const t = setTimeout(() => void rf.fitView({ padding: FIT_PAD, maxZoom: 1, duration: 300 }), 50);
    return () => clearTimeout(t);
  }, [layoutVersion, rf]);

  // When results land, reframe if the run card would hide a component.
  const hasReport = !!activeReport;
  useEffect(() => {
    if (!hasReport || !overlay || drawerOpen) return;
    const t = setTimeout(() => {
      const wrap = wrapRef.current?.getBoundingClientRect();
      const card = wrapRef.current?.querySelector("[data-run-card]")?.getBoundingClientRect();
      if (!wrap || !card || card.width === 0) return;
      const covered = rf.getNodes().some((n) => {
        const tl = rf.flowToScreenPosition(n.position);
        const br = rf.flowToScreenPosition({
          x: n.position.x + (n.measured?.width ?? 236),
          y: n.position.y + (n.measured?.height ?? 120),
        });
        return br.x > card.left - 8 && tl.x < card.right && tl.y < card.bottom + 8 && br.y > card.top;
      });
      if (!covered) return;
      // Fit either beside or below the card, whichever keeps the diagram larger.
      const b = rf.getNodesBounds(rf.getNodes());
      const zoomFor = (p: Pad) => Math.min((wrap.width - p.left - p.right) / b.width, (wrap.height - p.top - p.bottom) / b.height, 1);
      const beside = { ...BASE_PAD, right: Math.round(wrap.right - card.left) + 16 };
      const below = { ...BASE_PAD, top: Math.round(card.bottom - wrap.top) + 16 };
      const pad = zoomFor(below) >= zoomFor(beside) ? below : beside;
      void rf.fitView({ padding: px(pad), maxZoom: 1, duration: 300 });
    }, 80);
    return () => clearTimeout(t);
  }, [activeRunId, hasReport, overlay, drawerOpen, rf]);

  const isValidConnection: IsValidConnection<ArchEdge> = useCallback((c) => {
    const s = useEditor.getState();
    return checkConnection(c, s.nodes, s.edges, s.catalog).ok;
  }, []);

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
      ? { source: state.fromNode.id, sourceHandle: state.fromHandle?.id ?? null, target: state.toNode.id, targetHandle: state.toHandle?.id ?? null }
      : { source: state.toNode.id, sourceHandle: state.toHandle?.id ?? null, target: state.fromNode.id, targetHandle: state.fromHandle?.id ?? null };
    const check = checkConnection(conn, s.nodes, s.edges, s.catalog);
    if (!check.ok) toast.error("Cannot link these components", { description: check.reason });
  }, []);

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      const type = e.dataTransfer.getData(DND_MIME);
      if (!type) return;
      e.preventDefault();
      const p = rf.screenToFlowPosition({ x: e.clientX, y: e.clientY });
      addNode(type, { x: Math.round(p.x - NODE_W / 2), y: Math.round(p.y - 40) });
    },
    [rf, addNode],
  );

  const miniColor = (n: ArchNode) => {
    if (overlay && activeReport) {
      const nr = activeReport.nodes.find((x) => x.id === n.id);
      if (nr) return NODE_STATUS[nr.status].color;
    }
    return n.selected ? "var(--accent)" : "var(--node-stroke)";
  };

  return (
    <div ref={wrapRef} className="relative h-full w-full bg-canvas" onDragOver={(e) => e.preventDefault()} onDrop={onDrop}>
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
        proOptions={{ hideAttribution: false }}
        aria-label="Architecture canvas"
      >
        <Background id="dots" variant={BackgroundVariant.Dots} gap={24} size={1.1} color="var(--grid-dot)" />
        <Background id="cross" variant={BackgroundVariant.Cross} gap={120} size={9} lineWidth={1} color="var(--grid-cross)" />
        {nodes.length > 8 && (
        <MiniMap
          pannable
          zoomable
          nodeColor={miniColor}
          nodeBorderRadius={6}
          nodeStrokeWidth={0}
          position="bottom-right"
          style={{ width: 150, height: 96, marginBottom: 56 }}
          className="!hidden lg:!block"
        />
        )}
      </ReactFlow>
      {nodes.length === 0 ? <EmptyState /> : <RunReportCard />}
      <CanvasToolbar />
    </div>
  );
}
