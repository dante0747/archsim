"use client";

import { ChevronRight, Dices } from "lucide-react";
import { useId, useMemo } from "react";
import { AssumptionTag, FieldControl, NumberInput } from "@/components/editor/FieldControl";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/input";
import { compact, fmtNum } from "@/lib/format";
import type { FieldDescriptor, TrafficPattern, Workload } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

/** Rate of a replayed trace at ``t`` seconds: linear between points, flat after the last. */
export function traceRate(points: [number, number][], t: number): number {
  if (!points.length) return 0;
  if (t >= points[points.length - 1][0]) return points[points.length - 1][1];
  for (let i = 1; i < points.length; i++) {
    const [t0, r0] = points[i - 1];
    const [t1, r1] = points[i];
    if (t <= t1) return r0 + ((r1 - r0) * (t - t0)) / (t1 - t0);
  }
  return points[points.length - 1][1];
}

export function rateAt(w: Workload, t: number): number {
  const p = w.pattern;
  if (p.kind === "trace") return traceRate(p.points, t);
  if (p.kind === "ramp") {
    const frac = Math.min(Math.max(t / w.durationS, 0), 1);
    return p.startRps + (p.endRps - p.startRps) * frac;
  }
  if (p.kind === "burst" && t >= p.startS && t < p.startS + p.durationS) return p.burstRps;
  return w.rps;
}

export function expectedArrivals(w: Workload): number {
  const p = w.pattern;
  if (p.kind === "trace") {
    let total = 0;
    const steps = 200;
    for (let i = 0; i < steps; i++) total += traceRate(p.points, ((i + 0.5) / steps) * w.durationS) * (w.durationS / steps);
    return total;
  }
  if (p.kind === "ramp") return ((p.startRps + p.endRps) / 2) * w.durationS;
  if (p.kind === "burst") {
    const burst = Math.max(0, Math.min(p.durationS, w.durationS - p.startS));
    return w.rps * (w.durationS - burst) + p.burstRps * burst;
  }
  return w.rps * w.durationS;
}

const MAX_REQUESTS = 2_000_000;

const SHAPES: { kind: TrafficPattern["kind"]; label: string; hint: string }[] = [
  { kind: "constant", label: "Steady", hint: "The same rate for the whole run" },
  { kind: "ramp", label: "Ramp", hint: "Grows (or shrinks) from one rate to another" },
  { kind: "burst", label: "Burst", hint: "A steady rate with one spike" },
];

export function PatternEditor({
  value,
  onChange,
  durationS,
}: {
  value: TrafficPattern;
  onChange: (p: TrafficPattern) => void;
  durationS: number;
}) {
  const set = (kind: TrafficPattern["kind"]) => {
    if (kind === value.kind) return;
    if (kind === "constant") onChange({ kind });
    if (kind === "ramp") onChange({ kind, startRps: 50, endRps: 500 });
    if (kind === "burst")
      onChange({ kind, burstRps: 1000, startS: Math.round(durationS / 2), durationS: Math.max(1, Math.round(durationS / 6)) });
  };
  const nonNeg = (v: number) => (v < 0 ? "Must be ≥ 0" : v > 100000 ? "Must be ≤ 100,000" : null);
  return (
    <div className="space-y-2.5">
      {value.kind === "trace" && (
        <p className="rounded-lg bg-good-soft px-2.5 py-2 text-xs leading-relaxed text-ink-2">
          <b className="font-medium text-ink">Replayed traffic</b>: {value.points.length} points
          {value.source ? `, ${value.source}` : ""}. Pick a shape to replace it.
        </p>
      )}
      <div role="radiogroup" aria-label="Traffic pattern" className="flex rounded-lg border border-line bg-surface-2/70 p-0.5">
        {SHAPES.map((k) => (
          <button
            key={k.kind}
            type="button"
            role="radio"
            aria-checked={value.kind === k.kind}
            title={k.hint}
            onClick={() => set(k.kind)}
            className={cn(
              "h-7 flex-1 rounded-md px-2 text-[13px] font-medium transition-colors",
              value.kind === k.kind ? "bg-surface text-ink shadow-panel" : "text-ink-3 hover:text-ink",
            )}
          >
            {k.label}
          </button>
        ))}
      </div>
      {value.kind === "ramp" && (
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1">
            <Label className="text-xs text-ink-3">From</Label>
            <NumberInput value={value.startRps} unit="req/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, startRps: v })} ariaLabel="Ramp start rate" />
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-ink-3">To</Label>
            <NumberInput value={value.endRps} unit="req/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, endRps: v })} ariaLabel="Ramp end rate" />
          </div>
        </div>
      )}
      {value.kind === "burst" && (
        <div className="grid grid-cols-3 gap-2">
          <div className="space-y-1">
            <Label className="text-xs text-ink-3">Spike to</Label>
            <NumberInput value={value.burstRps} unit="/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, burstRps: v })} ariaLabel="Burst rate" />
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-ink-3">At</Label>
            <NumberInput
              value={value.startS}
              unit="s"
              validate={(v) => (v < 0 ? "Must be ≥ 0" : v >= durationS ? "Must be within the run" : null)}
              onCommit={(v) => onChange({ ...value, startS: v })}
              ariaLabel="Burst start"
            />
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-ink-3">For</Label>
            <NumberInput value={value.durationS} unit="s" validate={(v) => (v <= 0 ? "Must be > 0" : null)} onCommit={(v) => onChange({ ...value, durationS: v })} ariaLabel="Burst duration" />
          </div>
        </div>
      )}
    </div>
  );
}

