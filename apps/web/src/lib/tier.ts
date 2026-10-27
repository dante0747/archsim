/** Technology tiers and where "auto" defaults come from, read from the engine's catalog. */
import type { ArchitectureDocument, Catalog, ComponentType, Tier } from "@/lib/types";

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

/** Whether a technology is offered when adding parts: experimental ones only with Labs on. */
export function offeredWithLabs(tier: Tier | null | undefined, labs: boolean): boolean {
  return labs || tier !== "experimental";
}

/** Labels of a design's parts that use an experimental technology (empty without a catalog). */
export function experimentalParts(doc: ArchitectureDocument, catalog: Catalog | null): string[] {
  if (!catalog) return [];
  const byType = new Map(catalog.components.map((c) => [c.type, c]));
  return doc.architecture.nodes
    .filter((n) => partTier(byType.get(n.type), n.config) === "experimental")
    .map((n) => n.label || n.id);
}

/** The badge's tooltip: the engine's wording, or a plain fallback for an older API. */
export function tierNote(note: string | undefined): string {
  return note ?? "Not yet validated against measurements.";
}
