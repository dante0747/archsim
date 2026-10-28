"use client";

/**
 * Resource specs of a part (CPU cores, memory, storage per instance): three compact inputs, common
 * sizes as one-click chips, and an illustrative price for the size. An empty spec is "Auto": the
 * size the part needs for the design's peak load (lib/sizing.ts), used in runs, prices and exports.
 */
import { ChevronRight, Cpu, HardDrive, type LucideIcon, MemoryStick, Sparkles } from "lucide-react";
import { useId, useState } from "react";
import { patchNodeConfig } from "@/components/build/presets";
import { validateNumber } from "@/components/editor/FieldControl";
import { Input } from "@/components/ui/input";
import { formatUsd, priceOrigin } from "@/lib/cost";
import type { ComponentType, FieldDescriptor, NodeConfig, SpecKey } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { SPEC_KEYS, useAutoSize, useTargetCpu } from "@/store/sizes";

/** The specs this block has inputs for. */
const SPEC_FIELDS = ["cpuCores", "memoryGib", "storageGib"];
/** Fields this block shows itself (the inspector leaves them out of the settings list): the specs and
 * the instance type they run on (schema 1.4), which shows as the size's SKU. */
export const RESOURCE_FIELDS = [...SPEC_FIELDS, "instanceType"];

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

