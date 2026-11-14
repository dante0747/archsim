"use client";

import { ArrowRight, Check, CircleAlert, CircleCheck, CircleDashed, Pencil, Play, Plus, X } from "lucide-react";
import { useState } from "react";
import { FindingCard } from "@/components/report-view/FindingCard";
import { RunProgress } from "@/components/results/OverviewTab";
import { SINGLE_SERIES } from "@/components/results/charts";
import { Button } from "@/components/ui/button";
import { TierBadge } from "@/components/ui/TierBadge";
import { SignOffPanel } from "@/components/team/SignOff";
import { formatUsd, monthText, peakCount, peakHour, priceSource } from "@/lib/cost";
import { fmtPct, fmtRps } from "@/lib/format";
import { type SloCheck, type SloKey, evaluateSlo, formatSloValue, sloChecks } from "@/lib/slo";
import type { Objectives, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { EstimatePending, EstimateVsSimulation } from "./Confidence";
import { SeedConfidenceLine, SeedRange } from "./SeedRange";
import { EmptyPanel, Panel, StatusMark, runDesignNow, useDecideRun } from "./shared";

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
        className="h-7 w-24 rounded-sm border border-line bg-surface-2 px-2 font-mono text-xs text-ink focus-visible:border-accent focus-visible:outline-none aria-[invalid=true]:border-critical"
      />
      <span className="font-mono text-xs text-ink-3">{meta.unit}</span>
      <button type="button" onClick={commit} disabled={!!error} className="rounded-sm p-1 text-ink-2 hover:bg-surface-2 hover:text-ink disabled:opacity-40" aria-label="Save target">
        <Check className="size-3.5" />
      </button>
      <button type="button" onClick={onDone} className="rounded-sm p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Cancel">
        <X className="size-3.5" />
      </button>
      {error && <span className="font-mono text-[11px] text-danger-ink">{error}</span>}
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
        className="group flex min-h-36 flex-col items-start justify-center gap-1 rounded-xl bg-surface-2/60 p-4 text-left transition-colors hover:bg-surface-2"
      >
        <span className="flex items-center gap-1.5 text-[13px] font-medium text-ink-2 group-hover:text-ink">
          <Plus className="size-4 text-accent" /> {meta.label}
        </span>
        <span className="text-xs text-ink-3">Add a target</span>
      </button>
    );
  }
  return (
    <div className="flex min-h-36 flex-col rounded-md border border-line bg-surface p-4">
      <div className="flex items-start justify-between gap-2">
        <p className="min-w-0 text-[13px] font-medium text-ink-2" title={meta.hint}>
          {meta.label}
        </p>
        {check && <StatusMark pass={check.pass} />}
      </div>
      <p className="tabular mt-2 font-mono text-[26px] font-semibold leading-8 tracking-tight text-ink">{check ? formatSloValue(k, check.actual) : "–"}</p>
      {check && <Bullet check={check} />}
      <div className="mt-auto flex min-h-7 items-center justify-between gap-2 pt-2">
        {editing ? (
          <TargetEditor k={k} value={target} onDone={() => setEditing(false)} />
        ) : (
          <>
            <span className="font-mono text-xs text-ink-2">
              target {k === "availability" || k === "throughput" ? "≥" : "≤"} {target == null ? "–" : formatSloValue(k, target)}
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
// Cost
// ---------------------------------------------------------------------------------------------

function CostPanel({ report }: { report: Report }) {
  const cost = report.cost;
  if (!cost) {
    return (
      <Panel title="Cost per month">
        <p className="text-[13px] leading-relaxed text-ink-2">This run was made before reports included a cost estimate. Simulate again to price it.</p>
      </Panel>
    );
  }
  const max = Math.max(...cost.byNode.map((c) => c.monthly), 1e-9);
  const source = priceSource(cost);
  const hour = peakHour(cost);
  return (
    <Panel title="Cost per month">
      {!cost.hasCost ? (
        <p className="text-[13px] leading-relaxed text-ink-2">
          No prices set. Add <b className="text-ink">Cost per hour</b> on parts in Design.
        </p>
      ) : (
        <>
          <div className="flex items-end justify-between gap-3">
            <div>
              <p className="tabular font-mono text-[26px] font-semibold leading-8 tracking-tight text-ink">{formatUsd(cost.monthly)}</p>
              <p className="font-mono text-xs text-ink-3">per month · 730 h</p>
            </div>
            <div className="text-right">
              <p className="tabular font-mono text-[15px] font-semibold text-ink">{formatUsd(cost.perMillionRequests)}</p>
              <p className="font-mono text-xs text-ink-3">per 1M successful req</p>
            </div>
          </div>
          <p className="mt-2 text-xs text-ink-2" data-testid="cost-source" title={source.detail}>
            Prices: <span className={cn("font-mono", source.illustrative ? "text-ink-3" : "text-ink")}>{source.label}</span>
            {!source.illustrative && <span className="text-ink-3"> · on-demand list prices</span>}
          </p>
          {hour && (
            <p
              className="mt-1 text-xs text-ink-2"
              data-testid="cost-peak-hour"
              title={`${monthText(cost)} At the peak, autoscaled parts run more instances and per-request parts handle more requests.`}
            >
              An hour at the peak: <span className="tabular font-mono text-ink">{formatUsd(hour.peak)}</span>
              <span className="text-ink-3"> · {formatUsd(hour.base)} at the base load</span>
              {(cost.peakHours ?? 0) > 0 && (
                <span className="text-ink-3">
                  {" "}
                  · {cost.peakHours} peak {cost.peakHours === 1 ? "hour" : "hours"} a month
                </span>
              )}
            </p>
          )}
          <ul className="mt-4 space-y-2.5" aria-label="Cost by component">
            {cost.byNode.map((c) => (
              <li key={c.id}>
                <div className="flex items-baseline justify-between gap-2 text-xs">
                  <span className="min-w-0 truncate text-ink">
                    {c.label}
                    {c.priceSource === "size" && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-3" title="No price set: priced from its CPU, memory and storage at illustrative on-demand rates">
                        from size
                      </span>
                    )}
                    {c.priceSource === "preset" && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-3" title="The technology preset's illustrative price: choose a price snapshot to price it as an instance type">
                        preset price
                      </span>
                    )}
                    {c.sku && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-3" title={`On-demand list price of ${c.sku} on ${c.snapshotDate ?? "the snapshot date"}`}>
                        {c.sku}
                      </span>
                    )}
                    {c.priceSource === "set" && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-3" title="A price you set on the part">
                        your price
                      </span>
                    )}
                    {c.priceSource === "default" && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-3" title="The catalog's default price for this kind of part (illustrative)">
                        default price
                      </span>
                    )}
                  </span>
                  <span className="tabular shrink-0 font-mono text-ink">{c.unitPrice == null ? "not set" : formatUsd(c.monthly)}</span>
                </div>
                <div className="mt-1 flex items-center gap-2">
                  <div className="h-1.5 flex-1 rounded-[2px] bg-surface-3" aria-hidden>
                    <div className="h-full rounded-[2px]" style={{ width: `${(c.monthly / max) * 100}%`, background: SINGLE_SERIES }} />
                  </div>
                  <span className="w-28 shrink-0 truncate text-right font-mono text-[11px] text-ink-3" title={c.basis}>
                    {c.basis}
                  </span>
                </div>
                {peakCount(c) && (
                  <p className="mt-0.5 text-right font-mono text-[11px] leading-4 text-ink-3" data-testid="cost-peak-count">
                    {peakCount(c)}
                  </p>
                )}
                {c.items && c.items.length > 0 && (
                  <p className="mt-0.5 font-mono text-[11px] leading-4 text-ink-3" data-testid="cost-units" title={c.basis}>
                    {c.items.map((i) => i.text).join(" + ")}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
      {cost.notes.length > 0 && (
        <ul className="mt-3 space-y-1 border-t border-line pt-2.5">
          {cost.notes.map((n) => (
            <li key={n} className="text-xs leading-relaxed text-ink-3">
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

const SEVERITY_RANK: Record<string, number> = { critical: 0, warning: 1, info: 2, ok: 3 };

function Stat({ label, value, source }: { label: string; value: string; source?: { label: string; detail: string } }) {
  return (
    <div>
      <dd className="tabular font-mono text-[22px] font-semibold leading-7 tracking-tight text-ink">{value}</dd>
      <dt className="text-xs text-ink-3">
        {label}
        {source && (
          <span title={source.detail} data-testid="stat-price-source">
            {" "}
            · {source.label}
          </span>
        )}
      </dt>
    </div>
  );
}

/** Results → Answer: does the design hold up, why, and what to fix first. */
export function AnswerPage() {
  const objectives = useEditor((s) => s.objectives);
  const select = useEditor((s) => s.select);
  const setDetailsView = useDecide((s) => s.setDetailsView);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const running = followRun && (followRun.status === "queued" || followRun.status === "running") ? followRun : null;
  const { run, report } = useDecideRun();
  const analysis = useEditor((s) => s.analysis);
  // the live analysis is of the base design: only a run of the base design can borrow its estimate
  const pendingEstimate = running && !running.scenarioId ? (analysis?.estimate ?? null) : null;

  if (!run || !report) {
    return (
      <div className="mx-auto max-w-5xl space-y-5 px-6 py-8">
        {running && <RunProgress run={running} />}
        {pendingEstimate && <EstimatePending estimate={pendingEstimate} />}
        {!running && (
          <EmptyPanel
            icon={<Play />}
            title={run ? "Loading the run…" : "No results yet"}
            action={
              !run && (
                <Button variant="primary" onClick={runDesignNow}>
                  <Play className="fill-current" /> Simulate
                </Button>
              )
            }
          >
            Set your targets below, then simulate.
          </EmptyPanel>
        )}
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {ORDER.map((k) => (
            <ObjectiveTile key={k} k={k} check={undefined} objectives={objectives} />
          ))}
        </div>
      </div>
    );
  }

  const slo = evaluateSlo(objectives, report);
  const checks = sloChecks(objectives, report);
  const cost = report.cost ?? { monthly: 0, perMillionRequests: null, byNode: [], notes: [], hasCost: false };
  const bottleneck = report.findings.find((f) => f.kind === "bottleneck");
  const tone = slo.pass === true ? "good" : slo.pass === false ? "bad" : "none";
  const VerdictIcon = tone === "good" ? CircleCheck : tone === "bad" ? CircleAlert : CircleDashed;
  const toneColor = tone === "good" ? "var(--status-good)" : tone === "bad" ? "var(--status-critical)" : "var(--ink-3)";
  const missed = checks.filter((c) => c.pass === false);
  const headline =
    slo.total === 0 ? "No objectives yet" : slo.pass === true ? "Yes, it holds up." : slo.pass === false ? "No, it doesn’t hold up." : "Not enough data yet.";
  const reason =
    slo.total === 0
      ? "Set a latency or availability target below to get a yes or no."
      : slo.pass === false
        ? missed.map((c) => `${c.label} ${formatSloValue(c.key, c.actual)} (target ${c.better === "lower" ? "≤" : "≥"} ${formatSloValue(c.key, c.target)})`).join(" · ")
        : slo.pass === true
          ? `Meets all ${slo.total} objectives at ${fmtRps(report.summary.offeredRps)}.`
          : "Try a longer run or more traffic.";
  const experimental = (report.assumptions.technologies ?? []).filter((t) => t.tier === "experimental");
  // runs before report 1.13 have no confidence: fall back to the current design's
  const confidence = report.confidence ?? analysis?.confidence ?? null;
  const findings = report.findings
    .filter((f) => f.kind !== "healthy")
    .sort((a, b) => (SEVERITY_RANK[a.severity] ?? 9) - (SEVERITY_RANK[b.severity] ?? 9));

  return (
    <div className="mx-auto max-w-5xl space-y-8 px-6 py-8">
      {running && <RunProgress run={running} />}
      {pendingEstimate && <EstimatePending estimate={pendingEstimate} />}

      <section aria-label="Answer">
        <div key={report.provenance.resultDigest} className="pop-in flex items-start gap-4">
          <VerdictIcon className="mt-1.5 size-9 shrink-0" style={{ color: toneColor }} aria-hidden />
          <div className="min-w-0">
            <h2 className="font-display text-[46px] leading-[1.02] tracking-[-0.035em] text-ink">{headline}</h2>
            <p className="mt-2 text-[15px] leading-relaxed text-ink-2">{reason}</p>
            <SeedRange run={run} report={report} />
            {bottleneck && <p className="mt-1 text-[15px] text-ink">{bottleneck.title}.</p>}
            {confidence && <SeedConfidenceLine run={run} confidence={confidence} fromDesign={!report.confidence} />}
            {experimental.length > 0 && (
              <p className="mt-2 flex flex-wrap items-center gap-1.5 text-[13px] text-ink-3">
                <TierBadge tier="experimental" />
                {experimental.map((t) => t.label).join(", ")} {experimental.length === 1 ? "uses a technology" : "use technologies"} whose model is not yet validated against
                measurements.
              </p>
            )}
          </div>
        </div>
        <dl className="mt-6 flex flex-wrap gap-x-12 gap-y-4 border-t border-line pt-5">
          <Stat label="objectives met" value={`${slo.met}/${slo.total}`} />
          <Stat label="handled" value={fmtRps(report.summary.throughputRps)} />
          <Stat label="latency p95" value={formatSloValue("p95", report.summary.latencyMs.p95)} />
          {report.summary.successRate != null && report.summary.successRate < 1 && report.summary.latencyWithFailuresMs?.p95 != null && (
            <Stat label="p95 counting failures as timeouts" value={formatSloValue("p95", report.summary.latencyWithFailuresMs.p95)} />
          )}
          <Stat label="success" value={fmtPct(report.summary.successRate, report.summary.successRate === 1 ? 0 : 1)} />
          {cost.hasCost && <Stat label="per month" value={formatUsd(cost.monthly)} source={priceSource(cost)} />}
        </dl>
        <EstimateVsSimulation report={report} />
      </section>
      <SignOffPanel run={run} />

      {findings.length > 0 && (
        <section aria-label="Fix first">
          <div className="mb-3 flex items-baseline justify-between">
            <h3 className="text-[15px] font-semibold text-ink">{slo.pass === false ? "Fix first" : "Worth a look"}</h3>
            {findings.length > 2 && (
              <Button size="sm" variant="ghost" onClick={() => setDetailsView("summary")}>
                All {findings.length} findings <ArrowRight />
              </Button>
            )}
          </div>
          <ul className={cn("grid gap-2", findings.length > 1 && "lg:grid-cols-2")}>
            {findings.slice(0, 2).map((f, i) => (
              <FindingCard key={`${f.kind}-${i}`} f={f} onShowNode={(nodeId) => select({ nodeId })} />
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Objectives">
        <h3 className="mb-3 text-[15px] font-semibold text-ink">Objectives</h3>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {ORDER.map((k) => (
            <ObjectiveTile key={k} k={k} check={checks.find((c) => c.key === k)} objectives={objectives} />
          ))}
        </div>
      </section>

      <CostPanel report={report} />
    </div>
  );
}
