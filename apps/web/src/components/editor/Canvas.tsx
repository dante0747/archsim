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
import { ArchNodeView } from "@/components/editor/ArchNodeView";
import { RunReportCard } from "@/components/editor/CanvasHud";
import { CanvasToolbar } from "@/components/editor/CanvasToolbar";
import { DND_MIME } from "@/components/editor/Palette";
import { useIsDark } from "@/components/ui/theme-toggle";
import type { ArchEdge, ArchNode } from "@/lib/document";
import { checkConnection, componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { kindColor, kindOf } from "@/lib/theme";
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
  markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14, color: "var(--ink-3)" },
};

function EmptyState() {
  const steps = [
    { icon: MousePointerClick, title: "Add components", text: "Drag from the left rail, or click one. Start with Clients." },
    { icon: Cable, title: "Link them", text: "Drag from a right-side handle to the next component." },
    { icon: Play, title: "Simulate", text: "Press Run and watch queues, latency and bottlenecks." },
  ];
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-6">
      <div className="max-w-xl text-center">
        <p className="microlabel mb-2">Empty canvas</p>
        <h2 className="text-2xl font-semibold tracking-tight text-ink">
          Sketch a system, then <span className="brand-text">stress it</span>.
        </h2>
        <ol className="mt-6 grid gap-3 sm:grid-cols-3">
          {steps.map((s, i) => (
            <li key={s.title} className="rounded-2xl border border-line bg-glass p-4 text-left shadow-panel backdrop-blur">
              <div className="mb-2 flex items-center gap-2">
                <span className="brand-bg flex size-6 items-center justify-center rounded-lg font-mono text-[11px] font-bold text-accent-ink">{i + 1}</span>
                <s.icon className="size-4 text-ink-3" aria-hidden />
              </div>
              <p className="text-sm font-semibold text-ink">{s.title}</p>
              <p className="mt-0.5 text-xs leading-relaxed text-ink-2">{s.text}</p>
            </li>
          ))}
        </ol>
        <p className="mt-4 flex items-center justify-center gap-1 text-xs text-ink-3">
          Or open an example from the projects page <ArrowRight className="size-3" />
        </p>
      </div>
    </div>
  );
}

export function Canvas() {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const catalog = useEditor((s) => s.catalog);
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
      addNode(type, { x: Math.round(p.x - 118), y: Math.round(p.y - 40) });
    },
    [rf, addNode],
  );

  const miniColor = (n: ArchNode) => {
    if (overlay && activeReport) {
      const nr = activeReport.nodes.find((x) => x.id === n.id);
      if (nr) return NODE_STATUS[nr.status].color;
    }
    return kindColor(kindOf(n.data.type, componentOf(catalog, n.data.type)?.category));
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
        <Background id="minor" variant={BackgroundVariant.Lines} gap={24} lineWidth={1} color="var(--grid-minor)" />
        <Background id="major" variant={BackgroundVariant.Lines} gap={120} lineWidth={1} color="var(--grid-major)" />
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
