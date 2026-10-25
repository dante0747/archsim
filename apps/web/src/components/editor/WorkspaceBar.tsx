"use client";

import { ArrowRight, Check, LoaderCircle, X } from "lucide-react";
import { useWorkflow } from "@/components/editor/workflow";
import { Kbd, Tip } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";

/** Design · Test · Results switch, centred in the top bar. */
export function WorkspaceSwitch() {
  const { steps, goTo, mode } = useWorkflow();
  return (
    <nav
      role="tablist"
      aria-label="Workspaces"
      data-tour="workspaces"
      className="flex items-center gap-0.5 rounded-lg border border-line bg-surface-2/70 p-0.5"
    >
      {steps.map((s, i) => {
        const active = mode === s.key;
        return (
          <Tip
            key={s.key}
            content={
              <span className="flex items-center gap-2">
                {s.hint} <Kbd>{i + 1}</Kbd>
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
                "flex h-7 items-center gap-1.5 rounded-md px-3 text-[13px] font-medium transition-colors",
                active ? "bg-surface text-ink shadow-[0_0_0_1px_var(--line-strong),0_4px_14px_-6px_var(--accent)]" : "text-ink-3 hover:text-ink",
              )}
            >
              {s.busy ? (
                <LoaderCircle className="size-3.5 animate-spin text-accent" aria-hidden />
              ) : s.state === "done" ? (
                <Check className="size-3.5" style={{ color: "var(--status-good)" }} aria-label="done" />
              ) : null}
              {s.label}
            </button>
          </Tip>
        );
      })}
    </nav>
  );
}

/** One short "what next" line floating at the top of the canvas. */
export function NextStepPill() {
  const { next, doNext } = useWorkflow();
  const dismiss = useUi((s) => s.dismissHint);
  if (!next) return null;
  const color =
    next.tone === "critical" ? "var(--status-critical)" : next.tone === "success" ? "var(--status-good)" : "var(--accent)";
  return (
    <div className="pointer-events-none absolute inset-x-0 top-3 z-20 flex justify-center px-4">
      <div
        role="status"
        className="surface-glass pointer-events-auto flex max-w-[min(560px,100%)] items-center gap-2.5 rounded-full py-1 pl-3.5 pr-1"
      >
        {next.tone === "busy" ? (
          <LoaderCircle className="size-3.5 shrink-0 animate-spin text-accent" aria-hidden />
        ) : (
          <span className="size-2 shrink-0 rounded-full" style={{ background: color }} aria-hidden />
        )}
        <p className="truncate text-[13px] text-ink" title={next.text}>
          {next.text}
        </p>
        {next.progress != null && (
          <span className="h-1 w-20 shrink-0 overflow-hidden rounded-full bg-surface-3" aria-hidden>
            <span className="progress-stripes block h-full" style={{ width: `${Math.max(4, next.progress * 100)}%` }} />
          </span>
        )}
        {next.actionLabel ? (
          <button
            type="button"
            onClick={doNext}
            className="accent-fill flex shrink-0 items-center gap-1 rounded-full px-3 py-1 text-[12.5px] font-semibold hover:brightness-[1.08]"
          >
            {next.actionLabel} <ArrowRight className="size-3.5" />
          </button>
        ) : (
          <span className="w-1" />
        )}
        {next.tone !== "busy" && (
          <button type="button" onClick={() => dismiss(next.key)} className="shrink-0 rounded-full p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Dismiss">
            <X className="size-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}
