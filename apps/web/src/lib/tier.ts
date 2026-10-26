/** Technology tiers and where "auto" defaults come from, read from the engine's catalog. */
import type { ComponentType, Tier } from "@/lib/types";

/** The tier of a technology of this component, or null for parts without technology profiles. */
export function tierOf(comp: ComponentType | undefined, technology: unknown): Tier | null {
  if (typeof technology !== "string") return null;
  return comp?.technologyTiers?.[technology] ?? null;
}

/** A part's tier: its technology, or its type's default technology when it sets none. */
export function partTier(comp: ComponentType | undefined, config: Record<string, unknown> | undefined): Tier | null {
  return tierOf(comp, config?.technology ?? comp?.defaultConfig?.technology);
}

/** The documentation link behind a technology's "auto" default, or null when it is illustrative. */
export function defaultSource(comp: ComponentType | undefined, field: string, technology: unknown): string | null {
  if (typeof technology !== "string") return null;
  return comp?.technologySources?.[field]?.[technology] ?? null;
}

/** The badge's tooltip: the engine's wording, or a plain fallback for an older API. */
export function tierNote(note: string | undefined): string {
  return note ?? "Not yet validated against measurements.";
}
