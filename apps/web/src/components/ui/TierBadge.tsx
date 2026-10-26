"use client";

/** The "experimental" badge of a technology, with the engine's explanation as its tooltip. */
import { FlaskConical } from "lucide-react";
import { Tip } from "@/components/ui/misc";
import { tierNote } from "@/lib/tier";
import type { Tier } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

/** The tooltip text, from the catalog the editor loaded. */
export function useTierNote(): string {
  return tierNote(useEditor((s) => s.catalog?.tierNote));
}

/**
 * Shown only for experimental technologies (core ones need no badge). ``pill`` is focusable with
 * a tooltip; ``tile`` is a plain line for inside another control (a parts-library tile, whose
 * own native tooltip carries the explanation).
 */
export function TierBadge({ tier, variant = "pill", className }: { tier: Tier | null | undefined; variant?: "pill" | "tile"; className?: string }) {
  const note = useTierNote();
  if (tier !== "experimental") return null;
  if (variant === "tile") {
    return (
      <span className={cn("inline-flex items-center gap-0.5 text-[10px] leading-3 text-ink-3", className)}>
        <FlaskConical className="size-2.5 text-warning" aria-hidden />
        experimental
      </span>
    );
  }
  return (
    <Tip content={`Experimental: ${note}`}>
      <span
        tabIndex={0}
        aria-label={`Experimental technology: ${note}`}
        className={cn(
          "inline-flex cursor-help items-center gap-1 whitespace-nowrap rounded-full bg-warning-soft px-1.5 text-[10.5px] font-medium leading-4 text-ink-2 focus-visible:outline-2 focus-visible:outline-accent",
          className,
        )}
      >
        <FlaskConical className="size-3 text-warning" aria-hidden />
        experimental
      </span>
    </Tip>
  );
}
