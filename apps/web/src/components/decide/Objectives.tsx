"use client";

/**
 * The objectives a design is judged by, as tiles: the run's value against the target on a bullet
 * bar, with the target edited in place. A missing target is an invitation to add one.
 */
import { Check, Pencil, Plus, X } from "lucide-react";
import { useState } from "react";
import { type SloCheck, type SloKey, formatSloValue, objectiveKeys } from "@/lib/slo";
import type { Objectives } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { StatusMark } from "./shared";

const OBJECTIVE_META: Record<SloKey, { field: keyof Objectives; label: string; unit: string; hint: string; toInput: (v: number) => number; fromInput: (v: number) => number; placeholder: string }> = {
  p95: { field: "latencyP95Ms", label: "Latency p95", unit: "ms", hint: "95% of successful requests are faster", toInput: (v) => v, fromInput: (v) => v, placeholder: "300" },
  p99: { field: "latencyP99Ms", label: "Latency p99", unit: "ms", hint: "the slowest 1% start here", toInput: (v) => v, fromInput: (v) => v, placeholder: "800" },
  availability: {
    field: "availability",
    label: "Availability",
    unit: "%",
    hint: "share of requests that succeed",
    toInput: (v) => Math.round(v * 100_000) / 1000,
    fromInput: (v) => v / 100,
    placeholder: "99.9",
  },
  throughput: { field: "minThroughputRps", label: "Throughput", unit: "req/s", hint: "successful requests per second", toInput: (v) => v, fromInput: (v) => v, placeholder: "500" },
  messageAge: {
    field: "messageAgeP95Ms",
    label: "Message age p95",
    unit: "s",
    hint: "how old a message is when processed, at the slowest queue",
    toInput: (v) => v / 1000,
    fromInput: (v) => Math.round(v * 1000),
    placeholder: "10",
  },
};

/** The objectives this design is judged by: a message's age too when it has a queue (ADR-0084). */
export function useObjectiveKeys(objectives: Objectives): SloKey[] {
  const hasQueue = useEditor((s) => s.nodes.some((n) => n.data.type === "message_queue"));
  return objectiveKeys(hasQueue, objectives);
}

function validObjective(key: SloKey, v: number): string | null {
  if (!Number.isFinite(v)) return "Enter a number";
  if (key === "availability") return v <= 0 || v > 100 ? "Between 0 and 100" : null;
  return v <= 0 ? "Must be above 0" : null;
}

/** Inline editor for one objective target (Enter saves, Esc cancels, empty clears). */
function TargetEditor({ k, value, onDone }: { k: SloKey; value: number | null; onDone: () => void }) {
  const meta = OBJECTIVE_META[k];
  const setObjectives = useEditor((s) => s.setObjectives);
  const [text, setText] = useState(value == null ? "" : String(meta.toInput(value)));
  const parsed = text.trim() === "" ? null : Number(text);
  const error = parsed == null ? null : validObjective(k, parsed);
  const commit = () => {
    if (error) return;
    setObjectives({ [meta.field]: parsed == null ? null : meta.fromInput(parsed) } as Partial<Objectives>, meta.field);
    onDone();
  };
  return (
    <div className="flex flex-wrap items-center gap-1">
      <input
        autoFocus
        inputMode="decimal"
        value={text}
        placeholder={meta.placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") commit();
          if (e.key === "Escape") onDone();
        }}
        aria-label={`${meta.label} target`}
        aria-invalid={!!error}
        className="h-7 w-20 rounded-sm border border-line bg-surface px-2 font-mono text-xs text-ink focus-visible:border-accent focus-visible:outline-none aria-[invalid=true]:border-critical"
      />
      <span className="font-mono text-xs text-ink-3">{meta.unit}</span>
      <button type="button" onClick={commit} disabled={!!error} className="rounded-sm p-1 text-ink-2 hover:bg-surface-3 hover:text-ink disabled:opacity-40" aria-label="Save target">
        <Check className="size-3.5" />
      </button>
      <button type="button" onClick={onDone} className="rounded-sm p-1 text-ink-3 hover:bg-surface-3 hover:text-ink" aria-label="Cancel">
        <X className="size-3.5" />
      </button>
      {error && <span className="w-full font-mono text-[11px] text-danger-ink">{error}</span>}
    </div>
  );
}

/**
 * Bullet bar: the measured value against the target tick, on a scale that fits both.
 * Availability is shown as the failure share against the failures the target allows.
 */
