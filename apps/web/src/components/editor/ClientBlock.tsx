"use client";

/**
 * The client part's own settings, shown above the rest: what it is built with (logo chips) and its
 * share of the traffic, with the split between all client parts.
 */
import { FieldControl } from "@/components/editor/FieldControl";
import { TechIcon } from "@/components/TechIcon";
import { clientShares } from "@/lib/clients";
import { compact } from "@/lib/format";
import { technologyLabel } from "@/lib/theme";
import type { ComponentType, NodeConfig } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

/** Fields this block shows itself (the inspector leaves them out of the settings list). */
export const CLIENT_BLOCK_FIELDS = ["technology", "trafficShare"];

export function ClientBlock({ nodeId, comp, config }: { nodeId: string; comp: ComponentType; config: NodeConfig }) {
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const nodes = useEditor((s) => s.nodes);
  const catalog = useEditor((s) => s.catalog);
  const workload = useEditor((s) => s.workload);
  const techField = comp.fields.find((f) => f.key === "technology");
  const shareField = comp.fields.find((f) => f.key === "trafficShare");
  const shares = clientShares(nodes, catalog);
  const mine = shares.find((s) => s.id === nodeId)?.fraction ?? 1;
  const technology = (config.technology ?? techField?.default) as string | undefined;
  const steady = workload.pattern.kind === "constant";

  return (
    <section aria-label="Client" className="space-y-4">
      {techField && (techField.options?.length ?? 0) > 1 && (
        <div className="space-y-1.5">
          <p id={`${nodeId}-tech`} className="text-[12px] text-ink-3">
            Built with
          </p>
          <div role="radiogroup" aria-labelledby={`${nodeId}-tech`} className="flex flex-wrap gap-1.5">
            {techField.options!.map((o) => {
              const on = o === technology;
              return (
                <button
                  key={o}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  onClick={() => setNodeConfig(nodeId, "technology", o)}
                  className={cn(
                    "flex items-center gap-1.5 rounded-lg border px-2 py-1 text-[12px] transition-colors",
                    on ? "border-accent bg-accent-soft text-ink" : "border-line text-ink-2 hover:border-line-strong hover:text-ink",
                  )}
                >
                  <TechIcon technology={o} kind="traffic" size={15} />
                  {technologyLabel(comp, o)}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {shareField && (
        <div className="space-y-2">
          <FieldControl field={shareField} value={config.trafficShare ?? shareField.default} onChange={(v) => setNodeConfig(nodeId, "trafficShare", v)} idPrefix={`node-${nodeId}`} />
          {shares.length > 1 ? (
            <div className="space-y-1">
              <div className="flex h-2 gap-px overflow-hidden rounded-full" aria-hidden>
                {shares.map((s) => (
                  <span key={s.id} style={{ width: `${s.fraction * 100}%`, background: s.id === nodeId ? "var(--fam-traffic)" : "var(--line-strong)" }} />
                ))}
              </div>
              <p className="text-[12px] text-ink-2">
                Sends <span className="font-semibold text-ink">{Math.round(mine * 100)}%</span> of the traffic
                {steady ? ` · about ${compact(workload.rps * mine)} req/s` : ""}. Shares are relative across the {shares.length} client parts.
              </p>
            </div>
          ) : (
            <p className="text-[12px] text-ink-3">The only client part, so it sends all the traffic. Add a mobile app, browser or API consumers to split it.</p>
          )}
        </div>
      )}
    </section>
  );
}