function LoadPreview({ w }: { w: Workload }) {
  const W = 300;
  const H = 56;
  const points = useMemo(() => {
    const n = 120;
    const pts: [number, number][] = [];
    for (let i = 0; i <= n; i++) {
      const t = (i / n) * w.durationS;
      pts.push([t, rateAt(w, t)]);
    }
    return pts;
  }, [w]);
  const max = Math.max(1, ...points.map((p) => p[1]));
  const x = (t: number) => (t / w.durationS) * W;
  const y = (r: number) => H - 4 - (r / max) * (H - 12);
  const line = points.map(([t, r], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(r).toFixed(1)}`).join(" ");
  const warm = x(Math.min(w.warmupS, w.durationS));
  return (
    <figure
      className="relative rounded-md border border-line bg-surface-2/60 px-2 pb-1 pt-2"
      title={w.warmupS > 0 ? `Shaded: ${fmtNum(w.warmupS)} s warm-up, not measured` : undefined}
    >
      <span className="tabular absolute right-2 top-1.5 font-mono text-[11px] text-ink-3">peak {fmtNum(max)}/s</span>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-14 w-full" role="img" aria-label="Load over the run">
        <rect x={0} y={0} width={warm} height={H} fill="var(--warmup-wash)" />
        <line x1={0} x2={W} y1={H - 4} y2={H - 4} stroke="var(--axis)" strokeWidth={1} />
        <path d={`${line} L${W},${H - 4} L0,${H - 4} Z`} fill="var(--accent)" fillOpacity={0.1} />
        <path d={line} fill="none" stroke="var(--accent)" strokeWidth={1.75} strokeLinejoin="round" />
      </svg>
    </figure>
  );
}

const PRESETS = [50, 200, 500, 1000, 2000];

/** Design → Traffic tab: the load the Clients send, its shape, the read/write mix and the run length. */
export function WorkloadPanel() {
  const workload = useEditor((s) => s.workload);
  const catalog = useEditor((s) => s.catalog);
  const setWorkload = useEditor((s) => s.setWorkload);
  const fields = catalog?.workloadFields ?? [];
  const byKey = (k: string) => fields.find((f) => f.key === k);
  const estimate = expectedArrivals(workload);
  const rpsId = useId();
  const renderField = (key: string, override?: Partial<FieldDescriptor>) => {
    const f = byKey(key);
    if (!f) return null;
    return (
      <FieldControl
        key={key}
        field={{ ...f, ...override }}
        value={(workload as unknown as Record<string, unknown>)[key]}
        onChange={(v) => setWorkload({ [key]: v } as Partial<Workload>, key)}
        idPrefix="workload"
      />
    );
  };
  const kind = workload.pattern.kind;
  const reads = Math.round(workload.readRatio * 100);

  return (
    <div className="space-y-5 p-4">
      <section aria-label="Load" className="space-y-3">
        <h3
          className="flex items-center gap-1.5 text-[13px] font-semibold text-ink"
          title="Clients keep sending at this rate even when the system slows down (open-loop load)."
        >
          Load <AssumptionTag />
        </h3>
        <PatternEditor value={workload.pattern} durationS={workload.durationS} onChange={(pattern) => setWorkload({ pattern }, "pattern")} />
        {kind !== "ramp" && (
          <div>
            <Label htmlFor={rpsId} className="text-xs text-ink-3">
              {kind === "burst" ? "Normal rate" : "Requests per second"}
            </Label>
            <div className="mt-1 flex items-center gap-2">
              <NumberInput
                id={rpsId}
                value={workload.rps}
                validate={(v) => (v < 0 ? "Must be ≥ 0" : v > 100000 ? "Must be ≤ 100,000" : null)}
                onCommit={(v) => setWorkload({ rps: v }, "rps")}
                className="w-32 [&_input]:h-10 [&_input]:font-mono [&_input]:text-xl [&_input]:font-semibold"
              />
              <span className="text-[13px] text-ink-3">req/s</span>
            </div>
            <div className="mt-2 flex flex-wrap gap-1">
              {PRESETS.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setWorkload({ rps: p }, "rps")}
                  className={cn(
                    "rounded-md border px-2 py-0.5 font-mono text-xs transition-colors",
                    workload.rps === p ? "border-accent bg-accent-soft text-ink" : "border-line text-ink-2 hover:border-line-strong hover:text-ink",
                  )}
                >
                  {compact(p)}
                </button>
              ))}
            </div>
          </div>
        )}
        <LoadPreview w={workload} />
      </section>

      <section aria-label="Reads and writes" className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-ink-3">Reads / writes</span>
          <span className="tabular font-mono text-[13px] text-ink">
            {reads}% / {100 - reads}%
          </span>
        </div>
        <input
          type="range"
          min={0}
          max={100}
          step={5}
          value={reads}
          onChange={(e) => setWorkload({ readRatio: Number(e.target.value) / 100 }, "readRatio")}
          aria-label="Share of reads"
          aria-valuetext={`${reads}% reads, ${100 - reads}% writes`}
          className="w-full accent-[var(--accent)]"
        />
      </section>

      <section aria-label="Run length" className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="wl-dur" className="text-xs text-ink-3">
            Run length
          </Label>
          <NumberInput
            id="wl-dur"
            value={workload.durationS}
            unit="s"
            validate={(v) => (v <= workload.warmupS ? "Longer than warm-up" : v > 3600 ? "≤ 3600" : null)}
            onCommit={(v) => setWorkload({ durationS: v }, "durationS")}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="wl-warm" className="text-xs text-ink-3" title="The first seconds are not measured, so start-up does not skew the results.">
            Warm-up
          </Label>
          <NumberInput
            id="wl-warm"
            value={workload.warmupS}
            unit="s"
            validate={(v) => (v < 0 ? "≥ 0" : v >= workload.durationS ? "Shorter than the run" : null)}
            onCommit={(v) => setWorkload({ warmupS: v }, "warmupS")}
          />
        </div>
      </section>
      {estimate > MAX_REQUESTS && (
        <p className="text-xs text-danger-ink">
          About {fmtNum(estimate)} requests: above the default limit of {fmtNum(MAX_REQUESTS)}.
        </p>
      )}

      <details className="group rounded-lg border border-line">
        <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 text-[13px] font-medium text-ink-2 hover:text-ink [&::-webkit-details-marker]:hidden">
          <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" />
          More settings
        </summary>
        <div className="space-y-4 border-t border-line px-3.5 py-4">
          {renderField("arrivalProcess")}
          <div className="flex items-end gap-2">
            <div className="flex-1">{renderField("seed")}</div>
            <Button
              variant="secondary"
              size="icon"
              aria-label="Random seed"
              title="Pick a random seed (same model + same seed = identical results)"
              onClick={() => setWorkload({ seed: Math.floor(Math.random() * 2 ** 31) }, "seed")}
            >
              <Dices />
            </Button>
          </div>
          {renderField("drainS")}
          {renderField("keyCount")}
          {renderField("keySkew")}
        </div>
      </details>
    </div>
  );
}
