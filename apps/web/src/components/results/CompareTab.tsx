"use client";

import { ArrowLeftRight, ArrowRight, GitCompareArrows, ListChecks, LoaderCircle, Minus, TrendingDown, TrendingUp, TriangleAlert } from "lucide-react";
import { runOptionText } from "@/components/decide/shared";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/input";
import { Badge, Empty, Section } from "@/components/ui/misc";
import { fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { NODE_STATUS, SERIES } from "@/lib/status";
import { isClientType } from "@/lib/theme";
import type { ComparisonKpi } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { cn } from "@/lib/utils";
import { fmtAxisMs, fmtAxisRps, thClass, TimeSeriesChart } from "./charts";

function fmtKpi(v: number | null, unit: string): string {
  if (unit === "ratio") return fmtPct(v);
  if (unit === "ms") return fmtMs(v);
  if (unit === "req/s") return fmtRps(v);
  return v == null ? "–" : `${fmtNum(v, 2)}${unit}`;
}

function fmtValue(v: unknown): string {
  if (v === null || v === undefined) return "none";
  if (typeof v === "number") return fmtNum(v, Number.isInteger(v) ? 0 : 3);
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

function Verdict({ k }: { k: ComparisonKpi }) {
  if (k.verdict === "better")
    return (
      <span className="inline-flex items-center gap-1 font-mono text-xs text-success-ink">
        <TrendingUp className="size-3.5" /> better
      </span>
    );
  if (k.verdict === "worse")
    return (
      <span className="inline-flex items-center gap-1 font-mono text-xs text-danger-ink">
        <TrendingDown className="size-3.5" /> worse
      </span>
    );
  if (k.verdict === "changed") return <span className="font-mono text-xs text-ink-2">changed</span>;
  return (
    <span className="inline-flex items-center gap-1 font-mono text-xs text-ink-3">
      <Minus className="size-3.5" /> {k.verdict === "same" ? "same" : "n/a"}
    </span>
  );
}

export function CompareTab() {
  const runs = useRuns((s) => s.runs);
  const compareA = useRuns((s) => s.compareA);
  const compareB = useRuns((s) => s.compareB);
  const comparison = useRuns((s) => s.comparison);
  const comparing = useRuns((s) => s.comparing);
  const setCompare = useRuns((s) => s.setCompare);
  const startBatch = useRuns((s) => s.startBatch);
  const starting = useRuns((s) => s.starting);
  const scenarios = useEditor((s) => s.scenarios);
  const catalog = useEditor((s) => s.catalog);
  const toDocument = useEditor((s) => s.toDocument);
  const done = runs.filter((r) => r.status === "succeeded");

  const picker = (value: string | null, onChange: (v: string | null) => void, label: string) => (
    <NativeSelect aria-label={label} value={value ?? ""} onChange={(e) => onChange(e.target.value || null)} className="w-full sm:w-72">
      <option value="">Choose a finished run…</option>
      {done.map((r) => (
        <option key={r.id} value={r.id}>
          {runOptionText(r)}
        </option>
      ))}
    </NativeSelect>
  );

  return (
    <div className="space-y-4 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge className="font-mono">A</Badge>
        {picker(compareA, (v) => void setCompare(v, compareB), "Run A")}
        <Button variant="ghost" size="icon" aria-label="Swap A and B" onClick={() => void setCompare(compareB, compareA)}>
          <ArrowLeftRight />
        </Button>
        <Badge className="font-mono">B</Badge>
        {picker(compareB, (v) => void setCompare(compareA, v), "Run B")}
        {comparing && <LoaderCircle className="size-4 animate-spin text-ink-3" />}
        {scenarios.length >= 2 && (
          <Button size="sm" className="ml-auto" disabled={starting} onClick={() => void startBatch(toDocument(), scenarios.slice(0, 10).map((s) => s.id))}>
            <ListChecks /> Simulate all scenarios
          </Button>
        )}
      </div>

      {!comparison ? (
        <Empty icon={<GitCompareArrows />} title="Compare two runs side by side">
          Pick two finished runs above, for example the same design with 1 and 3 API replicas. The comparison diffs the
          exact inputs each run simulated and explains the differences in the results.
          {done.length < 2 && " You need at least two finished runs; try “Simulate all scenarios”."}
        </Empty>
      ) : (
        <>
          <div className="grid gap-4 xl:grid-cols-[1.2fr_1fr]">
            <Section title="What changed and why it matters">
              <ul className="space-y-1.5 rounded-md border border-line bg-surface p-3 text-sm text-ink">
                {comparison.explanations.map((e, i) => (
                  <li key={i} className="flex gap-2">
                    <ArrowRight className="mt-1 size-3.5 shrink-0 text-ink-3" />
                    <span>{e}</span>
                  </li>
                ))}
              </ul>
              {comparison.warnings.map((w, i) => (
                <p key={i} className="flex items-start gap-2 rounded-md bg-warning-soft p-2 text-xs text-ink">
                  <TriangleAlert className="mt-0.5 size-3.5 shrink-0" style={{ color: "var(--status-serious)" }} /> {w}
                </p>
              ))}
            </Section>
            <Section title={`Input differences (${comparison.inputDiff.length})`}>
              {comparison.inputDiff.length === 0 ? (
                <p className="text-xs text-ink-2">Both runs simulated identical inputs.</p>
              ) : (
                <div className="max-h-52 overflow-auto rounded-md border border-line bg-surface">
                  <table className="tabular w-full text-xs">
                    <thead className="sticky top-0 bg-surface text-left">
                      <tr className="border-b border-line">
                        <th className={cn(thClass, "px-3 py-1.5")}>Setting</th>
                        <th className={cn(thClass, "px-2 py-1.5")}>A</th>
                        <th className={cn(thClass, "px-3 py-1.5")}>B</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-line">
                      {comparison.inputDiff.map((d) => (
                        <tr key={d.path}>
                          <td className="px-3 py-1.5 text-ink" title={d.path}>
                            {d.label}
                          </td>
                          <td className="px-2 py-1.5 font-mono text-xs text-ink-2">{fmtValue(d.a)}</td>
                          <td className="px-3 py-1.5 font-mono text-xs font-medium text-ink">{fmtValue(d.b)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>
          </div>

          <div className="grid gap-4 xl:grid-cols-2">
            <Section title="Key metrics (after warm-up)">
              <div className="overflow-x-auto rounded-md border border-line bg-surface">
                <table className="tabular w-full text-xs">
                  <thead className="text-left">
                    <tr className="border-b border-line">
                      <th className={cn(thClass, "px-3 py-1.5")}>Metric</th>
                      <th className={cn(thClass, "px-2 py-1.5 text-right normal-case")}>A: {comparison.labels.a}</th>
                      <th className={cn(thClass, "px-2 py-1.5 text-right normal-case")}>B: {comparison.labels.b}</th>
                      <th className={cn(thClass, "px-2 py-1.5 text-right")}>Change</th>
                      <th className={cn(thClass, "px-3 py-1.5")}>B vs A</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {comparison.kpis.map((k) => (
                      <tr key={k.key}>
                        <td className="px-3 py-1.5 text-ink">{k.label}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs text-ink-2">{fmtKpi(k.a, k.unit)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">{fmtKpi(k.b, k.unit)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs text-ink-2">
                          {k.relativeDelta == null ? "–" : `${k.relativeDelta > 0 ? "+" : ""}${fmtPct(k.relativeDelta, 0)}`}
                        </td>
                        <td className="px-3 py-1.5">
                          <Verdict k={k} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Section>
            <Section title="Components">
              <div className="overflow-x-auto rounded-md border border-line bg-surface">
                <table className="tabular w-full text-xs">
                  <thead className="text-left">
                    <tr className="border-b border-line">
                      <th className={cn(thClass, "px-3 py-1.5")}>Component</th>
                      <th className={cn(thClass, "px-2 py-1.5")}>Status A → B</th>
                      <th className={cn(thClass, "px-2 py-1.5 text-right")}>Utilization</th>
                      <th className={cn(thClass, "px-2 py-1.5 text-right")}>Arrivals</th>
                      <th className={cn(thClass, "px-3 py-1.5 text-right")}>Rejected</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {comparison.nodes
                      .filter((n) => !isClientType(n.type, catalog))
                      .map((n) => (
                        <tr key={n.id}>
                          <td className="px-3 py-1.5 text-ink">{n.label}</td>
                          <td className="px-2 py-1.5 font-mono text-xs text-ink-2">
                            {n.statusA ? NODE_STATUS[n.statusA].label : "–"} → {n.statusB ? NODE_STATUS[n.statusB].label : "–"}
                          </td>
                          <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">
                            {fmtPct(n.a?.utilization ?? null, 0)} → {fmtPct(n.b?.utilization ?? null, 0)}
                          </td>
                          <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">
                            {fmtNum(n.a?.arrivalRps ?? null, 0)} → {fmtNum(n.b?.arrivalRps ?? null, 0)}
                          </td>
                          <td className="px-3 py-1.5 text-right font-mono text-xs text-ink">
                            {fmtNum(n.a?.rejectedTotal ?? null)} → {fmtNum(n.b?.rejectedTotal ?? null)}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            </Section>
          </div>

          <div className="grid gap-3 lg:grid-cols-2">
            <OverlayChart which="throughputRps" title="Successful throughput" format={fmtAxisRps} />
            <OverlayChart which="latencyP95Ms" title="p95 latency" format={fmtAxisMs} />
          </div>
        </>
      )}
    </div>
  );
}

function OverlayChart({
  which,
  title,
  format,
}: {
  which: "throughputRps" | "latencyP95Ms";
  title: string;
  format: (v: number) => string;
}) {
  const comparison = useRuns((s) => s.comparison);
  if (!comparison) return null;
  const a = comparison.timeseries.a;
  const b = comparison.timeseries.b;
  // Align on A's time axis when bucket sizes match; otherwise show A's axis and B values by index.
  const t = a.t.length >= b.t.length ? a.t : b.t;
  return (
    <TimeSeriesChart
      title={title}
      subtitle="A vs B over simulated time"
      t={t}
      format={format}
      series={[
        { key: "a", label: `A: ${comparison.labels.a}`, color: SERIES[0], values: a[which] },
        { key: "b", label: `B: ${comparison.labels.b}`, color: SERIES[1], values: b[which] },
      ]}
    />
  );
}
