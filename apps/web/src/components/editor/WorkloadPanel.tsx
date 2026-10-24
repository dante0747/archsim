"use client";

import { Dices } from "lucide-react";
import { useMemo } from "react";
import { FieldControl, NumberInput } from "@/components/editor/FieldControl";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/input";
import { Section } from "@/components/ui/misc";
import { fmtNum } from "@/lib/format";
import type { FieldDescriptor, TrafficPattern, Workload } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

export function rateAt(w: Workload, t: number): number {
  const p = w.pattern;
  if (p.kind === "ramp") {
    const frac = Math.min(Math.max(t / w.durationS, 0), 1);
    return p.startRps + (p.endRps - p.startRps) * frac;
  }
  if (p.kind === "burst" && t >= p.startS && t < p.startS + p.durationS) return p.burstRps;
  return w.rps;
}

export function expectedArrivals(w: Workload): number {
  const p = w.pattern;
  if (p.kind === "ramp") return ((p.startRps + p.endRps) / 2) * w.durationS;
  if (p.kind === "burst") {
    const burst = Math.max(0, Math.min(p.durationS, w.durationS - p.startS));
    return w.rps * (w.durationS - burst) + p.burstRps * burst;
  }
  return w.rps * w.durationS;
}

const MAX_REQUESTS = 2_000_000;

export function PatternEditor({
  value,
  onChange,
  durationS,
}: {
  value: TrafficPattern;
  onChange: (p: TrafficPattern) => void;
  durationS: number;
}) {
  const kinds: TrafficPattern["kind"][] = ["constant", "ramp", "burst"];
  const set = (kind: TrafficPattern["kind"]) => {
    if (kind === value.kind) return;
    if (kind === "constant") onChange({ kind });
    if (kind === "ramp") onChange({ kind, startRps: 50, endRps: 500 });
    if (kind === "burst")
      onChange({ kind, burstRps: 1000, startS: Math.round(durationS / 2), durationS: Math.max(1, Math.round(durationS / 6)) });
  };
  const nonNeg = (v: number) => (v < 0 ? "Must be ≥ 0" : v > 100000 ? "Must be ≤ 100,000" : null);
  return (
    <div className="space-y-2">
      <div role="radiogroup" aria-label="Traffic pattern" className="flex rounded-md border border-line bg-surface-2 p-0.5">
        {kinds.map((k) => (
          <button
            key={k}
            type="button"
            role="radio"
            aria-checked={value.kind === k}
            onClick={() => set(k)}
            className={cn(
              "h-7 flex-1 rounded px-2 text-xs font-medium capitalize",
              value.kind === k ? "bg-surface text-ink shadow-sm" : "text-ink-2 hover:text-ink",
            )}
          >
            {k}
          </button>
        ))}
      </div>
      {value.kind === "ramp" && (
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1">
            <Label className="text-[11px] text-ink-3">From</Label>
            <NumberInput value={value.startRps} unit="req/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, startRps: v })} ariaLabel="Ramp start rate" />
          </div>
          <div className="space-y-1">
            <Label className="text-[11px] text-ink-3">To</Label>
            <NumberInput value={value.endRps} unit="req/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, endRps: v })} ariaLabel="Ramp end rate" />
          </div>
        </div>
      )}
      {value.kind === "burst" && (
        <div className="grid grid-cols-3 gap-2">
          <div className="space-y-1">
            <Label className="text-[11px] text-ink-3">Burst rate</Label>
            <NumberInput value={value.burstRps} unit="/s" validate={nonNeg} onCommit={(v) => onChange({ ...value, burstRps: v })} ariaLabel="Burst rate" />
          </div>
          <div className="space-y-1">
            <Label className="text-[11px] text-ink-3">Starts at</Label>
            <NumberInput
              value={value.startS}
              unit="s"
              validate={(v) => (v < 0 ? "Must be ≥ 0" : v >= durationS ? "Must be within the run" : null)}
              onCommit={(v) => onChange({ ...value, startS: v })}
              ariaLabel="Burst start"
            />
          </div>
          <div className="space-y-1">
            <Label className="text-[11px] text-ink-3">Lasts</Label>
            <NumberInput value={value.durationS} unit="s" validate={(v) => (v <= 0 ? "Must be > 0" : null)} onCommit={(v) => onChange({ ...value, durationS: v })} ariaLabel="Burst duration" />
          </div>
        </div>
      )}
    </div>
  );
}

