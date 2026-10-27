import { ShieldAlert, ShieldCheck, ShieldQuestion } from "lucide-react";
import { CONFIDENCE_LABEL } from "@/lib/confidence";
import type { ConfidenceLevel } from "@/lib/types";
import { cn } from "@/lib/utils";

const TONE: Record<ConfidenceLevel, string> = {
  high: "bg-good-soft",
  medium: "bg-warning-soft",
  low: "bg-serious-soft",
};

const ICON = { high: ShieldCheck, medium: ShieldQuestion, low: ShieldAlert } as const;

/** The confidence every verdict carries: high, medium or low, with an icon (never color alone). */
export function ConfidenceBadge({ level, className }: { level: ConfidenceLevel; className?: string }) {
  const Icon = ICON[level];
  return (
    <span
      data-testid="confidence-badge"
      data-level={level}
      className={cn("inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-px text-[12.5px] font-medium leading-5 text-ink", TONE[level], className)}
    >
      <Icon className="size-3.5" aria-hidden />
      {CONFIDENCE_LABEL[level]}
      <span className="sr-only">:</span>
    </span>
  );
}
