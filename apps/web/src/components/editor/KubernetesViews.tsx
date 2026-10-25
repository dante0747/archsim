"use client";

/**
 * Kubernetes on the canvas: pod squares on a service card and a node view on a cluster card.
 * Both show the design (where the starting pods land) and, during replay, the recorded state.
 */
import { cn } from "@/lib/utils";

const MAX_PODS = 24;
const MAX_NODES = 8;
const MAX_PODS_PER_NODE = 12;

function Pod({ state }: { state: "ready" | "starting" | "pending" }) {
  return (
    <span
      className={cn("size-[7px] shrink-0 rounded-[1.5px]", state === "starting" && "pulse-soft")}
      style={
        state === "ready"
          ? { background: "var(--fam-compute)" }
          : state === "starting"
            ? { background: "color-mix(in srgb, var(--fam-compute) 35%, transparent)", outline: "1px solid var(--fam-compute)", outlineOffset: -1 }
            : { outline: "1px dashed var(--status-warning)", outlineOffset: -1 }
      }
      aria-hidden
    />
  );
}

/** Pods of a service on Kubernetes: ready (filled), starting (pulsing), Pending (dashed). */
export function PodStrip({ ready, starting, pending }: { ready: number; starting: number; pending: number }) {
  const r = Math.max(0, Math.round(ready));
  const s = Math.max(0, Math.round(starting));
  const p = Math.max(0, Math.round(pending));
  const shown: ("ready" | "starting" | "pending")[] = [];
  for (const [state, n] of [
    ["ready", r],
    ["starting", s],
    ["pending", p],
  ] as const) {
    for (let i = 0; i < n && shown.length < MAX_PODS; i++) shown.push(state);
  }
  const hidden = r + s + p - shown.length;
  const label = [`${r} ready`, s ? `${s} starting` : "", p ? `${p} Pending` : ""].filter(Boolean).join(", ");
  return (
    <div className="flex items-center gap-2 text-xs leading-[18px]" aria-label={`Pods: ${label}`} title={`Pods: ${label}`}>
      <span className="shrink-0 text-ink-3">Pods</span>
      <span className="flex min-w-0 flex-1 flex-wrap items-center gap-[3px]">
        {shown.map((st, i) => (
          <Pod key={i} state={st} />
        ))}
        {hidden > 0 && <span className="tabular text-[11px] text-ink-3">+{hidden}</span>}
      </span>
      {p > 0 ? (
        <span className="tabular shrink-0 font-medium" style={{ color: "var(--status-warning)" }}>
          {p} Pending
        </span>
      ) : (
        <span className="tabular shrink-0 font-medium text-ink">{r + s}</span>
      )}
    </div>
  );
}

/** Nodes of a cluster with the pods on each, nodes being added (dashed) and Pending pods. */
export function ClusterNodes({
  podsPerNode,
  provisioning,
  pending,
  cpuRatio,
  estimated,
}: {
  podsPerNode: number[];
  provisioning: number;
  pending: number;
  /** Requested CPU / allocatable CPU. */
  cpuRatio: number | null;
  /** True when pods are spread evenly for display (replay), not placed one by one. */
  estimated: boolean;
}) {
  const nodes = podsPerNode.length;
  const prov = Math.max(0, Math.round(provisioning));
  const shownNodes = podsPerNode.slice(0, MAX_NODES);
  const shownProv = Math.max(0, Math.min(prov, MAX_NODES - shownNodes.length));
  const hiddenNodes = nodes + prov - shownNodes.length - shownProv;
  const pend = Math.max(0, Math.round(pending));
  const pods = podsPerNode.reduce((a, b) => a + b, 0);
  return (
    <div className="space-y-1.5 pt-1" aria-label={`${nodes} nodes, ${pods} pods${pend ? `, ${pend} Pending` : ""}${prov ? `, ${prov} node(s) being added` : ""}`}>
      <div
        className="grid grid-cols-4 gap-1"
        title={estimated ? "Pods are shown spread evenly over the nodes, as the least-allocated scheduler keeps them" : "Where the starting pods land"}
      >
        {shownNodes.map((n, i) => (
          <div key={`n${i}`} className="flex h-[30px] flex-wrap content-start gap-[2px] rounded-[4px] border border-line bg-surface-2 p-[3px]">
            {Array.from({ length: Math.min(n, MAX_PODS_PER_NODE) }, (_, j) => (
              <span key={j} className="size-[5px] rounded-[1px]" style={{ background: "var(--fam-compute)" }} />
            ))}
            {n > MAX_PODS_PER_NODE && <span className="text-[9px] leading-[5px] text-ink-3">+</span>}
          </div>
        ))}
        {Array.from({ length: shownProv }, (_, i) => (
          <div
            key={`p${i}`}
            className="pulse-soft flex h-[30px] items-center justify-center rounded-[4px] border border-dashed border-line-strong text-[10px] text-ink-3"
          >
            adding
          </div>
        ))}
        {hiddenNodes > 0 && (
          <div className="flex h-[30px] items-center justify-center rounded-[4px] text-[11px] text-ink-3">+{hiddenNodes}</div>
        )}
      </div>
      <div className="flex items-center justify-between gap-2 text-xs leading-[18px]">
        <span className="text-ink-3">
          {pods} pods{cpuRatio != null && <> · {Math.round(cpuRatio * 100)}% CPU requested</>}
        </span>
        {pend > 0 && (
          <span className="tabular shrink-0 font-medium" style={{ color: "var(--status-warning)" }}>
            {pend} Pending
          </span>
        )}
      </div>
    </div>
  );
}
