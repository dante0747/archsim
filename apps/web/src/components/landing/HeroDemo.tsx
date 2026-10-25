"use client";

/**
 * The landing page's product shot: a scripted (not simulated) replay of a design losing its cache,
 * drawn with the same flat part cards as the editor. Traffic moves along the links, the API
 * autoscales, the database saturates, the verdict flips and recovers; a timeline runs below.
 * Loops while visible; one still frame when reduced motion is preferred.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { TechIcon } from "@/components/TechIcon";
import { useIsDark } from "@/components/ui/theme-toggle";
import { heatColor } from "@/lib/recorder";
import type { Kind } from "@/lib/theme";
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
const H = 68;
const NODES: { id: NodeId; kind: Kind; tech: string | null; name: string; sub: string; x: number; y: number }[] = [
  { id: "clients", kind: "traffic", tech: "mobile", name: "Shoppers", sub: "Mobile + web", x: 16, y: 122 },
  { id: "lb", kind: "network", tech: "nginx", name: "Edge", sub: "Nginx", x: 236, y: 122 },
  { id: "api", kind: "compute", tech: "go", name: "Checkout", sub: "Go · Kubernetes", x: 456, y: 122 },
  { id: "cache", kind: "cache", tech: "redis", name: "Cart cache", sub: "Redis", x: 690, y: 26 },
  { id: "db", kind: "database", tech: "postgresql", name: "Orders DB", sub: "PostgreSQL", x: 690, y: 218 },
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

  const dark = useIsDark();
  const heat = (u: number) => heatColor(u, dark);
  const f = frame(t);
  const s = story(t, f);
  const series = useMemo(() => Array.from({ length: T }, (_, i) => frame(i + 0.5)), []);
  const verdict =
    s.tone === "bad"
      ? { label: "SLO breached", color: "var(--status-critical)" }
      : s.tone === "warn"
        ? { label: "Recovering", color: "var(--status-warning)" }
        : { label: "Healthy", color: "var(--status-good)" };

  return (
    <div ref={host} className="select-none" aria-label="Illustration: a replay of a cache outage saturating the database, then recovering" role="img">
      {/* live numbers */}
      <div className="flex flex-wrap items-center gap-2 border-b border-line px-4 py-2.5 text-[13px]">
        <span className="inline-flex items-center gap-2 rounded-full bg-surface-2 px-2.5 py-1 font-medium text-ink">
          <span className={cn("size-2 rounded-full", s.tone === "bad" && "pulse-soft")} style={{ background: verdict.color }} />
          {verdict.label}
        </span>
        <span className="tabular text-ink-2">
          <span className="font-semibold text-ink">{Math.round(f.load)}</span> req/s
        </span>
        <span className="tabular text-ink-2">
          p95 <span className={cn("font-semibold", f.p95 > SLO_P95 ? "text-danger-ink" : "text-ink")}>{Math.round(f.p95).toLocaleString("en-US")} ms</span>
        </span>
        <span className="tabular text-ink-2">
          <span className="font-semibold text-ink">{(f.success * 100).toFixed(1)}%</span> ok
        </span>
        <span className="tabular ml-auto text-ink-3">t = {t.toFixed(1)} s</span>
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
                className="absolute overflow-hidden rounded-xl border bg-[var(--node-fill)] transition-[border-color,box-shadow] duration-300"
                style={{
                  left: n.x,
                  top: n.y,
                  width: W,
                  height: H,
                  borderColor: down ? "var(--status-critical)" : hot ? heat(u) : "var(--node-stroke)",
                  borderStyle: down ? "dashed" : "solid",
                  boxShadow: hot && !down ? `0 0 0 3px color-mix(in srgb, ${heat(u)} 20%, transparent)` : undefined,
                }}
              >
                <div className="flex items-center gap-2.5 px-3 pt-3">
                  <TechIcon technology={n.tech} kind={n.kind} size={18} tile className="rounded-lg" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-semibold leading-4 text-ink">{n.name}</p>
                    <p className="truncate text-[11px] leading-4 text-ink-3">{n.sub}</p>
                  </div>
                  {n.id === "api" && (
                    <span key={f.replicas} className="pop-in tabular rounded-full bg-surface-2 px-1.5 text-[11px] font-semibold leading-5 text-ink-2">×{f.replicas}</span>
                  )}
                </div>
                {n.id === "clients" ? (
                  <p className="tabular px-3 pt-1 text-[11px] text-ink-2">{Math.round(f.load)} req/s</p>
                ) : (
                  <>
                    <div className="flex items-center gap-1.5 px-3 pt-1">
                      <p className="tabular text-[11px] text-ink-2">{down ? "Not answering" : `${Math.round(u * 100)}% busy`}</p>
                      {down && <span className="pulse-soft ml-auto rounded-full bg-critical-soft px-1.5 text-[10px] font-semibold leading-4 text-danger-ink">Outage</span>}
                      {hot && !down && <span className="ml-auto rounded-full bg-critical-soft px-1.5 text-[10px] font-semibold leading-4 text-danger-ink">Saturated</span>}
                    </div>
                    <div className="absolute inset-x-0 bottom-0 h-1 bg-surface-3">
                      <div className="h-full transition-[width] duration-300" style={{ width: `${(down ? 0 : u) * 100}%`, background: heat(u) }} />
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* timeline */}
      <div className="border-t border-line px-4 pb-3 pt-2">
        <svg viewBox="0 0 640 96" className="h-auto w-full" aria-hidden>
          <rect x={60 + (OUTAGE[0] / T) * 576} y={0} width={((OUTAGE[1] - OUTAGE[0]) / T) * 576} height={96} rx={3} fill="var(--status-critical)" opacity={0.08} />
          {[
            ["Traffic", 10],
            ["Edge", 30],
            ["API", 44],
            ["Cache", 58],
            ["Orders DB", 72],
            ["p95", 88],
          ].map(([label, y]) => (
            <text key={label as string} x={0} y={(y as number) + 3} fontSize={9} fill="var(--ink-3)" fontFamily="var(--font-sans)">
              {label}
            </text>
          ))}
          <path
            d={`M60,18 ${series.map((p, i) => `L${60 + ((i + 0.5) / T) * 576},${18 - (p.load / 520) * 14}`).join(" ")} L636,18 Z`}
            fill="var(--accent)"
            opacity={0.18}
          />
          {(["lb", "api", "cache", "db"] as const).map((id, row) =>
            series.map((p, i) => (
              <rect key={`${id}-${i}`} x={60 + (i / T) * 576 + 0.5} y={24 + row * 14} width={576 / T - 1} height={11} rx={2} fill={heat(p.util[id])} opacity={0.9} />
            )),
          )}
          <line x1={60} x2={636} y1={95 - (SLO_P95 / 1300) * 14} y2={95 - (SLO_P95 / 1300) * 14} stroke="var(--status-critical)" strokeDasharray="3 3" strokeWidth={0.8} opacity={0.7} />
          <path
            d={`M${series.map((p, i) => `${60 + ((i + 0.5) / T) * 576},${95 - (Math.min(p.p95, 1300) / 1300) * 14}`).join(" L")}`}
            fill="none"
            stroke="var(--ink-2)"
            strokeWidth={1.2}
          />
          <line x1={60 + (t / T) * 576} x2={60 + (t / T) * 576} y1={0} y2={96} stroke="var(--accent)" strokeWidth={1.6} />
          <circle cx={60 + (t / T) * 576} cy={2} r={3} fill="var(--accent)" />
        </svg>
        <p className="mt-1.5 truncate font-code text-xs text-ink-2">
          <span className="text-accent">❯</span> {s.text}
        </p>
      </div>
    </div>
  );
}
