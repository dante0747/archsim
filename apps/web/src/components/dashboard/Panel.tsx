"use client";

/**
 * Panel chrome and the non-chart panel bodies: header (title, info popover with how each metric is
 * calculated, menu with fullscreen, inspect and show on canvas), stat panels with a background
 * sparkline and threshold colouring, and the failure breakdown table.
 */
import { CircleAlert, CircleCheck, EllipsisVertical, Info, LocateFixed, Maximize2, Table2, TriangleAlert } from "lucide-react";
import { Popover as RPopover } from "radix-ui";
import { formatUsd } from "@/lib/cost";
import {
  type MetricThresholds,
  type ThresholdStatus,
  formatValue,
  metricDef,
  thresholdStatus,
  unitLabel,
} from "@/lib/metrics-catalog";
import type { ExplainEntry, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { useDashView } from "./context";
import { type PanelSpec, type StatSpec, type TableSpec, statReadout } from "./panels";
import { lastDrawable, visibleBuckets } from "./range";
import { useCursor, useDashboard } from "./store";
import { STATUS_COLOR, TimeSeriesChart } from "./TimeSeriesChart";

/**
 * The engine's calculations behind a panel (report 1.5+): the component's own entries for its
 * panels, the system entries for system panels. Shared panels across components show none.
 */
function explainFor(report: Report, spec: PanelSpec): ExplainEntry[] {
  if (spec.nodeId) return report.nodes.find((n) => n.id === spec.nodeId)?.explain ?? [];
  if (spec.metrics.some((m) => m.scope !== "node")) return report.explain?.system ?? [];
  return [];
}

const explains = (e: ExplainEntry, key: string) => e.metric === key || e.id === key;

export const STATUS_LABEL: Record<ThresholdStatus, string> = {
  good: "Healthy",
  warning: "Warning",
  serious: "Serious",
  critical: "Critical",
};

const STATUS_SOFT: Record<ThresholdStatus, string> = {
  good: "var(--status-good-soft)",
  warning: "var(--status-warning-soft)",
  serious: "var(--status-serious-soft)",
  critical: "var(--status-critical-soft)",
};

function thresholdText(t: MetricThresholds, unit: Parameters<typeof formatValue>[1]): string {
  const word = t.direction === "above" ? "from" : "below";
  return t.steps.map((s) => `${STATUS_LABEL[s.status].toLowerCase()} ${word} ${formatValue(s.value, unit)}`).join(" · ");
}

/** Switch to Design and select the component. */
export function showOnCanvas(nodeId: string) {
  useUi.getState().setMode("build");
  useEditor.getState().select({ nodeId });
}

// ---------------------------------------------------------------------------------------------
// Header pieces
// ---------------------------------------------------------------------------------------------

function InfoPopover({ spec }: { spec: PanelSpec }) {
  const { report } = useDashView();
  const explain = explainFor(report, spec);
  return (
    <RPopover.Root>
      <RPopover.Trigger asChild>
        <button type="button" className="shrink-0 rounded-sm p-0.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label={`About ${spec.title}`}>
          <Info className="size-3.5" />
        </button>
      </RPopover.Trigger>
      <RPopover.Portal>
        <RPopover.Content
          side="bottom"
          align="start"
          sideOffset={6}
          collisionPadding={8}
          className="surface-glass z-50 max-h-[min(60vh,var(--radix-popover-content-available-height))] w-[340px] overflow-y-auto rounded-md p-3 text-xs leading-relaxed"
        >
          <p className="text-[13px] font-semibold text-ink">{spec.title}</p>
          {spec.description && <p className="mt-0.5 text-ink-2">{spec.description}</p>}
          {spec.metrics.map((m) => {
            const def = metricDef(m.key, m.scope);
            const ex = explain.find((e) => explains(e, m.key));
            return (
              <div key={`${m.scope}:${m.key}`} className="mt-2.5 border-t border-line pt-2">
                <p className="font-medium text-ink">
                  {def.label} <span className="font-normal text-ink-3">· {unitLabel(def.unit)}</span>
                </p>
                <p className="text-ink-2">{def.description}</p>
                <p className="mt-1 text-ink-2">
                  <span className="font-medium text-ink">How it’s calculated: </span>
                  {def.howCalculated}
                </p>
                {def.thresholds && <p className="mt-1 text-ink-3">Thresholds: {thresholdText(def.thresholds, def.unit)}</p>}
                {ex && (
                  <div className="mt-1.5 rounded-sm border border-line bg-surface-2 px-2 py-1.5">
                    <p className="mb-0.5 text-[11px] font-medium text-ink-2">In this run{ex.title ? `: ${ex.title}` : ""}</p>
                    <p className="font-code text-[11px] text-ink">{ex.formula}</p>
                    <p className="font-code text-[11px] text-ink-2">{ex.substituted}</p>
                    {ex.result != null && (
                      <p className="font-code text-[11px] text-ink">
                        = {ex.result.toLocaleString("en-US", { maximumFractionDigits: 3 })}
                        {ex.unit ? ` ${ex.unit}` : ""}
                      </p>
                    )}
                    {ex.note && <p className="mt-0.5 text-ink-3">{ex.note}</p>}
                    {ex.source &&
                      (/^https?:\/\//.test(ex.source) ? (
                        <a href={ex.source} target="_blank" rel="noreferrer" className="mt-0.5 block truncate text-[11px] text-accent hover:underline">
                          Source
                        </a>
                      ) : (
                        <p className="mt-0.5 truncate text-[11px] text-ink-3">Source: {ex.source}</p>
                      ))}
                  </div>
                )}
              </div>
            );
          })}
          {spec.kind === "table" && <p className="mt-2 text-ink-3">Counts cover the measurement window, so they do not follow the time range.</p>}
          {spec.kind !== "table" && spec.kind !== "stat" && (
            <p className="mt-2.5 border-t border-line pt-2 text-ink-3">Drag across the chart to zoom every panel; double-click to zoom out.</p>
          )}
        </RPopover.Content>
      </RPopover.Portal>
    </RPopover.Root>
  );
}

