"use client";

/**
 * Dashboard toolbars: the header (run picker, time range, zoom out / reset, replay with a LIVE
 * badge, run progress) and the variables bar (component filter, group by kind, expand / collapse,
 * annotation key). Replay drives the shared playhead, so the Test workspace stays in sync.
 */
import { Check, ChevronDown, ChevronsDownUp, ChevronsUpDown, Clock, LayoutDashboard, Pause, Play, RotateCcw, Square, ZoomOut } from "lucide-react";
import { RunPicker } from "@/components/decide/shared";
import { showSummary, togglePlay } from "@/components/timeline/playback";
import { Switch, Tip } from "@/components/ui/misc";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { fmtClock } from "@/lib/recorder";
import { KIND_LABEL, kindOf } from "@/lib/theme";
import type { NodeReport, Run } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { useDashView } from "./context";
import { OTHER_COLOR } from "./panels";
import { type TimeRange, clampRange, fmtRange, sameRange } from "./range";
import { useDashboard } from "./store";

export const REPLAY_SPEEDS = [1, 4, 10] as const;

function IconButton({ label, onClick, disabled, children }: { label: React.ReactNode; onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <Tip content={label}>
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-label={typeof label === "string" ? label : undefined}
        className="flex size-8 shrink-0 items-center justify-center rounded-md border border-line bg-surface text-ink-2 transition-colors hover:border-line-strong hover:text-ink disabled:pointer-events-none disabled:opacity-40 [&_svg]:size-4"
      >
        {children}
      </button>
    </Tip>
  );
}

export function RunProgressPill({ run }: { run: Run }) {
  return (
    <span className="flex h-8 items-center gap-2 rounded-md border border-line bg-surface px-2.5 text-xs text-ink-2" role="status">
      <span className="block h-1.5 w-20 overflow-hidden rounded-full bg-surface-3" aria-hidden>
        <span className="progress-stripes block h-full" style={{ width: `${Math.max(4, run.progress * 100)}%` }} />
      </span>
      {run.status === "queued" ? "Queued" : `Simulating ${Math.round(run.progress * 100)}%`}
    </span>
  );
}

function RangePicker() {
  const { range, full, measured, faults, setRange, zoomOut, zoomed } = useDashView();
  const isMeasured = sameRange(range, measured);
  const label = !zoomed ? "Whole run" : isMeasured ? "Measurement window" : fmtRange(range);
  const pick = (r: TimeRange | null) => setRange(r);
  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          className="flex h-8 shrink-0 items-center gap-2 rounded-md border border-line bg-surface px-2.5 text-[13px] text-ink transition-colors hover:border-line-strong data-[state=open]:border-line-strong"
          aria-label="Time range"
        >
          <Clock className="size-3.5 text-ink-3" />
          <span className="tabular">{label}</span>
          {zoomed && !isMeasured && <span className="text-xs text-ink-3">zoomed</span>}
          <ChevronDown className="size-3.5 text-ink-3" />
        </button>
      </MenuTrigger>
      <MenuContent align="start" className="w-72">
        <MenuLabel>Time range</MenuLabel>
        <MenuItem onSelect={() => pick(null)}>
          <Check className={cn(!zoomed ? "opacity-100" : "opacity-0")} />
          <span className="flex-1">Whole run</span>
          <span className="tabular text-xs text-ink-3">{fmtRange(full)}</span>
        </MenuItem>
        <MenuItem onSelect={() => pick(measured)}>
          <Check className={cn(isMeasured ? "opacity-100" : "opacity-0")} />
          <span className="flex-1">Measurement window</span>
          <span className="tabular text-xs text-ink-3">{fmtRange(measured)}</span>
        </MenuItem>
        {faults.length > 0 && (
          <>
            <MenuSeparator />
            <MenuLabel>Around a failure</MenuLabel>
            {faults.map(({ fault: f, label: faultName }) => {
              const margin = Math.max(5, f.durationS / 2);
              const r = clampRange({ from: f.startS - margin, to: f.startS + f.durationS + margin }, full, 1);
              return (
                <MenuItem key={f.id} onSelect={() => pick(r)}>
                  <Check className={cn(sameRange(range, r) ? "opacity-100" : "opacity-0")} />
                  <span className="min-w-0 flex-1 truncate">{faultName}</span>
                  <span className="tabular text-xs text-ink-3">{fmtRange(r)}</span>
                </MenuItem>
              );
            })}
          </>
        )}
        <MenuSeparator />
        <MenuItem onSelect={zoomOut} disabled={!zoomed}>
          <ZoomOut /> Zoom out
        </MenuItem>
        <p className="px-2 pb-1 pt-0.5 text-[11px] leading-snug text-ink-3">Drag across any chart to zoom every panel. Double-click a chart to zoom out.</p>
      </MenuContent>
    </Menu>
  );
}

