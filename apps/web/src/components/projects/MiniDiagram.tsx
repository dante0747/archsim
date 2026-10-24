"use client";

import { kindColor, kindOf, kindSoft, shortName } from "@/lib/theme";
import type { ArchitectureDocument } from "@/lib/types";

const NW = 120;
const NH = 44;

/** Small read-only rendering of a design, colored by component kind, with gentle flow dots. */
export function MiniDiagram({ doc, animate = true }: { doc: ArchitectureDocument; animate?: boolean }) {
  const nodes = doc.architecture.nodes;
  const pos = (id: string, i: number) => doc.layout?.nodes?.[id] ?? { x: (i % 4) * 300, y: Math.floor(i / 4) * 180 };
  const placed = nodes.map((n, i) => ({ n, p: pos(n.id, i) }));
  if (placed.length === 0) {
    return <div className="flex h-full items-center justify-center font-mono text-[11px] text-ink-3">empty canvas</div>;
  }
  // Layout coordinates are for ~236px-wide nodes; scale positions so mini nodes keep spacing.
  const xs = placed.map((q) => q.p.x);
  const ys = placed.map((q) => q.p.y);
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  const scale = 0.5;
  const pts = new Map(placed.map((q) => [q.n.id, { x: (q.p.x - minX) * scale, y: (q.p.y - minY) * scale }]));
  const maxX = Math.max(...[...pts.values()].map((p) => p.x)) + NW;
  const maxY = Math.max(...[...pts.values()].map((p) => p.y)) + NH;
  const pad = 14;
  // Keep a minimum frame so small designs are not blown up relative to larger ones.
  const w = Math.max(maxX + pad * 2, 720);
  const h = Math.max(maxY + pad * 2, 260);
  const vb = `${-pad - (w - (maxX + pad * 2)) / 2} ${-pad - (h - (maxY + pad * 2)) / 2} ${w} ${h}`;
  return (
    <svg viewBox={vb} className="h-full w-full" role="img" aria-label={`Diagram with ${nodes.length} components`}>
      {doc.architecture.edges.map((e) => {
        const a = pts.get(e.source);
        const b = pts.get(e.target);
        if (!a || !b) return null;
        const x1 = a.x + NW;
        const y1 = a.y + NH / 2;
        const x2 = b.x;
        const y2 = b.y + NH / 2;
        const mx = (x1 + x2) / 2;
        const d = `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`;
        const miss = e.sourcePort === "miss";
        return (
          <g key={e.id}>
            <path d={d} fill="none" stroke="var(--line-strong)" strokeWidth={2} strokeDasharray={miss ? "6 4" : undefined} />
            {animate && (
              <circle r={3} fill={e.config.requestTypes.includes("write") && !e.config.requestTypes.includes("read") ? "var(--req-write)" : "var(--req-read)"}>
                <animateMotion dur="2.6s" repeatCount="indefinite" path={d} />
              </circle>
            )}
          </g>
        );
      })}
      {placed.map(({ n }) => {
        const p = pts.get(n.id)!;
        const k = kindOf(n.type);
        return (
          <g key={n.id} transform={`translate(${p.x},${p.y})`}>
            <rect width={NW} height={NH} rx={10} fill="var(--surface)" stroke={kindColor(k)} strokeOpacity={0.55} />
            <rect x={0} y={10} width={3} height={NH - 20} rx={1.5} fill={kindColor(k)} />
            <rect x={10} y={10} width={24} height={24} rx={6} fill={kindSoft(k, 22)} />
            <circle cx={22} cy={22} r={5} fill={kindColor(k)} />
            <text x={42} y={20} fontSize={11} fontWeight={600} fill="var(--ink)" fontFamily="var(--font-sans)">
              {(n.label || n.id).slice(0, 11)}
            </text>
            <text x={42} y={33} fontSize={8} fill={kindColor(k)} fontFamily="var(--font-mono)" letterSpacing="0.08em">
              {shortName(n.type, n.type).toUpperCase()}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
