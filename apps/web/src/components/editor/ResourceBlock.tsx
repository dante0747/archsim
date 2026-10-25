"use client";

/**
 * Resource specs of a part (CPU cores, memory, storage per instance): three compact inputs, common
 * sizes as one-click chips, and an illustrative price for the size. Empty means "not modelled".
 */
import { Cpu, HardDrive, type LucideIcon, MemoryStick } from "lucide-react";
import { useId, useState } from "react";
import { patchNodeConfig } from "@/components/build/presets";
import { validateNumber } from "@/components/editor/FieldControl";
import { Input } from "@/components/ui/input";
import { formatUsd, sizePricePerHour } from "@/lib/cost";
import type { ComponentType, FieldDescriptor, NodeConfig } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

/** Fields this block shows itself (the inspector leaves them out of the settings list). */
export const RESOURCE_FIELDS = ["cpuCores", "memoryGib", "storageGib"];

const PER: Record<string, string> = {
  service: "per replica",
  database: "per instance",
  cache: "per node",
  load_balancer: "per instance",
  message_queue: "per broker",
};

const EFFECT: Record<string, string> = {
  service: "With fewer cores than workers, CPU work waits for a core. Memory limits how many requests a replica can hold.",
  database: "Cores serve the CPU share of each query. Memory acts as buffer cache once a hot working set is set. Storage is checked against the data size.",
  cache: "Cores run commands. Memory caps the keys the TTL model can hold; the least recently used are evicted.",
};
const INFO_ONLY = "Shown on the design and in price estimates; it does not change the simulation.";

/** Common instance sizes: vCPU and GiB. */
const SIZES: [number, number][] = [
  [1, 2],
  [2, 4],
  [2, 8],
  [4, 16],
  [8, 32],
  [16, 64],
];

const ICON: Record<string, LucideIcon> = { cpuCores: Cpu, memoryGib: MemoryStick, storageGib: HardDrive };
const NAME: Record<string, string> = { cpuCores: "CPU", memoryGib: "Memory", storageGib: "Storage" };

/** Number input where an empty box means "not set" (null). */
function SpecInput({ field, value, onCommit }: { field: FieldDescriptor; value: number | null; onCommit: (v: number | null) => void }) {
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? (value == null ? "" : String(value));
  const error = draft === null || draft.trim() === "" ? null : validateNumber(field, Number(draft));
  const Icon = ICON[field.key];
  const id = `spec-${field.key}-${useId()}`;
  return (
    <div className="min-w-0 space-y-1">
      <label htmlFor={id} className="flex items-center gap-1 text-[11.5px] text-ink-3">
        <Icon className="size-3.5" aria-hidden /> {NAME[field.key] ?? field.label}
      </label>
      <div className="relative">
        <Input
          id={id}
          type="number"
          inputMode="decimal"
          step={field.step ?? "any"}
          value={text}
          placeholder="not set"
          aria-invalid={error ? true : undefined}
          className="tabular pr-10 placeholder:text-ink-3/70"
          onFocus={() => setDraft(value == null ? "" : String(value))}
          onBlur={() => setDraft(null)}
          onChange={(e) => {
            const raw = e.target.value;
            setDraft(raw);
            if (raw.trim() === "") onCommit(null);
            else if (!validateNumber(field, Number(raw))) onCommit(Number(raw));
          }}
        />
        <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-[11px] text-ink-3">{field.unit}</span>
      </div>
      {error && (
        <p role="alert" className="text-[11px] text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}

export function ResourceBlock({ nodeId, comp, config }: { nodeId: string; comp: ComponentType; config: NodeConfig }) {
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const fields = RESOURCE_FIELDS.map((k) => comp.fields.find((f) => f.key === k)).filter((f): f is FieldDescriptor => !!f);
  if (!fields.length) return null;
  const num = (k: string) => (typeof config[k] === "number" ? (config[k] as number) : null);
  const cpu = num("cpuCores");
  const mem = num("memoryGib");
  const storage = num("storageGib");
  const price = sizePricePerHour(cpu, mem, storage);
  const hasCost = comp.fields.some((f) => f.key === "costPerHour");
  // The cost estimate uses a price set on the part; without one, this size's price.
  const ownPrice = typeof config.costPerHour === "number" && config.costPerHour !== comp.defaultConfig.costPerHour ? config.costPerHour : null;
  const anySet = cpu != null || mem != null || storage != null;

  return (
    <section aria-label="Resources" className="space-y-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-semibold text-ink">Resources</h3>
        <span className="text-[11.5px] text-ink-3">{PER[comp.type] ?? "per instance"}</span>
      </div>
      <div className={cn("grid gap-2", fields.length === 3 ? "grid-cols-3" : "grid-cols-2")}>
        {fields.map((f) => (
          <SpecInput key={f.key} field={f} value={num(f.key)} onCommit={(v) => setNodeConfig(nodeId, f.key, v)} />
        ))}
      </div>
      <div className="flex flex-wrap gap-1" role="group" aria-label="Common sizes">
        {SIZES.map(([c, m]) => {
          const on = cpu === c && mem === m;
          return (
            <button
              key={`${c}-${m}`}
              type="button"
              aria-pressed={on}
              onClick={() => patchNodeConfig(nodeId, { cpuCores: c, memoryGib: m })}
              className={cn(
                "rounded-md border px-1.5 py-0.5 font-mono text-[11px] transition-colors",
                on ? "border-accent bg-accent-soft text-ink" : "border-line text-ink-2 hover:border-line-strong hover:text-ink",
              )}
            >
              {c}·{m}
            </button>
          );
        })}
        {anySet && (
          <button
            type="button"
            onClick={() => patchNodeConfig(nodeId, Object.fromEntries(fields.map((f) => [f.key, null])))}
            className="rounded-md px-1.5 py-0.5 text-[11px] text-ink-3 hover:text-ink"
          >
            Clear
          </button>
        )}
      </div>
      {price != null && (
        <p className="flex flex-wrap items-center gap-x-1.5 text-[11.5px] text-ink-2">
          <span>
            About <span className="font-semibold text-ink">{formatUsd(price)}/h</span> for this size
            <span className="text-ink-3"> (illustrative on-demand price)</span>
            {hasCost &&
              (ownPrice == null ? (
                <span className="text-ink-3">; the cost estimate uses it.</span>
              ) : ownPrice === price ? (
                <span className="text-ink-3">; used as its cost.</span>
              ) : (
                <span className="text-ink-3">; the cost estimate uses your price of {formatUsd(ownPrice)}/h.</span>
              ))}
          </span>
          {hasCost && ownPrice != null && ownPrice !== price && (
            <button type="button" onClick={() => setNodeConfig(nodeId, "costPerHour", null)} className="font-medium text-accent hover:underline">
              Use size price
            </button>
          )}
        </p>
      )}
      <p className="text-[11.5px] leading-relaxed text-ink-3">{EFFECT[comp.type] ?? INFO_ONLY}</p>
    </section>
  );
}
