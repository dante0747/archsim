/** Technology presets: lookup, drag-and-drop payloads, and applying a preset to a node. */
import { type Family, FAMILIES, kindOf } from "@/lib/theme";
import type { Catalog, ComponentType, Preset } from "@/lib/types";
import { useEditor } from "@/store/editor";

/** MIME type of component drags from the technology library onto the canvas. */
export const DND_MIME = "application/x-archsim-component";

/** Drag payload: `type` for a plain component, `type:presetId` for a technology preset. */
export function dndPayload(type: string, presetId?: string | null): string {
  return presetId ? `${type}:${presetId}` : type;
}

export function parseDndPayload(raw: string): { type: string; presetId: string | null } {
  const i = raw.indexOf(":");
  if (i < 0) return { type: raw, presetId: null };
  return { type: raw.slice(0, i), presetId: raw.slice(i + 1) || null };
}

export function findPreset(catalog: Catalog | null, type: string, presetId: string | null | undefined): Preset | null {
  if (!presetId) return null;
  const comp = catalog?.components.find((c) => c.type === type);
  return comp?.presets?.find((p) => p.id === presetId) ?? null;
}

/** One entry of the technology library: a preset, or the generic component itself. */
export interface LibraryItem {
  key: string;
  type: string;
  comp: ComponentType;
  preset: Preset | null;
  name: string;
  technology: string;
  /** Sub-group inside the family, e.g. "Relational" (preset family) or "Generic". */
  group: string;
  description: string;
  tags: string[];
}

export function libraryItems(catalog: Catalog | null): LibraryItem[] {
  const out: LibraryItem[] = [];
  for (const comp of catalog?.components ?? []) {
    for (const p of comp.presets ?? []) {
      out.push({
        key: `${comp.type}:${p.id}`,
        type: comp.type,
        comp,
        preset: p,
        name: p.name,
        technology: p.technology,
        group: p.family,
        description: p.description,
        tags: p.tags ?? [],
      });
    }
    out.push({
      key: comp.type,
      type: comp.type,
      comp,
      preset: null,
      name: `Generic ${comp.displayName.split(" / ")[0].replace(/ \(.*\)$/, "").toLowerCase()}`,
      technology: typeof comp.defaultConfig.technology === "string" ? comp.defaultConfig.technology : "custom",
      group: "Generic",
      description: comp.description,
      // Client parts are found by what they are built with ("ios", "react", "graphql").
      tags: comp.isTrafficSource ? (comp.fields.find((f) => f.key === "technology")?.options ?? []).map((o) => o.replace(/_/g, " ")) : [],
    });
  }
  return out;
}

export function familyOfItem(item: LibraryItem): Family {
  const kind = kindOf(item.type, item.comp.category);
  return FAMILIES.find((f) => f.kinds.includes(kind)) ?? FAMILIES[FAMILIES.length - 1];
}

/** Case-insensitive search over name, technology, group, tags and description; all words must match. */
export function matchesItem(item: LibraryItem, query: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const hay = `${item.name} ${item.technology} ${item.group} ${item.tags.join(" ")} ${item.description} ${item.type}`.toLowerCase();
  return words.every((w) => hay.includes(w));
}

/** Merge several config values into a node as one undoable step. */
export function patchNodeConfig(nodeId: string, patch: Record<string, unknown>): void {
  const st = useEditor.getState();
  if (!st.nodes.some((n) => n.id === nodeId)) return;
  st.pushHistory();
  useEditor.setState((s) => ({
    nodes: s.nodes.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, config: { ...n.data.config, ...structuredClone(patch) } } } : n)),
    dirty: true,
  }));
}

/**
 * The config of a node switched to ``preset``: the preset's values, with every setting another
 * preset of this component sets, and every technology-behaviour setting, put back to its default
 * first, so nothing lingers from the previous technology (e.g. MongoDB's "read from replicas"
 * after switching to PostgreSQL). Settings no preset touches keep the user's values.
 */
export function switchToPreset(config: Record<string, unknown>, comp: ComponentType | undefined, preset: Preset): Record<string, unknown> {
  const out: Record<string, unknown> = { ...config };
  if (comp) {
    const keys = new Set<string>();
    for (const p of comp.presets ?? []) for (const k of Object.keys(p.config)) keys.add(k);
    for (const f of comp.fields) if (f.group === "Technology behaviour") keys.add(f.key);
    for (const k of keys) {
      if (k in preset.config) continue;
      if (k in comp.defaultConfig) out[k] = structuredClone(comp.defaultConfig[k]);
      else delete out[k];
    }
  }
  return { ...out, ...structuredClone(preset.config) };
}

/** Apply a preset to an existing node as one undoable step. */
export function applyPresetToNode(nodeId: string, preset: Preset): void {
  const st = useEditor.getState();
  const node = st.nodes.find((n) => n.id === nodeId);
  if (!node) return;
  const comp = st.catalog?.components.find((c) => c.type === node.data.type);
  st.pushHistory();
  useEditor.setState((s) => ({
    nodes: s.nodes.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, config: switchToPreset(n.data.config, comp, preset) } } : n)),
    dirty: true,
  }));
}
