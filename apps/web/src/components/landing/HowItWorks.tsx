"use client";

/** Three steps, each with a small live vignette of the workspace it names. */
import { CircleCheck } from "lucide-react";
import { TechIcon } from "@/components/TechIcon";
import { Reveal } from "@/components/landing/Reveal";
import landing from "@/data/landing-run.json";
import { formatUsd } from "@/lib/cost";
import type { Kind } from "@/lib/theme";

function MiniCard({ tech, kind, name, className = "" }: { tech: string; kind: Kind; name: string; className?: string }) {
  return (
    <div className={`flex items-center gap-2 rounded-lg border border-[var(--node-stroke)] bg-[var(--node-fill)] px-2 py-1.5 ${className}`}>
      <TechIcon technology={tech} kind={kind} size={14} tile className="rounded-md" />
      <span className="text-xs font-semibold text-ink">{name}</span>
    </div>
  );
}

function DesignVignette() {
  return (
    <div className="relative h-40">
      <svg className="absolute inset-0 h-full w-full" viewBox="0 0 300 160" preserveAspectRatio="none" aria-hidden>
        <path d="M84,52 L132,52" stroke="var(--wire)" strokeWidth={1.5} fill="none" />
        <path className="draw-loop" d="M204,52 Q222,52 222,70 L222,98 Q222,112 236,112" stroke="var(--accent)" strokeWidth={2} fill="none" />
      </svg>
      <MiniCard tech="nginx" kind="network" name="Edge" className="absolute left-0 top-9" />
      <MiniCard tech="go" kind="compute" name="API" className="absolute left-[44%] top-9 ring-2 ring-accent/30" />
      <MiniCard tech="postgresql" kind="database" name="Orders DB" className="absolute right-0 top-[92px]" />
      <span className="absolute bottom-1 left-0 rounded-full bg-surface-2 px-2 py-0.5 text-[11px] text-ink-2">Drag, drop, connect from any side</span>
    </div>
  );
}

function TestVignette() {
  const g = "var(--status-good)";
  const heat = [g, g, g, "var(--status-warning)", "var(--status-serious)", "var(--status-critical)", "var(--status-critical)", "var(--status-serious)", "var(--status-warning)", g];
  return (
    <div className="relative h-40 overflow-hidden">
      <svg className="absolute inset-x-0 top-1 h-12 w-full" viewBox="0 0 300 48" preserveAspectRatio="none" aria-hidden>
        <path d="M0,44 L300,10 L300,48 L0,48 Z" fill="var(--accent)" opacity={0.15} />
        <path d="M0,44 L300,10" stroke="var(--accent)" strokeWidth={1.5} fill="none" />
      </svg>
      <div className="absolute inset-y-0 left-[40%] w-[22%] rounded-md bg-[var(--status-critical)]/10" />
      <span className="absolute left-[41%] top-14 rounded-full bg-critical-soft px-1.5 text-[11px] font-semibold text-danger-ink">Cache outage</span>
      <div className="absolute inset-x-0 top-[88px] space-y-1.5">
        {[0, 1, 2].map((row) => (
          <div key={row} className="flex gap-0.5">
            {heat.map((c, i) => (
              <span key={i} className="h-2.5 flex-1 rounded-[3px]" style={{ background: row === 1 ? c : row === 0 ? g : heat[Math.min(9, i + 1)], opacity: 0.85 }} />
            ))}
          </div>
        ))}
      </div>
      <div className="sweep absolute inset-y-0 w-px bg-accent">
        <span className="absolute -left-1 top-0 size-2 rounded-full bg-accent" />
      </div>
    </div>
  );
}

/**
 * A real run's answer: the Black Friday template's scenario that holds up, as the engine simulates
 * it today (src/data/landing-run.json, which archsim-engine's GeneratedFileTests keeps current; the
 * engine refuses to write it for a run that misses its objectives, so the answer is yes).
 */
function ResultsVignette() {
  return (
    <div className="flex h-40 flex-col justify-center">
      <p className="flex items-center gap-2 text-[22px] font-semibold tracking-[-0.03em] text-ink">
        <CircleCheck className="size-6 text-[var(--status-good)]" /> Yes, it holds up.
      </p>
      <dl className="mt-4 grid grid-cols-3 gap-2">
        {[
          [`${Math.round(landing.throughputRps)}/s`, "handled"],
          [`${Math.round(landing.p95Ms)} ms`, "p95"],
          [formatUsd(landing.monthlyCost), `per month · ${landing.provider.toUpperCase()}`],
        ].map(([v, k]) => (
          <div key={k} className="rounded-lg bg-surface-2 px-2.5 py-2">
            <dd className="tabular text-[15px] font-semibold text-ink">{v}</dd>
            <dt className="text-[11px] text-ink-3">{k}</dt>
          </div>
        ))}
      </dl>
    </div>
  );
}

const STEPS = [
  { n: "1", title: "Design", text: "Drag in real technologies (PostgreSQL, Redis, Kafka, Kubernetes services, mobile and web clients) and connect them.", visual: <DesignVignette /> },
  { n: "2", title: "Test", text: "Send traffic, inject outages and slowdowns, and replay every second like a flight recorder.", visual: <TestVignette /> },
  {
    n: "3",
    title: "Learn",
    text: "See how it fares against your objectives: the bottleneck, the breaking point and the monthly bill, as hypotheses to test in a prototype or a load test.",
    visual: <ResultsVignette />,
  },
];

export function HowItWorks() {
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      {STEPS.map((s, i) => (
        <Reveal key={s.n} delay={i * 90}>
          <article className="flex h-full flex-col rounded-2xl border border-line bg-surface p-6">
            {s.visual}
            <div className="mt-6 flex items-center gap-3">
              <span className="flex size-7 items-center justify-center rounded-full bg-accent text-[13px] font-semibold text-[var(--accent-ink)]">{s.n}</span>
              <h3 className="text-xl font-semibold tracking-[-0.02em] text-ink">{s.title}</h3>
            </div>
            <p className="mt-2 text-[15px] leading-relaxed text-ink-2">{s.text}</p>
          </article>
        </Reveal>
      ))}
    </div>
  );
}
