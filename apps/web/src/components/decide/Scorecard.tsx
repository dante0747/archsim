"use client";

import { ArrowRight, Check, CircleAlert, CircleCheck, CircleDashed, Pencil, Play, Plus, X } from "lucide-react";
import { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { FindingCard, RunProgress } from "@/components/results/OverviewTab";
import { Legend, SINGLE_SERIES } from "@/components/results/charts";
import { Button } from "@/components/ui/button";
import { estimateCost, formatUsd } from "@/lib/cost";
import { compact, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { type SloCheck, type SloKey, errorBudget, evaluateSlo, formatSloValue, sloChecks } from "@/lib/slo";
import type { Objectives, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { EmptyPanel, PageHeader, Panel, RunPicker, StatusMark, runDesignNow, runLabel, useDecideRun } from "./shared";

// ---------------------------------------------------------------------------------------------
// Objectives
// ---------------------------------------------------------------------------------------------

const OBJECTIVE_META: Record<SloKey, { field: keyof Objectives; label: string; unit: string; hint: string; toInput: (v: number) => number; fromInput: (v: number) => number; placeholder: string }> = {
  p95: { field: "latencyP95Ms", label: "Latency p95", unit: "ms", hint: "95% of successful requests are faster", toInput: (v) => v, fromInput: (v) => v, placeholder: "300" },
  p99: { field: "latencyP99Ms", label: "Latency p99", unit: "ms", hint: "the slowest 1% start here", toInput: (v) => v, fromInput: (v) => v, placeholder: "800" },
  availability: {
    field: "availability",
    label: "Availability",
    unit: "%",
    hint: "share of requests that succeed",
    toInput: (v) => Math.round(v * 100_000) / 1000,
    fromInput: (v) => v / 100,
    placeholder: "99.9",
  },
  throughput: { field: "minThroughputRps", label: "Throughput", unit: "req/s", hint: "successful requests per second", toInput: (v) => v, fromInput: (v) => v, placeholder: "500" },
};

const ORDER: SloKey[] = ["p95", "p99", "availability", "throughput"];

function validObjective(key: SloKey, v: number): string | null {
  if (!Number.isFinite(v)) return "Enter a number";
  if (key === "availability") return v <= 0 || v > 100 ? "Between 0 and 100" : null;
  return v <= 0 ? "Must be above 0" : null;
}

/** Inline editor for one objective target (Enter saves, Esc cancels, empty clears). */
function TargetEditor({ k, value, onDone }: { k: SloKey; value: number | null; onDone: () => void }) {
  const meta = OBJECTIVE_META[k];
  const setObjectives = useEditor((s) => s.setObjectives);
  const [text, setText] = useState(value == null ? "" : String(meta.toInput(value)));
  const parsed = text.trim() === "" ? null : Number(text);
  const error = parsed == null ? null : validObjective(k, parsed);
  const commit = () => {
    if (error) return;
    setObjectives({ [meta.field]: parsed == null ? null : meta.fromInput(parsed) } as Partial<Objectives>, meta.field);
    onDone();
  };
  return (
    <div className="flex items-center gap-1">
      <input
        autoFocus
        inputMode="decimal"
        value={text}
        placeholder={meta.placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") commit();
          if (e.key === "Escape") onDone();
        }}
        aria-label={`${meta.label} target`}
        aria-invalid={!!error}
        className="h-7 w-24 rounded-sm border border-line bg-surface-2 px-2 font-mono text-[12px] text-ink focus-visible:border-accent focus-visible:outline-none aria-[invalid=true]:border-critical"
      />
      <span className="font-mono text-[11px] text-ink-3">{meta.unit}</span>
      <button type="button" onClick={commit} disabled={!!error} className="rounded-sm p-1 text-ink-2 hover:bg-surface-2 hover:text-ink disabled:opacity-40" aria-label="Save target">
        <Check className="size-3.5" />
      </button>
      <button type="button" onClick={onDone} className="rounded-sm p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Cancel">
        <X className="size-3.5" />
      </button>
      {error && <span className="font-mono text-[10.5px] text-danger-ink">{error}</span>}
    </div>
  );
}

/**
 * Bullet bar: the measured value against the target tick, on a scale that fits both.
 * Availability is shown as the failure share against the failures the target allows.
 */
function Bullet({ check }: { check: SloCheck }) {
  let value = check.actual ?? 0;
  let target = check.target;
  if (check.key === "availability") {
    value = check.actual == null ? 0 : 1 - check.actual;
    target = 1 - check.target;
  }
  const max = Math.max(value, target, 1e-9) * 1.18;
  const color = check.pass === true ? "var(--status-good)" : check.pass === false ? "var(--status-critical)" : "var(--ink-3)";
  return (
    <div className="relative mt-3 h-2.5" aria-hidden>
      <div className="absolute inset-0 rounded-[2px] bg-surface-3" />
      {check.actual != null && <div className="absolute inset-y-0 left-0 rounded-[2px]" style={{ width: `${(value / max) * 100}%`, background: color }} />}
      <div className="absolute -inset-y-1 w-0.5 rounded-full bg-ink" style={{ left: `calc(${(target / max) * 100}% - 1px)` }} />
    </div>
  );
}

function ObjectiveTile({ k, check, objectives }: { k: SloKey; check: SloCheck | undefined; objectives: Objectives }) {
  const meta = OBJECTIVE_META[k];
  const [editing, setEditing] = useState(false);
  const target = objectives[meta.field] ?? null;
  if (target == null && !editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="flex min-h-36 flex-col items-start justify-center gap-1 rounded-md border border-dashed border-line-strong p-4 text-left text-ink-3 transition-colors hover:border-accent hover:text-ink"
      >
        <span className="flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-wider">
          <Plus className="size-3.5" /> {meta.label}
        </span>
        <span className="text-[12px]">Set a target: {meta.hint}.</span>
      </button>
    );
  }
  return (
    <div className="flex min-h-36 flex-col rounded-md border border-line bg-surface p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="microlabel">{meta.label}</p>
          <p className="truncate text-[11px] text-ink-3">{meta.hint}</p>
        </div>
        {check && <StatusMark pass={check.pass} />}
      </div>
      <p className="tabular mt-2 font-mono text-[26px] font-semibold leading-8 tracking-tight text-ink">{check ? formatSloValue(k, check.actual) : "–"}</p>
      {check && <Bullet check={check} />}
      <div className="mt-auto flex min-h-7 items-center justify-between gap-2 pt-2">
        {editing ? (
          <TargetEditor k={k} value={target} onDone={() => setEditing(false)} />
        ) : (
          <>
            <span className="font-mono text-[11px] text-ink-2">
              target {k === "availability" || k === "throughput" ? "≥" : "≤"} {target == null ? "–" : formatSloValue(k, target)}
              {k === "availability" && check?.actual != null && (
                <span className="text-ink-3"> · failures {fmtPct(1 - check.actual, 2)} of ≤ {fmtPct(1 - check.target, 2)}</span>
              )}
            </span>
            <button type="button" onClick={() => setEditing(true)} className="rounded-sm p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label={`Edit ${meta.label} target`}>
              <Pencil className="size-3.5" />
            </button>
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Error budget
// ---------------------------------------------------------------------------------------------

function burnDown(report: Report, availability: number) {
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

function ErrorBudgetPanel({ report, objectives }: { report: Report; objectives: Objectives }) {
  const budget = errorBudget(objectives, report);
  const rows = useMemo(() => (objectives.availability != null ? burnDown(report, objectives.availability) : []), [report, objectives.availability]);
  if (!budget || objectives.availability == null) {
    return (
      <Panel title="Error budget">
        <p className="text-[12.5px] text-ink-2">Set an availability objective to see how much failure the design can afford.</p>
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
          <p className="font-mono text-[11px] text-ink-3">
            {fmtNum(budget.failed)} failed · {fmtNum(Math.floor(budget.allowed))} allowed
          </p>
        </div>
        <div className="min-w-48 flex-1">
          <div className="relative h-2.5 rounded-[2px] bg-surface-3" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(Math.min(consumed, 1) * 100)} aria-label="Error budget used">
            <div className="absolute inset-y-0 left-0 rounded-[2px]" style={{ width: `${Math.min(Number.isFinite(consumed) ? consumed : 1, 1) * 100}%`, background: color }} />
          </div>
          <div className="mt-1 flex justify-between font-mono text-[10px] text-ink-3">
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
                      <div className="rounded-md border border-line-strong bg-surface px-2 py-1.5 font-mono text-[11px] shadow-float">
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

// ---------------------------------------------------------------------------------------------
// Cost
// ---------------------------------------------------------------------------------------------

function CostPanel({ report }: { report: Report }) {
  const catalog = useEditor((s) => s.catalog);
  const cost = useMemo(() => estimateCost(report.input.architecture.nodes, catalog, report), [report, catalog]);
  const max = Math.max(...cost.byNode.map((c) => c.monthly), 1e-9);
  return (
    <Panel title="Cost estimate" subtitle="from the cost assumptions on each component">
      {!cost.hasCost ? (
        <p className="text-[12.5px] leading-relaxed text-ink-2">
          No component has a price yet. Set <b className="text-ink">Cost per hour</b> on components in Design to compare options by cost.
        </p>
      ) : (
        <>
          <div className="flex items-end justify-between gap-3">
            <div>
              <p className="tabular font-mono text-[26px] font-semibold leading-8 tracking-tight text-ink">{formatUsd(cost.monthly)}</p>
              <p className="font-mono text-[11px] text-ink-3">per month · 730 h</p>
            </div>
            <div className="text-right">
              <p className="tabular font-mono text-[15px] font-semibold text-ink">{formatUsd(cost.perMillionRequests)}</p>
              <p className="font-mono text-[11px] text-ink-3">per 1M successful req</p>
            </div>
          </div>
          <ul className="mt-4 space-y-2.5" aria-label="Cost by component">
            {cost.byNode.map((c) => (
              <li key={c.id}>
                <div className="flex items-baseline justify-between gap-2 text-[12px]">
                  <span className="min-w-0 truncate text-ink">{c.label}</span>
                  <span className="tabular shrink-0 font-mono text-ink">{c.unitPrice == null ? "not set" : formatUsd(c.monthly)}</span>
                </div>
                <div className="mt-1 flex items-center gap-2">
                  <div className="h-1.5 flex-1 rounded-[2px] bg-surface-3" aria-hidden>
                    <div className="h-full rounded-[2px]" style={{ width: `${(c.monthly / max) * 100}%`, background: SINGLE_SERIES }} />
                  </div>
                  <span className="w-28 shrink-0 truncate text-right font-mono text-[10px] text-ink-3">{c.basis}</span>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
      {cost.notes.length > 0 && (
        <ul className="mt-3 space-y-1 border-t border-line pt-2.5">
          {cost.notes.map((n) => (
            <li key={n} className="text-[11px] leading-relaxed text-ink-3">
              ≈ {n}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

export function Scorecard() {
  const objectives = useEditor((s) => s.objectives);
  const catalog = useEditor((s) => s.catalog);
  const setScorecardRunId = useDecide((s) => s.setScorecardRunId);
  const setSection = useDecide((s) => s.setSection);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const running = followRun && (followRun.status === "queued" || followRun.status === "running") ? followRun : null;
  const { run, report } = useDecideRun();

  if (!run || !report) {
    return (
      <div>
        <PageHeader kicker="summary" title="Does the design meet its objectives?" description="Press Simulate to score the design against its latency, availability and throughput objectives, its error budget and its cost." />
        <div className="space-y-4 p-6">
          {running && <RunProgress run={running} />}
          {!running && (
            <EmptyPanel
              icon={<Play />}
              title={run ? "Loading the run…" : "No results to score yet"}
              action={
                !run && (
                  <Button variant="primary" onClick={runDesignNow}>
                    <Play className="fill-current" /> Run simulation
                  </Button>
                )
              }
            >
              The scorecard compares one run with your objectives. Set targets now; they are saved with the design.
            </EmptyPanel>
          )}
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {ORDER.map((k) => (
              <ObjectiveTile key={k} k={k} check={undefined} objectives={objectives} />
            ))}
          </div>
        </div>
      </div>
    );
  }

  const slo = evaluateSlo(objectives, report);
  const checks = sloChecks(objectives, report);
  const cost = estimateCost(report.input.architecture.nodes, catalog, report);
  const bottleneck = report.findings.find((f) => f.kind === "bottleneck");
  const tone = slo.pass === true ? "good" : slo.pass === false ? "bad" : "none";
  const VerdictIcon = tone === "good" ? CircleCheck : tone === "bad" ? CircleAlert : CircleDashed;
  const toneColor = tone === "good" ? "var(--status-good)" : tone === "bad" ? "var(--status-critical)" : "var(--ink-3)";
  const title =
    slo.total === 0 ? "No objectives set" : slo.pass === true ? `Meets all ${slo.total} objectives` : slo.pass === false ? `Misses ${slo.total - slo.met} of ${slo.total} objectives` : "Not enough data to decide";
  const findings = report.findings.filter((f) => f.kind !== "healthy");

  return (
    <div>
      <PageHeader
        kicker="summary"
        title="Does the design meet its objectives?"
        actions={<RunPicker value={run.id} onChange={(id) => setScorecardRunId(id)} />}
      />
      <div className="space-y-4 p-6">
        {running && <RunProgress run={running} />}

        <section className="relative flex flex-wrap items-center justify-between gap-x-8 gap-y-3 overflow-hidden rounded-md border border-line bg-surface px-5 py-4" aria-label="Verdict">
          <span className="absolute inset-y-0 left-0 w-1" style={{ background: toneColor }} aria-hidden />
          <div className="flex min-w-0 items-center gap-3">
            <VerdictIcon className="size-7 shrink-0" style={{ color: toneColor }} aria-hidden />
            <div className="min-w-0">
              <p className="microlabel">verdict · {runLabel(run)} · seed {run.seed}</p>
              <p className="text-xl font-semibold tracking-tight text-ink">{title}</p>
              <p className="truncate text-[12.5px] text-ink-2">
                {fmtRps(report.summary.throughputRps)} of {fmtRps(report.summary.offeredRps)} offered
                {bottleneck ? ` · ${bottleneck.title}` : " · no component saturated"}
              </p>
            </div>
          </div>
          <dl className="flex shrink-0 gap-6 font-mono">
            <div>
              <dt className="microlabel">objectives</dt>
              <dd className="tabular text-[15px] font-semibold text-ink">
                {slo.met}/{slo.total}
              </dd>
            </div>
            {cost.hasCost && (
              <>
                <div>
                  <dt className="microlabel">per month</dt>
                  <dd className="tabular text-[15px] font-semibold text-ink">{formatUsd(cost.monthly)}</dd>
                </div>
                <div>
                  <dt className="microlabel">per 1M req</dt>
                  <dd className="tabular text-[15px] font-semibold text-ink">{formatUsd(cost.perMillionRequests)}</dd>
                </div>
              </>
            )}
          </dl>
        </section>

        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {ORDER.map((k) => (
            <ObjectiveTile key={k} k={k} check={checks.find((c) => c.key === k)} objectives={objectives} />
          ))}
        </div>

        <div className="grid gap-4 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
          <ErrorBudgetPanel report={report} objectives={objectives} />
          <CostPanel report={report} />
        </div>

        <Panel
          title="What to look at"
          subtitle={findings.length ? `${findings.length} finding${findings.length > 1 ? "s" : ""} from this run` : "no issues found"}
          actions={
            <Button size="sm" variant="ghost" onClick={() => setSection("insights")}>
              All insights <ArrowRight />
            </Button>
          }
        >
          <ul className={cn("grid gap-2", findings.length > 1 && "lg:grid-cols-2")}>
            {(findings.length ? findings.slice(0, 4) : report.findings.slice(0, 1)).map((f, i) => (
              <FindingCard key={`${f.kind}-${i}`} f={f} />
            ))}
          </ul>
        </Panel>
      </div>
    </div>
  );
}
