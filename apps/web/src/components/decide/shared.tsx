"use client";

/** Building blocks shared by the Decide workspace sections. */
import { CircleAlert, CircleCheck, CircleDashed } from "lucide-react";
import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { fmtRelativeTime } from "@/lib/format";
import { SWEEP_SCENARIO_PREFIX } from "@/lib/sweep";
import type { Report, Run } from "@/lib/types";
import { cn } from "@/lib/utils";
import { ensureReports, useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

export function runLabel(run: Pick<Run, "label" | "scenarioName"> | null | undefined): string {
  return run?.label ?? run?.scenarioName ?? "Current design";
}

/**
 * A run in a run picker: what it ran, its short id (runs of one design otherwise read the same),
 * its seed, whether it was a quick check, and when: "Current design · run 3f9a2c1d · seed 42 · 43s ago".
 */
export function runOptionText(run: Pick<Run, "id" | "label" | "scenarioName" | "seed" | "mode" | "createdAt">): string {
  return `${runLabel(run)} · run ${run.id.slice(0, 8)} · seed ${run.seed}${run.mode === "sampled" ? " · estimated" : ""} · ${fmtRelativeTime(run.createdAt)}`;
}

export function isSweepRun(run: Pick<Run, "scenarioId">): boolean {
  return !!run.scenarioId && run.scenarioId.startsWith(SWEEP_SCENARIO_PREFIX);
}

/** Small segmented control for views inside a Results tab. */
export function SubTabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { key: T; label: string }[] }) {
  return (
    <div role="tablist" className="flex items-center gap-0.5 rounded-lg border border-line bg-surface p-0.5">
      {items.map((it) => (
        <button
          key={it.key}
          type="button"
          role="tab"
          aria-selected={value === it.key}
          onClick={() => onChange(it.key)}
          className={cn(
            "h-7 rounded-md px-3 text-[13px] font-medium transition-colors",
            value === it.key ? "bg-surface-2 text-ink shadow-panel" : "text-ink-3 hover:text-ink",
          )}
        >
          {it.label}
        </button>
      ))}
    </div>
  );
}