function ReplayControl({ endS }: { endS: number }) {
  const playhead = useUi((s) => s.playhead);
  const playing = useUi((s) => s.playing);
  const speed = useUi((s) => s.speed);
  const setSpeed = useUi((s) => s.setSpeed);
  return (
    <div className="flex items-center gap-2">
      {playing ? (
        <span className="flex h-6 items-center gap-1.5 rounded-sm border border-accent/40 bg-accent-soft px-1.5 text-[11px] font-bold tracking-[0.08em] text-ink" role="status" aria-label="Replaying live">
          <span className="live-dot !size-1.5 text-accent" aria-hidden />
          LIVE
        </span>
      ) : null}
      {playhead != null && (
        <span className="tabular whitespace-nowrap text-xs text-ink-2" aria-live="off">
          <span className="font-semibold text-ink">{playhead.toFixed(1)} s</span>
          <span className="text-ink-3"> / {fmtClock(endS)}</span>
        </span>
      )}
      <div className="flex h-8 items-center gap-0.5 rounded-md border border-line bg-surface p-0.5">
        <Tip content={playing ? "Pause the replay" : "Replay the run: panels draw up to the playhead"}>
          <button
            type="button"
            onClick={() => togglePlay(endS)}
            className={cn(
              "flex h-[26px] items-center gap-1.5 rounded-[5px] px-2.5 text-[13px] font-semibold transition-[filter]",
              playing ? "bg-surface-2 text-ink hover:bg-surface-3" : "accent-fill hover:brightness-[1.08]",
            )}
            aria-label={playing ? "Pause replay" : "Replay"}
          >
            {playing ? <Pause className="size-3.5 fill-current" /> : <Play className="size-3.5 fill-current" />}
            {playing ? "Pause" : playhead != null && playhead < endS - 0.01 ? "Resume" : "Replay"}
          </button>
        </Tip>
        <div role="radiogroup" aria-label="Replay speed" className="flex items-center">
          {REPLAY_SPEEDS.map((v) => (
            <button
              key={v}
              type="button"
              role="radio"
              aria-checked={speed === v}
              onClick={() => setSpeed(v)}
              className={cn(
                "tabular h-[26px] min-w-8 rounded-[5px] px-1.5 text-xs transition-colors",
                speed === v ? "bg-surface-3 font-semibold text-ink" : "text-ink-3 hover:text-ink",
              )}
            >
              {v}×
            </button>
          ))}
        </div>
        {playhead != null && (
          <Tip content="Stop and show the whole run">
            <button type="button" onClick={showSummary} className="flex size-[26px] items-center justify-center rounded-[5px] text-ink-2 hover:bg-surface-2 hover:text-ink" aria-label="Stop replay">
              <Square className="size-3 fill-current" />
            </button>
          </Tip>
        )}
      </div>
    </div>
  );
}

