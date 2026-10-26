"use client";

/** Inspector header control: shows a node's technology and re-applies a technology preset. */
import { toast } from "sonner";
import { applyPresetToNode } from "@/components/build/presets";
import { TierBadge } from "@/components/ui/TierBadge";
import { technologyLabel } from "@/lib/theme";
import { partTier } from "@/lib/tier";
import type { ComponentType, NodeConfig, Preset } from "@/lib/types";

export function TechnologyPicker({ nodeId, comp, config }: { nodeId: string; comp: ComponentType | undefined; config: NodeConfig }) {
  const presets = comp?.presets ?? [];
  const tech = technologyLabel(comp, config.technology);
  const current: Preset | undefined =
    presets.find((p) => p.technology === config.technology && Object.entries(p.config).every(([k, v]) => JSON.stringify(config[k]) === JSON.stringify(v))) ??
    presets.find((p) => p.technology === config.technology);
  if (!presets.length && !tech) return null;

  const families = [...new Set(presets.map((p) => p.family))];
  const apply = (id: string) => {
    const p = presets.find((x) => x.id === id);
    if (!p) return;
    applyPresetToNode(nodeId, p);
    toast.success(`Applied ${p.name} defaults`, { description: "Settings now follow this technology preset. Undo with Ctrl Z." });
  };

  return (
    <div className="mt-3 space-y-1">
      <p className="flex items-center gap-1.5 text-xs text-ink-3">
        Technology{tech ? <span className="font-medium text-ink">{tech}</span> : null}
        <TierBadge tier={partTier(comp, config)} />
      </p>
      {presets.length > 0 && (
        <select
          value=""
          onChange={(e) => apply(e.target.value)}
          aria-label="Apply a technology preset"
          className="h-8 w-full cursor-pointer rounded-md border border-line bg-surface-2/70 px-2 text-[13px] text-ink-2 hover:border-line-strong focus-visible:border-accent focus-visible:outline-none"
        >
          <option value="" disabled>
            {current ? "Switch technology…" : "Pick a technology…"}
          </option>
          {families.map((f) => (
            <optgroup key={f} label={f}>
              {presets
                .filter((p) => p.family === f)
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                    {p.tier === "experimental" ? " · experimental" : ""}
                    {current?.id === p.id ? "  (current)" : ""}
                  </option>
                ))}
            </optgroup>
          ))}
        </select>
      )}
    </div>
  );
}
