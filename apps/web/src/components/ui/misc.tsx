import { Switch as RSwitch, Tooltip as RTooltip } from "radix-ui";
import * as React from "react";
import { cn } from "@/lib/utils";

export type Tone = "neutral" | "accent" | "good" | "warning" | "serious" | "critical";

export function Badge({
  className,
  tone = "neutral",
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  const tones: Record<Tone, string> = {
    neutral: "bg-surface-2 text-ink-2 border-line",
    accent: "bg-accent-soft text-ink border-transparent",
    good: "bg-good-soft text-ink border-transparent",
    warning: "bg-warning-soft text-ink border-transparent",
    serious: "bg-serious-soft text-ink border-transparent",
    critical: "bg-critical-soft text-ink border-transparent",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 whitespace-nowrap rounded-sm border px-1.5 py-px text-[11px] font-medium leading-4",
        tones[tone],
        className,
      )}
      {...props}
    />
  );
}

export function Kbd({ className, ...props }: React.HTMLAttributes<HTMLElement>) {
  return (
    <kbd
      className={cn(
        "rounded-[5px] border border-b-2 border-line-strong bg-surface-2 px-1 font-mono text-[10px] leading-4 text-ink-2",
        className,
      )}
      {...props}
    />
  );
}

export function Switch({
  checked,
  onCheckedChange,
  id,
  label,
}: {
  checked: boolean;
  onCheckedChange: (v: boolean) => void;
  id?: string;
  label?: string;
}) {
  return (
    <RSwitch.Root
      id={id}
      aria-label={label}
      checked={checked}
      onCheckedChange={onCheckedChange}
      className="relative h-[18px] w-8 shrink-0 rounded-full border border-line-strong bg-surface-3 transition-colors data-[state=checked]:border-transparent data-[state=checked]:bg-accent"
    >
      <RSwitch.Thumb className="block size-3.5 translate-x-px rounded-full bg-white shadow-[0_1px_2px_rgba(0,0,0,0.35)] transition-transform duration-150 data-[state=checked]:translate-x-[14px]" />
    </RSwitch.Root>
  );
}

export function Tip({
  content,
  children,
  side = "bottom",
}: {
  content: React.ReactNode;
  children: React.ReactElement;
  side?: "top" | "bottom" | "left" | "right";
}) {
  return (
    <RTooltip.Root delayDuration={300}>
      <RTooltip.Trigger asChild>{children}</RTooltip.Trigger>
      <RTooltip.Portal>
        <RTooltip.Content
          side={side}
          sideOffset={6}
          className="surface-glass z-50 max-w-xs rounded-md px-2 py-1 text-xs leading-relaxed text-ink"
        >
          {content}
        </RTooltip.Content>
      </RTooltip.Portal>
    </RTooltip.Root>
  );
}

export const TooltipProvider = RTooltip.Provider;

export function Section({
  title,
  children,
  actions,
  className,
}: {
  title: React.ReactNode;
  children: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("space-y-2", className)}>
      <div className="flex min-h-6 items-center justify-between gap-2">
        <h3 className="microlabel">
          <span className="mr-1 opacity-60" aria-hidden>
            {"//"}
          </span>
          {title}
        </h3>
        {actions}
      </div>
      {children}
    </section>
  );
}

export function Empty({
  icon,
  title,
  children,
  className,
}: {
  icon?: React.ReactNode;
  title: string;
  children?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 p-6 text-center", className)}>
      {icon && <div className="text-ink-3 [&_svg]:size-6">{icon}</div>}
      <p className="font-display text-[20px] leading-tight text-ink">{title}</p>
      {children && <div className="max-w-md text-xs leading-relaxed text-ink-2">{children}</div>}
    </div>
  );
}
