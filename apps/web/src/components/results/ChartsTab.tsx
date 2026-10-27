"use client";

import { LineChart as LineChartIcon } from "lucide-react";
import { useMemo } from "react";
import { SampleNote } from "@/components/decide/SeedRange";
import { Empty } from "@/components/ui/misc";
import { fmtMs, fmtNum, fmtRps } from "@/lib/format";
import { SERIES } from "@/lib/status";
import type { NodeReport, Report } from "@/lib/types";
import { useActiveReport } from "@/store/runs";
import { cn } from "@/lib/utils";
import { fmtAxisCount, fmtAxisMs, fmtAxisPct, fmtAxisRps, PERCENTILE_SERIES, type SeriesDef, thClass, TimeSeriesChart } from "./charts";

/** Components shown in per-component charts: busiest first, capped at the 8 categorical slots. */
export function chartNodes(report: Report): NodeReport[] {
  return report.nodes
    .filter((n) => n.category !== "traffic" && n.timeseries.utilization)
    .sort((a, b) => (b.summary.utilization ?? 0) - (a.summary.utilization ?? 0))
    .slice(0, 8);
}

export function ChartsTab() {
  const report = useActiveReport();
  const colorById = useMemo(() => {
    // Color follows the entity (stable by document order), never its rank.
    const m = new Map<string, string>();
    report?.nodes.filter((n) => n.category !== "traffic").forEach((n, i) => m.set(n.id, SERIES[i % SERIES.length]));
    return m;
  }, [report]);
  if (!report) {
    return (
      <Empty icon={<LineChartIcon />} title="No results to chart">
        Run a simulation to see throughput, latency percentiles, utilization and queues over simulated time.
      </Empty>
    );
  }
  const ts = report.timeseries;
  const w = report.window;
  const common = { t: ts.t, warmupS: w.warmupS, durationS: w.durationS };
  const nodes = chartNodes(report);
  const nodeSeries = (key: string): SeriesDef[] =>
    nodes.map((n) => ({ key: n.id, label: n.label, color: colorById.get(n.id) ?? SERIES[0], values: n.timeseries[key] ?? [] }));

  return (
    <div className="grid gap-3 p-4 lg:grid-cols-2 2xl:grid-cols-3">
      <SampleNote report={report} className="lg:col-span-2 2xl:col-span-3" />
      <TimeSeriesChart
        {...common}
        title="Offered vs successful throughput"
        subtitle={`req/s · ${ts.bucketS}s buckets`}
        format={fmtAxisRps}
        series={[
          { key: "offered", label: "Offered", color: SERIES[0], values: ts.offeredRps },
          { key: "throughput", label: "Successful", color: SERIES[1], values: ts.throughputRps },
        ]}
      />
      <TimeSeriesChart
        {...common}
        title="End-to-end latency (successful requests)"
        subtitle="by completion time"
        format={fmtAxisMs}
        series={[
          { key: "p50", label: "p50", color: PERCENTILE_SERIES[0], values: ts.latencyP50Ms },
          { key: "p95", label: "p95", color: PERCENTILE_SERIES[1], values: ts.latencyP95Ms },
          { key: "p99", label: "p99", color: PERCENTILE_SERIES[2], values: ts.latencyP99Ms },
        ]}
      />
      <TimeSeriesChart
        {...common}
        title="Failures"
        subtitle="req/s"
        format={fmtAxisRps}
        series={[
          { key: "errors", label: "Failed (rejected / dependency)", color: SERIES[0], values: ts.errorRps },
          { key: "timeouts", label: "Client timeouts", color: SERIES[1], values: ts.timeoutRps },
        ]}
      />
      <TimeSeriesChart
        {...common}
        title="Utilization by component"
        subtitle="share of workers / connections busy"
        format={fmtAxisPct}
        yMax={1}
        series={nodeSeries("utilization")}
      />
      <TimeSeriesChart
        {...common}
        title="Queue depth by component"
        subtitle="mean requests waiting"
        format={fmtAxisCount}
        series={nodeSeries("queueMean")}
      />
      <TimeSeriesChart
        {...common}
        title="Client attempts"
        subtitle="req/s including retries"
        format={fmtAxisRps}
        series={[
          { key: "attempts", label: "Attempts", color: SERIES[0], values: ts.attemptRps },
          { key: "offered", label: "Requests", color: SERIES[1], values: ts.offeredRps },
        ]}
      />
      <DataTableToggle report={report} />
    </div>
  );
}

function DataTableToggle({ report }: { report: Report }) {
  const ts = report.timeseries;
  return (
    <details className="rounded-md border border-line bg-surface p-3 lg:col-span-2 2xl:col-span-3">
      <summary className="cursor-pointer font-mono text-xs text-ink-2 hover:text-ink">table view of the time series</summary>
      <div className="mt-2 max-h-64 overflow-auto">
        <table className="tabular w-full font-mono text-xs">
          <thead className="sticky top-0 bg-surface text-left">
            <tr>
              <th className={cn(thClass, "py-1 pr-3")}>t (s)</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>Offered</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>Successful</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>Failed</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>Timeouts</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>p50</th>
              <th className={cn(thClass, "py-1 pr-3 text-right")}>p95</th>
              <th className={cn(thClass, "py-1 text-right")}>p99</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {ts.t.map((t, i) => (
              <tr key={t} className={t < report.window.warmupS ? "text-ink-3" : "text-ink"}>
                <td className="py-0.5 pr-3">{fmtNum(t, 1)}</td>
                <td className="py-0.5 pr-3 text-right">{fmtRps(ts.offeredRps[i])}</td>
                <td className="py-0.5 pr-3 text-right">{fmtRps(ts.throughputRps[i])}</td>
                <td className="py-0.5 pr-3 text-right">{fmtRps(ts.errorRps[i])}</td>
                <td className="py-0.5 pr-3 text-right">{fmtRps(ts.timeoutRps[i])}</td>
                <td className="py-0.5 pr-3 text-right">{fmtMs(ts.latencyP50Ms[i])}</td>
                <td className="py-0.5 pr-3 text-right">{fmtMs(ts.latencyP95Ms[i])}</td>
                <td className="py-0.5 text-right">{fmtMs(ts.latencyP99Ms[i])}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="mt-1 font-sans text-xs text-ink-3">Grey rows are inside the warm-up period (excluded from summary statistics).</p>
      </div>
    </details>
  );
}
