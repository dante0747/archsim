"use client";

import { LoaderCircle, X } from "lucide-react";
import { Fragment } from "react";
import { useWorkflow } from "@/components/editor/workflow";
import { Kbd, Tip } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";

/** Workspace switcher (Build → Stress → Decide) with the single best next action on the right. */
export function WorkspaceBar() {
  const { steps, goTo, next, doNext, mode } = useWorkflow();
  const dismiss = useUi((s) => s.dismissHint);
  const promptChar = next?.tone === "critical" ? "!" : next?.tone === "success" ? "✓" : "❯";
  const promptColor =
    next?.tone === "critical" ? "var(--status-critical)" : next?.tone === "success" ? "var(--status-good)" : "var(--accent)";
  return (
    <div className="relative z-20 flex h-12 shrink-0 items-stretch gap-4 border-b border-line bg-surface/85 pl-2 pr-3 backdrop-blur-md">
      <nav role="tablist" aria-label="Workspaces" className="flex shrink-0 items-stretch" data-tour="workspaces">
        {steps.map((s, i) => {
          const active = mode === s.key;
          return (
            <Fragment key={s.key}>
              {i > 0 && (
                <span className="flex items-center px-0.5 text-[12px] text-ink-3/50" aria-hidden>
                  ›
                </span>
              )}
              <Tip
                content={
                  <span className="block max-w-64">
                    <span className="mb-0.5 flex items-center gap-2 font-semibold">
                      {s.label} <Kbd>{i + 1}</Kbd>
                    </span>
                    <span className="block text-ink-2">{s.hint}</span>
                  </span>
                }
              >
                <button
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => goTo(s.key)}
                  data-testid={`workspace-${s.key}`}
                  className={cn(
                    "group relative my-1.5 flex items-center gap-2.5 rounded-lg px-3 transition-colors after:absolute after:inset-x-3 after:-bottom-1.5 after:h-[2px] after:rounded-full",
                    active ? "bg-surface-2 text-ink after:bg-accent" : "text-ink-3 hover:bg-surface-2/60 hover:text-ink after:bg-transparent",
                  )}
                >
                  <span
                    className={cn(
                      "flex size-5 items-center justify-center rounded-full border font-mono text-[10px] font-semibold",
                      active ? "border-accent/60 bg-accent-soft text-accent" : "border-line-strong text-ink-3 group-hover:text-ink-2",
                    )}
                  >
                    {s.busy ? <LoaderCircle className="size-3 animate-spin" /> : i + 1}
                  </span>
                  <span className="flex flex-col items-start leading-none">
                    <span className="flex items-center gap-1.5 text-[13.5px] font-semibold tracking-[-0.01em]">
                      {s.label}
                      {s.state === "done" && (
                        <span className="text-[10px] tracking-normal" style={{ color: "var(--status-good)" }} aria-label="done">
                          ✓
                        </span>
                      )}
                    </span>
                    <span className="mt-0.5 hidden text-[11px] font-normal tracking-normal text-ink-3 lg:block">{s.caption}</span>
                  </span>
                </button>
              </Tip>
            </Fragment>
          );
        })}
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
              className="accent-fill flex shrink-0 items-center gap-1.5 rounded-md px-2.5 py-1 text-[12px] font-semibold transition-[filter] hover:brightness-[1.08]"
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
