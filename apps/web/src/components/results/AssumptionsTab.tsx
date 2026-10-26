"use client";

import { ProvenanceTag } from "@/components/editor/FieldControl";
import { Badge, Section } from "@/components/ui/misc";
import { TierBadge, useTierNote } from "@/components/ui/TierBadge";
import { replayVerdict } from "@/lib/document";
import { technologyLabel } from "@/lib/theme";
import type { CalibrationRecord, TechnologyTier } from "@/lib/types";
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

/** Where the inputs came from, and how well the last calibration replays what was observed. */
function CalibrationSection({ record, counts }: { record: CalibrationRecord | null; counts?: Record<string, number> }) {
  if (!record && !counts) return null;
  const verdict = replayVerdict(record);
  return (
    <Section title="Where the inputs came from">
      {counts && (
        <p className="text-xs leading-relaxed text-ink-2">
          {counts.measured ?? 0} measured from telemetry, {counts.assumed ?? 0} assumed (typed in), {counts.illustrative ?? 0} illustrative (ArchSim defaults
          and presets).
        </p>
      )}
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
                        {u.provenance?.kind === "measured" ? <ProvenanceTag provenance={u.provenance} /> : (u.provenance?.kind ?? "–")}
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