function PanelMenu({ spec }: { spec: PanelSpec }) {
  // A dialog covers the panel before the pointer leaves it: drop the crosshair first.
  const setFullscreen = (id: string) => {
    useCursor.getState().setCursor(null, null);
    useDashboard.getState().setFullscreen(id);
  };
  const setInspect = (id: string) => {
    useCursor.getState().setCursor(null, null);
    useDashboard.getState().setInspect(id);
  };
  const components =
    spec.kind === "timeseries" && !spec.nodeId ? spec.series.filter((s) => s.nodeId).map((s) => ({ id: s.nodeId as string, label: s.label })) : [];
  if (spec.kind === "stat" && !spec.nodeId) return null;
  return (
    <Menu>
      <MenuTrigger asChild>
        <button type="button" className="rounded-sm p-0.5 text-ink-3 hover:bg-surface-2 hover:text-ink data-[state=open]:bg-surface-2 data-[state=open]:text-ink" aria-label={`${spec.title} menu`}>
          <EllipsisVertical className="size-3.5" />
        </button>
      </MenuTrigger>
      <MenuContent align="end" className="w-56">
        {spec.kind !== "stat" && (
          <MenuItem onSelect={() => setFullscreen(spec.id)}>
            <Maximize2 /> View fullscreen
          </MenuItem>
        )}
        {spec.kind === "timeseries" && (
          <MenuItem onSelect={() => setInspect(spec.id)}>
            <Table2 /> Inspect data
          </MenuItem>
        )}
        {spec.nodeId && (
          <MenuItem onSelect={() => showOnCanvas(spec.nodeId as string)}>
            <LocateFixed /> Show on canvas
          </MenuItem>
        )}
        {components.length > 0 && components.length <= 9 && (
          <>
            <MenuSeparator />
            <MenuLabel>Show on canvas</MenuLabel>
            {components.map((c) => (
              <MenuItem key={c.id} onSelect={() => showOnCanvas(c.id)}>
                <LocateFixed /> <span className="truncate">{c.label}</span>
              </MenuItem>
            ))}
          </>
        )}
      </MenuContent>
    </Menu>
  );
}

// ---------------------------------------------------------------------------------------------
// Frame
// ---------------------------------------------------------------------------------------------

