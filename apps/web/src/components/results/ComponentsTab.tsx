"use client";

import { Boxes, Crosshair } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/misc";
import { fmtMs, fmtNum, fmtPct, fmtRps } from "@/lib/format";
import { NODE_STATUS, SERIES } from "@/lib/status";
import type { NodeReport, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useActiveReport, useRuns } from "@/store/runs";
import { fmtAxisCount, fmtAxisMs, fmtAxisPct, fmtAxisRps, SINGLE_SERIES, thClass, TimeSeriesChart } from "./charts";

export function StatusPill({ status }: { status: NodeReport["status"] }) {
  const meta = NODE_STATUS[status];
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap font-mono text-[11px] text-ink">
      <span className="size-1.5 rounded-full" style={{ background: meta.color }} aria-hidden />
      {meta.label}
    </span>
  );
}

export function ComponentsTab() {
  const report = useActiveReport();
  const focusNodeId = useRuns((s) => s.focusNodeId);
  const focusNode = useRuns((s) => s.focusNode);
  if (!report) {
    return (
      <Empty icon={<Boxes />} title="No component metrics yet">
        After a run, each component shows its load, utilization, queueing and rejections here.
      </Empty>
    );
  }
  const rows = report.nodes.filter((n) => n.category !== "traffic");
  const focused = rows.find((n) => n.id === focusNodeId) ?? rows[0];
  return (
    <div className="grid gap-4 p-4 xl:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <div className="min-w-0 overflow-x-auto rounded-md border border-line bg-surface">
        <table className="tabular w-full min-w-[720px] text-xs">
          <caption className="sr-only">Per-component simulation metrics (after warm-up)</caption>
          <thead className="text-left">
            <tr className="border-b border-line">
              <th className={cn(thClass, "px-3 py-2")}>Component</th>
              <th className={cn(thClass, "px-2 py-2")}>Status</th>
              <th className={cn(thClass, "px-2 py-2 text-right")}>Arrivals</th>
              <th className={cn(thClass, "px-2 py-2 text-right")}>Util. avg / peak</th>
              <th className={cn(thClass, "px-2 py-2 text-right")} title="Share of busy worker time spent on its own processing (vs blocked on downstream calls)">
                Own work
              </th>
              <th className={cn(thClass, "px-2 py-2 text-right")}>Queue avg / peak</th>
              <th className={cn(thClass, "px-2 py-2 text-right")}>Wait p95</th>
              <th className={cn(thClass, "px-2 py-2 text-right")}>Rejected</th>
              <th className={cn(thClass, "px-3 py-2 text-right")} title="Requests/s it could sustain at the observed time each request held a slot">
                Est. capacity
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((n) => {
              const s = n.summary;
              const own = s.cpuUtilization != null && s.utilization ? s.cpuUtilization / s.utilization : null;
              return (
                <tr
                  key={n.id}
                  onClick={() => focusNode(n.id)}
                  className={cn("cursor-pointer hover:bg-surface-2", focused?.id === n.id && "bg-accent-soft")}
                  aria-selected={focused?.id === n.id}
                >
                  <td className="px-3 py-2 font-medium text-ink">
                    {n.label}
                    {s.hitRatio != null && <span className="ml-1.5 font-mono text-[10.5px] font-normal text-ink-3">hit {fmtPct(s.hitRatio, 0)}</span>}
                  </td>
                  <td className="px-2 py-2">
                    <StatusPill status={n.status} />
                  </td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink">{fmtRps(s.arrivalRps)}</td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink">
                    {fmtPct(s.utilization, 0)} / {fmtPct(s.peakUtilization, 0)}
                  </td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink-2">{fmtPct(own, 0)}</td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink">
                    {fmtNum(s.meanQueue, 1)} / {fmtNum(s.peakQueue)}
                  </td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink">{fmtMs(s.p95QueueWaitMs)}</td>
                  <td className="px-2 py-2 text-right font-mono text-[11px] text-ink">
                    {fmtNum(s.rejectedTotal)}
                    {s.rejectedTotal ? <span className="text-ink-3"> ({fmtPct(s.rejectionRate)})</span> : null}
                  </td>
                  <td className="px-3 py-2 text-right font-mono text-[11px] text-ink-2" title={s.capacityLimit && s.capacityLimit !== "slots" ? `Limited by ${s.capacityLimit === "cpu" ? "CPU cores" : "memory"}` : undefined}>
                    {fmtRps(s.capacityRps)}
                    {s.capacityLimit === "cpu" && <span className="ml-1 text-[10px] text-ink-3">CPU</span>}
                    {s.capacityLimit === "memory" && <span className="ml-1 text-[10px] text-ink-3">mem</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {focused && <NodeDetail report={report} node={focused} />}
    </div>
  );
}

function NodeDetail({ report, node }: { report: Report; node: NodeReport }) {
  const select = useEditor((s) => s.select);
  const ts = node.timeseries;
  const common = { t: report.timeseries.t, warmupS: report.window.warmupS, durationS: report.window.durationS, height: 130 };
  const reasons = node.statusReasons.join(", ");
  return (
    <div className="min-w-0 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold text-ink">{node.label}</h3>
        <StatusPill status={node.status} />
        {reasons && <span className="font-mono text-[10.5px] text-ink-3">({reasons})</span>}
        <Button size="sm" variant="ghost" className="ml-auto" onClick={() => select({ nodeId: node.id })}>
          <Crosshair /> Select on canvas
        </Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <TimeSeriesChart
          {...common}
          title="Load"
          subtitle="req/s"
          format={fmtAxisRps}
          series={[
            { key: "in", label: "Arrivals", color: SERIES[0], values: ts.arrivalRps ?? [] },
            { key: "done", label: "Completed", color: SERIES[1], values: ts.completedRps ?? [] },
            { key: "rej", label: "Rejected", color: SERIES[2], values: ts.rejectedRps ?? [] },
          ]}
        />
        {ts.utilization && (
          <TimeSeriesChart
            {...common}
            title="Utilization"
            format={fmtAxisPct}
            yMax={1}
            series={[
              { key: "busy", label: "Slots busy", color: ts.cpuUtilization ? SERIES[0] : SINGLE_SERIES, values: ts.utilization },
              ...(ts.cpuUtilization ? [{ key: "cpu", label: "Own processing", color: SERIES[1], values: ts.cpuUtilization }] : []),
            ]}
          />
        )}
        <TimeSeriesChart
          {...common}
          title="Queue depth"
          subtitle="requests waiting"
          format={fmtAxisCount}
          series={[
            { key: "mean", label: "Mean", color: SERIES[0], values: ts.queueMean ?? [] },
            { key: "max", label: "Peak", color: SERIES[1], values: ts.queueMax ?? [] },
          ]}
        />
        {ts.hitRatio ? (
          <TimeSeriesChart {...common} title="Cache hit ratio" format={fmtAxisPct} yMax={1} series={[{ key: "hit", label: "Hit ratio", color: SINGLE_SERIES, values: ts.hitRatio }]} />
        ) : (
          <TimeSeriesChart {...common} title="Queue wait" subtitle="mean, ms" format={fmtAxisMs} series={[{ key: "wait", label: "Mean wait", color: SINGLE_SERIES, values: ts.queueWaitMs ?? [] }]} />
        )}
      </div>
    </div>
  );
}
