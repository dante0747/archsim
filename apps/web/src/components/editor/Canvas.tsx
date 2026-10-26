"use client";

import {
  Background,
  BackgroundVariant,
  type Connection,
  ConnectionLineType,
  ConnectionMode,
  type FinalConnectionState,
  type IsValidConnection,
  MarkerType,
  MiniMap,
  ReactFlow,
  useNodesInitialized,
  useReactFlow,
  ViewportPortal,
} from "@xyflow/react";
import { ArrowRight, Cable, MousePointerClick, Play } from "lucide-react";
import { useCallback, useEffect, useRef } from "react";
import { toast } from "sonner";
import { ArchEdgeView } from "@/components/editor/ArchEdgeView";
import { ArchNodeView, NODE_W } from "@/components/editor/ArchNodeView";
import { CanvasToolbar } from "@/components/editor/CanvasToolbar";
import { findPreset, parseDndPayload } from "@/components/build/presets";
import { DND_MIME } from "@/components/editor/Palette";
import { useIsDark } from "@/components/ui/theme-toggle";
import type { ArchEdge, ArchNode } from "@/lib/document";
import { checkConnection } from "@/lib/graph-rules";
import { CLUSTER_TYPE, membersOf } from "@/lib/kubernetes";
import { NODE_STATUS } from "@/lib/status";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

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

function reversed<T extends { source: string | null; target: string | null; sourceHandle?: string | null; targetHandle?: string | null }>(c: T): T {
  return { ...c, source: c.target, target: c.source, sourceHandle: c.targetHandle ?? null, targetHandle: c.sourceHandle ?? null };
}

interface Frame {
  id: string;
  label: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

const FRAME_PAD = 22;

/** A dashed outline around each Kubernetes cluster and the services whose pods it runs. */
function ClusterFrames() {
  // Serialized so the frames re-render only when the geometry changes.
  const json = useEditor((s) => {
    const frames: Frame[] = [];
    for (const c of s.nodes) {
      if (c.data.type !== CLUSTER_TYPE) continue;
      const group = [c, ...membersOf(s.nodes, c.id).map((m) => s.nodes.find((n) => n.id === m.id)!)];
      if (group.length < 2) continue;
      let [x0, y0, x1, y1] = [Number.POSITIVE_INFINITY, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, Number.NEGATIVE_INFINITY];
      for (const n of group) {
        const w = n.measured?.width ?? NODE_W;
        const h = n.measured?.height ?? 120;
        x0 = Math.min(x0, n.position.x);
        y0 = Math.min(y0, n.position.y);
        x1 = Math.max(x1, n.position.x + w);
        y1 = Math.max(y1, n.position.y + h);
      }
      frames.push({ id: c.id, label: c.data.label, x: x0 - FRAME_PAD, y: y0 - FRAME_PAD - 18, w: x1 - x0 + 2 * FRAME_PAD, h: y1 - y0 + 2 * FRAME_PAD + 18 });
    }
    return JSON.stringify(frames);
  });
  const frames = JSON.parse(json) as Frame[];
  if (frames.length === 0) return null;
  return (
    <ViewportPortal>
      {frames.map((f) => (
        <div
          key={f.id}
          className="pointer-events-none absolute left-0 top-0 rounded-2xl border-[1.5px] border-dashed"
          style={{
            transform: `translate(${f.x}px, ${f.y}px)`,
            width: f.w,
            height: f.h,
            zIndex: -1,
            borderColor: "color-mix(in srgb, var(--fam-compute) 55%, transparent)",
            background: "color-mix(in srgb, var(--fam-compute) 4%, transparent)",
          }}
          aria-hidden
        >
          <span className="absolute left-3 top-1.5 text-[11px] font-medium tracking-wide text-ink-3">
            {f.label} · Kubernetes
          </span>
        </div>
      ))}
    </ViewportPortal>
  );
}

function EmptyState() {
  const steps = [
    { icon: MousePointerClick, title: "add", text: "Drag a part from the left onto the canvas. Start with Clients: they send the traffic." },
    { icon: Cable, title: "connect", text: "Drag from a dot on any side of a part to another part." },
    { icon: Play, title: "simulate", text: "Press Simulate (top right) and watch where the design struggles." },
  ];
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center p-6">
      <div className="max-w-xl">
        <p className="text-[13px] font-medium text-accent">Empty canvas</p>
        <h2 className="mt-2 font-display text-[34px] leading-[1.05] tracking-[-0.035em] text-ink">
          Sketch a system, then <span className="text-gradient">test it</span>
        </h2>
        <ol className="mt-5 grid gap-2 sm:grid-cols-3">
          {steps.map((s, i) => (
            <li key={s.title} className="rounded-md border border-line bg-surface p-3">
              <div className="mb-1.5 flex items-center gap-2 font-mono text-xs text-ink-3">
                <span className="text-accent">{String(i + 1).padStart(2, "0")}</span>
                <span className="uppercase text-ink">{s.title}</span>
                <s.icon className="ml-auto size-3.5" aria-hidden />
              </div>
              <p className="text-xs leading-relaxed text-ink-2">{s.text}</p>
            </li>
          ))}
        </ol>
        <p className="mt-3 flex items-center gap-1 font-mono text-xs text-ink-3">
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
  const mode = useUi((s) => s.mode);
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

  // Keep the whole design in view: reframe when the workspace changes, and when the canvas is
  // resized (panels, timeline) until the user pans or zooms it themselves.
  const userMoved = useRef(false);
  useEffect(() => {
    if (!didInitialFit.current) return;
    userMoved.current = false;
    const t = setTimeout(() => void rf.fitView({ padding: FIT_PAD, maxZoom: 1, duration: 250 }), 60);
    return () => clearTimeout(t);
  }, [mode, rf]);
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    let t: ReturnType<typeof setTimeout> | null = null;
    const ro = new ResizeObserver(() => {
      if (userMoved.current || !didInitialFit.current) return;
      if (t) clearTimeout(t);
      t = setTimeout(() => void rf.fitView({ padding: FIT_PAD, maxZoom: 1 }), 120);
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      if (t) clearTimeout(t);
    };
  }, [rf]);

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

