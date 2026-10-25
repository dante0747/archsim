"use client";

/** What the simulator models, as a grid of tiles of different sizes, each with a small visual. */
import { Check, Cpu, MemoryStick } from "lucide-react";
import { TechIcon } from "@/components/TechIcon";
import { Reveal } from "@/components/landing/Reveal";
import { cn } from "@/lib/utils";

function Tile({ title, text, children, className, delay = 0 }: { title: string; text: string; children: React.ReactNode; className?: string; delay?: number }) {
  return (
    <Reveal delay={delay} className={className}>
      <article className="flex h-full flex-col overflow-hidden rounded-2xl border border-line bg-surface">
        <div className="flex-1 p-6 pb-0">{children}</div>
        <div className="p-6">
          <h3 className="text-lg font-semibold tracking-[-0.02em] text-ink">{title}</h3>
          <p className="mt-1.5 text-[14px] leading-relaxed text-ink-2">{text}</p>
        </div>
      </article>
    </Reveal>
  );
}

const G = "var(--status-good)";
const W = "var(--status-warning)";
const S = "var(--status-serious)";
const C = "var(--status-critical)";

function FaultsVisual() {
  const lanes = [
    [G, G, G, G, G, G, G, G, G, G, G, G, G, G, G, G],
    [G, G, G, G, W, S, C, C, C, S, W, G, G, G, G, G],
    [G, G, G, G, C, C, C, C, C, C, G, G, G, G, G, G],
    [G, G, G, W, S, C, C, C, C, C, S, W, G, G, G, G],
  ];
  return (
    <div>
      <div className="flex flex-wrap gap-2">
        <span className="rounded-full bg-critical-soft px-2.5 py-1 text-xs font-semibold text-danger-ink">Outage · cache · 20–32 s</span>
        <span className="rounded-full bg-serious-soft px-2.5 py-1 text-xs font-semibold text-ink">Slow ×4 · payments</span>
        <span className="rounded-full bg-warning-soft px-2.5 py-1 text-xs font-semibold text-ink">+200 ms · edge link</span>
      </div>
      <div className="relative mt-5 space-y-1.5">
        <div className="absolute inset-y-[-6px] left-[24%] w-[40%] rounded-md bg-[var(--status-critical)]/8" />
        {lanes.map((row, r) => (
          <div key={r} className="relative flex items-center gap-2">
            <span className="w-16 shrink-0 text-[11px] text-ink-3">{["Edge", "API", "Cache", "Orders DB"][r]}</span>
            <div className="flex flex-1 gap-0.5">
              {row.map((c, i) => (
                <span key={i} className="h-3 flex-1 rounded-[3px]" style={{ background: c, opacity: 0.85 }} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function ChannelsVisual() {
  const parts = [
    { share: 50, name: "Mobile", techs: ["ios", "android"], color: "var(--fam-traffic)" },
    { share: 35, name: "Web", techs: ["react", "nextjs"], color: "var(--fam-network)" },
    { share: 15, name: "API", techs: ["graphql"], color: "var(--fam-messaging)" },
  ];
  return (
    <div>
      <div className="flex h-3 gap-0.5 overflow-hidden rounded-full">
        {parts.map((p) => (
          <span key={p.name} style={{ width: `${p.share}%`, background: p.color }} />
        ))}
      </div>
      <ul className="mt-4 space-y-2">
        {parts.map((p) => (
          <li key={p.name} className="flex items-center gap-2 text-[13px]">
            <span className="flex -space-x-1">
              {p.techs.map((t) => (
                <TechIcon key={t} technology={t} kind="traffic" size={14} tile className="rounded-md" />
              ))}
            </span>
            <span className="font-medium text-ink">{p.name}</span>
            <span className="tabular ml-auto text-ink-2">{p.share}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ResourcesVisual() {
  return (
    <div>
      <div className="flex flex-wrap gap-2">
        <span className="inline-flex items-center gap-1.5 rounded-lg bg-surface-2 px-2.5 py-1.5 text-[13px] font-medium text-ink">
          <Cpu className="size-4 text-ink-3" /> 1 vCPU
        </span>
        <span className="inline-flex items-center gap-1.5 rounded-lg bg-surface-2 px-2.5 py-1.5 text-[13px] font-medium text-ink">
          <MemoryStick className="size-4 text-ink-3" /> 2 GiB
        </span>
      </div>
      <p className="mt-4 text-[13px] text-ink-2">
        CPU caps it at <span className="tabular font-semibold text-ink">196 req/s</span>
      </p>
      <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-3">
        <div className="h-full w-full rounded-full" style={{ background: C }} />
      </div>
      <p className="tabular mt-1.5 text-[11px] text-ink-3">CPU 100% busy · memory peak 366 MiB of 2 GiB</p>
    </div>
  );
}

function CostVisual() {
  const pts = [
    { x: 30, y: 26, ok: false },
    { x: 84, y: 48, ok: false },
    { x: 108, y: 70, ok: true },
    { x: 140, y: 22, ok: false },
    { x: 176, y: 78, ok: true },
  ];
  return (
    <svg viewBox="0 0 200 100" className="h-36 w-full" aria-hidden>
      <line x1={0} x2={200} y1={58} y2={58} stroke={C} strokeDasharray="3 3" strokeWidth={0.8} />
      <path d="M30,26 L84,26 L84,48 L108,48 L108,70 L176,70 L176,78" fill="none" stroke="var(--ink-3)" strokeWidth={1} />
      {pts.map((p, i) =>
        p.ok ? (
          <circle key={i} cx={p.x} cy={p.y} r={4.5} fill="var(--accent)" />
        ) : (
          <g key={i} stroke={C} strokeWidth={2}>
            <line x1={p.x - 3.5} y1={p.y - 3.5} x2={p.x + 3.5} y2={p.y + 3.5} />
            <line x1={p.x - 3.5} y1={p.y + 3.5} x2={p.x + 3.5} y2={p.y - 3.5} />
          </g>
        ),
      )}
      <text x={2} y={54} fontSize={7} fill={C}>
        p95 objective
      </text>
    </svg>
  );
}

function ScaleVisual() {
  const reps = [3, 3, 3, 4, 5, 6, 7, 7, 7, 6, 5, 4, 3, 3];
  return (
    <div>
      <div className="flex h-28 items-end gap-1">
        {reps.map((r, i) => (
          <span key={i} className="flex-1 rounded-t-md" style={{ height: `${(r / 7) * 100}%`, background: r >= 6 ? "var(--accent)" : "color-mix(in srgb, var(--accent) 45%, var(--surface-3))" }} />
        ))}
      </div>
      <p className="tabular mt-2 text-[13px] text-ink-2">
        Replicas <span className="font-semibold text-ink">×3 → ×7 → ×3</span>
      </p>
    </div>
  );
}

function YamlVisual() {
  const lines = [
    ["kind: ", "Deployment"],
    ["  replicas: ", "3"],
    ["  resources:", ""],
    ["    requests: ", "{ cpu: \"2\", memory: \"8Gi\" }"],
    ["    limits:   ", "{ cpu: \"2\", memory: \"8Gi\" }"],
    ["kind: ", "HorizontalPodAutoscaler"],
    ["  maxReplicas: ", "14"],
  ];
  return (
    <pre className="overflow-hidden rounded-xl bg-surface-2 p-4 font-code text-[12px] leading-[1.7] text-ink-2">
      {lines.map(([k, v], i) => (
        <div key={i}>
          <span className="text-ink-3">{k}</span>
          <span className="text-accent">{v}</span>
        </div>
      ))}
    </pre>
  );
}

function ReproVisual() {
  return (
    <div className="space-y-2">
      {["Run · seed 42", "Re-run · seed 42"].map((label) => (
        <div key={label} className="flex items-center gap-3 rounded-xl bg-surface-2 px-3.5 py-2.5">
          <span className="text-[13px] font-medium text-ink">{label}</span>
          <span className="ml-auto font-code text-xs text-ink-2">adcdc5aff2…</span>
          <Check className="size-4 text-[var(--status-good)]" />
        </div>
      ))}
      <p className="text-[12px] text-ink-3">Same input, same seed, same result digest. Bit for bit.</p>
    </div>
  );
}

function DashboardVisual() {
  const lines = [
    { pts: "0,30 12,28 24,26 36,18 48,20 60,12 72,14 84,8 96,10", color: "var(--series-1)" },
    { pts: "0,34 12,33 24,31 36,30 48,27 60,26 72,24 84,22 96,21", color: "var(--series-3)" },
    { pts: "0,36 12,36 24,35 36,30 48,22 60,28 72,34 84,35 96,36", color: "var(--series-2)" },
  ];
  return (
    <div className="grid grid-cols-3 gap-2">
      <div className="col-span-1 rounded-md border border-line bg-surface-2 p-2.5">
        <div className="flex items-center justify-between text-[10px] text-ink-3">
          p95 latency
          <span className="inline-flex items-center gap-1 font-semibold text-[var(--status-critical)]">
            <span className="live-dot size-1.5" /> LIVE
          </span>
        </div>
        <div className="tabular mt-1 text-xl font-semibold text-ink">212 ms</div>
        <svg viewBox="0 0 96 20" className="mt-1 h-5 w-full" aria-hidden>
          <polyline fill="none" stroke="var(--accent)" strokeWidth={1.5} points="0,16 12,15 24,14 36,9 48,11 60,5 72,7 84,3 96,6" />
        </svg>
      </div>
      <div className="col-span-2 rounded-md border border-line bg-surface-2 p-2.5">
        <div className="text-[10px] text-ink-3">Utilization by component</div>
        <svg viewBox="0 0 96 40" preserveAspectRatio="none" className="mt-1 h-16 w-full" aria-hidden>
          <rect x={0} y={0} width={96} height={6} fill="var(--status-critical)" opacity={0.08} />
          <line x1={0} x2={96} y1={6} y2={6} stroke="var(--status-critical)" strokeDasharray="2 2" strokeWidth={0.5} />
          {lines.map((l) => (
            <polyline key={l.color} fill="none" stroke={l.color} strokeWidth={1.4} points={l.pts} vectorEffect="non-scaling-stroke" />
          ))}
          <line x1={60} x2={60} y1={0} y2={40} stroke="var(--ink-3)" strokeWidth={0.5} />
        </svg>
      </div>
      {["Replication lag", "Lock wait", "Event-loop lag"].map((t, i) => (
        <div key={t} className="rounded-md border border-line bg-surface-2 px-2.5 py-2">
          <div className="text-[10px] text-ink-3">{t}</div>
          <div className="tabular text-sm font-semibold text-ink">{["840 ms", "3.1 ms", "12 ms"][i]}</div>
        </div>
      ))}
    </div>
  );
}

function MathsVisual() {
  return (
    <div className="space-y-2">
      <div className="rounded-md border border-line bg-surface-2 p-2.5">
        <div className="flex items-baseline justify-between">
          <span className="text-[12px] font-semibold text-ink">Utilization</span>
          <span className="tabular text-sm font-semibold text-ink">73%</span>
        </div>
        <p className="mt-1 font-code text-[11px] text-ink-2">ρ = λ × hold ÷ (1000 × slots)</p>
        <p className="font-code text-[11px] text-ink-3">= 438 × 6.7 ÷ (1000 × 4)</p>
      </div>
      <div className="space-y-1 rounded-md border border-line bg-surface-2 p-2.5">
        {[
          ["Network", 0, 18, "var(--ink-3)"],
          ["Waiting for a lock", 18, 30, "var(--series-4)"],
          ["Query", 48, 30, "var(--series-1)"],
          ["Log flush", 78, 14, "var(--series-7)"],
        ].map(([label, x, w, c]) => (
          <div key={label as string} className="grid grid-cols-[96px_1fr] items-center gap-2 text-[10px] text-ink-3">
            <span className="truncate">{label}</span>
            <span className="relative h-2.5">
              <span className="absolute inset-y-0 rounded-sm" style={{ left: `${x}%`, width: `${w}%`, background: c as string }} />
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function FeatureBento() {
  const span = (n: 2 | 3 | 4) => cn(n === 2 && "lg:col-span-2", n === 3 && "lg:col-span-3", n === 4 && "lg:col-span-4");
  return (
    <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-6">
      <Tile className={span(4)} title="Watch it like production" text="A Grafana-style dashboard for every run: throughput, latency, saturation and every technology's own signals, replayed live with a synced crosshair.">
        <DashboardVisual />
      </Tile>
      <Tile className={span(2)} delay={80} title="Show your working" text="Every number comes with its formula and this run's values, and sampled requests show each hop, wait and lock they hit.">
        <MathsVisual />
      </Tile>
      <Tile className={span(4)} title="Break things on purpose" text="Schedule outages, slowdowns and network latency, then watch every part heat up and recover, second by second.">
        <FaultsVisual />
      </Tile>
      <Tile className={span(2)} delay={80} title="Clients from every channel" text="Mobile apps, web browsers and API consumers share the traffic, each with its own timeouts, retries and network.">
        <ChannelsVisual />
      </Tile>
      <Tile className={span(2)} title="Sized like real machines" text="CPU cores, memory and storage on every part. Too few cores and work queues; too little memory and requests are refused.">
        <ResourcesVisual />
      </Tile>
      <Tile className={span(2)} delay={80} title="Cost against speed" text="Run every scenario and see which options meet your objectives for the least money.">
        <CostVisual />
      </Tile>
      <Tile className={span(2)} delay={160} title="Autoscaling and serverless" text="HPA-style autoscaling with start-up delays, serverless cold starts and concurrency limits.">
        <ScaleVisual />
      </Tile>
      <Tile className={span(3)} title="From model to manifests" text="Export Kubernetes or Docker Compose scaffolding with replicas, autoscalers and resource limits from your model.">
        <YamlVisual />
      </Tile>
      <Tile className={span(3)} delay={80} title="Reproducible to the bit" text="Every run records its input, seed and engine version. Re-run it and get exactly the same answer.">
        <ReproVisual />
      </Tile>
    </div>
  );
}
