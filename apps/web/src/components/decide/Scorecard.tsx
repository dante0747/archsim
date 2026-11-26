"use client";

/**
 * Results → Answer: one card with the verdict, the objectives and the caveats; then four key
 * numbers, what to fix first, the month's cost and the review. Every explanation is one click away.
 */
import { CircleAlert, CircleCheck, CircleDashed, Play } from "lucide-react";
import { RunProgress } from "@/components/results/OverviewTab";
import { showOnCanvas } from "@/components/editor/workflow";
import { SignOffPanel } from "@/components/team/SignOff";
import { Button } from "@/components/ui/button";
import { costLeftOut, formatUsd, priceSource } from "@/lib/cost";
import { fmtPct, fmtRps } from "@/lib/format";
import { type SloResult, evaluateSlo, formatSloValue } from "@/lib/slo";
import type { Finding, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { Caveats } from "./Caveats";
import { EstimatePending } from "./Confidence";
import { CostCard } from "./CostCard";
import { FixFirst } from "./FixFirst";
import { ObjectivesGrid, useObjectiveKeys } from "./Objectives";
import { EmptyPanel, runDesignNow, useDecideRun } from "./shared";

const SEVERITY_RANK: Record<string, number> = { critical: 0, warning: 1, info: 2, ok: 3 };

type Tone = "good" | "bad" | "none";

const TONE_COLOR: Record<Tone, string> = { good: "var(--status-good)", bad: "var(--status-critical)", none: "var(--ink-3)" };

/** "Misses 2 of 2 objectives", "Meets all 3 objectives": the verdict in a few words. */
export function verdictSummary(slo: SloResult): string {
  if (slo.total === 0) return "No objectives set";
  const missed = slo.checks.filter((c) => c.pass === false).length;
  if (slo.pass === true) return slo.total === 1 ? "Meets its objective" : `Meets all ${slo.total} objectives`;
  if (slo.pass === false) return `Misses ${missed} of ${slo.total} ${slo.total === 1 ? "objective" : "objectives"}`;
  return "Not enough data yet";
}

function headlineOf(slo: SloResult): string {
  if (slo.total === 0) return "No objectives yet";
  return slo.pass === true ? "Yes, it holds up." : slo.pass === false ? "No, it doesn’t hold up." : "Not enough data yet.";
}

/** The line under the headline: the bottleneck when there is one, else what the verdict rests on. */
function Cause({ slo, report, bottleneck }: { slo: SloResult; report: Report; bottleneck: Finding | undefined }) {
  const part = useEditor((s) => (bottleneck?.nodeId ? (s.nodes.find((n) => n.id === bottleneck.nodeId)?.data.label ?? null) : null));
  if (bottleneck) {
    return (
      <p data-testid="verdict-cause" className="mt-2 flex flex-wrap items-baseline gap-x-3 text-[15px] text-ink-2">
        <span>{bottleneck.title}.</span>
        {bottleneck.nodeId && part && (
          <button
            type="button"
            onClick={() => showOnCanvas(bottleneck.nodeId!)}
            className="text-[13px] font-medium text-accent hover:underline"
            aria-label={`Show ${part} on the canvas`}
          >
            Show on canvas
          </button>
        )}
      </p>
    );
  }
  const text =
    slo.total === 0
      ? "Set a latency or availability target below to get a yes or no."
      : slo.pass === true
        ? `At ${fmtRps(report.summary.offeredRps)}, under the assumptions of this design.`
        : slo.pass === null
          ? "Try a longer run or more traffic."
          : null;
  return text ? (
    <p data-testid="verdict-cause" className="mt-2 text-[15px] text-ink-2">
      {text}
    </p>
  ) : null;
}

function KeyNumber({ label, value, note, warn }: { label: string; value: string; note?: string | null; warn?: string | null }) {
  return (
    <li className="rounded-xl border border-line bg-surface px-4 py-3.5">
      <dl>
        <dt className="text-xs font-medium text-ink-3">{label}</dt>
        <dd className="tabular mt-1 text-[22px] font-semibold leading-7 tracking-tight text-ink">{value}</dd>
        {note && <dd className="mt-0.5 truncate text-xs text-ink-3" title={note}>{note}</dd>}
        {warn && <dd className="mt-0.5 truncate text-xs text-[var(--status-warning)]" title={warn}>{warn}</dd>}
      </dl>
    </li>
  );
}

/** Four numbers a reader looks for first: what it handled, how fast, how reliably, and the bill. */
function KeyNumbers({ report }: { report: Report }) {
  const s = report.summary;
  const cost = report.cost;
  const withFailures = s.successRate != null && s.successRate < 1 ? (s.latencyWithFailuresMs?.p95 ?? null) : null;
  const failed = s.successRate != null && s.successRate < 1 ? `${fmtPct(1 - s.successRate, 1)} failed` : "no failures";
  return (
    <ul aria-label="Key numbers" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <KeyNumber label="Handled" value={fmtRps(s.throughputRps)} note={`of ${fmtRps(s.offeredRps)} offered`} />
      <KeyNumber
        label="Latency p95"
        value={formatSloValue("p95", s.latencyMs.p95)}
        note={withFailures != null ? `${formatSloValue("p95", withFailures)} counting failures` : `p50 ${formatSloValue("p95", s.latencyMs.p50)}`}
      />
      <KeyNumber label="Success" value={fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)} note={failed} />
      <KeyNumber
        label="Cost per month"
        value={cost?.hasCost ? formatUsd(cost.monthly) : "–"}
        note={cost?.hasCost ? priceSource(cost).label : "no prices set"}
        warn={costLeftOut(cost)}
      />
    </ul>
  );
}