function Bullet({ check }: { check: SloCheck }) {
  let value = check.actual ?? 0;
  let target = check.target;
  if (check.key === "availability") {
    value = check.actual == null ? 0 : 1 - check.actual;
    target = 1 - check.target;
  }
  const max = Math.max(value, target, 1e-9) * 1.18;
  const color = check.pass === true ? "var(--status-good)" : check.pass === false ? "var(--status-critical)" : "var(--ink-3)";
  return (
    <div className="relative mt-2.5 h-1.5" aria-hidden>
      <div className="absolute inset-0 rounded-full bg-surface-3" />
      {check.actual != null && <div className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${(value / max) * 100}%`, background: color }} />}
      <div className="absolute -inset-y-1 w-0.5 rounded-full bg-ink" style={{ left: `calc(${(target / max) * 100}% - 1px)` }} />
    </div>
  );
}

/** One objective with a target: the run's value against it, the target edited in place. */
function ObjectiveTile({ k, check, objectives }: { k: SloKey; check: SloCheck | undefined; objectives: Objectives }) {
  const meta = OBJECTIVE_META[k];
  const [editing, setEditing] = useState(false);
  const target = objectives[meta.field] ?? null;
  const tone = check?.pass === false ? "border-[color:color-mix(in_srgb,var(--status-critical)_45%,var(--line))]" : "border-line";
  return (
    <div role="group" aria-label={meta.label} className={cn("flex min-h-[104px] flex-col rounded-xl border bg-surface p-3.5", tone)}>
      <div className="flex items-center justify-between gap-2">
        <p className="min-w-0 truncate text-[12.5px] font-medium text-ink-2" title={meta.hint}>
          {meta.label}
        </p>
        {check && <StatusMark pass={check.pass} />}
      </div>
      <p className="tabular mt-1 text-[22px] font-semibold leading-7 tracking-tight text-ink">{check ? formatSloValue(k, check.actual) : "–"}</p>
      {check && <Bullet check={check} />}
      <div className="mt-auto flex min-h-7 items-center justify-between gap-2 pt-2">
        {editing ? (
          <TargetEditor k={k} value={target} onDone={() => setEditing(false)} />
        ) : (
          <>
            <span className="tabular text-xs text-ink-3">
              target {k === "availability" || k === "throughput" ? "≥" : "≤"} {target == null ? "–" : formatSloValue(k, target)}
            </span>
            <button type="button" onClick={() => setEditing(true)} className="rounded-sm p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label={`Edit ${meta.label} target`}>
              <Pencil className="size-3.5" />
            </button>
          </>
        )}
      </div>
    </div>
  );
}

/** The objectives not set yet, in one tile: pick one to give it a target. */
function AddObjective({ keys }: { keys: SloKey[] }) {
  const [adding, setAdding] = useState<SloKey | null>(null);
  return (
    <div className="flex min-h-[104px] flex-col justify-center gap-2 rounded-xl border border-dashed border-line-strong p-3.5">
      {adding ? (
        <>
          <p className="text-[12.5px] font-medium text-ink-2">{OBJECTIVE_META[adding].label}</p>
          <TargetEditor k={adding} value={null} onDone={() => setAdding(null)} />
        </>
      ) : (
        <>
          <p className="text-[12.5px] font-medium text-ink-3">Add an objective</p>
          <div className="flex flex-wrap gap-1.5">
            {keys.map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setAdding(k)}
                title={OBJECTIVE_META[k].hint}
                className="inline-flex items-center gap-1 rounded-full border border-line bg-surface px-2 py-0.5 text-xs font-medium text-ink-2 transition-colors hover:border-accent/60 hover:text-ink"
              >
                <Plus className="size-3 text-accent" aria-hidden /> {OBJECTIVE_META[k].label}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/** The objectives with a target as tiles, then one tile to add the others. */
export function ObjectivesGrid({ keys, checks, objectives }: { keys: SloKey[]; checks: SloCheck[]; objectives: Objectives }) {
  const set = keys.filter((k) => objectives[OBJECTIVE_META[k].field] != null);
  const unset = keys.filter((k) => objectives[OBJECTIVE_META[k].field] == null);
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {set.map((k) => (
        <ObjectiveTile key={k} k={k} check={checks.find((c) => c.key === k)} objectives={objectives} />
      ))}
      {unset.length > 0 && <AddObjective keys={unset} />}
    </div>
  );
}
