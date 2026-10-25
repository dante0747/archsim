"use client";

/**
 * Dashboard workspace: a Grafana-style monitoring view of the active run. Collapsible rows of
 * panels on a 24-column grid (overview stats, traffic, latency, saturation, errors, then one row
 * per component), one time range for every panel (drag to zoom), a synced crosshair, and a replay
 * that draws the run up to the shared playhead.
 */
import { Activity, ChevronRight, Download, LocateFixed, Play, X } from "lucide-react";
import { Dialog as RDialog } from "radix-ui";
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { usePlaybackLoop } from "@/components/timeline/playback";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/misc";
import { estimateCost } from "@/lib/cost";
import { formatCompact, formatValue } from "@/lib/metrics-catalog";
import { detectEvents, faultLabel, runFaults } from "@/lib/recorder";
import { NODE_STATUS } from "@/lib/status";
import { KIND_LABEL, type Kind } from "@/lib/theme";
import type { NodeReport, Report, Run } from "@/lib/types";
import { cn, modKey } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { type DashView, DashViewProvider, useDashView } from "./context";
import { DashboardHeader, RunProgressPill, VariablesBar } from "./DashboardHeader";
import { FailureTable, PanelView, showOnCanvas } from "./Panel";
import {
  type DashboardModel,
  OTHER_COLOR,
  type PanelSpec,
  type RowSpec,
  type TimeSeriesSpec,
  buildDashboard,
  componentColors,
  groupRowsByKind,
  narrowSpan,
  panelTable,
  toCsv,
} from "./panels";
import { type TimeRange, fmtRange, fullRange, measuredRange, sameRange, zoomOutRange } from "./range";
import { useCursor, useDashboard } from "./store";
import { TimeSeriesChart, useSize } from "./TimeSeriesChart";

/** Below this width panels double their span (two stats or two charts per line). */
const NARROW_PX = 1100;
/** While replaying, redraw the panels at most this often (the playhead itself moves every frame). */
const REPLAY_FRAME_MS = 60;

/** The shared playhead, throttled while playing so 30+ panels redraw at a steady rate. */
function useDashboardPlayhead(): number | null {
  const [playhead, setPlayhead] = useState(() => useUi.getState().playhead);
  useEffect(() => {
    let last = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const flush = () => {
      timer = null;
      last = performance.now();
      setPlayhead(useUi.getState().playhead);
    };
    const unsub = useUi.subscribe((s, prev) => {
      if (s.playhead === prev.playhead) return;
      const wait = REPLAY_FRAME_MS - (performance.now() - last);
      if (!s.playing || s.playhead == null || wait <= 0) {
        if (timer) clearTimeout(timer);
        flush();
      } else if (!timer) {
        timer = setTimeout(flush, wait);
      }
    });
    return () => {
      unsub();
      if (timer) clearTimeout(timer);
    };
  }, []);
  return playhead;
}

export function Dashboard() {
  const report = useActiveReport();
  const activeRunId = useRuns((s) => s.activeRunId);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const running = followRun && (followRun.status === "queued" || followRun.status === "running") ? followRun : null;
  if (!report || !activeRunId) return <EmptyDashboard running={running} />;
  return <DashboardBody report={report} runId={activeRunId} running={running} />;
}

// ---------------------------------------------------------------------------------------------
// Empty and loading
// ---------------------------------------------------------------------------------------------

