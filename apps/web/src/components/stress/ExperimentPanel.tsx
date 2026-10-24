"use client";

/**
 * Right panel of the Stress workspace: the steady-state hypothesis (objectives), traffic, faults,
 * one-click chaos experiments, the run button and, after a run, the verdict with evidence.
 * Rendered inside the shell's scrolling aside; it brings no width or border of its own.
 */
import { CircleCheck, CircleHelp, CircleX, Crosshair, FlaskConical, LoaderCircle, Pencil, Play, Plus, Square, TrendingUp, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { NumberInput } from "@/components/editor/FieldControl";
import { PatternEditor } from "@/components/editor/WorkloadPanel";
import { AddFaultMenu, FaultFields, FaultIcon, removeFault, useFaultContext } from "@/components/stress/faultUi";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/input";
import { Section, Switch } from "@/components/ui/misc";
import { compact, fmtPct } from "@/lib/format";
import { chaosPresets, clampFault, experimentVerdict, faultLabel, fmtWindow, type ChaosPreset, type Verdict } from "@/lib/faults";
import { formatSloValue } from "@/lib/slo";
import { REQ_GLYPH } from "@/lib/theme";
import type { Objectives, Report, Workload } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

// ---------------------------------------------------------------------------------------------
// Hypothesis
// ---------------------------------------------------------------------------------------------

export function objectivesSummary(o: Objectives): string {
  const parts: string[] = [];
  if (o.latencyP95Ms != null) parts.push(`p95 ≤ ${formatSloValue("p95", o.latencyP95Ms)}`);
  if (o.latencyP99Ms != null) parts.push(`p99 ≤ ${formatSloValue("p99", o.latencyP99Ms)}`);
  if (o.availability != null) parts.push(`availability ≥ ${formatSloValue("availability", o.availability)}`);
  if (o.minThroughputRps != null) parts.push(`throughput ≥ ${formatSloValue("throughput", o.minThroughputRps)}`);
  return parts.length ? parts.join(" · ") : "no objective set";
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
      <Label htmlFor={id} className="font-mono text-[11px] text-ink-2">
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

function HypothesisCard() {
  const objectives = useEditor((s) => s.objectives);
  const setObjectives = useEditor((s) => s.setObjectives);
  const [editing, setEditing] = useState(false);
  const pos = (v: number) => (v <= 0 ? "Must be > 0" : null);
  return (
    <Section
      title="Objectives"
      actions={
        <button
          type="button"
          onClick={() => setEditing((v) => !v)}
          aria-expanded={editing}
          className="flex items-center gap-1 rounded-sm px-1.5 py-0.5 font-mono text-[10.5px] text-ink-3 hover:bg-surface-2 hover:text-ink"
        >
          <Pencil className="size-3" /> {editing ? "done" : "edit"}
        </button>
      }
    >
      <div className="rounded-md border border-line bg-surface-2 p-3">
        <p className="font-mono text-[10.5px] uppercase tracking-wider text-ink-3">must stay true</p>
        <p className="mt-1 font-mono text-[12.5px] font-semibold leading-5 text-ink">{objectivesSummary(objectives)}</p>
        <p className="mt-1.5 text-[11px] leading-4 text-ink-3">
          Checked over the whole run and in every second of each failure.
        </p>
        {editing && (
          <div className="mt-3 space-y-2.5 border-t border-dashed border-line-strong pt-3">
            <ObjectiveRow id="obj-p95" label="latency p95" unit="ms" fallback={300} value={objectives.latencyP95Ms} validate={pos} onChange={(v) => setObjectives({ latencyP95Ms: v }, "p95")} />
            <ObjectiveRow id="obj-p99" label="latency p99" unit="ms" fallback={800} value={objectives.latencyP99Ms} validate={pos} onChange={(v) => setObjectives({ latencyP99Ms: v }, "p99")} />
            <ObjectiveRow
              id="obj-avail"
              label="availability"
              unit="%"
              scale={100}
              fallback={0.999}
              value={objectives.availability}
              validate={(v) => (v <= 0 || v > 100 ? "Between 0 and 100" : null)}
              onChange={(v) => setObjectives({ availability: v }, "availability")}
            />
            <ObjectiveRow
              id="obj-thr"
              label="min throughput"
              unit="req/s"
              fallback={100}
              value={objectives.minThroughputRps}
              validate={(v) => (v < 0 ? "Must be ≥ 0" : null)}
              onChange={(v) => setObjectives({ minThroughputRps: v }, "throughput")}
            />
          </div>
        )}
      </div>
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Traffic
// ---------------------------------------------------------------------------------------------

function trafficSummary(w: Workload): string {
  const p = w.pattern;
  const shape =
    p.kind === "constant"
      ? `${compact(w.rps)} req/s steady`
      : p.kind === "ramp"
        ? `ramp ${compact(p.startRps)}→${compact(p.endRps)} req/s`
        : `${compact(w.rps)} req/s, burst ${compact(p.burstRps)} at ${fmtWindow(p)}`;
  const reads = Math.round(w.readRatio * 100);
  return `${shape} · ${REQ_GLYPH.read}${reads}% ${REQ_GLYPH.write}${100 - reads}% · ${compact(w.durationS)} s`;
}

function TrafficCard() {
  const w = useEditor((s) => s.workload);
  const setWorkload = useEditor((s) => s.setWorkload);
  const [open, setOpen] = useState(false);
  return (
    <Section
      title="Traffic"
      actions={
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="flex items-center gap-1 rounded-sm px-1.5 py-0.5 font-mono text-[10.5px] text-ink-3 hover:bg-surface-2 hover:text-ink"
        >
          <Pencil className="size-3" /> {open ? "done" : "edit"}
        </button>
      }
    >
      <p className="rounded-md border border-line px-3 py-2 font-mono text-[11.5px] leading-5 text-ink">{trafficSummary(w)}</p>
      {open && (
        <div className="space-y-3 rounded-md border border-line p-3">
          {w.pattern.kind !== "ramp" && (
            <div className="space-y-1">
              <Label htmlFor="exp-rps" className="microlabel">
                {w.pattern.kind === "burst" ? "baseline" : "requests per second"}
              </Label>
              <NumberInput id="exp-rps" value={w.rps} unit="req/s" validate={(v) => (v < 0 || v > 100000 ? "0 to 100,000" : null)} onCommit={(v) => setWorkload({ rps: v }, "rps")} />
            </div>
          )}
          <PatternEditor value={w.pattern} durationS={w.durationS} onChange={(pattern) => setWorkload({ pattern }, "pattern")} />
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label htmlFor="exp-dur" className="microlabel">
                run length
              </Label>
              <NumberInput
                id="exp-dur"
                value={w.durationS}
                unit="s"
                validate={(v) => (v <= w.warmupS ? "Longer than warm-up" : v > 3600 ? "≤ 3600" : null)}
                onCommit={(v) => setWorkload({ durationS: v, faults: (w.faults ?? []).map((f) => clampFault(f, v)) }, "durationS")}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="exp-warm" className="microlabel">
                warm-up
              </Label>
              <NumberInput id="exp-warm" value={w.warmupS} unit="s" validate={(v) => (v < 0 ? "≥ 0" : v >= w.durationS ? "Shorter than the run" : null)} onCommit={(v) => setWorkload({ warmupS: v }, "warmupS")} />
            </div>
          </div>
          <p className="text-[11px] leading-4 text-ink-3">Tip: shape the load directly on the traffic track in the timeline below.</p>
        </div>
      )}
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Faults and presets
// ---------------------------------------------------------------------------------------------

function FaultList() {
  const { faults, names, targets, workload } = useFaultContext();
  const setPlayhead = useUi((s) => s.setPlayhead);
  const hasReport = useRuns((s) => s.activeRunId !== null);
  return (
    <Section
      title={`Failures${faults.length ? ` · ${faults.length}` : ""}`}
      actions={
        <AddFaultMenu align="end">
          <button type="button" className="flex items-center gap-1 rounded-sm px-1.5 py-0.5 font-mono text-[10.5px] text-ink-3 hover:bg-surface-2 hover:text-accent">
            <Plus className="size-3" /> add
          </button>
        </AddFaultMenu>
      }
    >
      {faults.length === 0 ? (
        <p className="rounded-md border border-dashed border-line-strong p-3 text-[11.5px] leading-relaxed text-ink-2">
          None planned: the run tests normal operation. Add a failure, or pick a quick test below.
        </p>
      ) : (
        <ul className="space-y-2">
          {faults.map((f) => {
            const invalid = !targets[f.kind].some((t) => t.id === f.target);
            return (
              <li key={f.id} className={cn("rounded-md border bg-surface p-3", invalid ? "border-critical/60" : "border-line")}>
                <div className="mb-2.5 flex items-center gap-2">
                  <FaultIcon kind={f.kind} />
                  <p className="min-w-0 flex-1 truncate font-mono text-[11.5px] font-semibold text-ink" title={faultLabel(f, names)}>
                    {faultLabel(f, names)}
                  </p>
                  <span className="tabular shrink-0 font-mono text-[10.5px] text-ink-3">{fmtWindow(f)}</span>
                  {hasReport && (
                    <button
                      type="button"
                      onClick={() => setPlayhead(f.startS)}
                      className="shrink-0 rounded-sm p-0.5 text-ink-3 hover:text-accent"
                      title="Jump the replay to the start of this failure"
                      aria-label="Jump to failure start"
                    >
                      <Crosshair className="size-3.5" />
                    </button>
                  )}
                  <button type="button" onClick={() => removeFault(f.id)} className="shrink-0 rounded-sm p-0.5 text-ink-3 hover:text-danger-ink" aria-label={`Remove ${faultLabel(f, names)}`}>
                    <Trash2 className="size-3.5" />
                  </button>
                </div>
                {invalid && <p className="mb-2 text-[11px] text-danger-ink">The target “{f.target}” is not in the design any more. Pick another one.</p>}
                <FaultFields fault={f} targets={targets} durationS={workload.durationS} idPrefix={`panel-${f.id}`} />
              </li>
            );
          })}
        </ul>
      )}
    </Section>
  );
}

function PresetIcon({ p }: { p: ChaosPreset }) {
  if (p.kind === "spike") return <TrendingUp className="size-3.5 shrink-0 text-accent" aria-hidden />;
  return <FaultIcon kind={p.kind} />;
}

function PresetList() {
  const { nodes, edges, catalog, workload } = useFaultContext();
  const setWorkload = useEditor((s) => s.setWorkload);
  const presets = useMemo(() => chaosPresets(nodes, edges, catalog, workload), [nodes, edges, catalog, workload]);
  return (
    <Section title="Quick failure tests">
      {presets.length === 0 ? (
        <p className="text-[11.5px] text-ink-3">Add components to get test suggestions.</p>
      ) : (
        <>
          <ul className="space-y-1.5">
            {presets.map((p) => (
              <li key={p.id}>
                <button
                  type="button"
                  onClick={() => {
                    setWorkload(p.apply(useEditor.getState().workload), `chaos:${p.id}`);
                    toast.success(`Loaded: ${p.title}`, { description: "Press Simulate to run it. Ctrl Z undoes it." });
                  }}
                  className="group flex w-full items-start gap-2.5 rounded-md border border-line p-2.5 text-left transition-colors hover:border-accent/50 hover:bg-surface-2"
                >
                  <span className="mt-0.5">
                    <PresetIcon p={p} />
                  </span>
                  <span className="min-w-0">
                    <span className="block font-mono text-[11.5px] font-semibold text-ink group-hover:text-accent">{p.title}</span>
                    <span className="mt-0.5 block text-[11px] leading-4 text-ink-2">{p.description}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <p className="text-[11px] text-ink-3">One failure at a time: picking a test replaces the planned failures.</p>
        </>
      )}
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Verdict
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

function VerdictCard({ report }: { report: Report }) {
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
    <Section title="Result">
      <div className="overflow-hidden rounded-md border border-line" role="status" aria-live="polite">
        <div className="border-l-2 bg-surface-2 p-3" style={{ borderLeftColor: tone }}>
          <p className="flex items-center gap-2 font-mono text-[12.5px] font-semibold uppercase tracking-wider" style={{ color: tone }}>
            <Icon className="size-4" aria-hidden />
            {v.held === true ? "objectives met" : v.held === false ? "objectives missed" : "not enough data"}
          </p>
          <p className="mt-1.5 text-[12px] leading-5 text-ink">{v.summary}</p>
          {stale && <p className="mt-1.5 text-[11px] text-ink-3">From the last run. Traffic or failures changed since: simulate again to test them.</p>}
        </div>
        {v.overall.checks.length > 0 && (
          <table className="w-full border-t border-line font-mono text-[11px]">
            <caption className="sr-only">Objectives over the measured window</caption>
            <tbody>
              {v.overall.checks.map((c) => (
                <tr key={c.key} className="border-b border-line last:border-0">
                  <th scope="row" className="px-3 py-1.5 text-left font-normal text-ink-2">
                    {c.label.toLowerCase()}
                  </th>
                  <td className="tabular px-1 py-1.5 text-right text-ink-3">
                    {c.better === "lower" ? "≤" : "≥"} {formatSloValue(c.key, c.target)}
                  </td>
                  <td className="tabular px-1 py-1.5 text-right font-semibold text-ink">{formatSloValue(c.key, c.actual)}</td>
                  <td className="w-7 py-1.5 pr-3 text-right" aria-label={c.pass ? "met" : c.pass === false ? "missed" : "no data"}>
                    <span style={{ color: c.pass ? "var(--status-good)" : c.pass === false ? "var(--status-critical)" : "var(--ink-3)" }}>
                      {c.pass ? "✓" : c.pass === false ? "✕" : "–"}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {v.windows.length > 0 && (
          <div className="space-y-2 border-t border-line p-3">
            <p className="microlabel">during each failure</p>
            {v.windows.map((w) => (
              <div key={w.fault.id} className="rounded-sm border border-line p-2">
                <div className="flex items-center gap-1.5">
                  <FaultIcon kind={w.fault.kind} className="size-3" />
                  <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-ink">{faultLabel(w.fault, names)}</span>
                  <span className="font-mono text-[10.5px]" style={{ color: w.pass ? "var(--status-good)" : w.pass === false ? "var(--status-critical)" : "var(--ink-3)" }}>
                    {w.pass ? "held" : w.pass === false ? "breached" : "no data"}
                  </span>
                </div>
                <dl className="tabular mt-1.5 grid grid-cols-3 gap-1 font-mono text-[10.5px]">
                  <div>
                    <dt className="text-ink-3">success</dt>
                    <dd className="text-ink">{fmtPct(w.success, w.success != null && w.success >= 0.995 ? 2 : 1)}</dd>
                  </div>
                  <div>
                    <dt className="text-ink-3">worst p95</dt>
                    <dd className="text-ink">{w.worstP95 == null ? "–" : `${Math.round(w.worstP95)} ms`}</dd>
                  </div>
                  <div>
                    <dt className="text-ink-3">breached</dt>
                    <dd className="text-ink">
                      {w.breached}/{w.buckets} s
                    </dd>
                  </div>
                </dl>
                <button type="button" onClick={() => jump(w.fault.startS)} className="mt-1.5 flex items-center gap-1 font-mono text-[10.5px] text-ink-3 hover:text-accent">
                  <Crosshair className="size-3" /> replay from {fmtWindow(w.fault)}
                </button>
              </div>
            ))}
          </div>
        )}
        {v.worst && (
          <div className="flex items-center gap-2 border-t border-line px-3 py-2 font-mono text-[11px]">
            <span className="min-w-0 flex-1 text-ink-2">
              worst moment t={v.worst.t.toFixed(0)} s · {v.worst.why}
              {v.worst.success != null ? ` ${fmtPct(v.worst.success, 0)}` : ""}
              {v.worst.p95 != null ? ` · p95 ${Math.round(v.worst.p95)} ms` : ""}
            </span>
            <Button size="sm" variant="soft" onClick={() => jump(v.worst!.t)}>
              <Crosshair /> jump
            </Button>
          </div>
        )}
      </div>
    </Section>
  );
}

// ---------------------------------------------------------------------------------------------
// Run bar
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
        <p className="text-[11px] leading-4 text-ink-3">
          Scenario “{scenario.name}” is applied on top ·{" "}
          <button type="button" onClick={() => selectScenario(null)} className="underline decoration-dotted hover:text-ink">
            use the current design
          </button>
        </p>
      )}
      {running && followRun ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5 text-[12px] text-ink">
              <LoaderCircle className="size-3.5 animate-spin" /> {followRun.status === "queued" ? "Waiting to start…" : `Simulating… ${Math.round(followRun.progress * 100)}%`}
            </p>
            <div className="mt-1 h-1 overflow-hidden rounded-[1px] bg-surface-3" role="progressbar" aria-valuenow={Math.round(followRun.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Simulation progress">
              <div className="progress-stripes h-full" style={{ width: `${Math.max(3, followRun.progress * 100)}%` }} />
            </div>
          </div>
          <Button size="sm" onClick={() => void cancel(followRun.id)} aria-label="Stop the simulation">
            <Square className="fill-current" /> stop
          </Button>
        </div>
      ) : (
        <div className="flex items-start gap-2.5">
          <Play className="mt-0.5 size-3.5 shrink-0 fill-current text-accent" aria-hidden />
          <p className="text-[12px] leading-5 text-ink-2">
            <span className="font-semibold text-ink">Next run: </span>
            {faults.length ? `${faults.length} failure${faults.length > 1 ? "s" : ""}` : "normal operation"} · {compact(workload.rps)} req/s · {compact(workload.durationS)} s.
            <span className="block text-[11px] text-ink-3">
              Press <span className="font-semibold text-ink-2">Simulate</span> at the top right to run it.
            </span>
          </p>
        </div>
      )}
    </div>
  );
}

/** Right panel of the Stress workspace (no props). */
export function ExperimentPanel() {
  const report = useActiveReport();
  return (
    <div className="flex min-h-full flex-col">
      <div className="flex-1 space-y-5 p-4">
        <header>
          <p className="microlabel flex items-center gap-1.5">
            <FlaskConical className="size-3" aria-hidden /> test plan
          </p>
          <h2 className="mt-1 font-display text-[26px] leading-[1.1] text-ink">
            Will it <em className="italic text-ink-2">hold up?</em>
          </h2>
          <p className="mt-1 text-[12.5px] leading-relaxed text-ink-2">
            Say what must stay true, optionally break something, then press Simulate and replay what happened.
          </p>
        </header>
        {report && <VerdictCard report={report} />}
        <HypothesisCard />
        <FaultList />
        <PresetList />
        <TrafficCard />
      </div>
      <NextRunBar />
    </div>
  );
}
