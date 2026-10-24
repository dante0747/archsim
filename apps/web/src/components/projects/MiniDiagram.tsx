"use client";

import { useId, useSyncExternalStore } from "react";
import { SHAPE_HANDLE, SHAPE_OF, SHAPE_PADDING, ShapeSvg } from "@/components/shapes";
import { KIND_TAG, type Kind, kindOf } from "@/lib/theme";
import type { ArchitectureDocument, RequestType } from "@/lib/types";

// ---- reduced motion ---------------------------------------------------------------------------

const RM_QUERY = "(prefers-reduced-motion: reduce)";

function subscribeReducedMotion(cb: () => void) {
  const m = window.matchMedia(RM_QUERY);
  m.addEventListener("change", cb);
  return () => m.removeEventListener("change", cb);
}

/** True when the user asked for less motion (false during server rendering). */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia(RM_QUERY).matches,
    () => false,
  );
}

// ---- orthogonal wires -------------------------------------------------------------------------

interface Pt {
  x: number;
  y: number;
}

const r1 = (n: number) => Math.round(n * 10) / 10;

/** Right-angle route from an output (right side) to an input (left side). */
function orthoRoute(a: Pt, b: Pt, gap: number): Pt[] {
  if (b.x - a.x >= 2 * gap) {
    if (Math.abs(a.y - b.y) < 0.5) return [a, b];
    const mx = (a.x + b.x) / 2;
    return [a, { x: mx, y: a.y }, { x: mx, y: b.y }, b];
  }
  // The target sits behind the source: loop around through the space between them.
  const my = (a.y + b.y) / 2;
  return [a, { x: a.x + gap, y: a.y }, { x: a.x + gap, y: my }, { x: b.x - gap, y: my }, { x: b.x - gap, y: b.y }, b];
}