function EmptyDashboard({ running }: { running: Run | null }) {
  const { run } = useEditorActions();
  const starting = useRuns((s) => s.starting);
  return (
    <div className="flex h-full flex-col bg-page">
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-line bg-surface px-3">
        <h2 className="text-[14px] font-semibold tracking-[-0.01em] text-ink">Dashboard</h2>
        {running && (
          <div className="ml-auto">
            <RunProgressPill run={running} />
          </div>
        )}
      </div>
      <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden p-6">
        {/* Ghost panels hint at what will appear. */}
        <div className="pointer-events-none absolute inset-3 grid grid-cols-[repeat(24,minmax(0,1fr))] content-start gap-2 opacity-60" aria-hidden>
          {[4, 4, 4, 4, 4, 4, 12, 12, 8, 8, 8].map((span, i) => (
            <div key={i} className={cn("rounded-md border border-dashed border-line", i < 6 ? "h-[112px]" : "h-[230px]")} style={{ gridColumn: `span ${span} / span ${span}` }} />
          ))}
        </div>
        <div className="relative w-full max-w-md rounded-lg border border-line-strong bg-surface p-6 text-center shadow-float">
          <span className="mx-auto mb-3 flex size-10 items-center justify-center rounded-md bg-accent-soft text-accent">
            <Activity className="size-5" />
          </span>
          {running ? (
            <>
              <p className="text-[15px] font-semibold text-ink">{running.status === "queued" ? "Waiting to start…" : "Simulating…"}</p>
              <p className="mt-1 text-[13px] leading-relaxed text-ink-2">Every panel fills in as soon as the run finishes.</p>
              <div className="mx-auto mt-4 h-1.5 w-56 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={Math.round(running.progress * 100)} aria-valuemin={0} aria-valuemax={100}>
                <div className="progress-stripes h-full" style={{ width: `${Math.max(4, running.progress * 100)}%` }} />
              </div>
              <p className="tabular mt-2 text-xs text-ink-3">{Math.round(running.progress * 100)}%</p>
            </>
          ) : (
            <>
              <p className="text-[15px] font-semibold text-ink">Nothing to monitor yet</p>
              <p className="mt-1 text-[13px] leading-relaxed text-ink-2">
                Simulate the design and watch it behave second by second: traffic, latency, saturation, errors and every component.
              </p>
              <div className="mt-4 flex items-center justify-center gap-2">
                <Button variant="primary" onClick={() => void run()} disabled={starting}>
                  <Play className="fill-current" /> Simulate
                </Button>
                <Kbd>{modKey()} Enter</Kbd>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------------------------

function DashboardBody({ report, runId, running }: { report: Report; runId: string; running: Run | null }) {
  const objectives = useEditor((s) => s.objectives);
  const catalog = useEditor((s) => s.catalog);
  const playhead = useDashboardPlayhead();
  const storedRange = useDashboard((s) => (s.rangeRunId === runId ? s.range : null));
  const setStoredRange = useDashboard((s) => s.setRange);
  const hiddenList = useDashboard((s) => s.hidden);
  const groupByKind = useDashboard((s) => s.groupByKind);
  const collapsed = useDashboard((s) => s.collapsed);
  const setCollapsed = useDashboard((s) => s.setCollapsed);
  const mainRef = useRef<HTMLElement>(null);
  const { w: mainW } = useSize(mainRef);

  const bucketS = report.timeseries.bucketS || 1;
  const n = report.timeseries.t.length;
  const full = useMemo(() => fullRange(report), [report]);
  const measured = useMemo(() => measuredRange(report), [report]);
  const range = storedRange ?? full;
  usePlaybackLoop(full.to, bucketS);

  const events = useMemo(() => detectEvents(report, objectives), [report, objectives]);
  const faults = useMemo(() => runFaults(report).map((f) => ({ fault: f, label: faultLabel(report, f) })), [report]);
  const colors = useMemo(() => componentColors(report.nodes), [report]);
  const cost = useMemo(() => {
    const c = estimateCost(report.input?.architecture?.nodes ?? [], catalog, report);
    return { monthly: c.monthly, perMillionRequests: c.perMillionRequests, hasCost: c.hasCost };
  }, [report, catalog]);
  const typeNames = useMemo(() => Object.fromEntries((catalog?.components ?? []).map((c) => [c.type, c.displayName])), [catalog]);
  const hidden = useMemo(() => new Set(hiddenList), [hiddenList]);
  const model = useMemo(
    () => buildDashboard({ report, objectives, cost, colors, hidden }),
    [report, objectives, cost, colors, hidden],
  );
  const rows = groupByKind ? groupRowsByKind(model.rows) : model.rows;

  const setRange = useCallback(
    (r: TimeRange | null) => setStoredRange(runId, r && !sameRange(r, full) ? r : null),
    [setStoredRange, runId, full],
  );
  const zoomOut = useCallback(() => {
    const s = useDashboard.getState();
    const cur = s.rangeRunId === runId ? s.range : null;
    if (cur) setStoredRange(runId, zoomOutRange(cur, full));
  }, [setStoredRange, runId, full]);

  const view = useMemo<DashView>(
    () => ({ report, range, full, measured, zoomed: storedRange != null, bucketS, n, playhead, events, faults, setRange, zoomOut }),
    [report, range, full, measured, storedRange, bucketS, n, playhead, events, faults, setRange, zoomOut],
  );

  const narrow = mainW > 0 && mainW < NARROW_PX;
  const nodesById = useMemo(() => new Map(report.nodes.map((x) => [x.id, x])), [report]);

  return (
    <DashViewProvider value={view}>
      <div className="flex h-full min-h-0 flex-col bg-page">
        <DashboardHeader runId={runId} endS={full.to} running={running} />
        <VariablesBar nodes={report.nodes} colors={colors} rowIds={rows.map((r) => r.id)} />
        <main
          ref={mainRef}
          className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-3 pb-10 pt-1"
          aria-label="Dashboard panels"
          onPointerLeave={() => useCursor.getState().setCursor(null, null)}
        >
          {rows.map((row, k) => {
            const prev = rows[k - 1];
            const heading = groupByKind && row.nodeId && (!prev?.nodeId || prev.kind !== row.kind) ? row.kind : null;
            return (
              <Fragment key={row.id}>
                {heading && <KindHeading kind={heading} count={rows.filter((r) => r.nodeId && r.kind === heading).length} />}
                <DashboardRow
                  row={row}
                  collapsed={collapsed[row.id] ?? row.defaultCollapsed}
                  onToggle={() => setCollapsed(row.id, !(collapsed[row.id] ?? row.defaultCollapsed))}
                  node={row.nodeId ? nodesById.get(row.nodeId) : undefined}
                  color={row.nodeId ? (colors.get(row.nodeId) ?? OTHER_COLOR) : undefined}
                  typeName={row.nodeId ? typeNames[nodesById.get(row.nodeId)?.type ?? ""] : undefined}
                  narrow={narrow}
                />
              </Fragment>
            );
          })}
          {hidden.size > 0 && (
            <p className="mt-3 text-center text-xs text-ink-3">
              {hidden.size} component{hidden.size > 1 ? "s" : ""} hidden by the component filter.
            </p>
          )}
        </main>
      </div>
      <FullscreenDialog model={model} />
      <InspectDialog model={model} />
    </DashViewProvider>
  );
}

function KindHeading({ kind, count }: { kind: Kind; count: number }) {
  return (
    <div className="mt-4 flex items-center gap-2 pb-0.5">
      <span className="microlabel">{KIND_LABEL[kind]}</span>
      <span className="tabular text-[11px] text-ink-3">{count}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

function NodeSummary({ node, typeName }: { node: NodeReport; typeName?: string }) {
  const st = NODE_STATUS[node.status] ?? NODE_STATUS.ok;
  const s = node.summary;
  return (
    <div className="flex min-w-0 items-center gap-2.5 text-xs">
      {typeName && <span className="hidden shrink-0 text-ink-3 sm:inline">{typeName}</span>}
      <span className="flex shrink-0 items-center gap-1 rounded-sm border border-line px-1.5 py-px text-[11px] font-medium text-ink-2" title={node.statusReasons.join("; ") || st.description}>
        <span className="size-1.5 rounded-full" style={{ background: st.color }} aria-hidden />
        {st.label}
      </span>
      {s?.utilization != null && <span className="tabular hidden shrink-0 text-ink-3 md:inline">{formatValue(s.utilization, "ratio")} busy</span>}
      {s?.arrivalRps != null && <span className="tabular hidden shrink-0 text-ink-3 md:inline">{formatCompact(s.arrivalRps, "req/s")} in</span>}
    </div>
  );
}

function DashboardRow({
  row,
  collapsed,
  onToggle,
  node,
  color,
  typeName,
  narrow,
}: {
  row: RowSpec;
  collapsed: boolean;
  onToggle: () => void;
  node?: NodeReport;
  color?: string;
  typeName?: string;
  narrow: boolean;
}) {
  const count = row.panels.length;
  return (
    <section className="mt-1" aria-label={row.title}>
      <div className="group/row flex h-9 items-center gap-2.5">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={!collapsed}
          className="flex min-w-0 shrink items-center gap-1.5 rounded-sm py-1 pr-1 text-left focus-visible:outline-offset-0"
        >
          <ChevronRight className={cn("size-4 shrink-0 text-ink-3 transition-transform duration-150", !collapsed && "rotate-90")} aria-hidden />
          {color && <span className="h-3.5 w-[3px] shrink-0 rounded-full" style={{ background: color }} aria-hidden />}
          <span className="truncate text-[14px] font-semibold tracking-[-0.01em] text-ink">{row.title}</span>
          <span className="tabular shrink-0 text-xs font-normal text-ink-3">
            ({count} panel{count === 1 ? "" : "s"})
          </span>
        </button>
        {node && <NodeSummary node={node} typeName={typeName} />}
        <span className="h-px min-w-4 flex-1 bg-line" aria-hidden />
        {node && (
          <button
            type="button"
            onClick={() => showOnCanvas(node.id)}
            className="flex shrink-0 items-center gap-1 rounded-md px-1.5 py-1 text-xs text-ink-3 opacity-0 transition-opacity hover:bg-surface-2 hover:text-ink focus-visible:opacity-100 group-hover/row:opacity-100"
          >
            <LocateFixed className="size-3.5" /> Show on canvas
          </button>
        )}
      </div>
      {!collapsed &&
        (count ? (
          <div className="grid grid-cols-[repeat(24,minmax(0,1fr))] gap-2 pb-2">
            {row.panels.map((p) => (
              <PanelView key={p.id} spec={p} span={narrow ? narrowSpan(p.span) : p.span} />
            ))}
          </div>
        ) : (
          <p className="pb-2 pl-6 text-xs text-ink-3">This component reported no time series.</p>
        ))}
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// Fullscreen and inspect
// ---------------------------------------------------------------------------------------------

function findPanel(model: DashboardModel, id: string | null): PanelSpec | null {
  if (!id) return null;
  for (const r of model.rows) for (const p of r.panels) if (p.id === id) return p;
  return null;
}

function DialogShell({
  open,
  onClose,
  title,
  subtitle,
  actions,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <RDialog.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <RDialog.Content className="fixed inset-4 z-50 flex flex-col overflow-hidden rounded-lg border border-line-strong bg-surface shadow-float md:inset-x-10 md:inset-y-8" aria-describedby={undefined}>
          <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line px-4">
            <RDialog.Title className="truncate text-[14px] font-semibold text-ink">{title}</RDialog.Title>
            {subtitle && <span className="truncate text-xs text-ink-3">{subtitle}</span>}
            <div className="ml-auto flex items-center gap-2">
              {actions}
              <RDialog.Close className="rounded-md p-1 text-ink-3 transition-colors hover:bg-surface-2 hover:text-ink" aria-label="Close">
                <X className="size-4" />
              </RDialog.Close>
            </div>
          </div>
          {children}
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}

function FullscreenDialog({ model }: { model: DashboardModel }) {
  const id = useDashboard((s) => s.fullscreenId);
  const setFullscreen = useDashboard((s) => s.setFullscreen);
  const { range, zoomed, report } = useDashView();
  const spec = findPanel(model, id);
  const node = spec?.nodeId ? report.nodes.find((x) => x.id === spec.nodeId) : undefined;
  return (
    <DialogShell
      open={!!spec && spec.kind !== "stat"}
      onClose={() => setFullscreen(null)}
      title={spec ? `${node ? `${node.label} · ` : ""}${spec.title}` : ""}
      subtitle={[spec?.subtitle, zoomed ? fmtRange(range) : "whole run"].filter(Boolean).join(" · ")}
    >
      <div className="min-h-0 flex-1 p-3">
        {spec?.kind === "timeseries" && <TimeSeriesChart spec={spec} expanded />}
        {spec?.kind === "table" && <FailureTable spec={spec} expanded />}
      </div>
    </DialogShell>
  );
}

function downloadText(filename: string, text: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "panel";

function InspectDialog({ model }: { model: DashboardModel }) {
  const id = useDashboard((s) => s.inspectId);
  const setInspect = useDashboard((s) => s.setInspect);
  const { range, bucketS, report } = useDashView();
  const found = findPanel(model, id);
  const spec = found?.kind === "timeseries" ? (found as TimeSeriesSpec) : null;
  const table = useMemo(() => (spec ? panelTable(spec, bucketS, range) : null), [spec, bucketS, range]);
  const node = spec?.nodeId ? report.nodes.find((x) => x.id === spec.nodeId) : undefined;
  const fileName = `${slug(report.input?.metadata?.name ?? "run")}-${slug([node?.label, spec?.title].filter(Boolean).join(" "))}.csv`;
  return (
    <DialogShell
      open={!!spec}
      onClose={() => setInspect(null)}
      title={spec ? `Inspect: ${node ? `${node.label} · ` : ""}${spec.title}` : ""}
      subtitle={table ? `${fmtRange(range)} · ${table.rows.length} buckets of ${bucketS} s` : undefined}
      actions={
        table && (
          <Button size="sm" onClick={() => downloadText(fileName, toCsv(table), "text/csv")}>
            <Download /> Download CSV
          </Button>
        )
      }
    >
      <div className="min-h-0 flex-1 overflow-auto">
        {spec && table && (
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-surface-2">
              <tr className="text-[11px] text-ink-3">
                {table.header.map((hd, k) => (
                  <th key={hd + k} className={cn("whitespace-nowrap px-3 py-1.5 font-medium", k === 0 ? "text-left" : "text-right")}>
                    {k > 0 && <span className="mr-1.5 inline-block h-[3px] w-3 rounded-full align-middle" style={{ background: spec.series[k - 1]?.color }} aria-hidden />}
                    {hd}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((r) => (
                <tr key={r[0]} className="border-t border-line hover:bg-surface-2">
                  {r.map((v, k) => (
                    <td key={k} className={cn("tabular whitespace-nowrap px-3 py-1", k === 0 ? "text-left text-ink-3" : "text-right text-ink")}>
                      {k === 0 ? `${v} s` : formatValue(v, spec.unit)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {table && table.rows.length === 0 && <p className="p-6 text-center text-xs text-ink-3">No buckets in this range.</p>}
      </div>
    </DialogShell>
  );
}
