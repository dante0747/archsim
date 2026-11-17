"use client";

/**
 * A shared run's results at /r/<slug>: read-only and phone-first, in one column. The verdict and
 * its confidence come first, then the key numbers, the objectives, what the run found, latency
 * over time and the error budget. Everything is a frozen copy made when the link was created.
 */
import { CircleAlert, CircleCheck, CircleDashed, FlaskConical } from "lucide-react";
import Link from "next/link";
import { Logo } from "@/components/brand";
import { ConfidenceBadge } from "@/components/report-view/ConfidenceBadge";
import { ErrorBudgetPanel } from "@/components/report-view/ErrorBudgetPanel";
import { FindingCard } from "@/components/report-view/FindingCard";
import { ProvenanceBar } from "@/components/report-view/ProvenanceBar";
import { fmtAxisMs, PERCENTILE_SERIES, TimeSeriesChart } from "@/components/results/charts";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { TooltipProvider } from "@/components/ui/misc";
import { costLeftOut, formatUsd, priceSource } from "@/lib/cost";
import { fmtPct, fmtRps } from "@/lib/format";
import type { PublicShare } from "@/lib/share-types";
import { type ShareView, shareFindings, shareObjectives, shareVerdict } from "@/lib/share-view";
import { formatSloValue, sloChecks } from "@/lib/slo";
import { cn } from "@/lib/utils";
import { OpenCopyButton } from "./OpenCopyButton";

export type SharePageData = Omit<PublicShare, "report"> & { report: ShareView };

const TONE_COLOR = { good: "var(--status-good)", bad: "var(--status-critical)", none: "var(--ink-3)" } as const;
const TONE_ICON = { good: CircleCheck, bad: CircleAlert, none: CircleDashed } as const;

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="min-w-0 bg-surface px-3 py-3">
      <dt className="microlabel truncate">{label}</dt>
      <dd className="tabular mt-0.5 truncate font-mono text-[19px] font-semibold leading-7 tracking-tight text-ink">{value}</dd>
      {hint && <dd className="truncate font-mono text-[11px] text-ink-3">{hint}</dd>}
    </div>
  );
}

function PassMark({ pass }: { pass: boolean | null }) {
  const Icon = pass === true ? CircleCheck : pass === false ? CircleAlert : CircleDashed;
  const color = pass === true ? "var(--status-good)" : pass === false ? "var(--status-critical)" : "var(--ink-3)";
  return (
    <span className="inline-flex shrink-0 items-center gap-1 text-xs font-semibold">
      <Icon className="size-3.5" style={{ color }} aria-hidden />
      <span className={pass === false ? "text-danger-ink" : pass === true ? "text-success-ink" : "text-ink-3"}>{pass === true ? "Pass" : pass === false ? "Fail" : "No data"}</span>
    </span>
  );
}

function Heading({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <h2 id={id} className="mb-3 text-[15px] font-semibold text-ink">
      {children}
    </h2>
  );
}

