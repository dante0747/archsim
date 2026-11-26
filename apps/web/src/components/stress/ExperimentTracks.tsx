"use client";

/**
 * Input tracks of the Stress timeline dock: the offered-load curve and one row per fault, edited
 * by direct manipulation on the shared time axis (see TimelineContext). Rendered by the dock
 * inside <TimelineProvider>; every row is `labelWidth` px of label followed by the plot area.
 *
 * Mouse: drag lines and handles; drag fault blocks to move, their edges to resize. Snaps to 1 s
 * (rates to a tidy step); hold Shift for free movement. Keyboard: every handle and block is
 * focusable; arrows nudge (Shift = larger step), Shift+←/→ resizes a fault, Delete removes it,
 * Enter edits it.
 */
import { Plus, Trash2 } from "lucide-react";
import { Popover } from "radix-ui";
import { useState } from "react";
import { rateAt } from "@/components/editor/WorkloadPanel";
import { AddFaultMenu, FaultFields, FaultIcon, removeFault, updateFault, useFaultContext } from "@/components/stress/faultUi";
import { useTimeline } from "@/components/timeline/TimelineContext";
import { compact } from "@/lib/format";
import { FAULT_META, faultLabel, faultParam, fmtWindow } from "@/lib/faults";
import type { Fault, Workload } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

const TRAFFIC_H = 68;
const PAD_T = 12;
const PAD_B = 8;
const FAULT_H = 30;
const EDGE_PX = 6;
const MAX_RPS = 100_000;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const round1 = (v: number) => Math.round(v * 10) / 10;
const snapT = (v: number, free: boolean) => (free ? round1(v) : Math.round(v));

