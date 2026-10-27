"use client";

import { ConfidenceBadge } from "@/components/decide/Confidence";
import { ProvenanceTag } from "@/components/editor/FieldControl";
import { Badge, Section } from "@/components/ui/misc";
import { TierBadge, useTierNote } from "@/components/ui/TierBadge";
import { KIND_LABEL, countsText } from "@/lib/confidence";
import { replayVerdict } from "@/lib/document";
import { technologyLabel } from "@/lib/theme";
import type { CalibrationRecord, Confidence, ProvenanceKind, TechnologyTier } from "@/lib/types";
import { cn } from "@/lib/utils";
import { thClass } from "./charts";
import { useEditor } from "@/store/editor";
import { useActiveReport } from "@/store/runs";

const GENERAL_NOTES = [
  "ArchSim runs a synthetic discrete-event simulation of the model you drew. It is offline: nothing is measured from a real system.",
  "Every performance input (service times, hit ratios, latencies, traffic) is an assumption you assert. Results inherit their accuracy.",
  "Without calibration against real telemetry, treat absolute numbers as rough. Comparisons between scenarios under the same assumptions are more trustworthy.",
  "Components behave like their technology (locks, group commit, replica lag, event loops, GC pauses, partitions...), with documented defaults where they exist. Not modeled: network bandwidth and packet loss, failures of machines or regions, noisy neighbours.",
];

