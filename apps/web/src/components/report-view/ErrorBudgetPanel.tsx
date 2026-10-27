"use client";

import { useMemo } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Legend, SINGLE_SERIES } from "@/components/results/charts";
import { compact, fmtNum, fmtPct } from "@/lib/format";
import { errorBudget } from "@/lib/slo";
import type { Objectives, Report } from "@/lib/types";
import { Panel } from "./Panel";

function burnDown(report: Pick<Report, "timeseries" | "window">, availability: number) {
  const ts = report.timeseries;
  const { warmupS, durationS } = report.window;
  let arrived = 0;
  let failed = 0;
  const rows: { t: number; failed: number; allowed: number }[] = [];
  ts.t.forEach((t, i) => {
    if (t < warmupS || t >= durationS) return;
    const ok = ts.throughputRps[i] ?? 0;
    const bad = (ts.errorRps[i] ?? 0) + (ts.timeoutRps[i] ?? 0);
    arrived += (ok + bad) * ts.bucketS;
    failed += bad * ts.bucketS;
    rows.push({ t, failed: Math.round(failed), allowed: Math.round((1 - availability) * arrived) });
  });
  return rows;
}

/** Failures against what the availability objective allows, over the measured window. */
export function ErrorBudgetPanel({ report, objectives }: { report: Pick<Report, "summary" | "timeseries" | "window">; objectives: Objectives }) {
  const budget = errorBudget(objectives, report);
  const rows = useMemo(() => (objectives.availability != null ? burnDown(report, objectives.availability) : []), [report, objectives.availability]);
  if (!budget || objectives.availability == null) {
    return (
      <Panel title="Error budget">
        <p className="text-[13px] text-ink-2">Set an availability objective to see how much failure the design can afford.</p>
      </Panel>
    );
  }
  const consumed = budget.consumed ?? 0;
  const over = !Number.isFinite(consumed) || consumed > 1;
  const color = over ? "var(--status-critical)" : consumed >= 0.75 ? "var(--status-warning)" : "var(--status-good)";
  const series = [
    { key: "failed", label: "Failed requests (cumulative)", color: SINGLE_SERIES },
    { key: "allowed", label: "Allowed by the objective", color: "var(--muted-axis)" },
  ];
  const tick = { fill: "var(--muted-axis)", fontSize: 10.5, fontFamily: "var(--font-mono)" };
  return (
    <Panel title="Error budget" subtitle={`availability ≥ ${fmtPct(objectives.availability, 2)} over the measured window`}>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="tabular font-mono text-[26px] font-semibold leading-8 tracking-tight text-ink">
            {!Number.isFinite(consumed) ? "exhausted" : over ? `${consumed.toFixed(1)}× over` : `${Math.round(consumed * 100)}% used`}
          </p>
          <p className="font-mono text-xs text-ink-3">
            {fmtNum(budget.failed)} failed · {fmtNum(Math.floor(budget.allowed))} allowed
          </p>
        </div>
        <div className="min-w-48 flex-1">
          <div className="relative h-2.5 rounded-[2px] bg-surface-3" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(Math.min(consumed, 1) * 100)} aria-label="Error budget used">
            <div className="absolute inset-y-0 left-0 rounded-[2px]" style={{ width: `${Math.min(Number.isFinite(consumed) ? consumed : 1, 1) * 100}%`, background: color }} />
          </div>
          <div className="mt-1 flex justify-between font-mono text-[11px] text-ink-3">
            <span>0%</span>
            <span>budget</span>
            <span>100%</span>
          </div>
        </div>
      </div>
      {rows.length > 1 && (
        <figure className="mt-4">
          <figcaption className="mb-1.5">
            <Legend series={series} />
          </figcaption>
          <div className="h-36">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={rows} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid stroke="var(--grid)" vertical={false} />
                <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} tickFormatter={(v: number) => `${v}s`} stroke="var(--axis)" tick={tick} tickLine={false} minTickGap={24} />
                <YAxis width={44} tickFormatter={(v: number) => compact(v)} tick={tick} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip
                  isAnimationActive={false}
                  cursor={{ stroke: "var(--line-strong)" }}
                  content={({ active, payload, label }) =>
                    active && payload?.length ? (
                      <div className="rounded-md border border-line-strong bg-surface px-2 py-1.5 font-mono text-xs shadow-float">
                        <p className="mb-1 text-ink-3">by t = {label}s</p>
                        {payload.map((p) => (
                          <p key={String(p.dataKey)} className="flex items-center gap-2 text-ink-2">
                            <span className="inline-block h-0.5 w-3 rounded" style={{ background: String(p.color) }} aria-hidden />
                            {p.name}
                            <span className="tabular ml-auto pl-3 text-ink">{fmtNum(Number(p.value))}</span>
                          </p>
                        ))}
                      </div>
                    ) : null
                  }
                />
                <Line type="monotone" dataKey="allowed" name={series[1].label} stroke={series[1].color} strokeWidth={1.5} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
                <Line type="monotone" dataKey="failed" name={series[0].label} stroke={series[0].color} strokeWidth={2} dot={false} isAnimationActive={false} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </figure>
      )}
    </Panel>
  );
}
