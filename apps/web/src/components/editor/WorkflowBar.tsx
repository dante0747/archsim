"use client";

import { LoaderCircle, X } from "lucide-react";
import { Fragment } from "react";
import { useWorkflow } from "@/components/editor/workflow";
import { Tip } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";

/** Guided workflow: numbered steps on the left, the single best next action as a prompt on the right. */
export function WorkflowBar() {
  const { steps, goTo, next, doNext } = useWorkflow();
  const dismiss = useUi((s) => s.dismissHint);
  const promptChar = next?.tone === "critical" ? "!" : next?.tone === "success" ? "✓" : "❯";
  const promptColor =
    next?.tone === "critical" ? "var(--status-critical)" : next?.tone === "success" ? "var(--status-good)" : "var(--accent)";
  return (
    <div className="relative z-20 flex h-10 shrink-0 items-center gap-4 border-b border-line bg-surface px-3">
      <nav aria-label="Workflow" className="flex shrink-0 items-center font-mono text-[11px]">
        {steps.map((s, i) => (
          <Fragment key={s.key}>
            {i > 0 && (
              <span
                className={cn("mx-1 h-px w-3 lg:w-6", steps[i - 1].state === "done" ? "bg-ink-3" : "bg-line-strong")}
                aria-hidden
              />
            )}
            <Tip content={`${i + 1}. ${s.label}: ${s.hint}`}>
              <button
                type="button"
                onClick={() => goTo(s.key)}
                aria-current={s.state === "current" ? "step" : undefined}
                className={cn(
                  "flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 transition-colors",
                  s.state === "current" && "border-accent/50 bg-accent-soft text-ink",
                  s.state === "done" && "border-transparent text-ink-2 hover:text-ink",
                  s.state === "todo" && "border-transparent text-ink-3 hover:text-ink-2",
                )}
              >
                <span className={cn("tabular", s.state === "current" ? "text-accent" : s.state === "done" ? "text-ink-2" : "text-ink-3")}>
                  {s.busy ? <LoaderCircle className="size-3 animate-spin" /> : s.state === "done" ? "✓" : String(i + 1).padStart(2, "0")}
                </span>
                <span className="hidden lowercase md:inline">{s.label}</span>
              </button>
            </Tip>
          </Fragment>
        ))}
      </nav>

      {next && (
        <div role="status" className="ml-auto flex min-w-0 items-center gap-2.5">
          <span className="shrink-0 font-mono text-[12px] font-bold" style={{ color: promptColor }} aria-hidden>
            {next.tone === "busy" ? <LoaderCircle className="size-3.5 animate-spin" /> : promptChar}
          </span>
          <div className="min-w-0">
            <p className="truncate text-[12.5px] text-ink" title={next.text}>
              {next.text}
            </p>
            {next.progress != null && (
              <div
                className="mt-0.5 h-1 w-48 overflow-hidden rounded-[1px] bg-surface-3"
                role="progressbar"
                aria-valuenow={Math.round(next.progress * 100)}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Simulation progress"
              >
                <div className="progress-stripes h-full transition-[width]" style={{ width: `${Math.max(3, next.progress * 100)}%` }} />
              </div>
            )}
          </div>
          {next.actionLabel && (
            <button
              type="button"
              onClick={doNext}
              className="flex shrink-0 items-center gap-1.5 rounded-sm bg-accent px-2 py-1 font-mono text-[11px] font-semibold lowercase text-accent-ink hover:brightness-110"
            >
              {next.actionLabel}
              <span aria-hidden>↵</span>
            </button>
          )}
          {next.tone !== "busy" && (
            <button type="button" onClick={() => dismiss(next.key)} className="shrink-0 rounded-sm p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Dismiss hint">
              <X className="size-3.5" />
            </button>
          )}
        </div>
      )}
    </div>
  );
}