/** SVG path through the points with small rounded corners. */
function roundedPath(pts: Pt[], radius: number): string {
  let d = `M${r1(pts[0].x)},${r1(pts[0].y)}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const p0 = pts[i - 1];
    const p = pts[i];
    const p1 = pts[i + 1];
    const d0 = Math.hypot(p.x - p0.x, p.y - p0.y);
    const d1 = Math.hypot(p1.x - p.x, p1.y - p.y);
    const rr = Math.min(radius, d0 / 2, d1 / 2);
    if (rr < 0.5 || d0 === 0 || d1 === 0) {
      d += ` L${r1(p.x)},${r1(p.y)}`;
      continue;
    }
    const ax = p.x + ((p0.x - p.x) / d0) * rr;
    const ay = p.y + ((p0.y - p.y) / d0) * rr;
    const bx = p.x + ((p1.x - p.x) / d1) * rr;
    const by = p.y + ((p1.y - p.y) / d1) * rr;
    d += ` L${r1(ax)},${r1(ay)} Q${r1(p.x)},${r1(p.y)} ${r1(bx)},${r1(by)}`;
  }
  const last = pts[pts.length - 1];
  return `${d} L${r1(last.x)},${r1(last.y)}`;
}

// ---- generic schematic ------------------------------------------------------------------------

export interface DiagramNode {
  id: string;
  kind: Kind;
  label: string;
  x: number;
  y: number;
  /** Replicas, drawn as stacked layers on compute nodes. */
  layers?: number;
  /** Small result annotation under the node (illustrations only). */
  note?: { text: string; color: string };
}

export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  types: RequestType[];
  miss?: boolean;
}

export interface SchematicDiagramProps {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  nodeW: number;
  nodeH: number;
  /** Decoration scale passed to ShapeSvg. */
  unit: number;
  /** Font size in diagram units. */
  font: number;
  /** "full": label + kind tag; "tag": kind tag only (thumbnails). */
  text: "full" | "tag";
  strokeWidth?: number;
  minW?: number;
  minH?: number;
  animate?: boolean;
  className?: string;
  ariaLabel?: string;
}

/** Read-only schematic: line-art shapes per kind, orthogonal wires, subtle accent flow dots. */
export function SchematicDiagram({
  nodes,
  edges,
  nodeW,
  nodeH,
  unit,
  font,
  text,
  strokeWidth = 1.5,
  minW = 0,
  minH = 0,
  animate = true,
  className = "h-full w-full",
  ariaLabel,
}: SchematicDiagramProps) {
  const uid = useId().replace(/[^a-zA-Z0-9]/g, "");
  const reduced = useReducedMotion();
  const moving = animate && !reduced;
  if (nodes.length === 0) {
    return <div className="flex h-full items-center justify-center font-mono text-[11px] text-ink-3">empty canvas</div>;
  }

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const noteH = nodes.some((n) => n.note) ? font * 2.2 : 0;
  const minX = Math.min(...nodes.map((n) => n.x));
  const minY = Math.min(...nodes.map((n) => n.y));
  const maxX = Math.max(...nodes.map((n) => n.x)) + nodeW;
  const maxY = Math.max(...nodes.map((n) => n.y + (n.note ? noteH : 0))) + nodeH;
  const pad = Math.max(10, font);
  const cw = maxX - minX + pad * 2;
  const ch = maxY - minY + pad * 2;
  const w = Math.max(cw, minW);
  const h = Math.max(ch, minH);
  const vb = `${r1(minX - pad - (w - cw) / 2)} ${r1(minY - pad - (h - ch) / 2)} ${r1(w)} ${r1(h)}`;
  const arrow = `arrow-${uid}`;

  const anchor = (n: DiagramNode, side: "in" | "out"): Pt => {
    const hd = SHAPE_HANDLE[SHAPE_OF[n.kind]];
    return side === "out"
      ? { x: n.x + nodeW - hd.right * unit, y: n.y + nodeH / 2 + hd.dy * unit }
      : { x: n.x + hd.left * unit, y: n.y + nodeH / 2 + hd.dy * unit };
  };

  const dot = font * 0.28;
  const dur = 3.2;

  return (
    <svg
      viewBox={vb}
      className={className}
      role={ariaLabel ? "img" : undefined}
      aria-label={ariaLabel}
      aria-hidden={ariaLabel ? undefined : true}
    >
      <defs>
        <marker
          id={arrow}
          viewBox="0 0 8 8"
          refX="7.5"
          refY="4"
          markerWidth={font * 0.42}
          markerHeight={font * 0.42}
          markerUnits="userSpaceOnUse"
          orient="auto"
        >
          <path d="M0,0.8 L8,4 L0,7.2 Z" fill="var(--wire)" />
        </marker>
      </defs>

      {edges.map((e, ei) => {
        const s = byId.get(e.source);
        const t = byId.get(e.target);
        if (!s || !t) return null;
        const d = roundedPath(orthoRoute(anchor(s, "out"), anchor(t, "in"), font * 0.9), font * 0.45);
        const types = [...new Set(e.types)].slice(0, 2);
        return (
          <g key={e.id}>
            <path
              d={d}
              fill="none"
              stroke="var(--wire)"
              strokeWidth={strokeWidth}
              strokeDasharray={e.miss ? `${strokeWidth * 3} ${strokeWidth * 2.5}` : undefined}
              strokeLinejoin="round"
              markerEnd={`url(#${arrow})`}
            />
            {moving &&
              types.map((ty, k) => {
                const begin = `${r1(-((k * dur) / types.length + ei * 0.53))}s`;
                const motion = <animateMotion dur={`${dur}s`} begin={begin} repeatCount="indefinite" path={d} />;
                return ty === "write" ? (
                  <rect key={ty} x={-dot} y={-dot} width={dot * 2} height={dot * 2} fill="var(--accent)" opacity={0.9}>
                    {motion}
                  </rect>
                ) : (
                  <circle key={ty} r={dot} fill="var(--accent)" opacity={0.9}>
                    {motion}
                  </circle>
                );
              })}
          </g>
        );
      })}

      {nodes.map((n) => {
        const shape = SHAPE_OF[n.kind];
        const p = SHAPE_PADDING[shape];
        const left = p.left * unit;
        const right = nodeW - p.right * unit;
        const top = p.top * unit;
        const bottom = nodeH - p.bottom * unit;
        const cx = (left + right) / 2;
        const cy = (top + bottom) / 2;
        const maxChars = Math.max(3, Math.floor((right - left) / (font * 0.62)));
        const label = n.label.length > maxChars ? `${n.label.slice(0, maxChars - 1)}…` : n.label;
        const tag = KIND_TAG[n.kind];
        return (
          <g key={n.id} transform={`translate(${r1(n.x)},${r1(n.y)})`}>
            <title>{n.label}</title>
            <ShapeSvg shape={shape} w={nodeW} h={nodeH} unit={unit} strokeWidth={strokeWidth} layers={n.layers ?? 1} />
            {text === "full" ? (
              <>
                <text
                  x={cx}
                  y={cy - font * 0.12}
                  textAnchor="middle"
                  fontSize={font}
                  fontWeight={600}
                  fill="var(--ink)"
                  fontFamily="var(--font-mono)"
                >
                  {label}
                </text>
                <text
                  x={cx}
                  y={cy + font * 0.95}
                  textAnchor="middle"
                  fontSize={font * 0.74}
                  fill="var(--ink-3)"
                  fontFamily="var(--font-mono)"
                  letterSpacing="0.06em"
                >
                  {tag}
                </text>
              </>
            ) : (
              <text
                x={cx}
                y={cy + font * 0.35}
                textAnchor="middle"
                fontSize={font}
                fill="var(--ink-2)"
                fontFamily="var(--font-mono)"
                letterSpacing="0.04em"
              >
                {tag}
              </text>
            )}
            {n.note && (
              <g transform={`translate(${r1(cx)},${r1(nodeH + font * 0.9)})`}>
                <rect
                  x={-font * 3.4}
                  y={-font * 0.75}
                  width={font * 6.8}
                  height={font * 1.5}
                  rx={font * 0.2}
                  fill="var(--surface)"
                  stroke="var(--line-strong)"
                  strokeWidth={strokeWidth * 0.8}
                />
                <circle cx={-font * 2.55} cy={0} r={font * 0.24} fill={n.note.color} className="pulse-soft" />
                <text
                  x={font * 0.3}
                  y={font * 0.3}
                  textAnchor="middle"
                  fontSize={font * 0.78}
                  fill="var(--ink)"
                  fontFamily="var(--font-mono)"
                >
                  {n.note.text}
                </text>
              </g>
            )}
          </g>
        );
      })}
    </svg>
  );
}