function LoadPreview({ w }: { w: Workload }) {
  const W = 300;
  const H = 72;
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
    <figure className="rounded-lg border border-line bg-surface p-2">
      <figcaption className="mb-1 flex justify-between text-[11px] text-ink-3">
        <span>Offered load (input)</span>
        <span className="tabular">peak {fmtNum(max)} req/s</span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-[72px] w-full" role="img" aria-label="Offered load over the run">
        <rect x={0} y={0} width={warm} height={H} fill="var(--warmup-wash)" />
        <line x1={0} x2={W} y1={H - 4} y2={H - 4} stroke="var(--axis)" strokeWidth={1} />
        <path d={`${line} L${W},${H - 4} L0,${H - 4} Z`} fill="var(--series-1)" opacity={0.1} />
        <path d={line} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" />
      </svg>
      <div className="flex justify-between text-[10px] text-ink-3">
        <span>0 s{w.warmupS > 0 ? ` · warm-up ${fmtNum(w.warmupS)} s (excluded)` : ""}</span>
        <span>{fmtNum(w.durationS)} s</span>
      </div>
    </figure>
  );
}

export function WorkloadPanel() {
  const workload = useEditor((s) => s.workload);
  const catalog = useEditor((s) => s.catalog);
  const setWorkload = useEditor((s) => s.setWorkload);
  const fields = catalog?.workloadFields ?? [];
  const byKey = (k: string) => fields.find((f) => f.key === k);
  const estimate = expectedArrivals(workload);
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

  return (
    <div className="space-y-6 p-4">
      <p className="text-xs leading-relaxed text-ink-2">
        Synthetic traffic from the traffic source. Load is <b>open-loop</b>: clients keep sending at this rate even when
        the system slows down.
      </p>
      <LoadPreview w={workload} />
      <Section title="Traffic">
        <div className="space-y-3">
          {renderField("rps", workload.pattern.kind === "ramp" ? { label: "Baseline load (unused by ramp)" } : undefined)}
          <div className="space-y-1.5">
            <Label>Traffic pattern</Label>
            <PatternEditor
              value={workload.pattern}
              durationS={workload.durationS}
              onChange={(pattern) => setWorkload({ pattern }, "pattern")}
            />
          </div>
          {renderField("readRatio")}
          {renderField("arrivalProcess")}
        </div>
      </Section>
      <Section title="Run">
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            {renderField("durationS")}
            {renderField("warmupS")}
          </div>
          <div className="flex items-end gap-2">
            <div className="flex-1">{renderField("seed")}</div>
            <Button
              variant="secondary"
              size="icon"
              aria-label="Random seed"
              title="Pick a random seed"
              onClick={() => setWorkload({ seed: Math.floor(Math.random() * 2 ** 31) }, "seed")}
            >
              <Dices />
            </Button>
          </div>
          {renderField("drainS")}
          <p className={cn("text-[11px]", estimate > MAX_REQUESTS ? "text-danger-ink" : "text-ink-3")}>
            ≈ {fmtNum(estimate)} requests will be simulated
            {estimate > MAX_REQUESTS ? ` — above the default limit of ${fmtNum(MAX_REQUESTS)}` : ""}. Same seed + same
            model = same results.
          </p>
        </div>
      </Section>
      <Section title="Keyspace (TTL caches only)">
        <div className="grid grid-cols-2 gap-3">
          {renderField("keyCount")}
          {renderField("keySkew")}
        </div>
      </Section>
    </div>
  );
}
