"use client";

/**
 * Right panel of the Test workspace: the result of the last run (when there is one) and one plan
 * card with three rows (traffic, objectives, failures). Quick tests live in the "Add failure"
 * menu. Rendered inside the shell's scrolling aside; it brings no width or border of its own.
 */
import { ArrowRight, ChevronDown, CircleCheck, CircleHelp, CircleX, Crosshair, FlaskConical, LoaderCircle, Pencil, Play, Plus, Square, X } from "lucide-react";
import { useMemo, useState } from "react";
import { openDecide } from "@/components/editor/workflow";
import { NumberInput } from "@/components/editor/FieldControl";
import { PatternEditor } from "@/components/editor/WorkloadPanel";
import { AddFaultMenu, FaultFields, FaultIcon, removeFault, useFaultContext } from "@/components/stress/faultUi";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/input";
import { Switch } from "@/components/ui/misc";
import { compact, fmtPct } from "@/lib/format";
import { clampFault, experimentVerdict, faultLabel, fmtWindow, type Verdict } from "@/lib/faults";
import { formatSloValue } from "@/lib/slo";
import type { Objectives, Report, Workload } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

// ---------------------------------------------------------------------------------------------
// Summaries
// ---------------------------------------------------------------------------------------------

export function objectivesSummary(o: Objectives): string {
  const parts: string[] = [];
  if (o.latencyP95Ms != null) parts.push(`p95 ≤ ${formatSloValue("p95", o.latencyP95Ms)}`);
  if (o.latencyP99Ms != null) parts.push(`p99 ≤ ${formatSloValue("p99", o.latencyP99Ms)}`);
  if (o.availability != null) parts.push(`availability ≥ ${formatSloValue("availability", o.availability)}`);
  if (o.minThroughputRps != null) parts.push(`throughput ≥ ${formatSloValue("throughput", o.minThroughputRps)}`);
  return parts.length ? parts.join(" · ") : "None set";
}

function trafficSummary(w: Workload): string {
  const p = w.pattern;
  const shape =
    p.kind === "constant"
      ? `${compact(w.rps)} req/s`
      : p.kind === "ramp"
        ? `${compact(p.startRps)} → ${compact(p.endRps)} req/s`
        : `${compact(w.rps)} req/s, burst to ${compact(p.burstRps)}`;
  return `${shape} · ${compact(w.durationS)} s`;
}

// ---------------------------------------------------------------------------------------------
// Plan rows
// ---------------------------------------------------------------------------------------------

type Open = "traffic" | "objectives" | null;

/** One line of the plan: a label, its value, and an inline editor when opened. */
function PlanRow({
  label,
  value,
  open,
  onToggle,
  children,
}: {
  label: string;
  value: React.ReactNode;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="px-3 py-2.5">
      <button type="button" onClick={onToggle} aria-expanded={open} className="group flex w-full items-start gap-2 text-left">
        <span className="min-w-0 flex-1">
          <span className="block text-[12px] text-ink-3">{label}</span>
          <span className="block text-[13px] font-medium leading-5 text-ink">{value}</span>
        </span>
        <span className="mt-0.5 flex shrink-0 items-center gap-1 rounded-md px-1.5 py-1 text-[12px] text-ink-3 group-hover:bg-surface-2 group-hover:text-ink">
          {open ? <ChevronDown className="size-3.5 rotate-180" /> : <Pencil className="size-3.5" />}
          {open ? "Done" : "Edit"}
        </span>
      </button>
      {open && <div className="mt-3 space-y-3">{children}</div>}
    </div>
  );
}

function TrafficEditor() {
  const w = useEditor((s) => s.workload);
  const setWorkload = useEditor((s) => s.setWorkload);
  return (
    <>
      {w.pattern.kind !== "ramp" && (
        <div className="space-y-1">
          <Label htmlFor="exp-rps">{w.pattern.kind === "burst" ? "Baseline" : "Requests per second"}</Label>
          <NumberInput id="exp-rps" value={w.rps} unit="req/s" validate={(v) => (v < 0 || v > 100000 ? "0 to 100,000" : null)} onCommit={(v) => setWorkload({ rps: v }, "rps")} />
        </div>
      )}
      <PatternEditor value={w.pattern} durationS={w.durationS} onChange={(pattern) => setWorkload({ pattern }, "pattern")} />
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="exp-dur">Run length</Label>
          <NumberInput
            id="exp-dur"
            value={w.durationS}
            unit="s"
            validate={(v) => (v <= w.warmupS ? "Longer than warm-up" : v > 3600 ? "≤ 3600" : null)}
            onCommit={(v) => setWorkload({ durationS: v, faults: (w.faults ?? []).map((f) => clampFault(f, v)) }, "durationS")}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="exp-warm">Warm-up</Label>
          <NumberInput id="exp-warm" value={w.warmupS} unit="s" validate={(v) => (v < 0 ? "≥ 0" : v >= w.durationS ? "Shorter than the run" : null)} onCommit={(v) => setWorkload({ warmupS: v }, "warmupS")} />
        </div>
      </div>
    </>
  );
}