/** Number input where an empty box means "auto" (null): it shows the auto size instead. */
function SpecInput({
  field,
  value,
  auto,
  onCommit,
}: {
  field: FieldDescriptor;
  value: number | null;
  auto: number | null;
  onCommit: (v: number | null) => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? (value == null ? "" : String(value));
  const error = draft === null || draft.trim() === "" ? null : validateNumber(field, Number(draft));
  const Icon = ICON[field.key];
  const id = `spec-${field.key}-${useId()}`;
  return (
    <div className="min-w-0 space-y-1">
      <label htmlFor={id} className="flex items-center gap-1 text-xs text-ink-3">
        <Icon className="size-3.5" aria-hidden /> {NAME[field.key] ?? field.label}
      </label>
      <div className="relative">
        <Input
          id={id}
          type="number"
          inputMode="decimal"
          step={field.step ?? "any"}
          value={text}
          placeholder={auto != null ? String(auto) : "not set"}
          aria-invalid={error ? true : undefined}
          aria-describedby={auto != null && value == null ? `${id}-auto` : undefined}
          className={cn("tabular pr-10", auto != null ? "placeholder:text-ink-2" : "placeholder:text-ink-3/70")}
          onFocus={() => setDraft(value == null ? "" : String(value))}
          onBlur={() => setDraft(null)}
          onChange={(e) => {
            const raw = e.target.value;
            setDraft(raw);
            if (raw.trim() === "") onCommit(null);
            else if (!validateNumber(field, Number(raw))) onCommit(Number(raw));
          }}
        />
        <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-xs text-ink-3">{field.unit}</span>
      </div>
      {auto != null && value == null && (
        <p id={`${id}-auto`} className="flex items-center gap-1 text-[11px] text-accent">
          <Sparkles className="size-3" aria-hidden /> auto
        </p>
      )}
      {error && (
        <p role="alert" className="text-xs text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}

export function ResourceBlock({ nodeId, comp, config }: { nodeId: string; comp: ComponentType; config: NodeConfig }) {
  const setNodeConfig = useEditor((s) => s.setNodeConfig);
  const pricing = useEditor((s) => s.analysis?.cost.pricing ?? null);
  const priceProvenance = useEditor((s) => s.extras?.provenance?.[`nodes/${nodeId}/costPerHour`]?.kind ?? null);
  const size = useAutoSize(nodeId);
  const targetCpu = useTargetCpu();
  const fields = SPEC_FIELDS.map((k) => comp.fields.find((f) => f.key === k)).filter((f): f is FieldDescriptor => !!f);
  if (!fields.length) return null;
  const num = (k: string) => (typeof config[k] === "number" ? (config[k] as number) : null);
  const cpu = num("cpuCores");
  const mem = num("memoryGib");
  const storage = num("storageGib");
  const autoOf = (k: string): number | null =>
    size && !size.skipped && (SPEC_KEYS as string[]).includes(k) ? (size.auto[k as (typeof SPEC_KEYS)[number]] ?? null) : null;
  // the engine prices the size a run uses: what is set, else the auto size
  const price = size?.pricePerHour ?? null;
  const sku = size?.sku ?? null;
  const autoUsed = fields.filter((f) => num(f.key) == null && autoOf(f.key) != null);
  const hasCost = comp.fields.some((f) => f.key === "costPerHour");
  // The cost estimate uses a price the user set on the part; without one, this size's price. A
  // technology preset's price is illustrative: a price snapshot reprices it, generic rates keep it.
  const origin = priceOrigin(comp, config, priceProvenance);
  const ownPrice = origin === "set" ? (config.costPerHour as number) : null;
  const snapshotPriced = !!(sku && pricing && !pricing.illustrative);
  const presetPrice = origin === "preset" && !snapshotPriced ? (config.costPerHour as number) : null;
  const otherPrice = ownPrice ?? presetPrice;
  // an instance type set on the part (schema 1.4): kept while it covers the size, so picking a
  // size or going back to auto clears it
  const hasType = comp.fields.some((f) => f.key === "instanceType");
  const typeSet = hasType && typeof config.instanceType === "string" && config.instanceType !== "";
  const clearType = hasType ? { instanceType: null } : {};
  const anySet = cpu != null || mem != null || storage != null || typeSet;

  return (
    <section aria-label="Resources" className="space-y-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-semibold text-ink">Resources</h3>
        <span className="text-xs text-ink-3">{PER[comp.type] ?? "per instance"}</span>
      </div>
      <div className={cn("grid gap-2", fields.length === 3 ? "grid-cols-3" : "grid-cols-2")}>
        {fields.map((f) => (
          <SpecInput key={f.key} field={f} value={num(f.key)} auto={autoOf(f.key)} onCommit={(v) => setNodeConfig(nodeId, f.key, v)} />
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
              onClick={() => patchNodeConfig(nodeId, { cpuCores: c, memoryGib: m, ...clearType })}
              className={cn(
                "rounded-md border px-1.5 py-0.5 font-mono text-xs transition-colors",
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
            onClick={() => patchNodeConfig(nodeId, { ...Object.fromEntries(fields.map((f) => [f.key, null])), ...clearType })}
            className="rounded-md px-1.5 py-0.5 text-xs text-ink-3 hover:text-ink"
            title="Clear the values you set and use the auto size"
          >
            {size && !size.skipped ? "Back to auto" : "Clear"}
          </button>
        )}
      </div>
      {price != null && (
        <p className="flex flex-wrap items-center gap-x-1.5 text-xs text-ink-2">
          <span>
            {sku && pricing && !pricing.illustrative ? (
              <>
                <span className="font-mono text-ink">{sku}</span>, <span className="font-semibold text-ink">{formatUsd(price)}/h</span>
                <span className="text-ink-3">
                  {" "}
                  (on-demand, {pricing.provider} {pricing.region}, prices of {pricing.snapshotDate})
                </span>
              </>
            ) : (
              <>
                About <span className="font-semibold text-ink">{formatUsd(price)}/h</span> for this size
                <span className="text-ink-3"> (illustrative on-demand price)</span>
              </>
            )}
            {hasCost &&
              (otherPrice == null ? (
                <span className="text-ink-3">; the cost estimate uses it.</span>
              ) : otherPrice === price ? (
                <span className="text-ink-3">; used as its cost.</span>
              ) : ownPrice != null ? (
                <span className="text-ink-3">; the cost estimate uses your price of {formatUsd(ownPrice)}/h.</span>
              ) : (
                <span className="text-ink-3">; the cost estimate uses the technology preset&apos;s illustrative price of {formatUsd(otherPrice)}/h.</span>
              ))}
          </span>
          {hasCost && otherPrice != null && otherPrice !== price && (
            <button type="button" onClick={() => setNodeConfig(nodeId, "costPerHour", null)} className="font-medium text-accent hover:underline">
              Use size price
            </button>
          )}
        </p>
      )}
      {size?.needText && !size.skipped && SPEC_FIELDS.some((k) => size.needText?.[k as SpecKey]) && (
        <ul className="space-y-0.5 text-xs leading-relaxed text-ink-2" data-testid="need-vs-instance" aria-label="Needs and instance">
          {SPEC_FIELDS.filter((k) => size.needText?.[k as SpecKey]).map((k) => (
            <li key={k}>
              <span className="text-ink-3">{NAME[k]}:</span> {size.needText?.[k as SpecKey]}
            </li>
          ))}
        </ul>
      )}
      {size?.skipped && <p className="text-xs text-ink-3">{size.skipped} Nothing is filled in automatically.</p>}
      {autoUsed.length > 0 && size && (
        <details className="group rounded-lg bg-accent-soft/40 px-2.5 py-2 text-xs text-ink-2">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 [&::-webkit-details-marker]:hidden">
            <ChevronRight className="size-3.5 shrink-0 transition-transform group-open:rotate-90" aria-hidden />
            <span>
              <span className="font-medium text-ink">Auto-sized</span> for about {Math.round(size.peakRps).toLocaleString("en-US")} req/s at peak
              {targetCpu != null && <> ({Math.round(targetCpu * 100)}% CPU target)</>}. Type
              a value to set your own.
            </span>
          </summary>
          <ul className="mt-1.5 space-y-1 pl-5">
            {autoUsed.map((f) => (
              <li key={f.key}>
                <span className="font-medium text-ink">
                  {NAME[f.key] ?? f.label} {autoOf(f.key)} {f.unit}
                </span>
                : {size.why[f.key as (typeof SPEC_KEYS)[number]]}
              </li>
            ))}
          </ul>
        </details>
      )}
      <p className="text-xs leading-relaxed text-ink-3">{EFFECT[comp.type] ?? INFO_ONLY}</p>
    </section>
  );
}
