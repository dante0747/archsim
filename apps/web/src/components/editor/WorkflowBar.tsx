"use client";

import { ArrowRight, Check, CircleAlert, CircleCheck, LoaderCircle, Sparkles, X } from "lucide-react";
import { Fragment } from "react";
import { useWorkflow } from "@/components/editor/workflow";
import { Tip } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";

/** Guided workflow: numbered steps on the left, the single best next action on the right. */
export function WorkflowBar() {
  const { steps, goTo, next, doNext } = useWorkflow();
  const dismiss = useUi((s) => s.dismissHint);
  const Icon =
    next?.tone === "critical" ? CircleAlert : next?.tone === "busy" ? LoaderCircle : next?.tone === "success" ? CircleCheck : Sparkles;
  const iconColor =
    next?.tone === "critical" ? "var(--status-critical)" : next?.tone === "success" ? "var(--status-good)" : "var(--accent)";
  return (
    <div className="relative z-20 flex h-11 shrink-0 items-center gap-4 border-b border-line bg-surface/80 px-3 backdrop-blur">
      <nav aria-label="Workflow" className="flex shrink-0 items-center">
        {steps.map((s, i) => (
          <Fragment key={s.key}>
            {i > 0 && (
              <span
                className="mx-0.5 h-px w-3 lg:w-5"
                style={{ background: steps[i - 1].state === "done" ? "var(--brand)" : "var(--line-strong)" }}
                aria-hidden
              />
            )}
            <Tip content={`${i + 1}. ${s.label}: ${s.hint}`}>
              <button
                type="button"
                onClick={() => goTo(s.key)}
                aria-current={s.state === "current" ? "step" : undefined}
                className={cn(
                  "flex items-center gap-1.5 rounded-full py-1 pl-1 pr-1 text-xs transition-colors md:pr-2.5",
                  s.state === "current" ? "bg-accent-soft font-semibold text-ink" : "text-ink-2 hover:bg-surface-2 hover:text-ink",
                )}
              >
                <span
                  className={cn(
                    "flex size-5 items-center justify-center rounded-full font-mono text-[10px] font-bold",
                    s.state === "done" && "brand-bg text-accent-ink",
                    s.state === "current" && "bg-surface text-ink ring-2 ring-accent",
                    s.state === "todo" && "bg-surface-3 text-ink-3",
                  )}
                >
                  {s.busy ? <LoaderCircle className="size-3 animate-spin" /> : s.state === "done" ? <Check className="size-3" strokeWidth={3} /> : i + 1}
                </span>
                <span className="hidden md:inline">{s.label}</span>
              </button>
            </Tip>
          </Fragment>
        ))}
      </nav>

      {next && (
        <div role="status" className="ml-auto flex min-w-0 items-center gap-2.5">
          <span className="microlabel hidden shrink-0 lg:inline">Next</span>
          <Icon className={cn("size-4 shrink-0", next.tone === "busy" && "animate-spin")} style={{ color: iconColor }} aria-hidden />
          <div className="min-w-0">
            <p className="truncate text-[12.5px] text-ink" title={next.text}>
              {next.text}
            </p>
            {next.progress != null && (
              <div className="mt-0.5 h-1 w-48 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={Math.round(next.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Simulation progress">
                <div className="progress-shimmer h-full rounded-full transition-[width]" style={{ width: `${Math.max(3, next.progress * 100)}%` }} />
              </div>
            )}
          </div>
          {next.actionLabel && (
            <button
              type="button"
              onClick={doNext}
              className="brand-bg flex shrink-0 items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-semibold text-accent-ink hover:brightness-110"
            >
              {next.actionLabel} <ArrowRight className="size-3.5" />
            </button>
          )}
          {next.tone !== "busy" && (
            <button type="button" onClick={() => dismiss(next.key)} className="shrink-0 rounded-md p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Dismiss hint">
              <X className="size-3.5" />
            </button>
          )}
        </div>
      )}
    </div>
  );
}