/** Section page header: mono kicker, title, optional description and actions. */
export function PageHeader({
  kicker,
  title,
  description,
  actions,
}: {
  kicker: string;
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3 border-b border-line px-6 pb-4 pt-5">
      <div className="min-w-0 max-w-3xl">
        <p className="microlabel">
          {kicker}
        </p>
        <h2 className="mt-1 font-display text-[28px] leading-[1.1] tracking-[-0.035em] text-ink">{title}</h2>
        {description && <p className="mt-1.5 text-[13px] leading-relaxed text-ink-2">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/** Bordered card with a mono title row (shared with the share page). */
export { Panel } from "@/components/report-view/Panel";

/** A call to action where data is missing. */
export function EmptyPanel({
  icon,
  title,
  children,
  action,
  className,
}: {
  icon?: React.ReactNode;
  title: string;
  children?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-start gap-2 rounded-md border border-dashed border-line-strong p-6", className)}>
      {icon && <div className="text-ink-3 [&_svg]:size-5">{icon}</div>}
      <p className="text-sm font-semibold text-ink">{title}</p>
      {children && <div className="max-w-xl text-[13px] leading-relaxed text-ink-2">{children}</div>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}

/** Pass / fail / no-data mark: icon plus word, never color alone. */
export function StatusMark({ pass, className }: { pass: boolean | null; className?: string }) {
  const Icon = pass === true ? CircleCheck : pass === false ? CircleAlert : CircleDashed;
  const color = pass === true ? "var(--status-good)" : pass === false ? "var(--status-critical)" : "var(--ink-3)";
  return (
    <span className={cn("inline-flex items-center gap-1 text-xs font-semibold", className)}>
      <Icon className="size-3.5" style={{ color }} aria-hidden />
      <span className={pass === false ? "text-danger-ink" : pass === true ? "text-success-ink" : "text-ink-3"}>
        {pass === true ? "Pass" : pass === false ? "Fail" : "No data"}
      </span>
    </span>
  );
}

/** Width of an element, kept current with a ResizeObserver. */
export function useElementWidth(ref: React.RefObject<HTMLElement | null>, fallback = 640): number {
  const [w, setW] = useState(fallback);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setW(el.clientWidth || fallback);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref, fallback]);
  return w;
}

/** Succeeded runs, newest first, without the breaking-point sweep steps. */
export function useSucceededRuns(includeSweeps = false): Run[] {
  const runs = useRuns((s) => s.runs);
  return useMemo(
    () => runs.filter((r) => r.status === "succeeded" && (includeSweeps || !isSweepRun(r))),
    [runs, includeSweeps],
  );
}

/** The run the scorecard and report show: the chosen one, else the run on the canvas, else the newest. */
export function useDecideRun(): { run: Run | null; report: Report | null } {
  const chosen = useDecide((s) => s.scorecardRunId);
  const activeRunId = useRuns((s) => s.activeRunId);
  const runs = useSucceededRuns(true);
  const run = runs.find((r) => r.id === chosen) ?? runs.find((r) => r.id === activeRunId) ?? runs.find((r) => !isSweepRun(r)) ?? null;
  const report = useRuns((s) => (run ? (s.reports[run.id] ?? null) : null));
  useEffect(() => {
    if (run && !report) void ensureReports([run.id]);
  }, [run, report]);
  return { run, report };
}

/** Select which succeeded run a section shows. */
export function RunPicker({ value, onChange, label = "Run" }: { value: string | null; onChange: (id: string) => void; label?: string }) {
  const runs = useSucceededRuns();
  if (!runs.length) return null;
  return (
    <label className="flex items-center gap-2 font-mono text-xs text-ink-3">
      {label && <span>{label}</span>}
      <select
        value={value ?? ""}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 max-w-72 rounded-md border border-line bg-surface-2 px-2 font-mono text-xs text-ink hover:border-line-strong focus-visible:border-accent focus-visible:outline-none"
      >
        {runs.map((r) => (
          <option key={r.id} value={r.id}>
            {runOptionText(r)}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Start simulating the design as drawn (or the scenario chosen in the run control). */
export function runDesignNow(): void {
  const doc = useEditor.getState().toDocument();
  const r = useRuns.getState();
  const scenarioId = doc.scenarios.some((s) => s.id === r.selectedScenarioId) ? r.selectedScenarioId : null;
  void r.startRun(doc, scenarioId);
}

/** Start one run per scenario (max 10) plus none for the design itself. */
export function runAllScenariosNow(): void {
  const ed = useEditor.getState();
  const ids = ed.scenarios.slice(0, 10).map((s) => s.id);
  if (ids.length) void useRuns.getState().startBatch(ed.toDocument(), ids);
}

/** "Nice" axis maximum and ticks for a [0, v] range. */
export function niceTicks(max: number, count = 5): { max: number; ticks: number[] } {
  const raw = Math.max(max, 1e-9) / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const r = raw / mag;
  const step = (r <= 1 ? 1 : r <= 2 ? 2 : r <= 2.5 ? 2.5 : r <= 5 ? 5 : 10) * mag;
  const top = Math.ceil(max / step) * step || step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6);
  return { max: top, ticks };
}

/** Recharts tooltip for curves keyed by offered load. */
export function LoadTooltip({
  active,
  payload,
  label,
  format,
  note,
}: {
  active?: boolean;
  payload?: { dataKey: string; value: number | null; color: string; name: string }[];
  label?: number;
  format: (v: number) => string;
  /** An extra line about the hovered load (e.g. its success rate). */
  note?: (rps?: number) => string | null;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-md border border-line-strong bg-surface px-2 py-1.5 font-mono text-xs shadow-float">
      <p className="mb-1 text-ink-3">offered {label?.toLocaleString("en-US")} req/s</p>
      {payload.map((p) => (
        <p key={p.dataKey} className="flex items-center gap-2 text-ink-2">
          <span className="inline-block h-0.5 w-3 rounded" style={{ background: p.color }} aria-hidden />
          <span>{p.name}</span>
          <span className="tabular ml-auto pl-3 text-ink">{p.value == null ? "–" : format(p.value)}</span>
        </p>
      ))}
      {note?.(label) && <p className="mt-1 text-ink-3">{note(label)}</p>}
    </div>
  );
}
