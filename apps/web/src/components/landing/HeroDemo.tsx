"use client";

/**
 * The landing page's product shot: a scripted (not simulated) replay of a design losing its cache,
 * drawn like the editor: an LCD readout, part cards with level meters, and the run as arrangement
 * lanes, one per part in its family colour. Traffic moves along the links, the API autoscales, the
 * database saturates, the verdict flips and recovers. Loops while visible; one still frame when
 * reduced motion is preferred.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { TechIcon } from "@/components/TechIcon";
import { Meter } from "@/components/ui/meter";
import { KIND_COLOR, type Kind } from "@/lib/theme";
import { cn } from "@/lib/utils";

const T = 60; // scripted seconds
const LOOP_MS = 16000;
const OUTAGE: [number, number] = [20, 32];
const SLO_P95 = 300;
const STILL = 26;

const smooth = (a: number, b: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};
const wobble = (t: number, k: number) => 0.5 + 0.5 * Math.sin(t * 1.7 + k) * Math.cos(t * 0.63 + k * 2.1);

/** Scripted metrics at second t. */
function frame(t: number) {
  const load = 300 + 200 * smooth(0, 40, t);
  const down = t >= OUTAGE[0] && t < OUTAGE[1];
  const recover = down ? 1 : t >= OUTAGE[1] ? Math.max(0, 1 - (t - OUTAGE[1]) / 6) : 0;
  const l = load / 500;
  const n = (k: number, amp = 0.04) => (wobble(t, k) - 0.5) * amp * 2;
  const util: Record<NodeId, number> = {
    clients: 0,
    lb: 0.14 + 0.1 * l + n(1, 0.01),
    api: Math.min(1, 0.38 + 0.32 * l + n(2) + 0.42 * recover),
    cache: down ? 0 : 0.2 * l + n(3, 0.02),
    db: Math.min(1, 0.2 + 0.12 * l + n(4) + 0.8 * recover),
  };
  const replicas = down && t > OUTAGE[0] + 4 ? 5 : t > OUTAGE[1] + 10 ? 3 : t >= OUTAGE[1] ? 5 : 3;
  const p95 = 46 + 28 * l + n(5, 6) * 3 + recover * (960 + 150 * Math.sin(t * 2.3));
  const success = down ? 0.62 + 0.05 * Math.sin(t) : 1 - 0.03 * recover;
  return { load, down, recover, util, replicas, p95, success };
}
type Frame = ReturnType<typeof frame>;

type NodeId = "clients" | "lb" | "api" | "cache" | "db";
const W = 184;
const H = 86;
const NODES: { id: NodeId; kind: Kind; tech: string | null; name: string; sub: string; x: number; y: number }[] = [
  { id: "clients", kind: "traffic", tech: "mobile", name: "Shoppers", sub: "Mobile + web", x: 16, y: 113 },
  { id: "lb", kind: "network", tech: "nginx", name: "Edge", sub: "Nginx", x: 236, y: 113 },
  { id: "api", kind: "compute", tech: "go", name: "Checkout", sub: "Go · Kubernetes", x: 456, y: 113 },
  { id: "cache", kind: "cache", tech: "redis", name: "Cart cache", sub: "Redis", x: 690, y: 12 },
  { id: "db", kind: "database", tech: "postgresql", name: "Orders DB", sub: "PostgreSQL", x: 690, y: 214 },
];
const STAGE_W = 896;
const STAGE_H = 312;
const at = (id: NodeId) => NODES.find((n) => n.id === id)!;
const mid = (id: NodeId) => at(id).y + H / 2;

type Link = { id: string; pts: [number, number][]; rate: (f: Frame) => number; write?: boolean; miss?: boolean };
const LINKS: Link[] = [
  { id: "c-lb", pts: [[16 + W, mid("clients")], [236, mid("lb")]], rate: (f) => f.load },
  { id: "lb-api", pts: [[236 + W, mid("lb")], [456, mid("api")]], rate: (f) => f.load },
  { id: "api-cache", pts: [[456 + W, mid("api")], [664, mid("api")], [664, mid("cache")], [690, mid("cache")]], rate: (f) => (f.down ? 0 : f.load * 0.9) },
  { id: "api-db", pts: [[456 + W, mid("api")], [664, mid("api")], [664, mid("db")], [690, mid("db")]], rate: (f) => f.load * (f.down ? 1 : 0.1), write: true },
  { id: "miss", pts: [[690 + W, mid("cache")], [888, mid("cache")], [888, mid("db")], [690 + W, mid("db")]], rate: (f) => (f.down ? 0 : f.load * 0.16), miss: true },
];