/** Each part's technology and tier: from the run, or from the live analysis before a run. */
function TechnologiesSection({ technologies, fromRun }: { technologies: TechnologyTier[]; fromRun: boolean }) {
  const catalog = useEditor((s) => s.catalog);
  const note = useTierNote();
  if (!technologies.length) return null;
  const experimental = technologies.filter((t) => t.tier === "experimental").length;
  return (
    <Section title="Technologies">
      <p className="text-xs leading-relaxed text-ink-2">
        {experimental
          ? `${experimental} of ${technologies.length} parts use an experimental technology. ${note}`
          : "Every part uses a core technology: one ArchSim focuses on. No technology has been validated against measurements yet."}
        {fromRun ? "" : " (Live, from the design; a run records its own.)"}
      </p>
      <div className="overflow-auto rounded-md border border-line bg-surface">
        <table className="w-full text-xs">
          <thead className="bg-surface text-left">
            <tr className="border-b border-line">
              <th className={cn(thClass, "px-3 py-1.5")}>Part</th>
              <th className={cn(thClass, "px-2 py-1.5")}>Technology</th>
              <th className={cn(thClass, "px-3 py-1.5")}>Tier</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {technologies.map((t) => {
              const comp = catalog?.components.find((c) => c.type === t.type);
              return (
                <tr key={t.id}>
                  <td className="px-3 py-1.5 text-ink">{t.label}</td>
                  <td className="px-2 py-1.5 text-ink-2">{technologyLabel(comp, t.technology) ?? t.technology}</td>
                  <td className="px-3 py-1.5 text-ink-2">{t.tier === "experimental" ? <TierBadge tier={t.tier} /> : "core"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

/** How far to trust the verdict: the engine's level, why, and which inputs to replace first. */
function ConfidenceSection({ confidence, fromRun }: { confidence: Confidence; fromRun: boolean }) {
  const basis =
    confidence.weightedBy === "latency"
      ? "Each part counts by its share of a request's latency in this run."
      : confidence.weightedBy === "utilization"
        ? "Each part counts by how busy the queueing formulas expect it to be at the peak."
        : "Each part counts equally.";
  return (
    <div id="confidence" className="scroll-mt-4">
      <Section title="Confidence">
        <p className="flex flex-wrap items-center gap-2 text-xs leading-relaxed text-ink-2">
          <ConfidenceBadge level={confidence.level} />
          <span>{fromRun ? "Recorded with this run." : "Live, from the design; a run records its own."}</span>
        </p>
        <ul className="list-disc space-y-1 pl-5 text-xs leading-relaxed text-ink-2" aria-label="Why">
          {confidence.reasons.map((r) => (
            <li key={r}>{r}.</li>
          ))}
        </ul>
        <p className="text-xs leading-relaxed text-ink-2">
          <b className="font-medium text-ink">To raise it:</b> {confidence.howToRaise}
        </p>
        {confidence.drivers.length > 0 && (
          <div className="text-xs text-ink-2">
            <p className="microlabel mb-1">Replace first</p>
            <ul className="space-y-0.5" aria-label="Inputs to replace first">
              {confidence.drivers.map((d) => (
                <li key={`${d.id}-${d.field}`} className="flex items-baseline justify-between gap-2">
                  <span className="min-w-0 truncate">
                    <span className="text-ink">{d.label}</span> · {d.fieldLabel}
                  </span>
                  <span className="shrink-0 font-mono text-[11px] text-ink-3">
                    {d.kind ? KIND_LABEL[d.kind] : "–"}
                    {d.weight != null && ` · ${Math.round(d.weight * 100)}% of the weight`}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="text-[11px] leading-relaxed text-ink-3">
          {basis} High needs 80% of that weight measured (fit error ≤ 15%) or documented, and no experimental technology on the request path. Low: under 40%, an
          experimental technology at the bottleneck, or a calibration fit error above 30%.
        </p>
      </Section>
    </div>
  );
}

/** Where the inputs came from, and how well the last calibration replays what was observed. */
function CalibrationSection({ record, counts }: { record: CalibrationRecord | null; counts?: Partial<Record<ProvenanceKind, number>> }) {
  if (!record && !counts) return null;
  const verdict = replayVerdict(record);
  return (
    <Section title="Where the inputs came from">
      {counts && <p className="text-xs leading-relaxed text-ink-2">{countsText(counts)}.</p>}
      {record ? (
        <p className="text-xs leading-relaxed text-ink-2">
          Calibrated {record.at.replace("T", " ").replace("Z", " UTC")} from {record.source}
          {record.timeRange ? ` (${record.timeRange})` : ""}: {record.inputs} input{record.inputs === 1 ? "" : "s"}.
          {verdict && ` ${verdict}`}
        </p>
      ) : (
        <p className="text-xs text-ink-3">Not calibrated yet: Ctrl/⌘ K → Calibrate from telemetry.</p>
      )}
    </Section>
  );
}

export function AssumptionsTab() {
  const report = useActiveReport();
  const catalog = useEditor((s) => s.catalog);
  const analysis = useEditor((s) => s.analysis);
  const runTechs = report?.assumptions.technologies;
  const confidence = report?.confidence ?? analysis?.confidence ?? null;
  const types = report
    ? Object.entries(report.assumptions.components)
    : (catalog?.components ?? []).map((c) => [c.type, { modeled: c.modeled, notModeled: c.notModeled }] as const);
  const name = (t: string) => catalog?.components.find((c) => c.type === t)?.displayName ?? t;
  return (
    <div className="grid gap-6 p-4 xl:grid-cols-2">
      <div className="space-y-6">
        <Section title="Read this before trusting a number">
          <ul className="list-disc space-y-1.5 pl-5 text-xs leading-relaxed text-ink-2">
            {(report?.assumptions.modelNotes ?? GENERAL_NOTES).map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        </Section>
        {confidence && <ConfidenceSection confidence={confidence} fromRun={!!report?.confidence} />}
        <TechnologiesSection technologies={runTechs ?? (report ? [] : (analysis?.technologies ?? []))} fromRun={!!runTechs} />
        <CalibrationSection record={report ? (report.assumptions.calibration ?? null) : (analysis?.calibration ?? null)} counts={report?.assumptions.provenanceCounts ?? analysis?.provenanceCounts} />
        <Section
          title={
            <span className="flex items-center gap-1.5">
              Your assumptions <Badge className="font-mono normal-case tracking-normal">inputs</Badge>
            </span>
          }
        >
          {report ? (
            <div className="max-h-80 overflow-auto rounded-md border border-line bg-surface">
              <table className="w-full text-xs">
                <thead className="sticky top-0 bg-surface text-left">
                  <tr className="border-b border-line">
                    <th className={cn(thClass, "px-3 py-1.5")}>Where</th>
                    <th className={cn(thClass, "px-2 py-1.5")}>Assumption</th>
                    <th className={cn(thClass, "px-2 py-1.5")}>Value used</th>
                    <th className={cn(thClass, "px-3 py-1.5")}>Basis</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {report.assumptions.userInputs.map((u, i) => (
                    <tr key={i}>
                      <td className="px-3 py-1.5 text-ink">{u.label}</td>
                      <td className="px-2 py-1.5 text-ink-2">{u.fieldLabel}</td>
                      <td className="tabular px-2 py-1.5 font-mono text-xs text-ink">{u.value}</td>
                      <td className="px-3 py-1.5 text-ink-2">
                        {u.provenance?.kind === "measured" || u.provenance?.kind === "documented" ? <ProvenanceTag provenance={u.provenance} /> : (u.provenance?.kind ?? "–")}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-xs text-ink-2">
              Fields tagged <b className="font-mono">≈</b> (assumption) in the inspector are your estimates. After a run, this table lists the exact
              values that run used.
            </p>
          )}
          <p className="text-xs text-ink-3">
            Simulation-derived values (utilization, queueing, latency, throughput) are shown in the other tabs and labeled
            as such. Calibration from Prometheus metrics is on the roadmap, not implemented.
          </p>
        </Section>
        {report && report.assumptions.validationWarnings.length > 0 && (
          <Section title="Warnings at run time">
            <ul className="list-disc space-y-1 pl-5 text-xs text-ink-2">
              {report.assumptions.validationWarnings.map((w, i) => (
                <li key={i}>{w.message}</li>
              ))}
            </ul>
          </Section>
        )}
      </div>
      <Section title="What each component model includes">
        <div className="space-y-3">
          {types.map(([t, m]) => (
            <div key={t} className="rounded-md border border-line bg-surface p-3 text-xs">
              <p className="mb-1.5 font-medium text-ink">{name(t)}</p>
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <p className="microlabel mb-1">Modeled</p>
                  <ul className="list-disc space-y-0.5 pl-4 text-ink-2">
                    {m.modeled.map((x) => (
                      <li key={x}>{x}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <p className="microlabel mb-1">Not modeled</p>
                  <ul className="list-disc space-y-0.5 pl-4 text-ink-2">
                    {m.notModeled.map((x) => (
                      <li key={x}>{x}</li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}