export function PanelFrame({
  spec,
  span,
  children,
  className,
  tint,
  extra,
}: {
  spec: PanelSpec;
  span: number;
  children: React.ReactNode;
  className?: string;
  tint?: string;
  /** Shown at the right end of the header (a stat's status). */
  extra?: React.ReactNode;
}) {
  return (
    <section
      className={cn("group/panel relative flex min-w-0 flex-col rounded-md border border-line bg-surface", className)}
      style={{ gridColumn: `span ${span} / span ${span}`, background: tint }}
      aria-label={spec.title}
      data-panel={spec.id}
    >
      <header className="flex h-8 shrink-0 items-center gap-1 pl-2.5 pr-1.5">
        <h3 className="min-w-0 truncate text-[13px] font-medium text-ink">{spec.title}</h3>
        {spec.subtitle && <span className="min-w-0 shrink truncate text-xs text-ink-3">{spec.subtitle}</span>}
        <InfoPopover spec={spec} />
        {extra && <div className="ml-auto pl-1">{extra}</div>}
        <div className={cn("flex items-center opacity-0 transition-opacity focus-within:opacity-100 group-hover/panel:opacity-100 has-[[data-state=open]]:opacity-100", !extra && "ml-auto")}>
          <PanelMenu spec={spec} />
        </div>
      </header>
      <div className="min-h-0 flex-1">{children}</div>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// Stat
// ---------------------------------------------------------------------------------------------

function splitValue(v: number | null, unit: StatSpec["unit"]): [string, string] {
  if (v == null || !Number.isFinite(v)) return ["–", ""];
  if (unit === "usd") return [formatUsd(v, { compact: true }), "/mo"];
  const s = formatValue(v, unit);
  if (s.endsWith("%")) return [s.slice(0, -1), "%"];
  const i = s.lastIndexOf(" ");
  return i > 0 ? [s.slice(0, i), s.slice(i + 1)] : [s, ""];
}

const StatusIcon = ({ status }: { status: ThresholdStatus }) => {
  const Icon = status === "good" ? CircleCheck : status === "critical" ? CircleAlert : TriangleAlert;
  return <Icon className="size-3 shrink-0" style={{ color: STATUS_COLOR[status] }} aria-hidden />;
};

function Sparkline({ spec, color }: { spec: StatSpec; color: string }) {
  const { range, bucketS: b, n, playhead } = useDashView();
  const data = spec.spark;
  if (!data) return null;
  const vis = visibleBuckets(n, b, range, null);
  if (!vis) return null;
  const last = Math.min(vis.i1, lastDrawable(n, b, playhead));
  const vals: number[] = [];
  for (let i = vis.i0; i <= vis.i1; i++) {
    const v = data[i];
    if (v != null && Number.isFinite(v)) vals.push(v);
  }
  if (!vals.length) return null;
  let lo = Math.min(...vals);
  let hi = Math.max(...vals);
  if (hi - lo < Math.max(Math.abs(hi) * 0.01, 1e-9)) {
    lo -= Math.max(Math.abs(hi) * 0.5, 1);
    hi += Math.max(Math.abs(hi) * 0.5, 1);
  }
  const pad = (hi - lo) * 0.12;
  lo -= pad;
  hi += pad;
  const W = 100;
  const H = 40;
  const span = Math.max(range.to - range.from, 1e-9);
  const x = (i: number) => (((i + 0.5) * b - range.from) / span) * W;
  const y = (v: number) => H - ((v - lo) / (hi - lo)) * H;
  let d = "";
  let first: number | null = null;
  let lastX = 0;
  for (let i = vis.i0; i <= last; i++) {
    const v = data[i];
    if (v == null || !Number.isFinite(v)) continue;
    d += `${first == null ? "M" : "L"}${x(i).toFixed(2)},${y(v).toFixed(2)}`;
    if (first == null) first = x(i);
    lastX = x(i);
  }
  if (!d || first == null) return null;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="pointer-events-none absolute inset-0 h-full w-full" aria-hidden>
      <path d={`${d}L${lastX.toFixed(2)},${H}L${first.toFixed(2)},${H}Z`} fill={color} fillOpacity={0.14} />
      <path d={d} fill="none" stroke={color} strokeOpacity={0.75} strokeWidth={1.5} vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
    </svg>
  );
}

export function StatPanel({ spec, span }: { spec: StatSpec; span: number }) {
  const view = useDashView();
  const r = statReadout(spec, { range: view.range, measured: view.measured, full: view.full, n: view.n, bucketS: view.bucketS, playhead: view.playhead });
  const status = spec.thresholds ? thresholdStatus(r.value, spec.thresholds) : null;
  const [num, unit] = splitValue(r.value, spec.unit);
  const alarm = status && status !== "good";
  const color = status ? STATUS_COLOR[status] : "var(--ink-3)";
  // a dollar figure names where its prices come from first
  const second = [spec.source?.label, r.owner ?? spec.note, r.caption].filter(Boolean).join(" · ");
  return (
    <PanelFrame
      spec={spec}
      span={span}
      tint={alarm ? STATUS_SOFT[status] : undefined}
      className="h-[116px] overflow-hidden"
      extra={
        status && (
          <span className="flex shrink-0 items-center gap-1 text-[11px] font-medium text-ink">
            <StatusIcon status={status} />
            {STATUS_LABEL[status]}
          </span>
        )
      }
    >
      <div className="flex h-full flex-col">
        <div className="px-3">
          <div className="flex items-baseline gap-1">
            <span className="text-[28px] font-semibold leading-none tracking-[-0.02em] text-ink">{num}</span>
            {unit && <span className="text-[13px] font-medium text-ink-2">{unit}</span>}
          </div>
          <p className="mt-1 truncate text-[11px] text-ink-3" title={spec.source ? [spec.source.detail, r.owner ?? spec.note, r.caption].filter(Boolean).join(" · ") : second}>
            {second || "\u00a0"}
          </p>
        </div>
        <div className="relative mt-auto h-[26px] shrink-0">
          <Sparkline spec={spec} color={color} />
        </div>
      </div>
    </PanelFrame>
  );
}

// ---------------------------------------------------------------------------------------------
// Failure breakdown table
// ---------------------------------------------------------------------------------------------

const OUTCOME: Record<string, string> = { rejected: "Rejected", failed: "Failed", timeout: "Timed out", error: "Error" };

export function FailureTable({ spec, expanded = false }: { spec: TableSpec; expanded?: boolean }) {
  if (!spec.rows.length) {
    return (
      <div className="flex h-full min-h-[150px] flex-col items-center justify-center gap-1.5 text-xs text-ink-2">
        <CircleCheck className="size-4" style={{ color: "var(--status-good)" }} aria-hidden />
        No failed requests in the measurement window
      </div>
    );
  }
  const total = spec.rows.reduce((s, r) => s + r.count, 0);
  return (
    <div className={cn("h-full overflow-auto px-1 pb-1", !expanded && "max-h-[248px]")}>
      <table className="w-full text-xs">
        <thead className="sticky top-0 z-[1] bg-surface">
          <tr className="text-[11px] text-ink-3">
            <th className="py-1 pl-1.5 text-left font-normal">Reason</th>
            <th className="py-1 text-left font-normal">Component</th>
            <th className="w-20 py-1 pr-4 text-right font-normal">Count</th>
            <th className="w-[32%] py-1 pr-1.5 text-left font-normal">Share</th>
          </tr>
        </thead>
        <tbody>
          {spec.rows.map((r, k) => {
            const share = r.share ?? (total ? r.count / total : 0);
            return (
              <tr key={`${r.reason}-${r.nodeId}-${r.edgeId}-${k}`} className="border-t border-line">
                <td className="py-1 pl-1.5">
                  <span className="block truncate text-ink">{r.reasonText || r.reason}</span>
                  <span className="text-[11px] text-ink-3">{OUTCOME[r.status] ?? r.status}</span>
                </td>
                <td className="max-w-0 py-1">
                  {r.nodeId ? (
                    <button type="button" className="block max-w-full truncate text-left text-ink-2 hover:text-accent hover:underline" onClick={() => showOnCanvas(r.nodeId as string)} title="Show on canvas">
                      {r.nodeLabel ?? r.nodeId}
                    </button>
                  ) : (
                    <span className="text-ink-3">–</span>
                  )}
                </td>
                <td className="tabular py-1 pr-4 text-right text-ink">{r.count.toLocaleString("en-US")}</td>
                <td className="py-1 pr-1.5">
                  <div className="flex items-center gap-2">
                    <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-3" aria-hidden>
                      <span className="block h-full rounded-full bg-ink-3" style={{ width: `${Math.max(2, share * 100)}%` }} />
                    </span>
                    <span className="tabular w-11 text-right text-ink-2">{formatValue(share, "ratio")}</span>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------------------------

export function PanelView({ spec, span }: { spec: PanelSpec; span: number }) {
  if (spec.kind === "stat") return <StatPanel spec={spec} span={span} />;
  return (
    <PanelFrame spec={spec} span={span}>
      {spec.kind === "table" ? <FailureTable spec={spec} /> : <TimeSeriesChart spec={spec} />}
    </PanelFrame>
  );
}