export function DashboardHeader({ runId, endS, running }: { runId: string; endS: number; running: Run | null }) {
  const { zoomed, setRange, zoomOut } = useDashView();
  const pickRun = (id: string) => {
    useDecide.getState().setScorecardRunId(id);
    void useRuns.getState().showRun(id);
  };
  return (
    <div className="flex h-12 shrink-0 items-center gap-2 border-b border-line bg-surface px-3">
      <LayoutDashboard className="size-4 text-accent" aria-hidden />
      <h2 className="mr-1 text-[14px] font-semibold tracking-[-0.01em] text-ink">Dashboard</h2>
      <span className="h-5 w-px bg-line" aria-hidden />
      <RunPicker value={runId} onChange={pickRun} />
      <RangePicker />
      <IconButton label="Zoom out" onClick={zoomOut} disabled={!zoomed}>
        <ZoomOut />
      </IconButton>
      <IconButton label="Reset to the whole run" onClick={() => setRange(null)} disabled={!zoomed}>
        <RotateCcw />
      </IconButton>
      <div className="ml-auto flex shrink-0 items-center gap-2">
        {running && <RunProgressPill run={running} />}
        <ReplayControl endS={endS} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Variables bar
// ---------------------------------------------------------------------------------------------

function ComponentFilter({ nodes, colors }: { nodes: NodeReport[]; colors: Map<string, string | null> }) {
  const hidden = useDashboard((s) => s.hidden);
  const toggle = useDashboard((s) => s.toggleNode);
  const setHidden = useDashboard((s) => s.setHidden);
  const hiddenHere = nodes.filter((n) => hidden.includes(n.id));
  const shown = nodes.length - hiddenHere.length;
  const value = hiddenHere.length === 0 ? "All" : shown === 1 ? (nodes.find((n) => !hidden.includes(n.id))?.label ?? "1") : `${shown} of ${nodes.length}`;
  return (
    <Menu>
      <MenuTrigger asChild>
        <button type="button" className="flex h-7 items-stretch overflow-hidden rounded-md border border-line text-xs transition-colors hover:border-line-strong data-[state=open]:border-line-strong" aria-label="Filter components">
          <span className="flex items-center bg-surface-2 px-2 font-medium text-ink-2">Component</span>
          <span className="flex items-center gap-1.5 bg-surface px-2 text-ink">
            <span className="max-w-40 truncate">{value}</span>
            <ChevronDown className="size-3.5 text-ink-3" />
          </span>
        </button>
      </MenuTrigger>
      <MenuContent align="start" className="max-h-[60vh] w-72 overflow-y-auto">
        <MenuItem
          onSelect={(e) => {
            e.preventDefault();
            setHidden(hiddenHere.length ? [] : nodes.map((n) => n.id));
          }}
        >
          <Check className={cn(hiddenHere.length === 0 ? "opacity-100" : "opacity-0")} />
          All components
        </MenuItem>
        <MenuSeparator />
        {nodes.map((n) => {
          const on = !hidden.includes(n.id);
          return (
            <MenuItem
              key={n.id}
              onSelect={(e) => {
                e.preventDefault();
                toggle(n.id);
              }}
            >
              <Check className={cn(on ? "opacity-100" : "opacity-0")} />
              <span className="h-[3px] w-3 shrink-0 rounded-full" style={{ background: colors.get(n.id) ?? OTHER_COLOR }} aria-hidden />
              <span className="min-w-0 flex-1 truncate">{n.label || n.id}</span>
              <span className="shrink-0 text-xs text-ink-3">{KIND_LABEL[kindOf(n.type, n.category)]}</span>
            </MenuItem>
          );
        })}
      </MenuContent>
    </Menu>
  );
}

function AnnotationKey() {
  return (
    <div className="hidden items-center gap-3 text-[11px] text-ink-3 lg:flex" aria-label="Annotations">
      <span className="flex items-center gap-1.5">
        <span className="h-2.5 w-4 rounded-[2px] border border-line" style={{ background: "var(--warmup-wash)" }} aria-hidden />
        Warm-up and drain
      </span>
      <span className="flex items-center gap-1.5">
        <span
          className="h-2.5 w-4 rounded-[2px] border-t-2"
          style={{
            borderColor: "var(--status-critical)",
            background: "repeating-linear-gradient(135deg, color-mix(in srgb, var(--status-critical) 22%, transparent) 0 2px, transparent 2px 5px)",
          }}
          aria-hidden
        />
        Failure window
      </span>
      <span className="flex items-center gap-1.5">
        <svg width="9" height="8" viewBox="0 0 9 8" aria-hidden>
          <path d="M0.5 7.5h8L4.5 1z" fill="var(--status-warning)" />
        </svg>
        Event
      </span>
      <span className="flex items-center gap-1.5">
        <span className="h-3 w-[2px] rounded-full bg-accent" aria-hidden />
        Playhead
      </span>
    </div>
  );
}

export function VariablesBar({
  nodes,
  colors,
  rowIds,
}: {
  nodes: NodeReport[];
  colors: Map<string, string | null>;
  rowIds: string[];
}) {
  const groupByKind = useDashboard((s) => s.groupByKind);
  const setGroupByKind = useDashboard((s) => s.setGroupByKind);
  const setMany = useDashboard((s) => s.setManyCollapsed);
  return (
    <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line bg-page px-3">
      <ComponentFilter nodes={nodes} colors={colors} />
      <label className="flex cursor-pointer items-center gap-2 text-xs text-ink-2">
        <Switch checked={groupByKind} onCheckedChange={setGroupByKind} label="Group components by kind" />
        Group by kind
      </label>
      <span className="h-4 w-px bg-line" aria-hidden />
      <button type="button" onClick={() => setMany(rowIds, false)} className="flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-ink-3 hover:bg-surface-2 hover:text-ink">
        <ChevronsUpDown className="size-3.5" /> Expand all
      </button>
      <button type="button" onClick={() => setMany(rowIds, true)} className="flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-ink-3 hover:bg-surface-2 hover:text-ink">
        <ChevronsDownUp className="size-3.5" /> Collapse all
      </button>
      <div className="ml-auto">
        <AnnotationKey />
      </div>
    </div>
  );
}