// ---- document thumbnail -----------------------------------------------------------------------

/** Positions are stored for full-size canvas nodes (~236px wide); this keeps the proportions. */
const POS_SCALE = 0.4;

/** Small read-only rendering of a design: shapes per kind, orthogonal wires, gentle flow dots. */
export function MiniDiagram({ doc, animate = true }: { doc: ArchitectureDocument; animate?: boolean }) {
  const list = doc.architecture.nodes;
  const pos = (id: string, i: number) => doc.layout?.nodes?.[id] ?? { x: (i % 4) * 330, y: Math.floor(i / 4) * 220 };
  const nodes: DiagramNode[] = list.map((n, i) => {
    const p = pos(n.id, i);
    const replicas = n.config.replicas;
    return {
      id: n.id,
      kind: kindOf(n.type),
      label: n.label || n.id,
      x: p.x * POS_SCALE,
      y: p.y * POS_SCALE,
      layers: typeof replicas === "number" ? replicas : 1,
    };
  });
  const edges: DiagramEdge[] = doc.architecture.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    types: e.config.requestTypes,
    miss: e.sourcePort === "miss",
  }));
  return (
    <SchematicDiagram
      nodes={nodes}
      edges={edges}
      nodeW={96}
      nodeH={44}
      unit={0.5}
      font={15}
      text="tag"
      strokeWidth={2}
      minW={560}
      minH={190}
      animate={animate}
      ariaLabel={`Diagram with ${list.length} components and ${edges.length} links`}
    />
  );
}