/** A round axis maximum above `v`. */
function niceCeil(v: number): number {
  const p = 10 ** Math.floor(Math.log10(Math.max(v, 1)));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

function rateStep(yMax: number): number {
  return yMax <= 100 ? 1 : yMax <= 1000 ? 5 : yMax <= 5000 ? 25 : 100;
}

function peakRate(w: Workload): number {
  const p = w.pattern;
  if (p.kind === "ramp") return Math.max(p.startRps, p.endRps);
  if (p.kind === "burst") return Math.max(w.rps, p.burstRps);
  return w.rps;
}

function Row({ label, height, children, className }: { label: React.ReactNode; height: number; children: React.ReactNode; className?: string }) {
  const { labelWidth, plotWidth } = useTimeline();
  return (
    <div className={cn("flex border-b border-line", className)} style={{ height }}>
      <div className="flex shrink-0 flex-col justify-center gap-0.5 overflow-hidden border-r border-line px-3" style={{ width: labelWidth }}>
        {label}
      </div>
      <div className="relative shrink-0" style={{ width: plotWidth, height }}>
        {children}
      </div>
    </div>
  );
}

/** Floating mono readout shown while dragging or hovering a handle. */
function Readout({ x, y, text }: { x: number; y: number; text: string }) {
  const { plotWidth } = useTimeline();
  const left = clamp(x, 40, plotWidth - 40);
  return (
    <div
      className="pointer-events-none absolute z-20 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-sm border border-line-strong bg-surface px-1.5 py-0.5 font-mono text-[11px] text-ink shadow-float"
      style={{ left, top: Math.max(y - 6, 14) }}
      role="status"
    >
      {text}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Traffic track
// ---------------------------------------------------------------------------------------------

type TrafficHandle = "level" | "rampStart" | "rampEnd" | "burstTop" | "burstMove" | "burstStart" | "burstEnd";

interface TrafficDrag {
  kind: TrafficHandle;
  x0: number;
  y0: number;
  w0: Workload;
  yMax: number;
  key: string;
}


/** Patch the workload for a drag of `kind` by (dx, dy) px. */
function dragPatch(d: TrafficDrag, dx: number, dy: number, free: boolean, plotWidth: number, axisS: number, plotH: number): Partial<Workload> {
  const w = d.w0;
  const step = free ? 1 : rateStep(d.yMax);
  const dr = (-dy / plotH) * d.yMax;
  const dt = (dx / plotWidth) * axisS;
  const rate = (v: number) => clamp(Math.round((v + dr) / step) * step, 0, MAX_RPS);
  const p = w.pattern;
  switch (d.kind) {
    case "level":
      return { rps: rate(w.rps) };
    case "rampStart":
      return p.kind === "ramp" ? { pattern: { ...p, startRps: rate(p.startRps) } } : {};
    case "rampEnd":
      return p.kind === "ramp" ? { pattern: { ...p, endRps: rate(p.endRps) } } : {};
    case "burstTop":
      return p.kind === "burst" ? { pattern: { ...p, burstRps: rate(p.burstRps) } } : {};
    case "burstMove": {
      if (p.kind !== "burst") return {};
      const start = clamp(snapT(p.startS + dt, free), 0, Math.max(0, w.durationS - Math.min(p.durationS, w.durationS)));
      return { pattern: { ...p, startS: Math.min(start, w.durationS - 0.1) } };
    }
    case "burstStart": {
      if (p.kind !== "burst") return {};
      const end = p.startS + p.durationS;
      const start = clamp(snapT(p.startS + dt, free), 0, end - (free ? 0.1 : 1));
      return { pattern: { ...p, startS: start, durationS: round1(end - start) } };
    }
    case "burstEnd": {
      if (p.kind !== "burst") return {};
      const dur = clamp(snapT(p.durationS + dt, free), free ? 0.1 : 1, Math.max(0.1, w.durationS - p.startS));
      return { pattern: { ...p, durationS: dur } };
    }
  }
}

function readoutFor(kind: TrafficHandle, w: Workload): string {
  const p = w.pattern;
  if (kind === "level") return `${compact(w.rps)} req/s${p.kind === "burst" ? " baseline" : ""}`;
  if (p.kind === "ramp") return kind === "rampStart" ? `start ${compact(p.startRps)} req/s` : `end ${compact(p.endRps)} req/s`;
  if (p.kind === "burst") {
    if (kind === "burstTop") return `burst ${compact(p.burstRps)} req/s`;
    return `burst ${fmtWindow(p)} · ${compact(p.burstRps)} req/s`;
  }
  return "";
}

function TrafficTrack() {
  const { plotWidth, x, durationS: axisS } = useTimeline();
  const w = useEditor((s) => s.workload);
  const setWorkload = useEditor((s) => s.setWorkload);
  const [drag, setDrag] = useState<TrafficDrag | null>(null);
  const [hot, setHot] = useState<{ kind: TrafficHandle; x: number; y: number } | null>(null);
  const runS = Math.min(w.durationS, axisS);
  const yMax = drag?.yMax ?? niceCeil(Math.max(peakRate(w) * 1.3, 10));
  const plotH = TRAFFIC_H - PAD_T - PAD_B;
  const y = (r: number) => PAD_T + (1 - clamp(r / yMax, 0, 1.05)) * plotH;
  const p = w.pattern;

  // Curve of the offered load over the run.
  const pts: [number, number][] =
    p.kind === "ramp"
      ? [
          [0, p.startRps],
          [runS, rateAt(w, runS)],
        ]
      : p.kind === "burst"
        ? [
            [0, w.rps],
            [Math.min(p.startS, runS), w.rps],
            [Math.min(p.startS, runS), p.burstRps],
            [Math.min(p.startS + p.durationS, runS), p.burstRps],
            [Math.min(p.startS + p.durationS, runS), w.rps],
            [runS, w.rps],
          ]
        : [
            [0, w.rps],
            [runS, w.rps],
          ];
  const line = pts.map(([t, r], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(r).toFixed(1)}`).join(" ");
  const area = `${line} L${x(runS).toFixed(1)},${y(0)} L0,${y(0)} Z`;

  const start = (kind: TrafficHandle) => (e: React.PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    setDrag({ kind, x0: e.clientX, y0: e.clientY, w0: w, yMax, key: `traffic:${kind}:${Date.now()}` });
  };
  const move = (e: React.PointerEvent) => {
    if (!drag) return;
    const patch = dragPatch(drag, e.clientX - drag.x0, e.clientY - drag.y0, e.shiftKey, plotWidth, axisS, plotH);
    setWorkload(patch, drag.key);
    const rect = (e.currentTarget as Element).closest("[data-plot]")?.getBoundingClientRect();
    if (rect) setHot({ kind: drag.kind, x: e.clientX - rect.left, y: e.clientY - rect.top });
  };
  const end = (e: React.PointerEvent) => {
    if (!drag) return;
    (e.currentTarget as Element).releasePointerCapture?.(e.pointerId);
    setDrag(null);
    setHot(null);
  };
  const key = (kind: TrafficHandle) => (e: React.KeyboardEvent) => {
    const big = e.shiftKey;
    const step = rateStep(yMax) * (big ? 10 : 1);
    const tStep = big ? 5 : 1;
    const base: TrafficDrag = { kind, x0: 0, y0: 0, w0: w, yMax, key: `traffic-key:${kind}` };
    let patch: Partial<Workload> | null = null;
    const pxPerRate = plotH / yMax;
    const pxPerS = plotWidth / axisS;
    const isBurst = kind.startsWith("burst");
    // Up/down always change a rate; on the burst block they change its height.
    const vKind: TrafficHandle = isBurst ? "burstTop" : kind;
    const hKind: TrafficHandle = kind === "burstTop" ? "burstMove" : kind;
    if (e.key === "ArrowUp") patch = dragPatch({ ...base, kind: vKind }, 0, -step * pxPerRate, true, plotWidth, axisS, plotH);
    else if (e.key === "ArrowDown") patch = dragPatch({ ...base, kind: vKind }, 0, step * pxPerRate, true, plotWidth, axisS, plotH);
    else if (e.key === "ArrowLeft" && isBurst) patch = dragPatch({ ...base, kind: hKind }, -tStep * pxPerS, 0, false, plotWidth, axisS, plotH);
    else if (e.key === "ArrowRight" && isBurst) patch = dragPatch({ ...base, kind: hKind }, tStep * pxPerS, 0, false, plotWidth, axisS, plotH);
    if (!patch) return;
    e.preventDefault();
    e.stopPropagation();
    setWorkload(patch, base.key);
  };
  const handlers = (kind: TrafficHandle) => ({
    onPointerDown: start(kind),
    onPointerMove: move,
    onPointerUp: end,
    onPointerCancel: end,
    onPointerEnter: (e: React.PointerEvent) => {
      if (drag) return;
      const rect = (e.currentTarget as Element).closest("[data-plot]")?.getBoundingClientRect();
      if (rect) setHot({ kind, x: e.clientX - rect.left, y: e.clientY - rect.top });
    },
    onPointerLeave: () => !drag && setHot(null),
  });
  const handleBox = (cx: number, cy: number, kind: TrafficHandle, label: string, value: number) => (
    <rect
      x={cx - 4.5}
      y={cy - 4.5}
      width={9}
      height={9}
      rx={1.5}
      tabIndex={0}
      role="slider"
      aria-label={label}
      aria-valuenow={Math.round(value)}
      aria-valuemin={0}
      aria-valuemax={MAX_RPS}
      className="cursor-ns-resize fill-surface stroke-accent outline-none [stroke-width:1.5] hover:fill-accent focus-visible:fill-accent"
      onKeyDown={key(kind)}
      {...handlers(kind)}
    />
  );

  const label = (
    <span className="flex min-w-0 flex-col" title="Drag the line or its handles to reshape the traffic">
      <span className="text-xs text-ink-2">Traffic</span>
      <span className="tabular truncate font-mono text-[13px] font-semibold text-ink">
        {p.kind === "ramp" ? `${compact(p.startRps)}→${compact(p.endRps)}` : compact(w.rps)}
        <span className="ml-1 text-[11px] font-normal text-ink-3">req/s</span>
      </span>
    </span>
  );

  const bx0 = p.kind === "burst" ? x(Math.min(p.startS, runS)) : 0;
  const bx1 = p.kind === "burst" ? x(Math.min(p.startS + p.durationS, runS)) : 0;
  return (
    <Row label={label} height={TRAFFIC_H}>
      <div data-plot className="absolute inset-0">
        <svg width={plotWidth} height={TRAFFIC_H} className="block overflow-visible" role="img" aria-label="Offered load over the run">
          {w.warmupS > 0 && (
            <g>
              <rect x={0} y={0} width={x(Math.min(w.warmupS, runS))} height={TRAFFIC_H} fill="var(--warmup-wash)" />
              <text x={4} y={10} className="fill-ink-3 font-mono text-[11px]">
                warm-up
              </text>
            </g>
          )}
          {runS < axisS && (
            <g>
              <rect x={x(runS)} y={0} width={plotWidth - x(runS)} height={TRAFFIC_H} fill="var(--warmup-wash)" />
              <text x={x(runS) + 4} y={10} className="fill-ink-3 font-mono text-[11px]">
                drain
              </text>
            </g>
          )}
          {(w.faults ?? []).map((f) => (
            <rect
              key={f.id}
              x={x(f.startS)}
              y={0}
              width={Math.max(1, x(f.durationS))}
              height={TRAFFIC_H}
              fill={`color-mix(in srgb, ${FAULT_META[f.kind].color} 8%, transparent)`}
            />
          ))}
          <line x1={0} x2={plotWidth} y1={y(0)} y2={y(0)} stroke="var(--axis)" strokeWidth={1} />
          <line x1={0} x2={plotWidth} y1={y(yMax / 2)} y2={y(yMax / 2)} stroke="var(--grid)" strokeDasharray="2 4" strokeWidth={1} />
          {/* the scale ends where the drain band starts, whose own label runs right from there */}
          <text x={(runS < axisS ? x(runS) : plotWidth) - 4} y={PAD_T - 3} textAnchor="end" className="fill-ink-3 font-mono text-[11px]">
            {compact(yMax)}/s
          </text>
          <path d={area} fill="var(--accent)" fillOpacity={0.09} />
          <path d={line} fill="none" stroke="var(--accent)" strokeWidth={1.5} strokeLinejoin="round" />

          {/* constant level / burst baseline: wide invisible hit line */}
          {p.kind !== "ramp" && (
            <line
              x1={0}
              x2={x(runS)}
              y1={y(w.rps)}
              y2={y(w.rps)}
              stroke="transparent"
              strokeWidth={12}
              className="cursor-ns-resize"
              {...handlers("level")}
            />
          )}
          {p.kind === "constant" && handleBox(x(runS) / 2, y(w.rps), "level", "Requests per second", w.rps)}
          {p.kind === "ramp" && (
            <>
              {handleBox(Math.max(5, x(0)), y(p.startRps), "rampStart", "Ramp start rate", p.startRps)}
              {handleBox(Math.min(x(runS), plotWidth) - 5, y(p.endRps), "rampEnd", "Ramp end rate", p.endRps)}
            </>
          )}
          {p.kind === "burst" && (
            <>
              {handleBox(Math.max(5, bx0 / 2), y(w.rps), "level", "Baseline requests per second", w.rps)}
              <rect
                x={bx0}
                y={y(p.burstRps)}
                width={Math.max(2, bx1 - bx0)}
                height={Math.max(2, y(Math.min(w.rps, p.burstRps)) - y(p.burstRps))}
                fill="transparent"
                tabIndex={0}
                role="slider"
                aria-label={`Burst from ${fmtWindow(p)}; arrows move it`}
                aria-valuenow={p.startS}
                aria-valuemin={0}
                aria-valuemax={w.durationS}
                className="cursor-grab outline-none focus-visible:stroke-accent active:cursor-grabbing"
                onKeyDown={key("burstMove")}
                {...handlers("burstMove")}
              />
              <rect x={bx0 - EDGE_PX / 2} y={y(p.burstRps)} width={EDGE_PX} height={Math.max(6, y(w.rps) - y(p.burstRps))} fill="transparent" className="cursor-ew-resize" {...handlers("burstStart")} />
              <rect
                x={bx1 - EDGE_PX / 2}
                y={y(p.burstRps)}
                width={EDGE_PX}
                height={Math.max(6, y(w.rps) - y(p.burstRps))}
                fill="transparent"
                tabIndex={0}
                role="slider"
                aria-label="Burst length; arrows resize"
                aria-valuenow={p.durationS}
                aria-valuemin={0}
                aria-valuemax={w.durationS}
                className="cursor-ew-resize outline-none"
                onKeyDown={key("burstEnd")}
                {...handlers("burstEnd")}
              />
              {handleBox((bx0 + bx1) / 2, y(p.burstRps), "burstTop", "Burst rate", p.burstRps)}
            </>
          )}
        </svg>
        {hot && <Readout x={hot.x} y={hot.y} text={readoutFor(hot.kind, w)} />}
      </div>
    </Row>
  );
}

// ---------------------------------------------------------------------------------------------
// Fault rows
// ---------------------------------------------------------------------------------------------

type FaultDragKind = "move" | "start" | "end";

function FaultRow({ fault, name, invalid, runS }: { fault: Fault; name: string; invalid: boolean; runS: number }) {
  const { x, plotWidth, durationS: axisS } = useTimeline();
  const ctx = useFaultContext();
  const [drag, setDrag] = useState<{ kind: FaultDragKind; x0: number; f0: Fault; key: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const [hover, setHover] = useState(false);
  const meta = FAULT_META[fault.kind];
  const left = x(fault.startS);
  const width = Math.max(4, x(fault.durationS));

  const apply = (kind: FaultDragKind, f0: Fault, dt: number, free: boolean, key: string) => {
    const end0 = f0.startS + f0.durationS;
    const minLen = free ? 0.1 : 1;
    if (kind === "move") {
      const startS = clamp(snapT(f0.startS + dt, free), 0, Math.max(0, runS - Math.min(f0.durationS, runS)));
      updateFault(fault.id, { startS }, key);
    } else if (kind === "start") {
      const startS = clamp(snapT(f0.startS + dt, free), 0, end0 - minLen);
      updateFault(fault.id, { startS, durationS: round1(end0 - startS) }, key);
    } else {
      const durationS = clamp(snapT(f0.durationS + dt, free), minLen, Math.max(minLen, runS - f0.startS));
      updateFault(fault.id, { durationS }, key);
    }
  };

  const start = (kind: FaultDragKind) => (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    setDrag({ kind, x0: e.clientX, f0: fault, key: `fault-drag:${fault.id}:${Date.now()}` });
  };
  const move = (e: React.PointerEvent) => {
    if (!drag) return;
    apply(drag.kind, drag.f0, ((e.clientX - drag.x0) / plotWidth) * axisS, e.shiftKey, drag.key);
  };
  const end = (e: React.PointerEvent) => {
    if (!drag) return;
    (e.currentTarget as Element).releasePointerCapture?.(e.pointerId);
    setDrag(null);
  };
  const handlers = (kind: FaultDragKind) => ({ onPointerDown: start(kind), onPointerMove: move, onPointerUp: end, onPointerCancel: end });

  const onKeyDown = (e: React.KeyboardEvent) => {
    const step = 1;
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      const dt = e.key === "ArrowLeft" ? -step : step;
      apply(e.shiftKey ? "end" : "move", fault, dt, false, `fault-key:${fault.id}`);
    } else if (e.key === "Delete" || e.key === "Backspace") {
      removeFault(fault.id);
    } else if (e.key === "Enter") {
      setEditing(true);
    } else return;
    e.preventDefault();
    e.stopPropagation();
  };

  const label = (
    <div className="group flex min-w-0 items-center gap-1.5">
      <FaultIcon kind={fault.kind} />
      <span className={cn("min-w-0 flex-1 truncate font-mono text-[11px]", invalid ? "text-danger-ink" : "text-ink-2")} title={invalid ? `Target ${fault.target} no longer exists` : name}>
        {fault.label || name}
      </span>
      <button
        type="button"
        onClick={() => removeFault(fault.id)}
        className="shrink-0 rounded-sm p-0.5 text-ink-3 opacity-0 transition-opacity hover:text-danger-ink focus-visible:opacity-100 group-hover:opacity-100"
        aria-label={`Remove ${faultLabel(fault, ctx.names)}`}
      >
        <Trash2 className="size-3" />
      </button>
    </div>
  );

  const readout = `${meta.label.toLowerCase()} ${faultParam(fault)} · ${fmtWindow(fault)} · ${fault.durationS}s`;
  return (
    <Row label={label} height={FAULT_H}>
      <Popover.Root open={editing} onOpenChange={setEditing}>
        <Popover.Anchor asChild>
          <div
            tabIndex={0}
            role="button"
            aria-label={`${faultLabel(fault, ctx.names)}, ${fmtWindow(fault)}. Arrows move, Shift+arrows resize, Delete removes, Enter edits.`}
            onKeyDown={onKeyDown}
            onDoubleClick={() => setEditing(true)}
            onPointerEnter={() => setHover(true)}
            onPointerLeave={() => setHover(false)}
            className={cn(
              "group/block absolute top-1/2 flex h-[22px] -translate-y-1/2 select-none items-center overflow-hidden rounded-sm border outline-none focus-visible:ring-2 focus-visible:ring-accent/60",
              invalid && "border-dashed",
            )}
            style={{
              left,
              width,
              borderColor: invalid ? "var(--status-critical)" : meta.color,
              background: `color-mix(in srgb, ${meta.color} ${drag ? 30 : 20}%, transparent)`,
            }}
          >
            <div className="absolute inset-y-0 left-0 z-10 cursor-ew-resize" style={{ width: EDGE_PX }} {...handlers("start")} />
            <div
              className="flex h-full min-w-0 flex-1 cursor-grab items-center gap-1 px-2 font-mono text-[11px] text-ink active:cursor-grabbing"
              {...handlers("move")}
            >
              <span className="truncate font-semibold">{faultParam(fault)}</span>
              {width > 120 && <span className="truncate text-ink-2">{fmtWindow(fault)}</span>}
            </div>
            <div className="absolute inset-y-0 right-0 z-10 cursor-ew-resize" style={{ width: EDGE_PX }} {...handlers("end")} />
          </div>
        </Popover.Anchor>
        <Popover.Portal>
          <Popover.Content
            side="top"
            align="start"
            sideOffset={8}
            collisionPadding={12}
            className="z-50 w-[320px] rounded-lg border border-line-strong bg-surface p-3 shadow-float"
            onOpenAutoFocus={(e) => e.preventDefault()}
          >
            <div className="mb-2.5 flex items-center gap-2">
              <FaultIcon kind={fault.kind} />
              <p className="min-w-0 flex-1 truncate font-mono text-xs font-semibold text-ink">{faultLabel(fault, ctx.names)}</p>
              <button type="button" onClick={() => removeFault(fault.id)} className="rounded-sm p-1 text-ink-3 hover:bg-critical-soft hover:text-danger-ink" aria-label="Remove failure">
                <Trash2 className="size-3.5" />
              </button>
            </div>
            <FaultFields fault={fault} targets={ctx.targets} durationS={ctx.workload.durationS} idPrefix={`track-${fault.id}`} />
            <p className="mt-2.5 text-xs leading-4 text-ink-3">{meta.description}</p>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      {(hover || drag) && !editing && <Readout x={left + width / 2} y={4} text={readout} />}
    </Row>
  );
}

function AddFaultRow() {
  const { faults } = useFaultContext();
  return (
    <Row
      height={28}
      label={
        <AddFaultMenu>
          <button
            type="button"
            className="flex w-fit items-center gap-1 rounded-sm px-1 py-0.5 font-mono text-[11px] text-ink-3 hover:bg-surface-2 hover:text-accent"
          >
            <Plus className="size-3" /> add failure
          </button>
        </AddFaultMenu>
      }
    >
      <p className="pointer-events-none absolute inset-0 flex items-center px-2 font-mono text-[11px] text-ink-3">
        {faults.length === 0 ? "" : "drag to move or resize"}
      </p>
    </Row>
  );
}

/** Traffic and fault tracks of the experiment (no props; renders inside <TimelineProvider>). */
export function ExperimentTracks() {
  const { faults, names, targets, workload } = useFaultContext();
  const { durationS: axisS } = useTimeline();
  const runS = Math.min(workload.durationS, axisS);
  return (
    <div role="group" aria-label="Next run: traffic and failures">
      <TrafficTrack />
      {faults.map((f) => (
        <FaultRow key={f.id} fault={f} name={faultLabel(f, names)} invalid={!targets[f.kind].some((t) => t.id === f.target)} runS={runS} />
      ))}
      <AddFaultRow />
    </div>
  );
}