function ObjectiveRow({
  id,
  label,
  value,
  fallback,
  unit,
  scale = 1,
  validate,
  onChange,
}: {
  id: string;
  label: string;
  value: number | null | undefined;
  fallback: number;
  unit: string;
  scale?: number;
  validate: (v: number) => string | null;
  onChange: (v: number | null) => void;
}) {
  const on = value != null;
  return (
    <div className="grid grid-cols-[1fr_auto] items-center gap-x-2.5 gap-y-1">
      <Label htmlFor={id} className="text-[12.5px] text-ink-2">
        {label}
      </Label>
      <Switch checked={on} onCheckedChange={(v) => onChange(v ? fallback : null)} label={`Use ${label} objective`} />
      {on && (
        <NumberInput
          id={id}
          className="col-span-2"
          value={Math.round((value as number) * scale * 1000) / 1000}
          unit={unit}
          validate={validate}
          onCommit={(v) => onChange(v / scale)}
        />
      )}
    </div>
  );
}

function ObjectivesEditor() {
  const objectives = useEditor((s) => s.objectives);
  const setObjectives = useEditor((s) => s.setObjectives);
  const pos = (v: number) => (v <= 0 ? "Must be > 0" : null);
  return (
    <>
      <ObjectiveRow id="obj-p95" label="Latency p95" unit="ms" fallback={300} value={objectives.latencyP95Ms} validate={pos} onChange={(v) => setObjectives({ latencyP95Ms: v }, "p95")} />
      <ObjectiveRow id="obj-p99" label="Latency p99" unit="ms" fallback={800} value={objectives.latencyP99Ms} validate={pos} onChange={(v) => setObjectives({ latencyP99Ms: v }, "p99")} />
      <ObjectiveRow
        id="obj-avail"
        label="Availability"
        unit="%"
        scale={100}
        fallback={0.999}
        value={objectives.availability}
        validate={(v) => (v <= 0 || v > 100 ? "Between 0 and 100" : null)}
        onChange={(v) => setObjectives({ availability: v }, "availability")}
      />
      <ObjectiveRow
        id="obj-thr"
        label="Min throughput"
        unit="req/s"
        fallback={100}
        value={objectives.minThroughputRps}
        validate={(v) => (v < 0 ? "Must be ≥ 0" : null)}
        onChange={(v) => setObjectives({ minThroughputRps: v }, "throughput")}
      />
    </>
  );
}

