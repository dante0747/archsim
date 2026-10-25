"use client";

/** Individual requests from the run, step by step: pick one and read its waterfall. */
import { useMemo, useState } from "react";
import { useDecideRun } from "@/components/decide/shared";
import { GROUPS, TraceWaterfall } from "@/components/explain/TraceWaterfall";
import { Empty } from "@/components/ui/misc";
import { fmtMs } from "@/lib/format";
import type { TraceRecord } from "@/lib/types";
import { cn } from "@/lib/utils";

const FILTERS = [
  { key: "all", label: "All" },
  { key: "slowest", label: "Slowest" },
  { key: "failed", label: "Failed" },
] as const;

const TAG_LABEL: Record<string, string> = { slowest: "Slowest", median: "Median", failed: "Failed", sample: "Sample" };

export function TracesTab() {
  const { report } = useDecideRun();
  const traces = useMemo(() => report?.traces ?? [], [report]);
  const [filter, setFilter] = useState<(typeof FILTERS)[number]["key"]>("all");
  const [picked, setPicked] = useState<number | null>(null);
  const shown = traces.filter((t) => filter === "all" || t.tags.includes(filter));
  const selected: TraceRecord | undefined =
    traces.find((t) => t.id === picked) ?? traces.find((t) => t.tags.includes("median")) ?? traces[0];
  const edgeLabel = useMemo(() => {
    const nodes = new Map((report?.nodes ?? []).map((n) => [n.id, n.label]));
    const edges = new Map((report?.edges ?? []).map((e) => [e.id, `${nodes.get(e.source) ?? e.source} → ${nodes.get(e.target) ?? e.target}`]));
    return (id: string) => `Network ${edges.get(id) ?? id}`;
  }, [report]);

  if (!report) return <Empty title="No run yet">Simulate the design to see individual requests.</Empty>;
  if (!traces.length)
    return <Empty title="No traces in this run">Runs from engine 0.5 or newer record a sample of requests. Simulate again to get them.</Empty>;

  return (
    <div className="grid gap-4 p-4 lg:grid-cols-[300px_minmax(0,1fr)]">
      <aside className="space-y-3">
        <p className="text-[13px] leading-relaxed text-ink-2">
          {traces.length} requests sampled from this run: evenly spread ones, the slowest, the median and failures. Each shows every hop, wait and piece of
          work it went through.
        </p>
        <div className="flex gap-1 rounded-lg bg-surface-2 p-0.5 text-xs font-medium">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f.key)}
              className={cn("flex-1 rounded-md px-2 py-1", filter === f.key ? "bg-surface text-ink" : "text-ink-3 hover:text-ink")}
            >
              {f.label}
            </button>
          ))}
        </div>
        <ul className="max-h-[560px] space-y-1 overflow-auto pr-1">
          {shown.map((t) => (
            <li key={t.id}>
              <button
                type="button"
                onClick={() => setPicked(t.id)}
                className={cn(
                  "flex w-full items-center gap-2 rounded-md border px-2.5 py-2 text-left text-xs transition-colors",
                  selected?.id === t.id ? "border-select bg-surface" : "border-transparent hover:bg-surface-2",
                )}
              >
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: t.status === "ok" ? "var(--status-good)" : "var(--status-critical)" }}
                  aria-label={t.status === "ok" ? "Succeeded" : "Failed"}
                />
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-ink">
                    {t.op === "read" ? "Read" : "Write"} at {t.startS.toFixed(1)} s
                  </span>
                  <span className="block truncate text-ink-3">{t.tags.map((x) => TAG_LABEL[x] ?? x).join(" · ")}</span>
                </span>
                <span className="tabular shrink-0 font-medium text-ink">{fmtMs(t.durationMs)}</span>
              </button>
            </li>
          ))}
          {!shown.length && <li className="px-2 py-4 text-xs text-ink-3">None in this run.</li>}
        </ul>
      </aside>
      {selected && (
        <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
          <header className="mb-3 flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <h3 className="text-[15px] font-semibold text-ink">
              {selected.op === "read" ? "Read" : "Write"} request {selected.id}
            </h3>
            <span className="tabular text-[13px] text-ink-2">
              {fmtMs(selected.durationMs)} · {selected.attempts} attempt{selected.attempts > 1 ? "s" : ""}
            </span>
            <span className={cn("text-[13px] font-medium", selected.status === "ok" ? "text-success-ink" : "text-danger-ink")}>
              {selected.status === "ok" ? "Succeeded" : `Failed: ${selected.reason ?? selected.status}`}
            </span>
          </header>
          <TraceWaterfall trace={selected} edgeLabel={edgeLabel} />
          <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-3">
            {GROUPS.map((g) => (
              <span key={g.key} className="inline-flex items-center gap-1.5">
                <span className="size-2.5 rounded-sm" style={{ background: g.color }} aria-hidden />
                {g.label}
              </span>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
