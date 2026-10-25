"use client";

import { Handle, type NodeProps, NodeToolbar, Position } from "@xyflow/react";
import { Copy, Cpu, HardDrive, MemoryStick, Trash2 } from "lucide-react";
import { memo, useMemo } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { ClusterNodes, PodStrip } from "@/components/editor/KubernetesViews";
import { TechIcon } from "@/components/TechIcon";
import { useIsDark } from "@/components/ui/theme-toggle";
import { type ArchNode, SIDES, type Side, sideHandle } from "@/lib/document";
import { isDistribution, shortDistribution } from "@/lib/distributions";
import { compact, compactRate, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { clientFraction, clientShares } from "@/lib/clients";
import { faultsAt, fmtRate, heatColor, runFaults, statusAt } from "@/lib/recorder";
import { NODE_STATUS } from "@/lib/status";
import { CLUSTER_TYPE, type ClusterPlan, clusterIdsOf, clusterOf, isOnKubernetes, membersOf, planCluster, spreadPods, startingPods } from "@/lib/kubernetes";
import { kindOf, technologyLabel } from "@/lib/theme";
import type { Fault, NodeReport, NodeStatus, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export const NODE_W = 212;

const SIDE_POSITION: Record<Side, Position> = { top: Position.Top, right: Position.Right, bottom: Position.Bottom, left: Position.Left };

/** State of one component in one bucket of the recording (flight-recorder replay). */
interface LiveState {
  t: number;
  util: number | null;
  arrival: number | null;
  rejected: number | null;
  queue: number | null;
  replicas: number | null;
  lag: number | null;
  hit: number | null;
  status: NodeStatus;
  faults: Fault[];
}

function liveStateAt(report: Report, node: NodeReport, bucket: number): LiveState {
  const ts = node.timeseries ?? {};
  const at = (k: string) => ts[k]?.[bucket] ?? null;
  const b = report.timeseries.bucketS || 1;
  const util = at("utilization");
  const arrival = at("arrivalRps");
  const rejected = at("rejectedRps");
  const t = bucket * b;
  return {
    t,
    util,
    arrival,
    rejected,
    queue: at("queueMean"),
    replicas: at("replicas"),
    lag: at("lag"),
    hit: at("hitRatio"),
    status: statusAt(util, arrival, rejected),
    faults: faultsAt(runFaults(report), t + b / 2).filter((f) => f.target === node.id),
  };
}

/** A row of small blocks above the node: requests waiting, on a log scale. */
function QueueBlocks({ queue, left }: { queue: number; left: number }) {
  const blocks = Math.min(16, Math.max(1, Math.ceil(Math.log2(queue + 1) * 2.5)));
  return (
    <div
      className="pointer-events-none absolute -top-4 flex items-end gap-[2px]"
      style={{ left }}
      aria-label={`${Math.round(queue)} requests waiting`}
    >
      {Array.from({ length: blocks }, (_, i) => (
        <span
          key={i}
          className="size-[6px] rounded-[1px] bg-ink-2"
          style={{ opacity: 0.35 + (0.65 * (i + 1)) / blocks }}
        />
      ))}
      <span className="ml-1 font-mono text-[11px] leading-none text-ink-3">{queue >= 10 ? Math.round(queue) : queue.toFixed(1)} queued</span>
    </div>
  );
}

function FaultTag({ fault }: { fault: Fault }) {
  if (fault.kind === "outage") {
    return (
      <span className="pulse-soft shrink-0 rounded-full bg-critical-soft px-2 text-[11px] font-semibold leading-5 text-danger-ink">Outage</span>
    );
  }
  const f = fault.factor ?? 4;
  return (
    <span className="shrink-0 rounded-full bg-serious-soft px-2 text-[11px] font-semibold leading-5 text-ink">Slow ×{Number.isInteger(f) ? f : f.toFixed(1)}</span>
  );
}

function ArchNodeViewImpl({ id, data, selected }: NodeProps<ArchNode>) {
  const comp = useEditor((s) => componentOf(s.catalog, data.type));
  const errorCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "error").length);
  const warningCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "warning").length);
  const rps = useEditor((s) => s.workload.rps);
  const patternKind = useEditor((s) => s.workload.pattern.kind);
  // With several client parts each sends its share of the workload.
  const clientCount = useEditor((s) => (comp?.isTrafficSource ? clientShares(s.nodes, s.catalog).length : 0));
  const fraction = useEditor((s) => (comp?.isTrafficSource ? clientFraction(s.nodes, s.catalog, id) : null));
  const duplicate = useEditor((s) => s.duplicateSelection);
  const remove = useEditor((s) => s.deleteElements);
  const overlay = useRuns((s) => s.overlay);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const nodeReport = useMemo(() => report?.nodes.find((n) => n.id === id) ?? null, [report, id]);
  const bucketS = report?.timeseries.bucketS || 1;
  const nBuckets = report?.timeseries.t.length ?? 0;
  // Quantized to the bucket so nodes re-render once per simulated bucket, not on every frame.
  const bucket = useUi((s) => (s.playhead == null ? -1 : Math.floor(s.playhead / bucketS + 1e-9)));
  const dark = useIsDark();
  // Kubernetes: where the starting pods land (the cluster's plan), as a string so the card only
  // re-renders when the plan changes, not on every edit elsewhere.
  const isCluster = data.type === CLUSTER_TYPE;
  const onK8s = isOnKubernetes(data.type, data.config);
  const planJson = useEditor((s) => {
    if (!isCluster && !onK8s) return "";
    const clusterId = isCluster ? id : clusterOf({ id, data }, clusterIdsOf(s.nodes));
    const cluster = clusterId ? s.nodes.find((n) => n.id === clusterId) : null;
    return cluster ? JSON.stringify(planCluster(cluster.data.config, membersOf(s.nodes, cluster.id))) : "";
  });
  const plan = useMemo(() => (planJson ? (JSON.parse(planJson) as ClusterPlan) : null), [planJson]);

  const liveOn = overlay && !!report && !!nodeReport && bucket >= 0 && bucket < nBuckets;
  const live = useMemo(
    () => (liveOn && report && nodeReport ? liveStateAt(report, nodeReport, bucket) : null),
    [liveOn, report, nodeReport, bucket],
  );

  const kind = kindOf(data.type, comp?.category);
  const configReplicas = typeof data.config.replicas === "number" ? data.config.replicas : 1;
  const replicas = live?.replicas != null ? Math.max(1, Math.round(live.replicas)) : configReplicas;
  const headline = (comp?.fields ?? []).filter((f) => f.headline && isFieldVisible(f, data.config)).slice(0, 2);
  const tsAt = (k: string): number | null => (live && nodeReport ? (nodeReport.timeseries?.[k]?.[bucket] ?? null) : null);
  let status: NodeStatus | null = live ? live.status : overlay && nodeReport ? nodeReport.status : null;
  let clusterView: React.ReactNode = null;
  let podView: React.ReactNode = null;
  if (isCluster) {
    const liveNodes = tsAt("nodes");
    if (live && liveNodes != null) {
      const n = Math.round(liveNodes);
      const pending = tsAt("podsPending") ?? 0;
      const cpuRatio = tsAt("cpuRequestedRatio");
      status = pending >= 0.5 ? "hot" : (cpuRatio ?? 0) >= 0.85 ? "warm" : "ok";
      clusterView = (
        <ClusterNodes
          podsPerNode={spreadPods(Math.round(tsAt("podsRunning") ?? 0), n)}
          provisioning={tsAt("nodesProvisioning") ?? 0}
          pending={pending}
          cpuRatio={cpuRatio}
          estimated
        />
      );
    } else if (plan) {
      clusterView = (
        <ClusterNodes
          podsPerNode={plan.podsPerNode}
          provisioning={0}
          pending={plan.pending}
          cpuRatio={plan.cpuAllocatable > 0 ? plan.cpuRequested / plan.cpuAllocatable : null}
          estimated={false}
        />
      );
    }
  } else if (onK8s) {
    const ready = tsAt("podsReady");
    if (live && ready != null) {
      podView = <PodStrip ready={ready} starting={tsAt("podsStarting") ?? 0} pending={tsAt("podsPending") ?? 0} />;
    } else {
      const pending = plan?.pendingBy[id] ?? 0;
      podView = <PodStrip ready={Math.max(0, startingPods(data.config) - pending)} starting={0} pending={pending} />;
    }
  }
  const meta = status ? NODE_STATUS[status] : null;
  const s = nodeReport?.summary;
  const util = live ? live.util : (s?.utilization ?? null);
  const alarm = status === "saturated" || status === "hot";
  const attention = alarm || status === "warm";
  const outage = live?.faults.find((f) => f.kind === "outage") ?? null;
  const slowdown = live?.faults.find((f) => f.kind === "slowdown") ?? null;
  const rejecting = !!live && (live.rejected ?? 0) > 0;
  const broken = !!outage || errorCount > 0;

  // Flat card: a hairline border that turns blue when selected and status-coloured when it
  // needs attention; no shadows or glows.
  const border = selected
    ? "var(--select)"
    : broken
      ? "var(--status-critical)"
      : alarm && meta
        ? meta.color
        : "var(--node-stroke)";
  const ring = selected
    ? "0 0 0 3px color-mix(in srgb, var(--select) 24%, transparent)"
    : status === "saturated" && meta
      ? `0 0 0 3px color-mix(in srgb, ${meta.color} 18%, transparent)`
      : undefined;
  const typeName = comp?.displayName.split(" / ")[0].replace(/ \(.*\)$/, "") ?? data.type;
  const technology = typeof data.config.technology === "string" ? data.config.technology : null;
  const tech = technologyLabel(comp, technology);
  const spec = (k: string) => (typeof data.config[k] === "number" ? (data.config[k] as number) : null);
  const [cpu, mem, disk] = [spec("cpuCores"), spec("memoryGib"), spec("storageGib")];
  const level = util != null ? Math.min(1, Math.max(0, util)) : null;
  const barColor = attention && meta ? meta.color : heatColor(level, dark);
  const facts: { k: string; v: string }[] = [];
  if (comp?.isTrafficSource) {
    facts.push({
      k: "Load",
      v:
        clientCount > 1 && fraction != null
          ? `${Math.round(fraction * 100)}%${patternKind === "constant" ? ` · ${compact(rps * fraction)} req/s` : ""}`
          : patternKind === "constant"
            ? `${compact(rps)} req/s`
            : patternKind,
    });
  }
  for (const f of headline) {
    const v = data.config[f.key] ?? f.default;
    facts.push({ k: f.label, v: isDistribution(v) ? shortDistribution(v, f.unit ?? "ms") : describeValue(f, v) });
  }

  return (
    <div className="group relative" style={{ width: NODE_W }} data-testid={`node-${id}`}>
      <NodeToolbar isVisible={selected} position={Position.Top} offset={10}>
        <div className="flex items-center gap-0.5 rounded-lg border border-line-strong bg-surface p-0.5 shadow-float">
          <button
            type="button"
            onClick={duplicate}
            className="flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-ink-2 hover:bg-surface-2 hover:text-ink"
            title="Duplicate (Ctrl/⌘ D)"
          >
            <Copy className="size-3.5" /> Duplicate
          </button>
          <button
            type="button"
            onClick={() => remove([id], [])}
            className="flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-danger-ink hover:bg-critical-soft"
            title="Delete (Del)"
          >
            <Trash2 className="size-3.5" /> Delete
          </button>
        </div>
      </NodeToolbar>

      {live && live.queue != null && live.queue >= 0.5 && !selected && <QueueBlocks queue={live.queue} left={12} />}

      {/* Attach points on all four sides: drag from any of them to another part (either way). */}
      {comp?.ports.length !== 0 && SIDES.map((side) => (
        <Handle key={side} id={sideHandle(side)} type="source" position={SIDE_POSITION[side]} className="arch-handle" title="Drag to another part to connect" />
      ))}

      <div
        className="relative overflow-hidden rounded-xl border transition-[border-color,box-shadow] duration-150 group-hover:border-line-strong"
        style={{
          background: broken ? "color-mix(in srgb, var(--status-critical) 6%, var(--node-fill))" : "var(--node-fill)",
          borderColor: border,
          borderStyle: broken && !selected ? "dashed" : "solid",
          boxShadow: ring,
        }}
      >
        <div className="flex items-center gap-3 px-3 pt-3">
          <TechIcon technology={technology} kind={kind} size={20} tile className="rounded-lg" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold leading-5 text-ink">{data.label}</p>
            <p className="truncate text-xs leading-4 text-ink-3">{tech && tech !== "Generic" ? tech : typeName}</p>
          </div>
          {outage ? (
            <FaultTag fault={outage} />
          ) : slowdown ? (
            <FaultTag fault={slowdown} />
          ) : errorCount > 0 ? (
            <span className="rounded-full bg-critical-soft px-1.5 text-[11px] font-semibold leading-5 text-danger-ink" title={`${errorCount} error(s)`}>
              {errorCount} {errorCount === 1 ? "error" : "errors"}
            </span>
          ) : warningCount > 0 ? (
            <span className="rounded-full bg-warning-soft px-1.5 text-[11px] font-semibold leading-5 text-ink" title={`${warningCount} warning(s)`}>
              {warningCount}!
            </span>
          ) : replicas > 1 && !onK8s ? (
            <span className="tabular rounded-full bg-surface-2 px-1.5 text-[11px] font-semibold leading-5 text-ink-2" title={`${replicas} replicas`}>
              ×{replicas}
            </span>
          ) : null}
        </div>

        <div className="space-y-1 px-3 pb-3 pt-2.5">
          {facts.map((f) => (
            <div key={f.k} className="flex items-baseline justify-between gap-3 text-xs leading-[18px]">
              <span className="truncate text-ink-3">{f.k}</span>
              <span className="tabular shrink-0 font-medium text-ink">{f.v}</span>
            </div>
          ))}
          {podView}
          {clusterView}
          {(cpu != null || mem != null || disk != null) && (
            <p className="flex flex-wrap items-center gap-x-2.5 gap-y-0.5 pt-0.5 text-xs text-ink-2" aria-label="Resources">
              {cpu != null && (
                <span className="inline-flex items-center gap-1">
                  <Cpu className="size-3.5 text-ink-3" aria-hidden />
                  {cpu} vCPU
                </span>
              )}
              {mem != null && (
                <span className="inline-flex items-center gap-1">
                  <MemoryStick className="size-3.5 text-ink-3" aria-hidden />
                  {mem} GiB
                </span>
              )}
              {disk != null && (
                <span className="inline-flex items-center gap-1">
                  <HardDrive className="size-3.5 text-ink-3" aria-hidden />
                  {disk >= 1000 ? `${+(disk / 1000).toFixed(1)} TB` : `${disk} GiB`}
                </span>
              )}
            </p>
          )}
          {meta && (live || s) && (status !== "ok" || rejecting) && !isCluster && (
            <div className="flex items-center gap-1.5 pt-1 text-xs" aria-label={live ? `State at ${live.t.toFixed(0)} s` : "Simulation result for this component"}>
              <span className={cn("size-2 rounded-full", status === "saturated" && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
              <span className="font-medium text-ink">{meta.label}</span>
              {rejecting && (
                <span key={bucket} className="pulse-soft rounded-full bg-critical-soft px-1.5 text-[11px] font-semibold text-danger-ink">
                  {fmtRate(live!.rejected)}/s refused
                </span>
              )}
              <span className="tabular ml-auto text-ink-2">
                {util != null ? `${fmtPct(util, 0)} busy` : live ? `${fmtRate(live.arrival ?? 0)}/s` : `${compactRate(s?.arrivalRps ?? 0)}/s`}
              </span>
            </div>
          )}
        </div>

        {/* Utilisation: a thin bar along the bottom edge, coloured by status when it needs attention. */}
        {meta && level != null && (
          <div className="absolute inset-x-0 bottom-0 h-1 bg-surface-3" aria-hidden>
            <div className="h-full transition-[width] duration-500 ease-out" style={{ width: `${level * 100}%`, background: barColor }} />
          </div>
        )}
      </div>
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
