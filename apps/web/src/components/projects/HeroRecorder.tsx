"use client";

/**
 * Home-page illustration of the flight recorder: a scripted (not simulated) replay of the starter
 * design losing its cache. Loops while visible; a still frame when reduced motion is preferred.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { ShapeSvg } from "@/components/shapes";
import { useIsDark } from "@/components/ui/theme-toggle";
import { heatColor } from "@/lib/recorder";
import type { Kind } from "@/lib/theme";

const T = 60; // simulated seconds
const LOOP_MS = 15000;
const OUTAGE: [number, number] = [20, 32];
const SLO_P95 = 300;

const smooth = (a: number, b: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};
const wobble = (t: number, k: number) => 0.5 + 0.5 * Math.sin(t * 1.7 + k) * Math.cos(t * 0.63 + k * 2.1);

/** Scripted metrics of the illustration at second t. */
function frame(t: number) {
  const load = 300 + 200 * smooth(0, 40, t); // req/s
  const down = t >= OUTAGE[0] && t < OUTAGE[1];
  const recover = down ? 1 : Math.max(0, 1 - (t - OUTAGE[1]) / 5) * (t >= OUTAGE[1] ? 1 : 0);
  const l = load / 500;
  const n = (k: number, amp = 0.04) => (wobble(t, k) - 0.5) * amp * 2;
  const util = {
    clients: 0,
    lb: 0.12 + 0.1 * l + n(1, 0.01),
    api: Math.min(1, 0.36 + 0.34 * l + n(2) + 0.45 * recover),
    cache: down ? 0 : 0.22 * l + n(3, 0.02),
    db: Math.min(1, 0.22 + 0.12 * l + n(4) + 0.78 * recover),
  };
  const p95 = 46 + 28 * l + n(5, 6) * 3 + recover * (980 + 160 * Math.sin(t * 2.3));
  const success = down ? 0.62 + 0.05 * Math.sin(t) : 1 - 0.03 * recover;
  return { load, down, util, p95, success };
}

type NodeId = "clients" | "lb" | "api" | "cache" | "db";
const NODES: { id: NodeId; kind: Kind; label: string; tech: string; x: number; y: number; layers?: number }[] = [
  { id: "clients", kind: "traffic", label: "clients", tech: "web + mobile", x: 4, y: 96 },
  { id: "lb", kind: "network", label: "edge", tech: "nginx", x: 152, y: 96 },
  { id: "api", kind: "compute", label: "api", tech: "go · k8s", x: 300, y: 96, layers: 3 },
  { id: "cache", kind: "cache", label: "cache", tech: "redis", x: 470, y: 14 },
  { id: "db", kind: "database", label: "orders-db", tech: "postgresql", x: 470, y: 176 },
];
const NW = 126;
const NH = 52;

type Edge = { id: string; pts: [number, number][]; rate: (f: ReturnType<typeof frame>) => number; write?: boolean; miss?: boolean };
const cy = (id: NodeId) => NODES.find((n) => n.id === id)!.y + NH / 2;
const EDGES: Edge[] = [
  { id: "c-lb", pts: [[4 + NW, cy("clients")], [152, cy("lb")]], rate: (f) => f.load },
  { id: "lb-api", pts: [[152 + NW, cy("lb")], [300, cy("api")]], rate: (f) => f.load },
  { id: "api-cache", pts: [[300 + NW - 10, cy("api")], [446, cy("api")], [446, cy("cache")], [470, cy("cache")]], rate: (f) => (f.down ? 0 : f.load * 0.9) },
  { id: "api-db", pts: [[300 + NW - 10, cy("api")], [446, cy("api")], [446, cy("db")], [470, cy("db")]], rate: (f) => f.load * (f.down ? 1 : 0.1), write: true },
  { id: "miss", pts: [[470 + NW, cy("cache")], [618, cy("cache")], [618, cy("db")], [470 + NW, cy("db")]], rate: (f) => (f.down ? 0 : f.load * 0.18), miss: true },
];

function along(pts: [number, number][], f: number): [number, number] {
  const segs = pts.slice(1).map((p, i) => Math.hypot(p[0] - pts[i][0], p[1] - pts[i][1]));
  let d = f * segs.reduce((a, b) => a + b, 0);
  for (let i = 0; i < segs.length; i++) {
    if (d <= segs[i] || i === segs.length - 1) {
      const r = segs[i] ? Math.min(1, d / segs[i]) : 0;
      return [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * r, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * r];
    }
    d -= segs[i];
  }
  return pts[pts.length - 1];
}

