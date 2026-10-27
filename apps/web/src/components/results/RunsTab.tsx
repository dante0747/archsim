"use client";

import { CircleAlert, CircleCheck, CircleSlash, Eye, History, LoaderCircle, MoreHorizontal, RotateCcw, Square, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/misc";
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/components/ui/overlays";
import { fmtMs, fmtPct, fmtRelativeTime, fmtRps, shortDigest } from "@/lib/format";
import type { Run } from "@/lib/types";
import { cn } from "@/lib/utils";
import { thClass } from "./charts";
import { useRuns } from "@/store/runs";

function StatusIcon({ run }: { run: Run }) {
  switch (run.status) {
    case "succeeded":
      return <CircleCheck className="size-4" style={{ color: "var(--status-good)" }} aria-label="succeeded" />;
    case "failed":
      return <CircleAlert className="size-4" style={{ color: "var(--status-critical)" }} aria-label="failed" />;
    case "cancelled":
      return <CircleSlash className="size-4 text-ink-3" aria-label="cancelled" />;
    default:
      return <LoaderCircle className="size-4 animate-spin text-ink-3" aria-label={run.status} />;
  }
}

export function RunsTab() {
  const runs = useRuns((s) => s.runs);
  const activeRunId = useRuns((s) => s.activeRunId);
  const showRun = useRuns((s) => s.showRun);
  const setCompare = useRuns((s) => s.setCompare);
  const compareA = useRuns((s) => s.compareA);
  const compareB = useRuns((s) => s.compareB);
  const rerun = useRuns((s) => s.rerun);
  const cancel = useRuns((s) => s.cancel);
  const remove = useRuns((s) => s.remove);
  const openDrawer = useRuns((s) => s.openDrawer);

  if (runs.length === 0) {
    return (
      <Empty icon={<History />} title="No runs yet">
        Every simulation is stored with the exact document, scenario and seed it used, so you can revisit, compare and
        reproduce it later.
      </Empty>
    );
  }
  return (
    <div className="overflow-x-auto p-4">
      <table className="tabular w-full min-w-[820px] text-xs">
        <thead className="text-left">
          <tr className="border-b border-line">
            <th className={cn(thClass, "px-2 py-2")} aria-label="Status" />
            <th className={cn(thClass, "px-2 py-2")}>Run</th>
            <th className={cn(thClass, "px-2 py-2")}>When</th>
            <th className={cn(thClass, "px-2 py-2 text-right")}>Seed</th>
            <th className={cn(thClass, "px-2 py-2 text-right")}>Throughput</th>
            <th className={cn(thClass, "px-2 py-2 text-right")}>Success</th>
            <th className={cn(thClass, "px-2 py-2 text-right")}>p95</th>
            <th className={cn(thClass, "px-2 py-2")}>Bottleneck</th>
            <th className={cn(thClass, "px-2 py-2")}>Result digest</th>
            <th className="px-2 py-2" aria-label="Actions" />
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {runs.map((r) => {
            const active = r.status === "queued" || r.status === "running";
            return (
              <tr key={r.id} className={cn("hover:bg-surface-2", r.id === activeRunId && "bg-accent-soft")}>
                <td className="px-2 py-1.5">
                  <StatusIcon run={r} />
                </td>
                <td className="max-w-56 px-2 py-1.5">
                  <p className="truncate font-medium text-ink">{r.label}</p>
                  {r.mode === "sampled" && <p className="font-mono text-[11px] text-ink-3">estimated (quick check)</p>}
                  {r.status === "failed" && r.error && <p className="truncate text-danger-ink" title={r.error.message}>{r.error.message}</p>}
                  {active && <p className="font-mono text-[11px] text-ink-3">{r.status} · {Math.round(r.progress * 100)}%</p>}
                  {(r.id === compareA || r.id === compareB) && (
                    <p className="font-mono text-[11px] text-ink-3">comparing as {r.id === compareA ? "A" : "B"}</p>
                  )}
                </td>
                <td className="whitespace-nowrap px-2 py-1.5 font-mono text-xs text-ink-2">{fmtRelativeTime(r.createdAt)}</td>
                <td className="px-2 py-1.5 text-right font-mono text-xs text-ink-2">{r.seed}</td>
                <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">{fmtRps(r.summary?.throughputRps)}</td>
                <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">{fmtPct(r.summary?.successRate)}</td>
                <td className="px-2 py-1.5 text-right font-mono text-xs text-ink">{fmtMs(r.summary?.p95)}</td>
                <td className="max-w-48 truncate px-2 py-1.5 text-ink-2" title={r.summary?.bottleneck ?? ""}>
                  {r.summary ? (r.summary.bottleneck ?? "none") : "–"}
                </td>
                <td className="px-2 py-1.5 font-mono text-xs text-ink-3" title={r.resultDigest ?? ""}>
                  {shortDigest(r.resultDigest)}
                </td>
                <td className="px-2 py-1.5 text-right">
                  <div className="flex justify-end gap-0.5">
                    {r.status === "succeeded" && (
                      <Button size="icon-sm" variant="ghost" aria-label={`View ${r.label}`} title="View results" onClick={() => { void showRun(r.id); openDrawer("overview"); }}>
                        <Eye />
                      </Button>
                    )}
                    {active && (
                      <Button size="icon-sm" variant="ghost" aria-label="Cancel run" title="Cancel" onClick={() => void cancel(r.id)}>
                        <Square />
                      </Button>
                    )}
                    <Menu>
                      <MenuTrigger asChild>
                        <Button size="icon-sm" variant="ghost" aria-label={`More actions for ${r.label}`}>
                          <MoreHorizontal />
                        </Button>
                      </MenuTrigger>
                      <MenuContent>
                        <MenuItem disabled={r.status !== "succeeded"} onSelect={() => { void setCompare(r.id, compareB === r.id ? null : compareB); openDrawer("compare"); }}>
                          Compare as A
                        </MenuItem>
                        <MenuItem disabled={r.status !== "succeeded"} onSelect={() => { void setCompare(compareA === r.id ? null : compareA, r.id); openDrawer("compare"); }}>
                          Compare as B
                        </MenuItem>
                        <MenuItem disabled={active} onSelect={() => void rerun(r.id)}>
                          <RotateCcw /> Reproduce (same input & seed)
                        </MenuItem>
                        <MenuItem asChild>
                          <a href={`/api/v1/runs/${r.id}/input`} download={`run-${r.id.slice(0, 8)}-input.archsim.json`}>
                            Download simulated input
                          </a>
                        </MenuItem>
                        {r.status === "succeeded" && (
                          <MenuItem asChild>
                            <a href={`/api/v1/runs/${r.id}/report`} download={`run-${r.id.slice(0, 8)}-report.json`}>
                              Download report JSON
                            </a>
                          </MenuItem>
                        )}
                        <MenuSeparator />
                        <MenuItem className="text-danger-ink" onSelect={() => void remove(r.id)}>
                          <Trash2 /> Delete run
                        </MenuItem>
                      </MenuContent>
                    </Menu>
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