function FailuresRow() {
  const { faults, names, targets, workload } = useFaultContext();
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <div className="px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[12px] text-ink-3">Failures</span>
        <AddFaultMenu align="end">
          <button type="button" className="flex items-center gap-1 rounded-md px-1.5 py-1 text-[12px] font-medium text-accent hover:bg-accent-soft">
            <Plus className="size-3.5" /> Add failure
          </button>
        </AddFaultMenu>
      </div>
      {faults.length === 0 ? (
        <p className="text-[13px] font-medium leading-5 text-ink">None</p>
      ) : (
        <ul className="mt-1 space-y-1">
          {faults.map((f) => {
            const invalid = !targets[f.kind].some((t) => t.id === f.target);
            const open = openId === f.id;
            return (
              <li key={f.id} className={cn("rounded-md border", invalid ? "border-critical/60" : "border-line")}>
                <div className="flex items-center gap-2 px-2 py-1.5">
                  <FaultIcon kind={f.kind} />
                  <button
                    type="button"
                    onClick={() => setOpenId(open ? null : f.id)}
                    aria-expanded={open}
                    className="min-w-0 flex-1 truncate text-left text-[13px] text-ink hover:text-accent"
                    title={`${faultLabel(f, names)} · ${fmtWindow(f)} · click to edit`}
                  >
                    {faultLabel(f, names)}
                  </button>
                  <span className="tabular shrink-0 font-mono text-[11px] text-ink-3">{fmtWindow(f)}</span>
                  <button type="button" onClick={() => removeFault(f.id)} className="shrink-0 rounded p-0.5 text-ink-3 hover:text-danger-ink" aria-label={`Remove ${faultLabel(f, names)}`}>
                    <X className="size-3.5" />
                  </button>
                </div>
                {invalid && <p className="px-2 pb-2 text-[11.5px] text-danger-ink">This part is no longer in the design.</p>}
                {open && (
                  <div className="border-t border-line p-2.5">
                    <FaultFields fault={f} targets={targets} durationS={workload.durationS} idPrefix={`panel-${f.id}`} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function PlanCard() {
  const w = useEditor((s) => s.workload);
  const objectives = useEditor((s) => s.objectives);
  const [open, setOpen] = useState<Open>(null);
  const toggle = (k: Exclude<Open, null>) => setOpen((v) => (v === k ? null : k));
  return (
    <section aria-label="Plan">
      <h3 className="mb-2 text-[13px] font-semibold text-ink">Plan</h3>
      <div className="divide-y divide-line rounded-lg border border-line bg-surface">
        <PlanRow label="Traffic" value={trafficSummary(w)} open={open === "traffic"} onToggle={() => toggle("traffic")}>
          <TrafficEditor />
        </PlanRow>
        <PlanRow label="Must stay true" value={objectivesSummary(objectives)} open={open === "objectives"} onToggle={() => toggle("objectives")}>
          <ObjectivesEditor />
        </PlanRow>
        <FailuresRow />
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------------------------

function sameExperiment(report: Report, w: Workload): boolean {
  const r = report.input.workload;
  return (
    JSON.stringify(r.faults ?? []) === JSON.stringify(w.faults ?? []) &&
    JSON.stringify(r.pattern) === JSON.stringify(w.pattern) &&
    r.rps === w.rps &&
    r.durationS === w.durationS
  );
}

function ResultCard({ report }: { report: Report }) {
  const objectives = useEditor((s) => s.objectives);
  const workload = useEditor((s) => s.workload);
  const { names } = useFaultContext();
  const setPlayhead = useUi((s) => s.setPlayhead);
  const setPlaying = useUi((s) => s.setPlaying);
  const v: Verdict = useMemo(() => experimentVerdict(report, objectives, names), [report, objectives, names]);
  const tone = v.held === true ? "var(--status-good)" : v.held === false ? "var(--status-critical)" : "var(--ink-3)";
  const Icon = v.held === true ? CircleCheck : v.held === false ? CircleX : CircleHelp;
  const stale = !sameExperiment(report, workload);
  const jump = (t: number) => {
    setPlaying(false);
    setPlayhead(t + report.timeseries.bucketS / 2);
  };
  return (
    <section aria-label="Result" role="status" aria-live="polite">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-[13px] font-semibold text-ink">Result</h3>
        <button type="button" onClick={openDecide} className="flex items-center gap-1 rounded-md px-1.5 py-1 text-[12px] font-medium text-accent hover:bg-accent-soft">
          Results <ArrowRight className="size-3.5" />
        </button>
      </div>
      <div className="overflow-hidden rounded-lg border border-line bg-surface">
        <div className="flex items-start gap-2.5 border-l-2 px-3 py-2.5" style={{ borderLeftColor: tone }}>
          <Icon className="mt-0.5 size-4 shrink-0" style={{ color: tone }} aria-hidden />
          <div className="min-w-0">
            <p className="text-[14px] font-semibold text-ink">{v.held === true ? "Held up" : v.held === false ? "Didn’t hold up" : "Not enough data"}</p>
            {v.held !== true && <p className="mt-0.5 text-[12.5px] leading-5 text-ink-2">{v.summary}</p>}
            {stale && <p className="mt-0.5 text-[11.5px] text-ink-3">From the last run · the plan changed since</p>}
          </div>
        </div>
        {v.overall.checks.length > 0 && (
          <ul className="border-t border-line px-3 py-1.5">
            {v.overall.checks.map((c) => (
              <li key={c.key} className="flex items-center gap-2 py-1 text-[12.5px]">
                <span className="min-w-0 flex-1 text-ink-2">{c.label}</span>
                <span className="tabular font-mono font-semibold text-ink">{formatSloValue(c.key, c.actual)}</span>
                <span className="w-4 text-right" aria-label={c.pass ? "met" : c.pass === false ? "missed" : "no data"} style={{ color: c.pass ? "var(--status-good)" : c.pass === false ? "var(--status-critical)" : "var(--ink-3)" }}>
                  {c.pass ? "✓" : c.pass === false ? "✕" : "–"}
                </span>
              </li>
            ))}
          </ul>
        )}
        {v.windows.length > 0 && (
          <ul className="border-t border-line px-3 py-1.5">
            {v.windows.map((w) => (
              <li key={w.fault.id} className="flex items-center gap-2 py-1 text-[12.5px]">
                <FaultIcon kind={w.fault.kind} className="size-3" />
                <button type="button" onClick={() => jump(w.fault.startS)} className="min-w-0 flex-1 truncate text-left text-ink-2 hover:text-accent" title="Replay from the start of this failure">
                  {faultLabel(w.fault, names)}
                </button>
                <span className="text-[12px]" style={{ color: w.pass ? "var(--status-good)" : w.pass === false ? "var(--status-critical)" : "var(--ink-3)" }}>
                  {w.pass ? "held" : w.pass === false ? `breached ${w.breached} s` : "no data"}
                </span>
              </li>
            ))}
          </ul>
        )}
        {v.worst && (
          <button
            type="button"
            onClick={() => jump(v.worst!.t)}
            className="flex w-full items-center gap-2 border-t border-line px-3 py-2 text-left text-[12px] text-ink-3 hover:bg-surface-2 hover:text-ink"
          >
            <Crosshair className="size-3.5 shrink-0" />
            <span className="min-w-0 flex-1 truncate">
              Worst moment at {v.worst.t.toFixed(0)} s{v.worst.success != null ? ` · ${fmtPct(v.worst.success, 0)} success` : ""}
            </span>
            <span className="shrink-0 font-medium text-accent">Replay</span>
          </button>
        )}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// Footer
// ---------------------------------------------------------------------------------------------

function NextRunBar() {
  const { faults, workload } = useFaultContext();
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const cancel = useRuns((s) => s.cancel);
  const selectedScenarioId = useRuns((s) => s.selectedScenarioId);
  const selectScenario = useRuns((s) => s.selectScenario);
  const scenario = useEditor((s) => s.scenarios.find((x) => x.id === selectedScenarioId) ?? null);
  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  return (
    <div className="sticky bottom-0 z-10 space-y-2 border-t border-line bg-surface p-3">
      {scenario && (
        <p className="text-[11.5px] leading-4 text-ink-3">
          Scenario “{scenario.name}” is applied ·{" "}
          <button type="button" onClick={() => selectScenario(null)} className="underline decoration-dotted hover:text-ink">
            use the current design
          </button>
        </p>
      )}
      {running && followRun ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5 text-[12.5px] text-ink">
              <LoaderCircle className="size-3.5 animate-spin" /> {followRun.status === "queued" ? "Waiting to start…" : `Simulating… ${Math.round(followRun.progress * 100)}%`}
            </p>
            <div className="mt-1 h-1 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={Math.round(followRun.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Simulation progress">
              <div className="progress-stripes h-full" style={{ width: `${Math.max(3, followRun.progress * 100)}%` }} />
            </div>
          </div>
          <Button size="sm" onClick={() => void cancel(followRun.id)} aria-label="Stop the simulation">
            <Square className="fill-current" /> Stop
          </Button>
        </div>
      ) : (
        <p className="flex items-center gap-2 text-[12.5px] text-ink-2">
          <Play className="size-3.5 shrink-0 fill-current text-accent" aria-hidden />
          <span className="font-semibold text-ink">Next run</span>
          {faults.length ? `${faults.length} failure${faults.length > 1 ? "s" : ""}` : "no failures"} · {compact(workload.rps)} req/s · {compact(workload.durationS)} s
        </p>
      )}
    </div>
  );
}

/** Right panel of the Test workspace (no props). */
export function ExperimentPanel() {
  const report = useActiveReport();
  return (
    <div className="flex min-h-full flex-col">
      <div className="flex-1 space-y-5 p-4">
        <h2 className="flex items-center gap-2 font-display text-[24px] leading-none text-ink">
          <FlaskConical className="size-4 text-ink-3" aria-hidden /> Test plan
        </h2>
        {report && <ResultCard report={report} />}
        <PlanCard />
      </div>
      <NextRunBar />
    </div>
  );
}
