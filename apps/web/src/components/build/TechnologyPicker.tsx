"use client";

/** Inspector header control: shows a node's technology and re-applies a technology preset. */
import { toast } from "sonner";
import { applyPresetToNode } from "@/components/build/presets";
import { technologyLabel } from "@/lib/theme";
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
    <div className="mt-2.5 space-y-1.5 border-t border-dashed border-line-strong pt-2.5">
      <div className="flex items-center gap-2">
        <span className="microlabel shrink-0">technology</span>
        {tech && <span className="truncate rounded-sm border border-line bg-surface px-1.5 font-mono text-[11px] text-ink">{tech}</span>}
        {current && <span className="truncate font-mono text-[10px] text-ink-3">{current.family.toLowerCase()}</span>}
      </div>
      {presets.length > 0 && (
        <select
          value=""
          onChange={(e) => apply(e.target.value)}
          aria-label="Apply a technology preset"
          className="h-7 w-full cursor-pointer rounded-md border border-line bg-surface px-2 font-mono text-[11px] text-ink-2 hover:border-line-strong focus-visible:border-accent focus-visible:outline-none"
        >
          <option value="" disabled>
            {current ? "Switch technology…" : "Apply a technology preset…"}
          </option>
          {families.map((f) => (
            <optgroup key={f} label={f}>
              {presets
                .filter((p) => p.family === f)
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                    {current?.id === p.id ? "  (current)" : ""}
                  </option>
                ))}
            </optgroup>
          ))}
        </select>
      )}
      {current && <p className="text-[11px] leading-4 text-ink-2">{current.description}</p>}
      <p className="text-[10.5px] leading-4 text-ink-3">Preset numbers are assumptions to start from, not vendor benchmarks. Tune them to what you measure.</p>
    </div>
  );
}