/** A path through orthogonal points with rounded corners. */
function rounded(pts: [number, number][], r = 12): string {
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const [x0, y0] = pts[i - 1];
    const [x1, y1] = pts[i];
    const [x2, y2] = pts[i + 1];
    const a = Math.min(r, Math.hypot(x1 - x0, y1 - y0) / 2);
    const b = Math.min(r, Math.hypot(x2 - x1, y2 - y1) / 2);
    const p1 = [x1 - (Math.sign(x1 - x0) * a || 0), y1 - (Math.sign(y1 - y0) * a || 0)];
    const p2 = [x1 + (Math.sign(x2 - x1) * b || 0), y1 + (Math.sign(y2 - y1) * b || 0)];
    d += ` L${p1[0]},${p1[1]} Q${x1},${y1} ${p2[0]},${p2[1]}`;
  }
  const last = pts[pts.length - 1];
  return `${d} L${last[0]},${last[1]}`;
}

function along(pts: [number, number][], f: number): [number, number] {
  const segs = pts.slice(1).map((p, i) => Math.hypot(p[0] - pts[i][0], p[1] - pts[i][1]));
  let d = f * segs.reduce((a, b) => a + b, 0);
  for (let i = 0; i < segs.length; i++) {
    if (d <= segs[i] || i === segs.length - 1) {
      const k = segs[i] ? Math.min(1, d / segs[i]) : 0;
      return [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * k, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * k];
    }
    d -= segs[i];
  }
  return pts[pts.length - 1];
}

function story(t: number, f: Frame): { text: string; tone: "ok" | "bad" | "warn" } {
  if (t < OUTAGE[0]) return { text: `Ramping to 500 req/s · Checkout API ${Math.round(f.util.api * 100)}% busy · p95 ${Math.round(f.p95)} ms`, tone: "ok" };
  if (t < OUTAGE[0] + 3) return { text: "Fault injected: the cart cache goes down", tone: "bad" };
  if (t < OUTAGE[0] + 6) return { text: "Reads fall through to PostgreSQL · it saturates", tone: "bad" };
  if (t < OUTAGE[1]) return { text: "The autoscaler adds 2 API replicas · the database is still the bottleneck", tone: "bad" };
  if (t < OUTAGE[1] + 6) return { text: "Cache back · queues draining", tone: "warn" };
  return { text: "Recovered · verdict: give the database a fallback or replicas", tone: "ok" };
}

/** Scale the fixed-size stage to the width of its container. */
function useFit(ref: React.RefObject<HTMLDivElement | null>, width: number) {
  const [k, setK] = useState(1);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setK(Math.min(1.12, el.clientWidth / width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref, width]);
  return k;
}

/** One readout cell of the LCD. */
function Cell({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("flex flex-col justify-center whitespace-nowrap border-[color:var(--lcd-line)] px-3", className)}>
      <span className="text-[9.5px] font-medium uppercase tracking-[0.08em] text-ink-3">{label}</span>
      <span className="font-readout text-[15px] leading-tight text-ink">{children}</span>
    </div>
  );
}

// Arrangement lanes: a label column, then the run.
const LX = 70;
const LW = 566;
const lx = (s: number) => LX + (s / T) * LW;
const LANES: { id: "load" | NodeId; label: string; kind: Kind }[] = [
  { id: "load", label: "Shoppers", kind: "traffic" },
  { id: "lb", label: "Edge", kind: "network" },
  { id: "api", label: "Checkout", kind: "compute" },
  { id: "cache", label: "Cart cache", kind: "cache" },
  { id: "db", label: "Orders DB", kind: "database" },
];
const LANE_H = 15;
const LANE_GAP = 3;

/** Path coordinates to 0.1 px, so the server and the browser write the same path (no hydration mismatch). */
const r1 = (v: number) => Math.round(v * 10) / 10;

/** A lane's waveform (values 0..1) inside a region of height h at y: its outline and its filled area. */
function wave(values: number[], y: number, h: number): { line: string; area: string } {
  const yOf = (v: number) => y + h - 2 - Math.min(1, Math.max(0, v)) * (h - 5);
  const line = `M${values.map((v, i) => `${r1(lx(i + 0.5))},${r1(yOf(v))}`).join(" L")}`;
  return { line, area: `${line} L${r1(lx(values.length - 0.5))},${y + h - 1} L${r1(lx(0.5))},${y + h - 1} Z` };
}

