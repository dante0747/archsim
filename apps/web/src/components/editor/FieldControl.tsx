"use client";

import { ChevronDown, Info } from "lucide-react";
import { useId, useState } from "react";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Switch, Tip } from "@/components/ui/misc";
import {
  DISTRIBUTION_KINDS,
  convertDistribution,
  describeDistribution,
  isDistribution,
  meanOf,
  validateDistribution,
} from "@/lib/distributions";
import { fmtNum } from "@/lib/format";
import { technologyLabel } from "@/lib/theme";
import type { Distribution, FieldDescriptor, RequestType } from "@/lib/types";
import { cn } from "@/lib/utils";

export function humanize(option: string): string {
  const special: Record<string, string> = { ttl_keyspace: "TTL keyspace", fixed_ratio: "Fixed ratio" };
  if (special[option]) return special[option];
  const s = option.replace(/_/g, " ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function isFieldVisible(field: FieldDescriptor, values: Record<string, unknown>): boolean {
  if (!field.visibleWhen) return true;
  return Object.entries(field.visibleWhen).every(([k, v]) => values[k] === v);
}

export function AssumptionTag() {
  return (
    <Tip content="≈ Assumption: your estimate, not a measurement. Calibrate it against real telemetry before trusting absolute numbers.">
      <span
        aria-label="assumption"
        className="cursor-help rounded-md px-1 font-mono text-[12px] font-bold leading-4 text-accent"
        style={{ background: "color-mix(in srgb, var(--accent) 14%, transparent)" }}
      >
        ≈
      </span>
    </Tip>
  );
}

function nullLabel(field: FieldDescriptor): string {
  if (field.key.toLowerCase().includes("timeout")) return "No limit";
  if (field.key === "drainS") return "Automatic";
  return "None";
}

function fallbackValue(field: FieldDescriptor): number {
  if (typeof field.default === "number") return field.default;
  if (field.key.toLowerCase().includes("timeout")) return 1000;
  return field.min ?? 0;
}

export function validateNumber(field: Pick<FieldDescriptor, "kind" | "min" | "max" | "exclusiveMin">, v: number): string | null {
  if (!Number.isFinite(v)) return "Enter a number";
  if (field.kind === "integer" && !Number.isInteger(v)) return "Must be a whole number";
  if (field.min != null && (field.exclusiveMin ? v <= field.min : v < field.min))
    return field.exclusiveMin ? `Must be > ${field.min}` : `Must be ≥ ${field.min}`;
  if (field.max != null && v > field.max) return `Must be ≤ ${fmtNum(field.max)}`;
  return null;
}

/** Number input that keeps the user's text while typing and only commits valid values. */
export function NumberInput({
  id,
  value,
  onCommit,
  validate,
  unit,
  step,
  ariaLabel,
  className,
}: {
  id?: string;
  value: number;
  onCommit: (v: number) => void;
  validate: (v: number) => string | null;
  unit?: string;
  step?: number;
  ariaLabel?: string;
  className?: string;
}) {
  // While focused we show the user's draft text (which may be temporarily invalid);
  // otherwise the committed value.
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? String(value);
  const error = draft === null ? null : draft.trim() === "" ? "Enter a number" : validate(Number(draft));
  return (
    <div className={cn("min-w-0", className)}>
      <div className="relative">
        <Input
          id={id}
          type="number"
          inputMode="decimal"
          step={step ?? "any"}
          value={text}
          aria-label={ariaLabel}
          aria-invalid={error ? true : undefined}
          className={cn("tabular", unit && "pr-12")}
          onFocus={() => setDraft(String(value))}
          onBlur={() => setDraft(null)}
          onChange={(e) => {
            setDraft(e.target.value);
            const v = Number(e.target.value);
            const err = e.target.value.trim() === "" ? "Enter a number" : validate(v);
            if (!err) onCommit(v);
          }}
        />
        {unit && (
          <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-xs text-ink-3">
            {unit}
          </span>
        )}
      </div>
      {error && (
        <p role="alert" className="mt-1 text-[11px] text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
  labelledBy,
}: {
  value: T;
  options: T[];
  onChange: (v: T) => void;
  labelledBy?: string;
}) {
  return (
    <div role="radiogroup" aria-labelledby={labelledBy} className="flex rounded-md border border-line bg-surface-2 p-0.5">
      {options.map((o) => (
        <button
          key={o}
          type="button"
          role="radio"
          aria-checked={value === o}
          onClick={() => onChange(o)}
          className={cn(
            "h-7 min-w-0 flex-1 truncate rounded px-2 text-xs font-medium",
            value === o ? "bg-surface text-ink shadow-sm" : "text-ink-2 hover:text-ink",
          )}
        >
          {humanize(o)}
        </button>
      ))}
    </div>
  );
}

export function DistributionEditor({
  value,
  onChange,
  unit = "ms",
  idPrefix,
}: {
  value: Distribution;
  onChange: (d: Distribution) => void;
  unit?: string;
  idPrefix: string;
}) {
  // Fixed order per kind (stored JSON may come back with keys reordered, e.g. by PostgreSQL JSONB).
  const ORDER: Record<Distribution["kind"], string[]> = {
    constant: ["value"],
    exponential: ["mean"],
    uniform: ["min", "max"],
    lognormal: ["median", "p95"],
  };
  const params = ORDER[value.kind].filter((k) => k in value);
  const paramLabel: Record<string, string> = {
    value: "Value",
    mean: "Mean",
    min: "Min",
    max: "Max",
    median: "Median (p50)",
    p95: "p95",
  };
  const err = validateDistribution(value);
  return (
    <div className="space-y-2 rounded-md border border-line bg-surface-2/50 p-2">
      <NativeSelect
        aria-label="Distribution shape"
        title={DISTRIBUTION_KINDS.find((k) => k.kind === value.kind)?.help}
        value={value.kind}
        onChange={(e) => onChange(convertDistribution(value, e.target.value as Distribution["kind"]))}
      >
        {DISTRIBUTION_KINDS.map((k) => (
          <option key={k.kind} value={k.kind}>
            {k.label}
          </option>
        ))}
      </NativeSelect>
      <div className={cn("grid gap-2", params.length > 1 ? "grid-cols-2" : "grid-cols-1")}>
        {params.map((p) => (
          <div key={p} className="space-y-1">
            <Label htmlFor={`${idPrefix}-${p}`} className="text-[11px] text-ink-3">
              {paramLabel[p] ?? p}
            </Label>
            <NumberInput
              id={`${idPrefix}-${p}`}
              value={(value as unknown as Record<string, number>)[p]}
              unit={unit}
              validate={(v) => (v < 0 ? "Must be ≥ 0" : null)}
              onCommit={(v) => onChange({ ...value, [p]: v } as Distribution)}
            />
          </div>
        ))}
      </div>
      <p className={cn("text-[11px]", err ? "text-danger-ink" : "text-ink-3")}>
        {err ?? `≈ mean ${fmtNum(meanOf(value), meanOf(value) < 10 ? 2 : 1)} ${unit}`}
      </p>
    </div>
  );
}

/** One-line summary of a distribution ("p50 8 / p95 25 ms · mean 10.2 ms"); click to edit it. */
function CompactDistribution({
  value,
  onChange,
  unit,
  idPrefix,
}: {
  value: Distribution;
  onChange: (d: Distribution) => void;
  unit: string;
  idPrefix: string;
}) {
  const [open, setOpen] = useState(false);
  const mean = meanOf(value);
  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex h-8 w-full items-center gap-2 rounded-md border border-line bg-surface-2/50 px-2.5 text-left text-[12.5px] text-ink hover:border-line-strong"
      >
        <span className="tabular min-w-0 flex-1 truncate font-mono">
          {describeDistribution(value, unit)}
          <span className="text-ink-3"> · mean {fmtNum(mean, mean < 10 ? 2 : 1)} {unit}</span>
        </span>
        <ChevronDown className={cn("size-3.5 shrink-0 text-ink-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && <DistributionEditor value={value} onChange={onChange} unit={unit} idPrefix={idPrefix} />}
    </div>
  );
}

function RequestTypesEditor({
  value,
  onChange,
  allowed,
}: {
  value: RequestType[];
  onChange: (v: RequestType[]) => void;
  allowed: RequestType[];
}) {
  return (
    <div className="flex gap-2">
      {(["read", "write"] as RequestType[]).map((t) => {
        const on = value.includes(t);
        const disabled = !allowed.includes(t) || (on && value.length === 1);
        return (
          <label
            key={t}
            className={cn(
              "flex flex-1 cursor-pointer items-center gap-2 rounded-md border px-2 py-1.5 text-xs",
              on ? "border-accent bg-accent-soft text-ink" : "border-line text-ink-2",
              disabled && !on && "cursor-not-allowed opacity-50",
            )}
          >
            <input
              type="checkbox"
              checked={on}
              disabled={disabled}
              className="accent-[var(--accent)]"
              onChange={() => onChange(on ? value.filter((x) => x !== t) : ([...value, t].sort() as RequestType[]))}
            />
            {t === "read" ? "Reads" : "Writes"}
          </label>
        );
      })}
    </div>
  );
}

export function FieldControl({
  field,
  value,
  onChange,
  idPrefix,
  allowedRequestTypes = ["read", "write"],
}: {
  field: FieldDescriptor;
  value: unknown;
  onChange: (v: unknown) => void;
  idPrefix: string;
  allowedRequestTypes?: RequestType[];
}) {
  const uid = useId();
  const id = `${idPrefix}-${field.key}-${uid}`;
  const labelId = `${id}-label`;
  const isNull = value === null || value === undefined;

  let control: React.ReactNode;
  switch (field.kind) {
    case "integer":
    case "number": {
      const numberEl = (
        <NumberInput
          id={id}
          value={isNull ? fallbackValue(field) : (value as number)}
          unit={field.unit}
          step={field.step ?? (field.kind === "integer" ? 1 : undefined)}
          validate={(v) => validateNumber(field, v)}
          onCommit={onChange}
          className="flex-1"
        />
      );
      control = field.nullable ? (
        <div className="flex items-start gap-2">
          {isNull ? (
            <div className="flex h-8 flex-1 items-center rounded-md border border-dashed border-line px-2 text-xs text-ink-3">
              {nullLabel(field)}
            </div>
          ) : (
            numberEl
          )}
          <label className="flex h-8 shrink-0 items-center gap-1.5 text-[11px] text-ink-2">
            <input
              type="checkbox"
              className="accent-[var(--accent)]"
              aria-label={`${field.label}: ${nullLabel(field)}`}
              checked={isNull}
              onChange={(e) => onChange(e.target.checked ? null : fallbackValue(field))}
            />
            {nullLabel(field)}
          </label>
        </div>
      ) : (
        numberEl
      );
      break;
    }
    case "enum": {
      const options = field.options ?? [];
      const label = (o: string) => (field.key === "technology" ? (technologyLabel(undefined, o) ?? humanize(o)) : humanize(o));
      const totalLen = options.reduce((n, o) => n + label(o).length, 0);
      control =
        options.length <= 3 && totalLen <= 34 ? (
          <Segmented value={String(value)} options={options} onChange={onChange} labelledBy={labelId} />
        ) : (
          <NativeSelect id={id} value={String(value)} onChange={(e) => onChange(e.target.value)}>
            {options.map((o) => (
              <option key={o} value={o}>
                {label(o)}
              </option>
            ))}
          </NativeSelect>
        );
      break;
    }
    case "boolean":
      control = <Switch id={id} checked={Boolean(value)} onCheckedChange={onChange} label={field.label} />;
      break;
    case "distribution":
      control = isDistribution(value) ? (
        <CompactDistribution value={value} onChange={onChange} unit={field.unit ?? "ms"} idPrefix={id} />
      ) : (
        <p className="text-xs text-danger-ink">Invalid distribution value</p>
      );
      break;
    case "requestTypes":
      control = (
        <RequestTypesEditor
          value={(value as RequestType[]) ?? []}
          onChange={onChange}
          allowed={allowedRequestTypes}
        />
      );
      break;
    default:
      control = (
        <Input id={id} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)} />
      );
  }

  const isSelectEnum =
    field.kind === "enum" &&
    !((field.options ?? []).length <= 3 && (field.options ?? []).reduce((n, o) => n + humanize(o).length, 0) <= 34);
  const row =
    field.kind === "boolean" || isSelectEnum || ((field.kind === "integer" || field.kind === "number") && !field.nullable);
  return (
    <div className={cn(row ? "grid grid-cols-[minmax(0,1fr)_136px] items-start gap-3" : "space-y-1.5", field.kind === "boolean" && "items-center [&>*:last-child]:justify-self-end")}>
      <div className={cn("flex min-w-0 items-center gap-1.5", row && "min-h-8")}>
        <Label id={labelId} htmlFor={field.kind === "enum" && !isSelectEnum ? undefined : id} className="truncate text-[12.5px] text-ink-2">
          {field.label}
        </Label>
        {field.description && (
          <Tip content={field.description}>
            <button type="button" className="text-ink-3 hover:text-ink" aria-label={`About ${field.label}`}>
              <Info className="size-3.5" />
            </button>
          </Tip>
        )}
        {field.assumption && <AssumptionTag />}
      </div>
      {control}
    </div>
  );
}

/** Render fields grouped by their `group`, hiding fields whose visibleWhen does not match. */
export function FieldGroups({
  fields,
  values,
  onChange,
  idPrefix,
  allowedRequestTypes,
  flat = false,
}: {
  fields: FieldDescriptor[];
  values: Record<string, unknown>;
  onChange: (key: string, v: unknown) => void;
  idPrefix: string;
  allowedRequestTypes?: RequestType[];
  flat?: boolean;
}) {
  const groups: { name: string; fields: FieldDescriptor[] }[] = [];
  for (const f of fields) {
    if (!isFieldVisible(f, values)) continue;
    const name = flat ? "" : (f.group ?? "Settings");
    let g = groups.find((x) => x.name === name);
    if (!g) groups.push((g = { name, fields: [] }));
    g.fields.push(f);
  }
  return (
    <div className="space-y-5">
      {groups.map((g) => (
        <fieldset key={g.name || "all"} className="space-y-3">
          {g.name && <legend className="microlabel mb-2">{g.name}</legend>}
          {g.fields.map((f) => (
            <FieldControl
              key={f.key}
              field={f}
              value={values[f.key] ?? f.default}
              onChange={(v) => onChange(f.key, v)}
              idPrefix={idPrefix}
              allowedRequestTypes={allowedRequestTypes}
            />
          ))}
        </fieldset>
      ))}
    </div>
  );
}

export function describeValue(field: FieldDescriptor | undefined, value: unknown): string {
  if (isDistribution(value)) return describeDistribution(value, field?.unit ?? "ms");
  if (value === null || value === undefined) return field ? nullLabel(field).toLowerCase() : "none";
  if (typeof value === "boolean") return value ? "on" : "off";
  if (typeof value === "number") {
    if (field?.key === "hitRatio" || field?.key === "readRatio") return `${fmtNum(value * 100, 0)}%`;
    return `${fmtNum(value, Number.isInteger(value) ? 0 : 2)}${field?.unit ? ` ${field.unit}` : ""}`;
  }
  if (typeof value === "string") return humanize(value);
  if (Array.isArray(value)) return value.join(" + ");
  if (typeof value === "object" && value && "kind" in value) return humanize(String((value as { kind: string }).kind));
  return JSON.stringify(value);
}