function caption(t: number): string {
  if (t < 6) return "t=0s  recording · 300 req/s, ramping to 500";
  if (t < OUTAGE[0]) return `t=${t.toFixed(1)}s  api at ${Math.round(frame(t).util.api * 100)}% · p95 ${Math.round(frame(t).p95)} ms · healthy`;
  if (t < OUTAGE[0] + 3) return `t=${t.toFixed(1)}s  ✕ fault injected: cache outage`;
  if (t < OUTAGE[1]) return `t=${t.toFixed(1)}s  reads fall through to postgresql → saturated · SLO breached`;
  if (t < OUTAGE[1] + 5) return `t=${t.toFixed(1)}s  cache back · queues draining`;
  return `t=${t.toFixed(1)}s  recovered · verdict: add a fallback or replicas`;
}

export function HeroRecorder() {
  const [t, setT] = useState(26);
  const raf = useRef<number | null>(null);
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let visible = true;
    const io = new IntersectionObserver(([e]) => (visible = e.isIntersecting));
    if (host.current) io.observe(host.current);
    const start = performance.now() - (26 / T) * LOOP_MS;
    let last = 0;
    const tick = (now: number) => {
      raf.current = requestAnimationFrame(tick);
      if (!visible || now - last < 33) return;
      last = now;
      setT((((now - start) % LOOP_MS) / LOOP_MS) * T);
    };
    raf.current = requestAnimationFrame(tick);
    return () => {
      io.disconnect();
      if (raf.current) cancelAnimationFrame(raf.current);
    };
  }, []);

  const dark = useIsDark();
  const heat = (u: number) => heatColor(u, dark);
  const f = frame(t);
  const series = useMemo(() => Array.from({ length: T }, (_, i) => frame(i + 0.5)), []);
  const PW = 560; // timeline plot width
  const LW = 76;
  const x = (s: number) => LW + (s / T) * PW;
  const p95Max = 1300;

  return (
    <div ref={host} className="select-none">
      <svg viewBox="0 0 640 236" className="h-auto w-full" role="img" aria-label="Illustration: replay of a cache outage saturating the database">
        {EDGES.map((e) => {
          const rate = e.rate(f);
          const d = `M${e.pts.map((p) => p.join(",")).join(" L")}`;
          const dots = Math.round(Math.min(6, rate / 80));
          return (
            <g key={e.id}>
              <path d={d} fill="none" stroke="var(--wire)" strokeWidth={1 + Math.min(2.2, rate / 260)} strokeDasharray={e.miss ? "5 4" : undefined} strokeLinejoin="round" />
              {Array.from({ length: dots }, (_, i) => {
                const [px, py] = along(e.pts, (t * 0.9 + i / dots) % 1);
                return e.write ? (
                  <rect key={i} x={px - 2.4} y={py - 2.4} width={4.8} height={4.8} fill="var(--accent)" />
                ) : (
                  <circle key={i} cx={px} cy={py} r={2.6} fill="var(--accent)" />
                );
              })}
            </g>
          );
        })}
        {NODES.map((n) => {
          const u = f.util[n.id];
          const down = n.id === "cache" && f.down;
          const hot = u >= 0.85;
          const stroke = down ? "var(--status-critical)" : hot ? heat(u) : "var(--node-stroke)";
          const shape = "card" as const;
          const mid = `hero-mask-${n.id}`;
          return (
            <g key={n.id} transform={`translate(${n.x},${n.y})`}>
              <defs>
                <mask id={mid}>
                  <ShapeSvg shape={shape} w={NW} h={NH} unit={0.55} layers={n.layers} stroke="white" fill="white" />
                </mask>
              </defs>
              <ShapeSvg shape={shape} w={NW} h={NH} unit={0.55} layers={n.layers} stroke={stroke} strokeWidth={1.3} outlineStyle={{ strokeDasharray: down ? "5 3" : undefined }} />
              {n.id !== "clients" && (
                <g mask={`url(#${mid})`}>
                  <rect x={0} y={NH * (1 - u)} width={NW} height={NH * u} fill={heat(u)} opacity={0.32} />
                  <line x1={0} x2={NW} y1={NH * (1 - u)} y2={NH * (1 - u)} stroke={heat(u)} strokeWidth={1} opacity={u > 0.02 ? 0.9 : 0} />
                </g>
              )}
              <text x={NW / 2} y={21} textAnchor="middle" fontSize={11.5} fontWeight={600} fill="var(--ink)" fontFamily="var(--font-mono)">
                {n.label}
              </text>
              <text x={NW / 2} y={35} textAnchor="middle" fontSize={8.5} fill="var(--ink-3)" fontFamily="var(--font-mono)" letterSpacing="0.06em">
                {n.tech.toUpperCase()}
              </text>
              {n.id !== "clients" && (
                <text x={NW / 2} y={NH + 13} textAnchor="middle" fontSize={9} fill={down ? "var(--status-critical)" : hot ? heat(u) : "var(--ink-2)"} fontFamily="var(--font-mono)">
                  {down ? "OUTAGE" : `${Math.round(u * 100)}% busy`}
                </text>
              )}
              {n.id === "clients" && (
                <text x={NW / 2} y={NH + 13} textAnchor="middle" fontSize={9} fill="var(--ink-2)" fontFamily="var(--font-mono)">
                  {Math.round(f.load)} req/s
                </text>
              )}
            </g>
          );
        })}
      </svg>

      <svg viewBox={`0 0 ${LW + PW + 4} 118`} className="mt-1 h-auto w-full" aria-hidden>
        {/* fault window */}
        <rect x={x(OUTAGE[0])} y={0} width={x(OUTAGE[1]) - x(OUTAGE[0])} height={118} fill="var(--status-critical)" opacity={0.07} />
        {[
          ["traffic", 2],
          ["fault", 22],
          ["edge", 36],
          ["api", 48],
          ["cache", 60],
          ["orders-db", 72],
          ["p95", 88],
        ].map(([label, y]) => (
          <text key={label as string} x={0} y={(y as number) + 8} fontSize={8.5} fill="var(--ink-3)" fontFamily="var(--font-mono)">
            {label}
          </text>
        ))}
        <path
          d={`M${x(0)},18 ${series.map((s, i) => `L${x(i + 0.5)},${18 - (s.load / 520) * 15}`).join(" ")} L${x(T)},18 Z`}
          fill="var(--accent)"
          opacity={0.14}
        />
        <path d={`M${series.map((s, i) => `${x(i + 0.5)},${18 - (s.load / 520) * 15}`).join(" L")}`} fill="none" stroke="var(--accent)" strokeWidth={1.2} />
        <rect x={x(OUTAGE[0])} y={22} width={x(OUTAGE[1]) - x(OUTAGE[0])} height={10} rx={1.5} fill="var(--status-critical)" opacity={0.35} />
        <text x={x(OUTAGE[0]) + 4} y={30} fontSize={7.5} fill="var(--ink)" fontFamily="var(--font-mono)">
          cache outage
        </text>
        {(["lb", "api", "cache", "db"] as const).map((id, row) =>
          series.map((s, i) => (
            <rect key={`${id}-${i}`} x={x(i) + 0.4} y={36 + row * 12} width={PW / T - 0.8} height={10} rx={1} fill={heat(s.util[id])} opacity={0.85} />
          )),
        )}
        <line x1={x(0)} x2={x(T)} y1={116 - (SLO_P95 / p95Max) * 26} y2={116 - (SLO_P95 / p95Max) * 26} stroke="var(--status-critical)" strokeDasharray="3 3" strokeWidth={0.8} opacity={0.7} />
        <path
          d={`M${series.map((s, i) => `${x(i + 0.5)},${116 - (Math.min(s.p95, p95Max) / p95Max) * 26}`).join(" L")}`}
          fill="none"
          stroke="var(--ink-2)"
          strokeWidth={1.1}
        />
        <line x1={x(t)} x2={x(t)} y1={0} y2={118} stroke="var(--accent)" strokeWidth={1.4} />
        <rect x={x(t) - 3} y={0} width={6} height={4} fill="var(--accent)" />
      </svg>

      <p className="mt-2 truncate font-mono text-xs text-ink-2" aria-hidden>
        <span className="text-accent">❯</span> {caption(t)}
        <span className="ml-3 text-ink-3">
          p95 {Math.round(f.p95)} ms · ok {(f.success * 100).toFixed(1)}%
        </span>
      </p>
    </div>
  );
}
