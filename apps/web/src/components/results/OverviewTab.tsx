"use client";

import { CircleAlert, CircleCheck, Info, LoaderCircle, Play, RotateCcw, TriangleAlert } from "lucide-react";
import { verdictOf } from "@/components/editor/workflow";
import { TechIcon } from "@/components/TechIcon";
import { Button } from "@/components/ui/button";
import { Badge, Empty, Section, Tip } from "@/components/ui/misc";
import { fmtMs, fmtNum, fmtPct, fmtRps, shortDigest } from "@/lib/format";
import { NODE_STATUS, SEVERITY } from "@/lib/status";
import { REQ_GLYPH } from "@/lib/theme";
import type { Finding, Report, Run, Series } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { SINGLE_SERIES, thClass } from "./charts";

/** Tiny single-series trend line (the tile's label names the series, so no legend). */
function Sparkline({ values, color, from = 0 }: { values: Series; color: string; from?: number }) {
  const pts = values.slice(from).map((v, i) => [i, v] as const).filter((p): p is readonly [number, number] => p[1] != null);
  if (pts.length < 2) return null;
  const W = 100;
  const H = 24;
  const maxX = pts[pts.length - 1][0] || 1;
  const maxY = Math.max(...pts.map((p) => p[1])) || 1;
  const d = pts.map(([x, y], i) => `${i ? "L" : "M"}${((x / maxX) * W).toFixed(1)},${(H - 2 - (y / maxY) * (H - 5)).toFixed(1)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="h-6 w-full" aria-hidden>
      <line x1={0} y1={H - 0.5} x2={W} y2={H - 0.5} stroke="var(--line)" strokeWidth={1} vectorEffect="non-scaling-stroke" />
      <path d={d} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
    </svg>
  );
}

/** A flat instrument cell: mono label, mono value, optional hint and sparkline. */
function Kpi({
  label,
  value,
  hint,
  spark,
  sparkColor = SINGLE_SERIES,
  sparkFrom,
  big,
}: {
  label: string;
  value: string;
  hint?: string;
  spark?: Series;
  sparkColor?: string;
  sparkFrom?: number;
  big?: boolean;
}) {
  return (
    <div className="min-w-0 bg-surface px-3 py-2.5">
      <p className="microlabel truncate">{label}</p>
      <p className={cn("tabular truncate font-mono font-semibold tracking-tight text-ink", big ? "text-xl leading-8" : "text-[15px] leading-6")}>{value}</p>
      {hint && <p className="truncate font-mono text-[10.5px] text-ink-3">{hint}</p>}
      {spark && (
        <div className="mt-1.5">
          <Sparkline values={spark} color={sparkColor} from={sparkFrom} />
        </div>
      )}
    </div>
  );
}

const SEVERITY_ICON = {
  critical: CircleAlert,
  warning: TriangleAlert,
  info: Info,
  ok: CircleCheck,
};

export function FindingCard({ f }: { f: Finding }) {
  const meta = SEVERITY[f.severity];
  const Icon = SEVERITY_ICON[f.severity];
  const select = useEditor((s) => s.select);
  return (
    <li className="relative flex gap-2.5 overflow-hidden rounded-md border border-line bg-surface p-3 pl-3.5">
      <span className="absolute inset-y-0 left-0 w-0.5" style={{ background: meta.color }} aria-hidden />
      <Icon className="mt-0.5 size-3.5 shrink-0" style={{ color: meta.color }} aria-hidden />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="sr-only">{meta.label}:</span>
          <p className="text-[13px] font-semibold text-ink">{f.title}</p>
          {f.nodeId && (
            <button type="button" className="font-mono text-[10px] uppercase tracking-wider text-accent hover:underline" onClick={() => select({ nodeId: f.nodeId })}>
              locate
            </button>
          )}
        </div>
        <p className="text-xs leading-relaxed text-ink-2">{f.detail}</p>
        {f.suggestion && (
          <p className="rounded-sm border-l border-accent bg-surface-2 px-2.5 py-1.5 text-xs leading-relaxed text-ink">
            <span className="mr-1.5 font-mono text-[10.5px] text-accent">try →</span>
            {f.suggestion}
          </p>
        )}
      </div>
    </li>
  );
}

export function RunProgress({ run }: { run: Run }) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-line bg-surface p-3" role="status">
      <LoaderCircle className="size-4 animate-spin text-accent" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-ink">
          {run.status === "queued" ? "Queued" : "Simulating"}: {run.label}
        </p>
        <p className="font-mono text-[10.5px] text-ink-3">
          Progress follows the engine&apos;s simulated clock ({Math.round(run.progress * 100)}% of simulated time).
        </p>
        <div className="mt-1.5 h-1 overflow-hidden rounded-sm bg-surface-3">
          <div className="progress-stripes h-full rounded-sm transition-[width]" style={{ width: `${Math.max(3, run.progress * 100)}%` }} />
        </div>
      </div>
    </div>
  );
}

export function ProvenanceBar({ report, run }: { report: Report; run: Run | undefined }) {
  const rerun = useRuns((s) => s.rerun);
  const p = report.provenance;
  return (
    <div className="flex flex-wrap items-center gap-1.5 font-mono text-[10.5px] text-ink-2">
      <Badge tone="accent" className="font-mono">
        simulation-derived
      </Badge>
      <Tip content="Random seed: the same document + scenario + seed + engine version reproduces these results exactly.">
        <Badge className="font-mono">seed {p.seed}</Badge>
      </Tip>
      <Badge className="font-mono">engine {p.engineVersion}</Badge>
      <Tip content={`Input digest ${p.inputDigest}`}>
        <Badge className="font-mono">in {shortDigest(p.inputDigest)}</Badge>
      </Tip>
      <Tip content={`Result digest ${p.resultDigest}`}>
        <Badge className="font-mono">out {shortDigest(p.resultDigest)}</Badge>
      </Tip>
      <Badge className="font-mono">
        {fmtNum(p.requestsGenerated)} req · {fmtNum(report.window.simulatedS, 1)} s sim · {fmtNum(p.wallSeconds, 1)} s wall
      </Badge>
      {run && (
        <Button size="sm" variant="ghost" onClick={() => void rerun(run.id)} title="Re-run the stored snapshot and check the result digest matches">
          <RotateCcw /> Reproduce
        </Button>
      )}
    </div>
  );
}

const VERDICT_WORD = { ok: "healthy", warn: "running hot", bad: "bottleneck" } as const;

export function OverviewTab() {
  const activeRunId = useRuns((s) => s.activeRunId);
  const report = useRuns((s) => (s.activeRunId ? s.reports[s.activeRunId] : null));
  const runs = useRuns((s) => s.runs);
  const followRunId = useRuns((s) => s.followRunId);
  const followRun = runs.find((r) => r.id === followRunId);
  const run = runs.find((r) => r.id === activeRunId);
  const running = followRun && (followRun.status === "queued" || followRun.status === "running") ? followRun : null;

  if (!report) {
    return (
      <div className="space-y-3 p-4">
        {running && <RunProgress run={running} />}
        {!running && (
          <Empty icon={<Play />} title="No results yet">
            Press <b>Run</b>. The engine generates requests from your traffic settings, moves each one through queues and
            workers, and reports what it measured. Every number here comes from that run.
          </Empty>
        )}
      </div>
    );
  }
  const s = report.summary;
  const lat = s.latencyMs;
  const acc = s.accounting;
  const ts = report.timeseries;
  const warmIdx = Math.ceil(report.window.warmupS / ts.bucketS);
  const endIdx = Math.floor(report.window.durationS / ts.bucketS);
  const v = verdictOf(report);
  const vColor = v.tone === "ok" ? NODE_STATUS.ok.color : v.tone === "warn" ? NODE_STATUS.hot.color : NODE_STATUS.saturated.color;
  const clip = (series: Series) => series.slice(0, endIdx);
  // Failures are a "bad" series: they wear the critical status color only when there are any.
  const anyFailures = s.failed + s.timedOut > 0;
  return (
    <div className="space-y-4 p-4">
      {running && <RunProgress run={running} />}
      <div className="relative overflow-hidden rounded-md border border-line bg-surface p-4 pl-5">
        <span className="absolute inset-y-0 left-0 w-0.5" style={{ background: vColor }} aria-hidden />
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
          <p className="microlabel">Verdict · {run?.label ?? report.provenance.scenarioName ?? "Design as drawn"}</p>
          <span className="flex items-center gap-1.5 font-mono text-[10.5px] text-ink-2">
            <span className={cn("size-2 rounded-full", v.tone !== "ok" && "pulse-soft")} style={{ background: vColor }} aria-hidden />
            {VERDICT_WORD[v.tone]}
          </span>
        </div>
        <p className="mt-1.5 text-lg font-semibold leading-snug text-ink">{v.title}</p>
        <div className="mt-3">
          <ProvenanceBar report={report} run={run} />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-line bg-line lg:grid-cols-4">
        <Kpi big label="Throughput" value={fmtRps(s.throughputRps)} hint="successful, after warm-up" spark={clip(ts.throughputRps)} sparkFrom={warmIdx} />
        <Kpi big label="Latency p95" value={fmtMs(lat.p95)} hint={`p50 ${fmtMs(lat.p50)} · p99 ${fmtMs(lat.p99)}`} spark={clip(ts.latencyP95Ms)} sparkFrom={warmIdx} />
        <Kpi
          big
          label="Success rate"
          value={fmtPct(s.successRate)}
          hint={`${fmtNum(s.succeeded)} of ${fmtNum(s.arrived)} requests`}
          spark={clip(ts.errorRps)}
          sparkColor={anyFailures ? "var(--status-critical)" : "var(--ink-3)"}
          sparkFrom={warmIdx}
        />
        <Kpi big label="Offered load" value={fmtRps(s.offeredRps)} hint="what clients sent" spark={clip(ts.offeredRps)} sparkFrom={warmIdx} />
      </div>
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-line bg-line sm:grid-cols-5">
        <Kpi label="p50" value={fmtMs(lat.p50)} />
        <Kpi label="p99" value={fmtMs(lat.p99)} hint={`max ${fmtMs(lat.max)}`} />
        <Kpi label="Failed" value={fmtNum(s.failed)} hint={fmtPct(s.errorRate)} />
        <Kpi label="Client timeouts" value={fmtNum(s.timedOut)} hint={fmtPct(s.timeoutRate)} />
        <Kpi label="Attempts / request" value={s.retryAmplification == null ? "–" : `${fmtNum(s.retryAmplification, 2)}×`} hint="1.00× = no retries" />
      </div>
      <p className="font-mono text-[10.5px] text-ink-3">Sparklines: success-rate cell shows failed req/s; all exclude warm-up.</p>

      <div className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <Section title="What the simulation found">
          <ul className="space-y-2">
            {report.findings.map((f, i) => (
              <FindingCard key={i} f={f} />
            ))}
          </ul>
        </Section>
        <div className="space-y-4">
          <Section title="Request accounting · after warm-up">
            <div className="rounded-md border border-line bg-surface p-3 text-xs">
              <p className="tabular font-mono text-[11.5px] text-ink">
                {fmtNum(acc.measured.arrived)} = {fmtNum(acc.measured.succeeded)} ok + {fmtNum(acc.measured.failed)} failed + {fmtNum(acc.measured.timedOut)} timed out + {fmtNum(acc.measured.outstanding)} open
              </p>
              <p className="mt-1.5 flex items-center gap-1.5 text-ink-2">
                {acc.invariantHolds ? (
                  <CircleCheck className="size-3.5 shrink-0" style={{ color: "var(--status-good)" }} />
                ) : (
                  <CircleAlert className="size-3.5 shrink-0" style={{ color: "var(--status-critical)" }} />
                )}
                {acc.invariantHolds ? "Every request is accounted for." : "Accounting mismatch: please report this as a bug."} {fmtNum(acc.warmup.arrived)} warm-up requests excluded.
              </p>
            </div>
          </Section>
          <Section title="Latency by request type">
            <div className="overflow-hidden rounded-md border border-line bg-surface">
              <table className="tabular w-full font-mono text-[11.5px]">
                <thead>
                  <tr className="border-b border-line text-left">
                    <th className={cn(thClass, "px-3 py-1.5")}>type</th>
                    <th className={cn(thClass, "px-2 py-1.5 text-right")}>count</th>
                    <th className={cn(thClass, "px-2 py-1.5 text-right")}>p50</th>
                    <th className={cn(thClass, "px-2 py-1.5 text-right")}>p95</th>
                    <th className={cn(thClass, "px-3 py-1.5 text-right")}>p99</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {(["read", "write"] as const).map((op) => {
                    const l = s.latencyByOp[op];
                    return (
                      <tr key={op}>
                        <td className="px-3 py-1.5 text-ink">
                          <span className="mr-1.5 text-ink-2" aria-hidden>
                            {REQ_GLYPH[op]}
                          </span>
                          {op}s
                        </td>
                        <td className="px-2 py-1.5 text-right text-ink-2">{fmtNum(l.count)}</td>
                        <td className="px-2 py-1.5 text-right text-ink">{fmtMs(l.p50)}</td>
                        <td className="px-2 py-1.5 text-right text-ink">{fmtMs(l.p95)}</td>
                        <td className="px-3 py-1.5 text-right text-ink">{fmtMs(l.p99)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Section>
          {(s.clients?.length ?? 0) > 1 && <ClientsTable report={report} />}
          {s.failureBreakdown.length > 0 && (
            <Section title="Where requests failed">
              <ul className="space-y-1 rounded-md border border-line bg-surface p-3 text-xs">
                {s.failureBreakdown.slice(0, 5).map((f, i) => (
                  <li key={i} className="flex justify-between gap-2">
                    <span className="text-ink-2">
                      {f.nodeLabel ?? "?"}: {f.reasonText}
                    </span>
                    <span className="tabular font-mono text-ink">
                      {fmtNum(f.count)} ({fmtPct(f.share, 0)})
                    </span>
                  </li>
                ))}
              </ul>
            </Section>
          )}
        </div>
      </div>
    </div>
  );
}

/** What each client part experienced: mobile users can see a different system than partners. */
function ClientsTable({ report }: { report: Report }) {
  const clients = report.summary.clients ?? [];
  return (
    <Section title="By client">
      <div className="overflow-hidden rounded-md border border-line bg-surface">
        <table className="tabular w-full font-mono text-[11.5px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className={cn(thClass, "px-3 py-1.5")}>client</th>
              <th className={cn(thClass, "px-2 py-1.5 text-right")}>share</th>
              <th className={cn(thClass, "px-2 py-1.5 text-right")}>success</th>
              <th className={cn(thClass, "px-2 py-1.5 text-right")}>p50</th>
              <th className={cn(thClass, "px-3 py-1.5 text-right")}>p95</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {clients.map((c) => {
              const node = report.nodes.find((n) => n.id === c.nodeId);
              const tech = typeof node?.config.technology === "string" ? node.config.technology : null;
              return (
                <tr key={c.nodeId}>
                  <td className="px-3 py-1.5 text-ink">
                    <span className="flex items-center gap-1.5">
                      <TechIcon technology={tech} kind="traffic" size={13} />
                      <span className="truncate">{c.label}</span>
                    </span>
                  </td>
                  <td className="px-2 py-1.5 text-right text-ink-2">{fmtPct(c.share, 0)}</td>
                  <td className="px-2 py-1.5 text-right text-ink">{fmtPct(c.successRate, 1)}</td>
                  <td className="px-2 py-1.5 text-right text-ink">{fmtMs(c.latencyMs.p50)}</td>
                  <td className="px-3 py-1.5 text-right text-ink">{fmtMs(c.latencyMs.p95)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Section>
  );
}
