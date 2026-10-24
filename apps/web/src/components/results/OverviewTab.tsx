"use client";

import { CircleAlert, CircleCheck, Info, LoaderCircle, Play, RotateCcw, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge, Empty, Section, Tip } from "@/components/ui/misc";
import { fmtMs, fmtNum, fmtPct, fmtRps, shortDigest } from "@/lib/format";
import { SEVERITY } from "@/lib/status";
import type { Finding, Report, Run } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

function Kpi({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="min-w-0 rounded-lg border border-line bg-surface px-3 py-2">
      <p className="truncate text-[11px] text-ink-3">{label}</p>
      <p className="truncate text-lg font-semibold leading-7 text-ink">{value}</p>
      {hint && <p className="truncate text-[11px] text-ink-3">{hint}</p>}
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
    <li className="flex gap-3 rounded-lg border border-line bg-surface p-3">
      <Icon className="mt-0.5 size-4 shrink-0" style={{ color: meta.color }} aria-hidden />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="sr-only">{meta.label}:</span>
          <p className="text-sm font-medium text-ink">{f.title}</p>
          {f.nodeId && (
            <button type="button" className="text-[11px] text-accent hover:underline" onClick={() => select({ nodeId: f.nodeId })}>
              show on canvas
            </button>
          )}
        </div>
        <p className="text-xs leading-relaxed text-ink-2">{f.detail}</p>
        {f.suggestion && <p className="text-xs leading-relaxed text-ink"><span className="font-medium">Try: </span>{f.suggestion}</p>}
      </div>
    </li>
  );
}

export function RunProgress({ run }: { run: Run }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-line bg-surface p-3" role="status">
      <LoaderCircle className="size-4 animate-spin text-ink-3" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-ink">
          {run.status === "queued" ? "Queued" : "Simulating"}: {run.label}
        </p>
        <p className="text-[11px] text-ink-3">
          Progress is the simulated clock of the discrete-event engine ({Math.round(run.progress * 100)}% of simulated time).
        </p>
        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-surface-3">
          <div className="h-full rounded-full bg-accent transition-[width]" style={{ width: `${run.progress * 100}%` }} />
        </div>
      </div>
    </div>
  );
}