export function SharePage({ share }: { share: SharePageData }) {
  const report = share.report;
  const objectives = shareObjectives(share.objectives);
  const verdict = shareVerdict(objectives, report);
  const checks = sloChecks(objectives, report);
  const findings = shareFindings(report.findings);
  const s = report.summary;
  const ts = report.timeseries;
  const Icon = TONE_ICON[verdict.tone];
  const cost = report.cost;
  const experimental = report.technologies.filter((t) => t.tier === "experimental");
  const runName = share.scenarioName ?? share.runLabel;
  const latencySeries = [
    { key: "p50", label: "p50", color: PERCENTILE_SERIES[0], values: ts.latencyP50Ms },
    { key: "p95", label: "p95", color: PERCENTILE_SERIES[1], values: ts.latencyP95Ms },
    { key: "p99", label: "p99", color: PERCENTILE_SERIES[2], values: ts.latencyP99Ms },
  ];

  return (
    <TooltipProvider>
      <div className="min-h-full overflow-x-clip bg-page">
        <header className="sticky top-0 z-30 border-b border-line bg-page/95 backdrop-blur">
          <div className="mx-auto flex h-14 max-w-2xl items-center gap-3 px-4 sm:px-6">
            <Logo />
            <span className="truncate text-xs font-medium text-ink-3">Shared results</span>
            <div className="ml-auto">
              <ThemeToggle />
            </div>
          </div>
        </header>

        <main className="mx-auto max-w-2xl space-y-9 px-4 pb-16 pt-6 sm:px-6 sm:pt-8" data-testid="share-page">
          <section aria-label="Verdict">
            <p className="microlabel truncate">
              {share.projectName}
              {runName ? ` · ${runName}` : ""}
            </p>
            {share.title !== share.projectName && <p className="mt-1 text-[15px] font-medium text-ink">{share.title}</p>}
            <div className="mt-3 flex items-start gap-3">
              <Icon className="mt-1 size-7 shrink-0 sm:size-8" style={{ color: TONE_COLOR[verdict.tone] }} aria-hidden />
              <h1 className="font-display text-[34px] leading-[1.04] tracking-[-0.035em] text-ink sm:text-[42px]" data-testid="share-verdict">
                {verdict.headline}
              </h1>
            </div>
            <p className="mt-3 text-[15px] leading-relaxed text-ink-2">{verdict.reason}</p>
            {verdict.bottleneck && <p className="mt-1 text-[15px] text-ink">{verdict.bottleneck}.</p>}
            {report.confidence && (
              <p className="mt-3 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[13.5px] leading-relaxed text-ink-2" data-testid="share-confidence">
                <ConfidenceBadge level={report.confidence.level} />
                <span>{report.confidence.reason}</span>
                <span aria-hidden className="hidden text-ink-3 sm:inline">
                  ·
                </span>
                <Link href="/accuracy" className="font-medium text-ink underline decoration-dotted underline-offset-2 hover:decoration-solid">
                  measured accuracy
                </Link>
              </p>
            )}
            {experimental.length > 0 && (
              <p className="mt-2 flex items-start gap-1.5 text-[13px] leading-relaxed text-ink-3">
                <FlaskConical className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                <span>
                  {experimental.map((t) => t.label).join(", ")} {experimental.length === 1 ? "uses a technology" : "use technologies"} whose model is not yet validated against
                  measurements.
                </span>
              </p>
            )}
            {share.canCopy && (
              <div className="mt-5">
                <OpenCopyButton slug={share.slug} />
              </div>
            )}
          </section>

          <section aria-label="Key numbers">
            <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-line bg-line sm:grid-cols-3">
              <Stat label="Latency p95" value={formatSloValue("p95", s.latencyMs.p95)} hint={`p50 ${formatSloValue("p95", s.latencyMs.p50)} · p99 ${formatSloValue("p99", s.latencyMs.p99)}`} />
              <Stat label="Handled" value={fmtRps(s.throughputRps)} hint="successful, after warm-up" />
              <Stat label="Success" value={fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)} />
              <Stat label="Offered load" value={fmtRps(s.offeredRps)} hint="what clients sent" />
              <Stat label="Objectives met" value={verdict.slo.total ? `${verdict.slo.met}/${verdict.slo.total}` : "–"} />
              {cost?.hasCost ? <Stat label="Per month" value={formatUsd(cost.monthly)} hint={[priceSource(cost).label, costLeftOut(cost)].filter(Boolean).join("; ")} /> : <Stat label="Per month" value="–" hint="no prices set" />}
            </dl>
          </section>

          <section aria-labelledby="share-objectives">
            <Heading id="share-objectives">Objectives</Heading>
            {checks.length === 0 ? (
              <p className="text-[13px] text-ink-2">No objectives were set for this design.</p>
            ) : (
              <ul className="divide-y divide-line overflow-hidden rounded-md border border-line bg-surface">
                {checks.map((c) => (
                  <li key={c.key} className="flex items-center justify-between gap-3 px-3 py-2.5">
                    <div className="min-w-0">
                      <p className="text-[13px] font-medium text-ink">{c.label}</p>
                      <p className="font-mono text-xs text-ink-3">
                        target {c.better === "lower" ? "≤" : "≥"} {formatSloValue(c.key, c.target)}
                      </p>
                    </div>
                    <div className="flex shrink-0 flex-col items-end gap-0.5">
                      <span className="tabular font-mono text-[15px] font-semibold text-ink">{formatSloValue(c.key, c.actual)}</span>
                      <PassMark pass={c.pass} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {findings.length > 0 && (
            <section aria-labelledby="share-findings">
              <Heading id="share-findings">{verdict.slo.pass === false ? "Fix first" : "What the simulation found"}</Heading>
              <ul className="space-y-2">
                {findings.map((f, i) => (
                  <FindingCard key={`${f.kind}-${i}`} f={f} />
                ))}
              </ul>
            </section>
          )}

          <section aria-labelledby="share-latency">
            <Heading id="share-latency">Latency over time</Heading>
            <TimeSeriesChart
              title="Successful requests, by completion time"
              t={ts.t}
              series={latencySeries}
              format={fmtAxisMs}
              warmupS={report.window.warmupS}
              durationS={report.window.durationS}
              height={200}
            />
          </section>

          {objectives.availability != null && <ErrorBudgetPanel report={report} objectives={objectives} />}

          <footer className={cn("space-y-3 border-t border-line pt-5")}>
            <ProvenanceBar report={report} />
            <p className="text-xs leading-relaxed text-ink-3">
              Simulation-derived estimates from a model of the design and its stated assumptions, not measurements of a running system. A frozen copy made{" "}
              {formatDay(share.createdAt)}
              {share.expiresAt ? `; the link works until ${formatDay(share.expiresAt)}` : ""}.
            </p>
            <p className="text-xs text-ink-3">
              Made with{" "}
              <Link href="/" className="font-medium text-ink-2 underline decoration-dotted underline-offset-2 hover:text-ink">
                ArchSim
              </Link>
              , an architecture simulator.
            </p>
          </footer>
        </main>
      </div>
    </TooltipProvider>
  );
}
