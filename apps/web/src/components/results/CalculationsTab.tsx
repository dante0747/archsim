"use client";

/**
 * The calculations behind the numbers: system formulas, where a request's time goes, and for each
 * component its technology behaviour and the formulas with this run's values filled in, next to
 * what the simulation measured.
 */
import { useState } from "react";
import { useDecideRun } from "@/components/decide/shared";
import { FormulaCard } from "@/components/explain/FormulaCard";
import { LatencyBreakdown } from "@/components/explain/LatencyBreakdown";
import { TechTraits } from "@/components/explain/TechTraits";
import { TechIcon } from "@/components/TechIcon";
import { Empty } from "@/components/ui/misc";
import { kindOf } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

export function CalculationsTab() {
  const { report } = useDecideRun();
  const catalog = useEditor((s) => s.catalog);
  const nodes = (report?.nodes ?? []).filter((n) => (n.explain?.length ?? 0) > 0 || (n.behaviour?.length ?? 0) > 0);
  const [picked, setPicked] = useState<string | null>(null);
  if (!report) return <Empty title="No run yet">Simulate the design to see the calculations behind every number.</Empty>;
  if (!report.explain)
    return <Empty title="No calculations in this run">Runs from engine 0.5 or newer include them. Simulate again to get them.</Empty>;
  const node = nodes.find((n) => n.id === picked) ?? nodes[0];

  return (
    <div className="space-y-6 p-4">
      <p className="max-w-3xl text-[13px] leading-relaxed text-ink-2">
        Every formula below is filled in with this run&apos;s numbers. The queueing predictions are textbook models that the simulation does not use, so you
        can check one against the other: where they differ, a real effect (bursts, long tails, locks, downstream waits) is at work.
      </p>
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <section className="rounded-lg border border-line bg-surface p-4">
          <h3 className="text-[15px] font-semibold text-ink">Where a request&apos;s time goes</h3>
          <p className="mt-1 text-xs text-ink-3">Mean time a successful request spends in each component, excluding its downstream calls, from the traced requests.</p>
          <div className="mt-4">
            <LatencyBreakdown rows={report.explain.latencyBreakdown} />
          </div>
        </section>
        <section className="grid content-start gap-3 sm:grid-cols-2">
          {report.explain.system.map((e) => (
            <FormulaCard key={e.id} entry={e} />
          ))}
        </section>
      </div>

      {node && (
        <div className="grid gap-4 lg:grid-cols-[240px_minmax(0,1fr)]">
          <nav aria-label="Components" className="space-y-1">
            <p className="px-2 pb-1 text-xs font-medium text-ink-3">Components</p>
            {nodes.map((n) => {
              const comp = catalog?.components.find((c) => c.type === n.type);
              const tech = typeof n.config.technology === "string" ? n.config.technology : null;
              return (
                <button
                  key={n.id}
                  type="button"
                  onClick={() => setPicked(n.id)}
                  className={cn(
                    "flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-[13px] transition-colors",
                    node.id === n.id ? "bg-surface-2 text-ink" : "text-ink-2 hover:bg-surface-2/60 hover:text-ink",
                  )}
                >
                  <TechIcon technology={tech} kind={kindOf(n.type, comp?.category)} size={16} tile className="rounded-md" />
                  <span className="min-w-0 flex-1 truncate">{n.label}</span>
                  <span className="tabular text-xs text-ink-3">{n.explain?.length ?? 0}</span>
                </button>
              );
            })}
          </nav>
          <section className="min-w-0 space-y-4">
            <header>
              <h3 className="text-[17px] font-semibold tracking-[-0.01em] text-ink">{node.label}</h3>
              <p className="text-xs text-ink-3">
                {catalog?.components.find((c) => c.type === node.type)?.displayName ?? node.type}
                {typeof node.config.technology === "string" ? ` · ${node.config.technology}` : ""}
              </p>
            </header>
            {(node.behaviour?.length ?? 0) > 0 && (
              <div>
                <h4 className="mb-2 text-[13px] font-semibold text-ink">Technology behaviour in effect</h4>
                <TechTraits traits={node.behaviour ?? []} dense className="grid gap-2 space-y-0 md:grid-cols-2" />
              </div>
            )}
            <div>
              <h4 className="mb-2 text-[13px] font-semibold text-ink">Calculations</h4>
              <div className="grid gap-3 md:grid-cols-2">
                {(node.explain ?? []).map((e) => (
                  <FormulaCard key={e.id} entry={e} />
                ))}
              </div>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