/** Results → Answer: does the design hold up, why, and what to fix first. */
export function AnswerPage() {
  const objectives = useEditor((s) => s.objectives);
  const objectiveOrder = useObjectiveKeys(objectives);
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
        <ObjectivesGrid keys={objectiveOrder} checks={[]} objectives={objectives} />
      </div>
    );
  }

  const slo = evaluateSlo(objectives, report);
  const tone: Tone = slo.pass === true ? "good" : slo.pass === false ? "bad" : "none";
  const VerdictIcon = tone === "good" ? CircleCheck : tone === "bad" ? CircleAlert : CircleDashed;
  const bottleneck = report.findings.find((f) => f.kind === "bottleneck");
  // runs before report 1.13 have no confidence: fall back to the current design's
  const confidence = report.confidence ?? analysis?.confidence ?? null;
  const findings = report.findings
    .filter((f) => f.kind !== "healthy")
    .sort((a, b) => (SEVERITY_RANK[a.severity] ?? 9) - (SEVERITY_RANK[b.severity] ?? 9));

  return (
    <div className="mx-auto max-w-5xl space-y-6 px-6 py-8">
      {running && <RunProgress run={running} />}
      {pendingEstimate && <EstimatePending estimate={pendingEstimate} />}

      <section aria-label="Answer" className="overflow-hidden rounded-2xl border border-line bg-surface">
        <div
          key={report.provenance.resultDigest}
          className="pop-in px-6 pb-6 pt-5"
          style={{ backgroundImage: `linear-gradient(180deg, color-mix(in srgb, ${TONE_COLOR[tone]} 10%, transparent), transparent 140px)` }}
        >
          <p
            data-testid="verdict-summary"
            className={cn(
              "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[12.5px] font-semibold text-ink",
              tone === "good" ? "bg-good-soft" : tone === "bad" ? "bg-critical-soft" : "bg-surface-2",
            )}
          >
            <VerdictIcon className="size-3.5" style={{ color: TONE_COLOR[tone] }} aria-hidden />
            {verdictSummary(slo)}
          </p>
          <h2 className="mt-3 font-display text-[40px] leading-[1.05] tracking-[-0.035em] text-ink">{headlineOf(slo)}</h2>
          <Cause slo={slo} report={report} bottleneck={bottleneck} />
          <div className="mt-5">
            <ObjectivesGrid keys={objectiveOrder} checks={slo.checks} objectives={objectives} />
          </div>
        </div>
        <CaveatsFooter>
          <Caveats run={run} report={report} confidence={confidence} fromDesign={!report.confidence} />
        </CaveatsFooter>
      </section>

      <KeyNumbers report={report} />

      <FixFirst findings={findings} title={slo.pass === false ? "Fix first" : "Worth a look"} onShowNode={showOnCanvas} onShowAll={() => setDetailsView("summary")} />

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <CostCard cost={report.cost} />
        <SignOffPanel run={run} />
      </div>
    </div>
  );
}

/** The caveats sit under the verdict, on a quieter band; nothing when there are none. */
function CaveatsFooter({ children }: { children: React.ReactNode }) {
  return <div className="border-t border-line bg-surface-2/50 px-6 py-3 empty:hidden">{children}</div>;
}