export function HeroDemo() {
  const [t, setT] = useState(STILL);
  const host = useRef<HTMLDivElement>(null);
  const k = useFit(host, STAGE_W);
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let visible = true;
    const io = new IntersectionObserver(([e]) => (visible = e.isIntersecting));
    if (host.current) io.observe(host.current);
    const start = performance.now() - (4 / T) * LOOP_MS;
    let last = 0;
    let raf = requestAnimationFrame(function tick(now) {
      raf = requestAnimationFrame(tick);
      if (!visible || now - last < 33) return;
      last = now;
      setT((((now - start) % LOOP_MS) / LOOP_MS) * T);
    });
    return () => {
      io.disconnect();
      cancelAnimationFrame(raf);
    };
  }, []);

  const f = frame(t);
  const s = story(t, f);
  const series = useMemo(() => Array.from({ length: T }, (_, i) => frame(i + 0.5)), []);
  const verdict =
    s.tone === "bad"
      ? { label: "SLO breached", color: "var(--status-critical)" }
      : s.tone === "warn"
        ? { label: "Recovering", color: "var(--status-warning)" }
        : { label: "Healthy", color: "var(--status-good)" };
  const clock = `00:${t.toFixed(1).padStart(4, "0")}`;
  const lanesH = LANES.length * (LANE_H + LANE_GAP) + 26;
  const p95Y = LANES.length * (LANE_H + LANE_GAP) + 4;

  return (
    <div ref={host} className="select-none" aria-label="Illustration: a replay of a cache outage saturating the database, then recovering" role="img">
      {/* the LCD */}
      <div className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-2.5">
        <div className="flex h-10 items-stretch rounded-[9px] border border-line bg-[color:var(--lcd)] shadow-[inset_0_1px_0_var(--highlight)]">
          {/* on a phone: time and p95 only */}
          <Cell label="Time" className="border-r">
            {clock}
            <span className="text-[10.5px] text-ink-3"> / 01:00</span>
          </Cell>
          <Cell label="Throughput" className="hidden border-r sm:flex">
            <span style={{ color: "var(--accent-2)" }}>{Math.round(f.load * f.success)}</span>
            <span className="text-[10.5px] text-ink-3"> req/s</span>
          </Cell>
          <Cell label={`p95 · goal ${SLO_P95}`} className="sm:border-r">
            <span style={{ color: f.p95 > SLO_P95 ? "var(--status-critical)" : undefined }}>{Math.round(f.p95).toLocaleString("en-US")}</span>
            <span className="text-[10.5px] text-ink-3"> ms</span>
          </Cell>
          <Cell label="Success" className="hidden sm:flex">
            <span style={{ color: f.success < 0.999 ? "var(--status-critical)" : "var(--status-good)" }}>{(f.success * 100).toFixed(f.success >= 0.999 ? 2 : 1)}</span>
            <span className="text-[10.5px] text-ink-3"> %</span>
          </Cell>
        </div>
        <span className="ml-auto inline-flex items-center gap-2 rounded-full bg-surface-2 px-2.5 py-1 text-[13px] font-medium text-ink">
          <span className={cn("size-2 rounded-full", s.tone === "bad" && "pulse-soft")} style={{ background: verdict.color }} />
          {verdict.label}
        </span>
      </div>

      {/* the diagram, at a fixed size scaled to fit */}
      <div className="flex justify-center overflow-hidden" style={{ height: STAGE_H * k }}>
        <div className="relative shrink-0 origin-top" style={{ width: STAGE_W, height: STAGE_H, transform: `scale(${k})` }}>
          <svg className="absolute inset-0" width={STAGE_W} height={STAGE_H} aria-hidden>
            {LINKS.map((e) => {
              const rate = e.rate(f);
              const dots = Math.round(Math.min(6, rate / 75));
              return (
                <g key={e.id}>
                  <path
                    d={rounded(e.pts)}
                    fill="none"
                    stroke={e.id === "api-db" && f.down ? "color-mix(in srgb, var(--status-critical) 60%, var(--wire))" : "var(--wire)"}
                    strokeWidth={1.5 + Math.min(2, rate / 280)}
                    strokeDasharray={e.miss ? "6 5" : undefined}
                  />
                  {Array.from({ length: dots }, (_, i) => {
                    const [px, py] = along(e.pts, (t * 0.85 + i / dots) % 1);
                    return e.write ? (
                      <rect key={i} x={px - 3} y={py - 3} width={6} height={6} rx={1} fill="var(--accent)" />
                    ) : (
                      <circle key={i} cx={px} cy={py} r={3.2} fill="var(--accent)" />
                    );
                  })}
                </g>
              );
            })}
          </svg>
          {NODES.map((n) => {
            const u = f.util[n.id];
            const down = n.id === "cache" && f.down;
            const hot = u >= 0.9;
            return (
              <div
                key={n.id}
                className="absolute overflow-hidden rounded-xl border transition-[border-color,box-shadow] duration-300"
                style={{
                  left: n.x,
                  top: n.y,
                  width: W,
                  height: H,
                  background: down ? "color-mix(in srgb, var(--status-critical) 6%, var(--node-fill))" : "var(--node-fill)",
                  borderColor: down || hot ? "var(--status-critical)" : "var(--node-stroke)",
                  borderStyle: down ? "dashed" : "solid",
                  boxShadow: hot && !down ? "0 0 0 3px color-mix(in srgb, var(--status-critical) 18%, transparent)" : "var(--node-shadow)",
                }}
              >
                <div className="flex items-center gap-2.5 px-3 pt-3">
                  <TechIcon technology={n.tech} kind={n.kind} size={18} tile />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-semibold leading-4 text-ink">{n.name}</p>
                    <p className="truncate text-[11px] leading-4 text-ink-3">{n.sub}</p>
                  </div>
                  {n.id === "api" && (
                    <span key={f.replicas} className="pop-in font-readout rounded-full bg-surface-2 px-1.5 text-[11px] leading-5 text-ink-2">
                      ×{f.replicas}
                    </span>
                  )}
                </div>
                {n.id === "clients" ? (
                  <div className="flex items-baseline justify-between px-3 pt-2.5 text-[11px]">
                    <span className="text-ink-3">Arrivals</span>
                    <span className="font-readout text-ink">{Math.round(f.load)}/s</span>
                  </div>
                ) : (
                  <div className="px-3 pt-2">
                    <div className="flex items-baseline justify-between text-[11px]">
                      {down ? (
                        <span className="pulse-soft font-semibold text-danger-ink">Outage</span>
                      ) : hot ? (
                        <span className="font-semibold text-danger-ink">Saturated</span>
                      ) : (
                        <span className="text-ink-3">Busy</span>
                      )}
                      <span className="font-readout text-ink">{down ? "–" : `${Math.round(u * 100)}%`}</span>
                    </div>
                    <Meter level={down ? 0 : u} className="pt-1" />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* the run as arrangement lanes */}
      <div className="border-t border-line px-4 pb-3 pt-2.5">
        <svg viewBox={`0 0 640 ${lanesH}`} className="h-auto w-full" aria-hidden>
          {LANES.map((lane, row) => {
            const y = row * (LANE_H + LANE_GAP);
            const color = KIND_COLOR[lane.kind];
            const values = series.map((p) => (lane.id === "load" ? p.load / 640 : p.util[lane.id as NodeId]));
            const w = wave(values, y, LANE_H);
            return (
              <g key={lane.id}>
                <rect x={0} y={y + 4} width={7} height={7} rx={2} fill={color} />
                <text x={12} y={y + 11} fontSize={8.5} fill="var(--ink-2)" fontFamily="var(--font-sans)">
                  {lane.label}
                </text>
                <rect x={LX + 0.5} y={y + 0.5} width={LW - 1} height={LANE_H - 1} rx={4} fill={color} fillOpacity={0.16} stroke={color} strokeOpacity={0.5} />
                <path d={w.area} fill={color} fillOpacity={0.3} />
                <path d={w.line} fill="none" stroke={color} strokeWidth={1.1} strokeLinejoin="round" />
                {lane.id === "cache" && (
                  <rect x={lx(OUTAGE[0]) + 0.5} y={y + 2} width={lx(OUTAGE[1]) - lx(OUTAGE[0]) - 1} height={LANE_H - 4} rx={2.5} fill="var(--status-critical)" />
                )}
                {lane.id === "db" &&
                  series.map((p, i) => (p.util.db >= 0.9 ? <rect key={i} x={lx(i) + 0.5} y={y + 1} width={LW / T - 1} height={2} fill="var(--status-critical)" /> : null))}
              </g>
            );
          })}
          <text x={12} y={p95Y + 13} fontSize={8.5} fill="var(--ink-2)" fontFamily="var(--font-sans)">
            p95
          </text>
          <line
            x1={LX}
            x2={LX + LW}
            y1={p95Y + 18 - (SLO_P95 / 1300) * 16}
            y2={p95Y + 18 - (SLO_P95 / 1300) * 16}
            stroke="var(--status-critical)"
            strokeDasharray="3 3"
            strokeWidth={0.8}
            opacity={0.7}
          />
          <path
            d={`M${series.map((p, i) => `${r1(lx(i + 0.5))},${r1(p95Y + 18 - (Math.min(p.p95, 1300) / 1300) * 16)}`).join(" L")}`}
            fill="none"
            stroke="var(--ink-2)"
            strokeWidth={1.1}
            strokeLinejoin="round"
          />
          <line x1={lx(t)} x2={lx(t)} y1={0} y2={lanesH} stroke="var(--ink)" strokeWidth={1.2} />
          <path d={`M${lx(t) - 4},0 L${lx(t) + 4},0 L${lx(t)},5 Z`} fill="var(--ink)" />
        </svg>
        <p className="mt-1.5 truncate font-readout text-xs text-ink-2">
          <span className="text-accent">❯</span> {s.text}
        </p>
      </div>
    </div>
  );
}
