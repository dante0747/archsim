"use client";

/**
 * A template's design drawn like the editor, joined by curved wires with traffic flowing along
 * them in the template's colour. Two styles: part cards (logo, name, technology) for roomy
 * previews, and logo tiles with a short label for small cards. The layout keeps the design's
 * columns and rows but closes the gaps, so wide designs stay legible. Read only; fits its
 * container (SVG viewBox, parts in foreignObject).
 */
import { TechIcon } from "@/components/TechIcon";
import { partTechnology } from "@/components/templates/templates-meta";
import { KIND_LABEL, kindOf, technologyLabel } from "@/lib/theme";
import type { ArchitectureDocument } from "@/lib/types";

const r1 = (n: number) => Math.round(n * 10) / 10;

/** Index of each value among the distinct values (snapped to ``snap`` canvas pixels). */
function ranks(values: number[], snap: number): number[] {
  const keys = [...new Set(values.map((v) => Math.round(v / snap)))].sort((a, b) => a - b);
  return values.map((v) => keys.indexOf(Math.round(v / snap)));
}

export function TemplatePreview({ doc, hue, compact = false }: { doc: ArchitectureDocument; hue: string; compact?: boolean }) {
  const list = doc.architecture.nodes;
  if (!list.length) return null;
  const W = compact ? 56 : 152;
  const H = compact ? 56 : 52;
  const colGap = compact ? 52 : 64;
  const rowGap = compact ? 20 : 30;
  const labelH = compact ? 18 : 0;
  const raw = list.map((n, i) => doc.layout?.nodes?.[n.id] ?? { x: (i % 4) * 330, y: Math.floor(i / 4) * 220 });
  const cols = ranks(raw.map((p) => p.x), 120);
  const rows = ranks(raw.map((p) => p.y), 150);
  const nodes = list.map((n, i) => {
    const tech = partTechnology(n.type, n.config);
    const kind = kindOf(n.type);
    return {
      id: n.id,
      x: cols[i] * (W + colGap),
      y: rows[i] * (H + labelH + rowGap),
      label: n.label || n.id,
      tech,
      kind,
      sub: technologyLabel(undefined, tech) ?? KIND_LABEL[kind],
    };
  });
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const pad = compact ? 14 : 20;
  const maxX = Math.max(...nodes.map((n) => n.x)) + W + pad;
  const maxY = Math.max(...nodes.map((n) => n.y)) + H + labelH + pad;
  // small designs keep a minimum canvas so two parts don't fill the whole card
  const vw = Math.max(maxX + pad, compact ? 460 : 640);
  const vh = Math.max(maxY + pad, compact ? 190 : 260);
  const ox = -pad - (vw - (maxX + pad)) / 2;
  const oy = -pad - (vh - (maxY + pad)) / 2;

  return (
    <svg
      viewBox={`${r1(ox)} ${r1(oy)} ${r1(vw)} ${r1(vh)}`}
      className="h-full w-full"
      role="img"
      aria-label={`Design with ${list.length} parts and ${doc.architecture.edges.length} links`}
    >
      {doc.architecture.edges.map((e) => {
        const s = byId.get(e.source);
        const t = byId.get(e.target);
        if (!s || !t) return null;
        const sx = s.x + W;
        const sy = s.y + H / 2;
        const tx = t.x;
        const ty = t.y + H / 2;
        const dx = Math.max(compact ? 22 : 30, Math.abs(tx - sx) / 2);
        const d = `M${r1(sx)},${r1(sy)} C${r1(sx + dx)},${r1(sy)} ${r1(tx - dx)},${r1(ty)} ${r1(tx)},${r1(ty)}`;
        const miss = e.sourcePort === "miss";
        return (
          <g key={e.id}>
            <path d={d} fill="none" stroke="var(--wire)" strokeWidth={1.6} strokeDasharray={miss ? "5 5" : undefined} opacity={0.6} />
            <path d={d} fill="none" stroke={hue} strokeWidth={2.4} strokeLinecap="round" className="edge-flow" />
            <circle cx={sx} cy={sy} r={3} fill="var(--surface)" stroke={hue} strokeWidth={1.5} />
            <circle cx={tx} cy={ty} r={3} fill="var(--surface)" stroke={hue} strokeWidth={1.5} />
          </g>
        );
      })}
      {nodes.map((n) =>
        compact ? (
          <foreignObject key={n.id} x={r1(n.x - colGap / 2 + 4)} y={r1(n.y)} width={W + colGap - 8} height={H + labelH + 4}>
            <div className="flex h-full flex-col items-center">
              <div className="flex size-14 items-center justify-center rounded-2xl border border-line-strong bg-surface shadow-[0_1px_2px_rgba(0,0,0,0.06)]">
                <TechIcon technology={n.tech} kind={n.kind} size={26} tile className="rounded-xl" />
              </div>
              <p className="mt-1 w-full truncate text-center text-[11px] font-medium leading-tight text-ink-2" title={n.label}>
                {n.label}
              </p>
            </div>
          </foreignObject>
        ) : (
          <foreignObject key={n.id} x={r1(n.x)} y={r1(n.y)} width={W} height={H}>
            <div className="flex h-full items-center gap-2 rounded-[10px] border border-line-strong bg-surface px-2.5 shadow-[0_1px_2px_rgba(0,0,0,0.06)]">
              <TechIcon technology={n.tech} kind={n.kind} size={16} tile className="shrink-0 rounded-md" />
              <div className="min-w-0 leading-tight">
                <p className="truncate text-[12px] font-semibold text-ink">{n.label}</p>
                <p className="truncate text-[10px] text-ink-3">{n.sub}</p>
              </div>
            </div>
          </foreignObject>
        ),
      )}
    </svg>
  );
}
