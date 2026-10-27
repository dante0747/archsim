"use client";

import { CircleAlert, CircleCheck, Crosshair, LoaderCircle, Play, RotateCcw, Square } from "lucide-react";
import { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ReferenceArea, ReferenceDot, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Legend, PERCENTILE_SERIES, SINGLE_SERIES, VIZ_VARS, fmtAxisMs, fmtAxisRps, thClass } from "@/components/results/charts";
import { Button } from "@/components/ui/button";
import { fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { failureAllowance, tooManyFailures } from "@/lib/slo";
import { MAX_SWEEP_STEPS, type SweepConfig, analyzeSweep, failedRps, rowMeetsSlo, suggestRange, sweepLevels } from "@/lib/sweep";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { LoadTooltip, PageHeader, Panel, StatusMark } from "./shared";

function NumberField({
  label,
  value,
  onChange,
  unit,
  min,
  max,
  step = 1,
  disabled,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  unit: string;
  min: number;
  max: number;
  step?: number;
  disabled?: boolean;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="microlabel">{label}</span>
      <span className="flex items-center gap-1.5">
        <input
          type="number"
          value={Number.isFinite(value) ? value : ""}
          min={min}
          max={max}
          step={step}
          disabled={disabled}
          onChange={(e) => onChange(Number(e.target.value))}
          className="h-8 w-24 rounded-md border border-line bg-surface-2 px-2 font-mono text-xs text-ink hover:border-line-strong focus-visible:border-accent focus-visible:outline-none disabled:opacity-50"
        />
        <span className="font-mono text-xs text-ink-3">{unit}</span>
      </span>
    </label>
  );
}

const tick = { fill: "var(--muted-axis)", fontSize: 10.5, fontFamily: "var(--font-mono)" };

export function BreakingPoint() {
  const workloadRps = useEditor((s) => s.workload.rps);
  const objectives = useEditor((s) => s.objectives);
  const projectId = useRuns((s) => s.projectId);
  const nodes = useEditor((s) => s.nodes);
  const autoscaled = useMemo(
    () =>
      nodes
        .filter((n) => n.data.type === "service" && (n.data.config.autoscaling === true || n.data.config.platform === "serverless"))
        .map((n) => n.data.label),
    [nodes],
  );
  const sweep = useDecide((s) => s.sweep);
  const startSweep = useDecide((s) => s.startSweep);
  const cancelSweep = useDecide((s) => s.cancelSweep);
  const clearSweep = useDecide((s) => s.clearSweep);
  const [cfg, setCfg] = useState<SweepConfig>(() => ({ ...suggestRange(workloadRps), steps: 8, durationS: 20, warmupS: 5 }));
  const mine = sweep.projectId === projectId ? sweep : null;
  const busy = mine?.status === "starting" || mine?.status === "running";
  const levels = useMemo(() => sweepLevels(cfg), [cfg]);
  const estimate = levels.reduce((s, l) => s + l * cfg.durationS, 0);
  const invalid =
    !(cfg.fromRps > 0) || !(cfg.toRps > cfg.fromRps) || cfg.toRps > 100_000 || !(cfg.durationS >= 2) || cfg.durationS > 600 || cfg.warmupS < 0 || cfg.warmupS >= cfg.durationS;
  const analysis = useMemo(() => (mine ? analyzeSweep(mine.rows, objectives) : null), [mine, objectives]);
  const rows = analysis?.rows ?? [];

  const data = rows.map((r) => ({
    rps: r.rps,
    throughput: r.throughput,
    ideal: r.offered ?? r.rps,
    failed: failedRps(r),
    p50: r.p50,
    p95: r.p95,
    p99: r.p99,
    p95All: r.p95WithFailures,
  }));
  const tpSeries = [
    { key: "throughput", label: "Successful throughput", color: SINGLE_SERIES },
    { key: "ideal", label: "Offered load (keeping up)", color: "var(--muted-axis)" },
    { key: "failed", label: "Failed requests", color: "var(--status-critical)" },
  ];
  // p95 counting failures: drawn only when some step failed requests, where it differs from p95.
  const anyFailed = rows.some((r) => r.p95WithFailures != null && r.successRate != null && r.successRate < 1);
  const latSeries: { key: string; label: string; color: string; dashed?: boolean }[] = [
    { key: "p50", label: "p50", color: PERCENTILE_SERIES[0] },
    { key: "p95", label: "p95", color: PERCENTILE_SERIES[1] },
    { key: "p99", label: "p99", color: PERCENTILE_SERIES[2] },
    ...(anyFailed ? [{ key: "p95All", label: "p95 counting failures", color: PERCENTILE_SERIES[1], dashed: true }] : []),
  ];
  const knee = analysis?.knee ?? null;
  const best = analysis?.maxWithinSlo ?? null;
  const headroom = best && workloadRps > 0 ? best.rps / workloadRps : null;
  // Steps where more requests failed than the objectives allow: latency percentiles cover only the
  // requests that succeeded, so the charts shade these steps instead of letting the curve flatter.
  const failing = analysis?.failingFrom ?? null;
  const failIdx = failing ? rows.indexOf(failing) : -1;
  const failZone =
    failing && failIdx >= 0
      ? { x1: failIdx > 0 ? (rows[failIdx - 1].rps + failing.rps) / 2 : failing.rps, x2: rows[rows.length - 1].rps }
      : null;
  const allowed = fmtPct(failureAllowance(objectives), 1);
  const successAt = (rps?: number) => {
    const r = rows.find((x) => x.rps === rps);
    return r?.successRate != null ? `succeeded ${fmtPct(r.successRate, 1)}` : null;
  };
  const zone = (label: boolean) =>
    failZone && failZone.x2 > failZone.x1 ? (
      <ReferenceArea
        x1={failZone.x1}
        x2={failZone.x2}
        fill="var(--status-critical)"
        fillOpacity={0.08}
        stroke="none"
        ifOverflow="hidden"
        label={label ? { value: `> ${allowed} failed`, position: "insideBottomRight", fill: "var(--danger-ink)", fontSize: 10.5, fontFamily: "var(--font-mono)" } : undefined}
      />
    ) : null;

  return (
    <div>
      <PageHeader
        kicker="breaking point"
        title="How much traffic can it take?"
      />
      <div className="space-y-4 p-6">
        <Panel
          title="Sweep"
          subtitle={`${levels.length} steps · ≈ ${fmtNum(estimate)} requests`}
          actions={
            mine && !busy && mine.status !== "idle" ? (
              <Button size="sm" variant="ghost" onClick={clearSweep}>
                <RotateCcw /> Clear
              </Button>
            ) : null
          }
        >
          <div className="flex flex-wrap items-end gap-x-5 gap-y-3">
            <NumberField label="From" unit="req/s" value={cfg.fromRps} min={1} max={100_000} disabled={busy} onChange={(v) => setCfg((c) => ({ ...c, fromRps: v }))} />
            <NumberField label="To" unit="req/s" value={cfg.toRps} min={1} max={100_000} disabled={busy} onChange={(v) => setCfg((c) => ({ ...c, toRps: v }))} />
            <NumberField label="Steps" unit={`≤ ${MAX_SWEEP_STEPS}`} value={cfg.steps} min={2} max={MAX_SWEEP_STEPS} disabled={busy} onChange={(v) => setCfg((c) => ({ ...c, steps: v }))} />
            <NumberField label="Each step" unit="s" value={cfg.durationS} min={2} max={600} disabled={busy} onChange={(v) => setCfg((c) => ({ ...c, durationS: v }))} />
            <NumberField label="Warm-up" unit="s" value={cfg.warmupS} min={0} max={600} disabled={busy} onChange={(v) => setCfg((c) => ({ ...c, warmupS: v }))} />
            <div className="ml-auto flex items-center gap-2">
              {busy ? (
                <Button onClick={() => void cancelSweep()}>
                  <Square className="fill-current" /> Stop
                </Button>
              ) : (
                <Button variant="primary" disabled={invalid || !projectId} onClick={() => void startSweep(useEditor.getState().toDocument(), cfg)}>
                  <Crosshair /> Find the breaking point
                </Button>
              )}
            </div>
          </div>
          <ol className="mt-4 flex flex-wrap gap-1.5" aria-label="Steps">
            {(mine?.levels.length ? mine.levels : levels).map((l, i) => {
              const done = mine?.doneSteps[i];
              const row = mine?.rows[i];
              const ok = row?.throughput != null;
              const state = !mine || mine.status === "idle" ? "planned" : done ? (ok ? "done" : "failed") : busy ? "running" : "stopped";
              return (
                <li
                  key={`${l}-${i}`}
                  className={cn(
                    "flex items-center gap-1.5 rounded-sm border px-2 py-1 font-mono text-xs",
                    state === "done" ? "border-line-strong text-ink" : state === "failed" ? "border-critical/60 text-danger-ink" : "border-line text-ink-3",
                  )}
                >
                  {state === "running" && <LoaderCircle className="size-3 animate-spin text-accent" aria-label="running" />}
                  {state === "done" && <CircleCheck className="size-3" style={{ color: "var(--status-good)" }} aria-label="done" />}
                  {state === "failed" && <CircleAlert className="size-3" style={{ color: "var(--status-critical)" }} aria-label="failed" />}
                  {fmtNum(l)}
                </li>
              );
            })}
          </ol>
          {busy && mine && (
            <div className="mt-3 h-1 overflow-hidden rounded-sm bg-surface-3" role="progressbar" aria-valuemin={0} aria-valuemax={mine.levels.length} aria-valuenow={mine.finished} aria-label="Sweep progress">
              <div className="progress-stripes h-full transition-[width]" style={{ width: `${Math.max(4, (mine.finished / Math.max(1, mine.levels.length)) * 100)}%` }} />
            </div>
          )}
          {mine?.error && <p className="mt-2 font-mono text-xs text-danger-ink">{mine.error}</p>}
          {invalid && !busy && <p className="mt-2 font-mono text-xs text-danger-ink">Check the range: To must be above From, and warm-up shorter than each step.</p>}
        </Panel>

        {analysis && rows.length > 0 && (
          <>
            <section className="relative overflow-hidden rounded-md border border-line bg-surface px-5 py-4" aria-label="Result">
              <span className="absolute inset-y-0 left-0 w-1" style={{ background: best ? "var(--accent)" : "var(--status-critical)" }} aria-hidden />
              <p className="microlabel">Result{busy ? " so far" : ""}</p>
              <p className="mt-1 text-lg font-semibold tracking-tight text-ink">{analysis.verdict}</p>
              <dl className="mt-3 grid gap-px overflow-hidden rounded-md border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
                {(
                  [
                    ["max within objectives", best ? fmtRps(best.rps) : "–"],
                    ["knee", knee ? `≈ ${fmtRps(knee.rps)}` : "not reached"],
                    ["bottleneck", analysis.saturatedAt ?? "–"],
                    ["headroom vs today", headroom != null ? `${headroom.toFixed(1)}× ${fmtNum(workloadRps)} req/s` : "–"],
                  ] as [string, string][]
                ).map(([k, v]) => (
                  <div key={k} className="bg-surface px-3 py-2.5">
                    <dt className="microlabel">{k}</dt>
                    <dd className="tabular truncate font-mono text-[15px] font-semibold text-ink">{v}</dd>
                  </div>
                ))}
              </dl>
            </section>

            <div className={cn("grid gap-4 xl:grid-cols-2", VIZ_VARS)}>
              <figure className="min-w-0 rounded-md border border-line bg-surface p-3">
                <figcaption className="mb-2 space-y-1">
                  <span className="text-xs font-semibold text-ink">Throughput against offered load</span>
                  <Legend series={tpSeries} />
                </figcaption>
                <div className="h-60">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                      <CartesianGrid stroke="var(--grid)" vertical={false} />
                      <XAxis dataKey="rps" type="number" domain={["dataMin", "dataMax"]} tickFormatter={fmtAxisRps} stroke="var(--axis)" tick={tick} tickLine={false} minTickGap={24} />
                      <YAxis width={44} tickFormatter={fmtAxisRps} tick={tick} tickLine={false} axisLine={false} domain={[0, "auto"]} />
                      <Tooltip content={<LoadTooltip format={(v) => fmtRps(v)} />} cursor={{ stroke: "var(--line-strong)" }} isAnimationActive={false} />
                      {zone(false)}
                      {knee && (
                        <ReferenceLine x={knee.rps} stroke="var(--status-serious)" strokeDasharray="4 3" label={{ value: "knee", position: "insideTopRight", fill: "var(--ink-2)", fontSize: 10.5, fontFamily: "var(--font-mono)" }} />
                      )}
                      <Line type="linear" dataKey="ideal" name={tpSeries[1].label} stroke={tpSeries[1].color} strokeWidth={1.5} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
                      <Line type="linear" dataKey="throughput" name={tpSeries[0].label} stroke={tpSeries[0].color} strokeWidth={2} dot={{ r: 3, strokeWidth: 2, stroke: "var(--surface)", fill: SINGLE_SERIES }} activeDot={{ r: 5, strokeWidth: 2, stroke: "var(--surface)" }} isAnimationActive={false} />
                      <Line type="linear" dataKey="failed" name={tpSeries[2].label} stroke={tpSeries[2].color} strokeWidth={2} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }} isAnimationActive={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </figure>
              <figure className="min-w-0 rounded-md border border-line bg-surface p-3">
                <figcaption className="mb-2 space-y-1">
                  <span className="text-xs font-semibold text-ink">Latency against offered load</span>
                  <Legend series={latSeries} />
                  <p className="text-[11px] leading-snug text-ink-3">
                    Solid lines: successful requests only.
                    {anyFailed && <> The dashed p95 counts each failed request as a full client timeout.</>}
                    {failZone && <> In the shaded steps more than {allowed} of requests failed; red dots mark a p95 that leaves them out.</>}
                  </p>
                </figcaption>
                <div className="h-60">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                      <CartesianGrid stroke="var(--grid)" vertical={false} />
                      <XAxis dataKey="rps" type="number" domain={["dataMin", "dataMax"]} tickFormatter={fmtAxisRps} stroke="var(--axis)" tick={tick} tickLine={false} minTickGap={24} />
                      <YAxis width={48} tickFormatter={fmtAxisMs} tick={tick} tickLine={false} axisLine={false} domain={[0, "auto"]} />
                      <Tooltip content={<LoadTooltip format={(v) => fmtMs(v)} note={successAt} />} cursor={{ stroke: "var(--line-strong)" }} isAnimationActive={false} />
                      {zone(true)}
                      {objectives.latencyP95Ms != null && (
                        <ReferenceLine
                          y={objectives.latencyP95Ms}
                          stroke="var(--status-critical)"
                          strokeDasharray="5 4"
                          ifOverflow="extendDomain"
                          label={{ value: `p95 objective ${fmtMs(objectives.latencyP95Ms)}`, position: "insideTopLeft", fill: "var(--ink-2)", fontSize: 10.5, fontFamily: "var(--font-mono)" }}
                        />
                      )}
                      {knee && <ReferenceLine x={knee.rps} stroke="var(--status-serious)" strokeDasharray="4 3" />}
                      {best?.p95 != null && <ReferenceDot x={best.rps} y={best.p95} r={6} fill="none" stroke="var(--accent)" strokeWidth={2} />}
                      {latSeries.map((s) => (
                        <Line
                          key={s.key}
                          type="linear"
                          dataKey={s.key}
                          name={s.label}
                          stroke={s.color}
                          strokeWidth={2}
                          strokeDasharray={s.dashed ? "5 4" : undefined}
                          dot={false}
                          activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }}
                          isAnimationActive={false}
                        />
                      ))}
                      {rows
                        .filter((r) => r.p95 != null && tooManyFailures(r.successRate, objectives))
                        .map((r) => (
                          <ReferenceDot key={r.rps} x={r.rps} y={r.p95 as number} r={4} fill="var(--status-critical)" stroke="var(--surface)" strokeWidth={1.5} />
                        ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </figure>
            </div>

            <div className="overflow-x-auto rounded-md border border-line bg-surface">
              <table className="tabular w-full min-w-[800px] text-xs">
                <thead>
                  <tr className="border-b border-line text-left">
                    {["Offered", "Throughput", "Kept up", "p50", "p95", "p99", "p95 with failures", "Success", "Bottleneck", "Objectives"].map((h, i) => (
                      <th key={h} className={cn(thClass, "px-3 py-2", i > 0 && i < 8 && "text-right")}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.rps} className={cn("border-b border-line last:border-0", knee === r && "bg-serious-soft", best === r && "bg-accent-soft")}>
                      <td className="px-3 py-2 font-mono text-ink">
                        {fmtRps(r.rps)}
                        {knee === r && <span className="ml-2 text-[11px] text-ink-2">Knee</span>}
                        {best === r && <span className="ml-2 text-[11px] text-accent">Max OK</span>}
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-ink">{fmtRps(r.throughput)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink-2">{r.throughput != null && r.offered ? fmtPct(r.throughput / r.offered, 0) : "–"}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink-2">{fmtMs(r.p50)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink">{fmtMs(r.p95)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink-2">{fmtMs(r.p99)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink-2">{fmtMs(r.p95WithFailures)}</td>
                      <td className={cn("px-3 py-2 text-right font-mono", tooManyFailures(r.successRate, objectives) ? "text-danger-ink" : "text-ink")}>{fmtPct(r.successRate, 2)}</td>
                      <td className="px-3 py-2 text-ink-2">{r.bottleneck ?? "–"}</td>
                      <td className="px-3 py-2">
                        <StatusMark pass={rowMeetsSlo(r, objectives)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        {autoscaled.length > 0 && (
          <p className="rounded-md border border-dashed border-line-strong px-3 py-2 text-xs leading-relaxed text-ink-2">
            <span className="font-mono text-xs text-ink">Autoscaling: </span>
            {autoscaled.join(", ")} {autoscaled.length > 1 ? "scale" : "scales"} during a run. Each step starts at the configured
            replica count, so short steps measure capacity before the scaler reacts. Use steps longer than the scale interval plus
            start-up time to include scale-out.
          </p>
        )}

        {(!mine || mine.status === "idle") && (
          <p className="flex items-center gap-2 font-mono text-xs text-ink-3">
            <Play className="size-3.5" /> Starts {levels.length} short runs from {fmtNum(levels[0])} to {fmtNum(levels[levels.length - 1])} req/s. Faults and traffic
            shape are ignored so the curve measures steady-state capacity.
          </p>
        )}
      </div>
    </div>
  );
}
