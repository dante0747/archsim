"use client";

import { Handle, type NodeProps, NodeToolbar, Position } from "@xyflow/react";
import { Copy, Cpu, HardDrive, MemoryStick, Trash2 } from "lucide-react";
import { memo, useMemo } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { ClusterNodes, PodStrip } from "@/components/editor/KubernetesViews";
import { TechIcon } from "@/components/TechIcon";
import { NodeCommentBadge } from "@/components/team/CommentBadge";
import { Meter } from "@/components/ui/meter";
import { type ArchNode, SIDES, type Side, sideHandle } from "@/lib/document";
import { isDistribution, shortDistribution } from "@/lib/distributions";
import { compact, compactRate, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { clientFraction, clientShares } from "@/lib/clients";
import { faultsAt, fmtRate, runFaults, statusAt } from "@/lib/recorder";
import { NODE_STATUS } from "@/lib/status";
import { CLUSTER_TYPE, type ClusterPlan, clusterIdsOf, clusterOf, isOnKubernetes, membersOf, planCluster, spreadPods, startingPods } from "@/lib/kubernetes";
import { kindOf, technologyLabel } from "@/lib/theme";
import type { Fault, NodeReport, NodeStatus, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { autoSizeKey, needKey } from "@/store/sizes";
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

/** Figma-style selection: four square handles on the card's corners. */
function SelectionHandles() {
  const h = "absolute size-[9px] rounded-[2px] border-[1.5px] border-[color:var(--select)] bg-white";
  return (
    <span className="pointer-events-none absolute inset-0 z-10" aria-hidden>
      <span className={cn(h, "-left-[5px] -top-[5px]")} />
      <span className={cn(h, "-right-[5px] -top-[5px]")} />
      <span className={cn(h, "-bottom-[5px] -left-[5px]")} />
      <span className={cn(h, "-bottom-[5px] -right-[5px]")} />
    </span>
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
  // Auto sizes fill the specs a part leaves empty (as a string so moving parts does not re-render).
  const autoKey = useEditor((s) => autoSizeKey(s.analysis, id));
  // what the part needs, beside the instance it gets (engine 0.13+)
  const needOf = useEditor((s) => needKey(s.analysis, id));

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
    ? "0 0 0 1px var(--select)"
    : status === "saturated" && meta
      ? `0 0 0 3px color-mix(in srgb, ${meta.color} 18%, transparent)`
      : "var(--node-shadow)";
  const typeName = comp?.displayName.split(" / ")[0].replace(/ \(.*\)$/, "") ?? data.type;
  const technology = typeof data.config.technology === "string" ? data.config.technology : null;
  const tech = technologyLabel(comp, technology);
  const spec = (k: string) => (typeof data.config[k] === "number" ? (data.config[k] as number) : null);
  const autos = autoKey.split("|").map((v) => (v === "" ? null : Number(v)));
  const [setCpu, setMem, setDisk] = [spec("cpuCores"), spec("memoryGib"), spec("storageGib")];
  const [cpu, mem, disk] = [setCpu ?? autos[0], setMem ?? autos[1], setDisk ?? autos[2]];
  const isAuto = [setCpu == null && autos[0] != null, setMem == null && autos[1] != null, setDisk == null && autos[2] != null];
  const [needCpu, needMem] = needOf.split("|").map((v) => (v === "" ? null : Number(v)));
  const needText = [
    needCpu != null && cpu != null ? `${needCpu < 10 ? +needCpu.toFixed(2) : Math.round(needCpu)} vCPU` : null,
    needMem != null && mem != null ? (needMem < 1 ? `${Math.round(needMem * 1024)} MiB` : `${+needMem.toFixed(1)} GiB`) : null,
  ].filter(Boolean);
  const level = util != null ? Math.min(1, Math.max(0, util)) : null;
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

      {selected && <SelectionHandles />}
      <NodeCommentBadge nodeId={id} />
      <div
        className="arch-card relative overflow-hidden rounded-xl border transition-[border-color,box-shadow] duration-150 group-hover:border-line-strong"
        style={{
          background: broken ? "color-mix(in srgb, var(--status-critical) 6%, var(--node-fill))" : "var(--node-fill)",
          borderColor: border,
          borderStyle: broken && !selected ? "dashed" : "solid",
          boxShadow: ring,
        }}
      >
        <div className="flex items-center gap-3 px-3 pt-3">
          <TechIcon technology={technology} kind={kind} size={18} tile />
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
            <div key={f.k} className="arch-card-fact flex items-baseline justify-between gap-3 text-xs leading-[18px]">
              <span className="truncate text-ink-3">{f.k}</span>
              <span className="font-readout shrink-0 text-ink">{f.v}</span>
            </div>
          ))}
          {podView}
          {clusterView}
          {(cpu != null || mem != null || disk != null) && (
            <p
              className="arch-card-fact flex flex-wrap items-center gap-x-2.5 gap-y-0.5 pt-0.5 text-xs text-ink-2"
              aria-label={isAuto.some(Boolean) ? "Resources (auto-sized for the peak load)" : "Resources"}
              title={isAuto.some(Boolean) ? "Auto-sized for the design's peak load; set your own in the inspector" : undefined}
            >
              {cpu != null && (
                <span className={cn("inline-flex items-center gap-1", isAuto[0] && "text-ink-3")}>
                  <Cpu className="size-3.5 text-ink-3" aria-hidden />
                  {cpu} vCPU
                </span>
              )}
              {mem != null && (
                <span className={cn("inline-flex items-center gap-1", isAuto[1] && "text-ink-3")}>
                  <MemoryStick className="size-3.5 text-ink-3" aria-hidden />
                  {mem} GiB
                </span>
              )}
              {disk != null && (
                <span className={cn("inline-flex items-center gap-1", isAuto[2] && "text-ink-3")}>
                  <HardDrive className="size-3.5 text-ink-3" aria-hidden />
                  {disk >= 1000 ? `${+(disk / 1000).toFixed(1)} TB` : `${disk} GiB`}
                </span>
              )}
              {isAuto.some(Boolean) && <span className="text-[11px] font-medium text-accent">auto</span>}
            </p>
          )}
          {needText.length > 0 && (
            <p
              className="arch-card-fact text-[11px] leading-4 text-ink-3"
              data-testid="node-need"
              title="What the part needs at the design's peak (CPU at the 60% target, typical memory); the line above is what its instance has"
            >
              needs {needText.join(" · ")}
            </p>
          )}
          {meta && level != null && <Meter level={level} />}
          {meta && (live || s) && (status !== "ok" || rejecting) && !isCluster && (
            <div className="flex items-center gap-1.5 pt-1 text-xs" aria-label={live ? `State at ${live.t.toFixed(0)} s` : "Simulation result for this component"}>
              <span className={cn("size-2 rounded-full", status === "saturated" && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
              <span className="font-medium text-ink">{meta.label}</span>
              {rejecting && (
                <span key={bucket} className="pulse-soft rounded-full bg-critical-soft px-1.5 text-[11px] font-semibold text-danger-ink">
                  {fmtRate(live!.rejected)}/s refused
                </span>
              )}
              <span className="font-readout ml-auto text-ink-2">
                {util != null ? `${fmtPct(util, 0)} busy` : live ? `${fmtRate(live.arrival ?? 0)}/s` : `${compactRate(s?.arrivalRps ?? 0)}/s`}
              </span>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
