"use client";

/**
 * One request's journey, step by step: every hop, wait and piece of work as a bar on a shared
 * time axis (like a distributed-tracing waterfall). Rows are indented by call depth.
 */
import { useState } from "react";
import { fmtMs } from "@/lib/format";
import type { TraceRecord, TraceSpan } from "@/lib/types";
import { cn } from "@/lib/utils";

type Group = "work" | "wait" | "durability" | "pause" | "network" | "container" | "marker" | "failure";

const KINDS: Record<string, { group: Group; label: string }> = {
  network: { group: "network", label: "Network" },
  handle: { group: "container", label: "Time in component" },
  scatter: { group: "container", label: "Fan-out to every shard" },
  service: { group: "work", label: "Processing" },
  execute: { group: "work", label: "Query" },
  consume: { group: "work", label: "Consumer work" },
  publish: { group: "work", label: "Publish" },
  external: { group: "work", label: "Third-party call" },
  queue: { group: "wait", label: "Waiting for a worker or connection" },
  tickets: { group: "wait", label: "Waiting for a ticket" },
  lock: { group: "wait", label: "Waiting for a lock" },
  blocked: { group: "wait", label: "Publisher blocked" },
  queued: { group: "wait", label: "Waiting in the backlog" },
  backoff: { group: "wait", label: "Retry backoff" },
  commit: { group: "durability", label: "Log flush" },
  replication: { group: "durability", label: "Waiting for replicas" },
  gc: { group: "pause", label: "GC pause" },
  cold_start: { group: "pause", label: "Cold start" },
  fork: { group: "pause", label: "Snapshot fork" },
  hit: { group: "marker", label: "Cache hit" },
  miss: { group: "marker", label: "Cache miss" },
  populate: { group: "marker", label: "Written back to the cache" },
  stale: { group: "failure", label: "Stale read" },
  rejected: { group: "failure", label: "Rejected" },
  timeout: { group: "failure", label: "Gave up (timeout)" },
};

export const GROUPS: { key: Group; label: string; color: string }[] = [
  { key: "work", label: "Work", color: "var(--series-1)" },
  { key: "wait", label: "Waiting", color: "var(--series-4)" },
  { key: "durability", label: "Durability", color: "var(--series-7)" },
  { key: "pause", label: "Runtime pause", color: "var(--series-5)" },
  { key: "network", label: "Network", color: "var(--ink-3)" },
  { key: "marker", label: "Cache", color: "var(--series-3)" },
  { key: "failure", label: "Failure", color: "var(--status-critical)" },
];

const color = (g: Group) => GROUPS.find((x) => x.key === g)?.color ?? "var(--ink-3)";

export function kindInfo(kind: string): { group: Group; label: string } {
  return KINDS[kind] ?? { group: "work", label: kind.replace(/_/g, " ") };
}

function niceStep(span: number): number {
  const raw = span / 5;
  const pow = 10 ** Math.floor(Math.log10(raw || 1));
  const m = raw / pow;
  return (m >= 5 ? 5 : m >= 2 ? 2 : 1) * pow;
}

export function TraceWaterfall({ trace, edgeLabel }: { trace: TraceRecord; edgeLabel: (edgeId: string) => string }) {
  const [hover, setHover] = useState<TraceSpan | null>(null);
  const end = Math.max(trace.durationMs, ...trace.spans.map((s) => s.endMs), 0.001);
  const x = (ms: number) => `${(Math.max(0, ms) / end) * 100}%`;
  const step = niceStep(end);
  const ticks: number[] = [];
  for (let t = 0; t <= end + 1e-9; t += step) ticks.push(t);
  const label = (s: TraceSpan) => {
    if (s.kind === "network" && s.edgeId) return edgeLabel(s.edgeId);
    const what = kindInfo(s.kind).label;
    return s.kind === "handle" ? (s.label ?? "?") : `${s.label ?? "?"} · ${what.toLowerCase()}`;
  };
  return (
    <div className="text-xs">
      <div className="grid grid-cols-[minmax(160px,240px)_minmax(0,1fr)] border-b border-line pb-1 text-ink-3">
        <span>Step</span>
        <div className="relative h-4">
          {ticks.map((t) => (
            <span key={t} className="tabular absolute -translate-x-1/2" style={{ left: x(t) }}>
              {fmtMs(t)}
            </span>
          ))}
        </div>
      </div>
      <div className="relative">
        <div className="pointer-events-none absolute inset-0 grid grid-cols-[minmax(160px,240px)_minmax(0,1fr)]" aria-hidden>
          <span />
          <div className="relative">
            {ticks.map((t) => (
              <span key={t} className="absolute inset-y-0 w-px bg-line" style={{ left: x(t) }} />
            ))}
          </div>
        </div>
        <Row label={`Request ${trace.id} (${trace.op})`} depth={0} bold>
          <span className="absolute inset-y-1 rounded-sm bg-ink/15" style={{ left: 0, width: x(trace.durationMs) }} />
        </Row>
        {trace.spans.map((s, i) => {
          const info = kindInfo(s.kind);
          const zero = s.endMs - s.startMs < end * 0.002;
          return (
            <Row key={i} label={label(s)} depth={s.depth + 1} onHover={(on) => setHover(on ? s : null)} active={hover === s}>
              {zero ? (
                <span
                  className="absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[2px]"
                  style={{ left: x(s.startMs), background: color(info.group) }}
                />
              ) : info.group === "container" ? (
                <span
                  className="absolute inset-y-[5px] rounded-sm border border-line-strong bg-surface-3/70"
                  style={{ left: x(s.startMs), width: x(s.endMs - s.startMs) }}
                />
              ) : (
                <span
                  className={cn("absolute rounded-sm", info.group === "network" ? "inset-y-[8px]" : "inset-y-[4px]")}
                  style={{ left: x(s.startMs), width: `max(2px, ${x(s.endMs - s.startMs)})`, background: color(info.group) }}
                />
              )}
            </Row>
          );
        })}
      </div>
      <div className="mt-2 flex min-h-9 items-center gap-3 rounded-md bg-surface-2 px-3 py-2 text-[12px] text-ink-2" aria-live="polite">
        {hover ? (
          <>
            <span className="size-2.5 shrink-0 rounded-sm" style={{ background: color(kindInfo(hover.kind).group) }} aria-hidden />
            <span className="font-medium text-ink">{label(hover)}</span>
            <span className="tabular">
              at {fmtMs(hover.startMs)} for {fmtMs(hover.endMs - hover.startMs)}
            </span>
            {hover.detail && <span className="truncate text-ink-3">{hover.detail}</span>}
          </>
        ) : (
          <span className="text-ink-3">Hover a step to see what it was and how long it took.</span>
        )}
      </div>
    </div>
  );
}

function Row({
  label,
  depth,
  children,
  bold,
  onHover,
  active,
}: {
  label: string;
  depth: number;
  children: React.ReactNode;
  bold?: boolean;
  onHover?: (on: boolean) => void;
  active?: boolean;
}) {
  return (
    <div
      className={cn("grid h-[22px] grid-cols-[minmax(160px,240px)_minmax(0,1fr)] items-center rounded-sm", active && "bg-surface-2")}
      onMouseEnter={onHover ? () => onHover(true) : undefined}
      onMouseLeave={onHover ? () => onHover(false) : undefined}
    >
      <span className={cn("truncate pr-2", bold ? "font-semibold text-ink" : "text-ink-2")} style={{ paddingLeft: Math.min(depth, 8) * 10 }} title={label}>
        {label}
      </span>
      <div className="relative h-full">{children}</div>
    </div>
  );
}