export function ProvenanceBar({ report, run }: { report: Report; run: Run | undefined }) {
  const rerun = useRuns((s) => s.rerun);
  const p = report.provenance;
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-ink-2">
      <Badge tone="accent">Simulation-derived</Badge>
      <span className="font-medium text-ink">{run?.label ?? p.scenarioName ?? "Design as drawn"}</span>
      <Tip content="Random seed: the same document + scenario + seed + engine version reproduces these results exactly.">
        <Badge>seed {p.seed}</Badge>
      </Tip>
      <Badge>engine {p.engineVersion}</Badge>
      <Tip content={`Input digest ${p.inputDigest}`}>
        <Badge>input {shortDigest(p.inputDigest)}</Badge>
      </Tip>
      <Tip content={`Result digest ${p.resultDigest}`}>
        <Badge>result {shortDigest(p.resultDigest)}</Badge>
      </Tip>
      <Badge>
        {fmtNum(p.requestsGenerated)} requests · {fmtNum(report.window.simulatedS, 1)} s simulated in {fmtNum(p.wallSeconds, 1)} s
      </Badge>
      {run && (
        <Button size="sm" variant="ghost" onClick={() => void rerun(run.id)} title="Re-run the stored snapshot and check the result digest matches">
          <RotateCcw /> Reproduce
        </Button>
      )}
    </div>
  );
}

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
            Press <b>Run</b> to simulate the design (or a scenario). The engine generates requests from your workload,
            moves them through queues and workers, and reports what it measured. Every number here comes from that run.
          </Empty>
        )}
      </div>
    );
  }
  const s = report.summary;
  const lat = s.latencyMs;
  const acc = s.accounting;
  return (
    <div className="space-y-4 p-4">
      {running && <RunProgress run={running} />}
      <ProvenanceBar report={report} run={run} />
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5 2xl:grid-cols-9">
        <Kpi label="Offered load" value={fmtRps(s.offeredRps)} hint="arrivals after warm-up" />
        <Kpi label="Throughput (successful)" value={fmtRps(s.throughputRps)} />
        <Kpi label="Success rate" value={fmtPct(s.successRate)} hint={`${fmtNum(s.succeeded)} of ${fmtNum(s.arrived)}`} />
        <Kpi label="Latency p50" value={fmtMs(lat.p50)} hint="successful requests" />
        <Kpi label="Latency p95" value={fmtMs(lat.p95)} />
        <Kpi label="Latency p99" value={fmtMs(lat.p99)} hint={`max ${fmtMs(lat.max)}`} />
        <Kpi label="Failed" value={fmtNum(s.failed)} hint={fmtPct(s.errorRate)} />
        <Kpi label="Timed out (client)" value={fmtNum(s.timedOut)} hint={fmtPct(s.timeoutRate)} />
        <Kpi label="Attempts per request" value={s.retryAmplification == null ? "–" : `${fmtNum(s.retryAmplification, 2)}×`} hint="1.00× = no retries" />
      </div>

      <div className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <Section title="What the simulation found">
          <ul className="space-y-2">
            {report.findings.map((f, i) => (
              <FindingCard key={i} f={f} />
            ))}
          </ul>
        </Section>
        <div className="space-y-4">
          <Section title="Request accounting (after warm-up)">
            <div className="rounded-lg border border-line bg-surface p-3 text-xs">
              <p className="tabular text-ink">
                {fmtNum(acc.measured.arrived)} arrived = {fmtNum(acc.measured.succeeded)} succeeded + {fmtNum(acc.measured.failed)} failed +{" "}
                {fmtNum(acc.measured.timedOut)} timed out + {fmtNum(acc.measured.outstanding)} unfinished
              </p>
              <p className="mt-1 flex items-center gap-1.5 text-ink-2">
                {acc.invariantHolds ? (
                  <CircleCheck className="size-3.5" style={{ color: "var(--status-good)" }} />
                ) : (
                  <CircleAlert className="size-3.5" style={{ color: "var(--status-critical)" }} />
                )}
                {acc.invariantHolds ? "Every request is accounted for." : "Accounting mismatch: please report this as a bug."}{" "}
                {fmtNum(acc.warmup.arrived)} warm-up requests excluded from statistics.
              </p>
            </div>
          </Section>
          <Section title="Latency by request type">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-ink-3">
                  <th className="py-1 font-medium">Type</th>
                  <th className="py-1 text-right font-medium">Count</th>
                  <th className="py-1 text-right font-medium">p50</th>
                  <th className="py-1 text-right font-medium">p95</th>
                  <th className="py-1 text-right font-medium">p99</th>
                </tr>
              </thead>
              <tbody className="tabular divide-y divide-line">
                {(["read", "write"] as const).map((op) => {
                  const l = s.latencyByOp[op];
                  return (
                    <tr key={op}>
                      <td className="py-1 capitalize text-ink">{op}s</td>
                      <td className="py-1 text-right text-ink-2">{fmtNum(l.count)}</td>
                      <td className="py-1 text-right text-ink">{fmtMs(l.p50)}</td>
                      <td className="py-1 text-right text-ink">{fmtMs(l.p95)}</td>
                      <td className="py-1 text-right text-ink">{fmtMs(l.p99)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Section>
          {s.failureBreakdown.length > 0 && (
            <Section title="Where requests failed">
              <ul className="space-y-1 text-xs">
                {s.failureBreakdown.slice(0, 5).map((f, i) => (
                  <li key={i} className="flex justify-between gap-2">
                    <span className="text-ink-2">
                      {f.nodeLabel ?? "?"}: {f.reasonText}
                    </span>
                    <span className="tabular text-ink">
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