  // Links can start on either part: if A → B is not allowed but B → A is, draw B → A.
  const isValidConnection: IsValidConnection<ArchEdge> = useCallback((c) => {
    const s = useEditor.getState();
    return checkConnection(c, s.nodes, s.edges, s.catalog).ok || checkConnection(reversed(c), s.nodes, s.edges, s.catalog).ok;
  }, []);

  const onConnect = useCallback(
    (c: Connection) => {
      const s = useEditor.getState();
      const forward = checkConnection(c, s.nodes, s.edges, s.catalog);
      const res = forward.ok || !checkConnection(reversed(c), s.nodes, s.edges, s.catalog).ok ? connect(c) : connect(reversed(c));
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
    if (!check.ok && !checkConnection(reversed(conn), s.nodes, s.edges, s.catalog).ok) toast.error("Cannot link these components", { description: check.reason });
  }, []);

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      const raw = e.dataTransfer.getData(DND_MIME);
      if (!raw) return;
      e.preventDefault();
      const { type, presetId } = parseDndPayload(raw);
      const p = rf.screenToFlowPosition({ x: e.clientX, y: e.clientY });
      const pos = { x: Math.round(p.x - NODE_W / 2), y: Math.round(p.y - 40) };
      const ed = useEditor.getState();
      const preset = findPreset(ed.catalog, type, presetId);
      if (!(preset && ed.addPresetNode(type, pos, preset))) addNode(type, pos);
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
    <div ref={wrapRef} data-tour="canvas" className="relative h-full w-full bg-canvas" onDragOver={(e) => e.preventDefault()} onDrop={onDrop}>
      {/* Shared paint for node bodies: a faint top-down gradient gives shapes a sense of material. */}
      <svg width="0" height="0" className="absolute" aria-hidden>
        <defs>
          <linearGradient id="archsim-node-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--node-fill)" />
            <stop offset="100%" stopColor="var(--node-fill-2)" />
          </linearGradient>
        </defs>
      </svg>
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
        onMoveStart={(e) => {
          if (e) userMoved.current = true;
        }}
        isValidConnection={isValidConnection}
        connectionMode={ConnectionMode.Loose}
        connectionLineType={ConnectionLineType.SmoothStep}
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
        {/* A quiet dot grid, like a pro app's canvas. */}
        <Background id="dots" variant={BackgroundVariant.Dots} gap={16} size={1.3} color="var(--grid-major)" />
        <ClusterFrames />
        {nodes.length > 8 && (
        <MiniMap
          pannable
          zoomable
          nodeColor={miniColor}
          nodeBorderRadius={6}
          nodeStrokeWidth={0}
          position="top-right"
          style={{ width: 150, height: 96 }}
          className="!hidden lg:!block"
        />
        )}
      </ReactFlow>
      {nodes.length === 0 && <EmptyState />}
      <CanvasToolbar />
    </div>
  );
}
