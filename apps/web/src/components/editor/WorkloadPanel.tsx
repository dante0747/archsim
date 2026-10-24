"use client";

import { ChevronRight, Dices } from "lucide-react";
import { useId, useMemo } from "react";
import { AssumptionTag, FieldControl, NumberInput } from "@/components/editor/FieldControl";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/input";
import { Section } from "@/components/ui/misc";
import { compact, fmtNum } from "@/lib/format";
import { REQ_COLOR } from "@/lib/theme";
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
      <div role="radiogroup" aria-label="Traffic pattern" className="flex rounded-xl border border-line bg-surface-2 p-0.5">
        {kinds.map((k) => (
          <button
            key={k}
            type="button"
            role="radio"
            aria-checked={value.kind === k}
            onClick={() => set(k)}
            className={cn(
              "h-7 flex-1 rounded-lg px-2 text-xs font-medium capitalize",
              value.kind === k ? "bg-surface text-ink shadow-panel" : "text-ink-2 hover:text-ink",
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
  const gid = useId().replace(/:/g, "");
  const W = 300;
  const H = 84;
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
  const y = (r: number) => H - 6 - (r / max) * (H - 18);
  const line = points.map(([t, r], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(r).toFixed(1)}`).join(" ");
  const warm = x(Math.min(w.warmupS, w.durationS));
  return (
    <figure className="rounded-2xl border border-line bg-surface-2 p-3">
      <figcaption className="mb-1 flex justify-between">
        <span className="microlabel">Offered load · input</span>
        <span className="tabular font-mono text-[11px] text-ink-2">peak {fmtNum(max)} req/s</span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-[84px] w-full" role="img" aria-label="Offered load over the run">
        <defs>
          <linearGradient id={`g${gid}`} x1="0" x2="1" y1="0" y2="0">
            <stop offset="0%" stopColor="var(--cyan)" />
            <stop offset="55%" stopColor="var(--accent)" />
            <stop offset="100%" stopColor="var(--pink)" />
          </linearGradient>
          <linearGradient id={`f${gid}`} x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.28" />
            <stop offset="100%" stopColor="var(--accent)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <rect x={0} y={0} width={warm} height={H} fill="var(--warmup-wash)" />
        <line x1={0} x2={W} y1={H - 6} y2={H - 6} stroke="var(--axis)" strokeWidth={1} />
        <path d={`${line} L${W},${H - 6} L0,${H - 6} Z`} fill={`url(#f${gid})`} />
        <path d={line} fill="none" stroke={`url(#g${gid})`} strokeWidth={2.2} strokeLinejoin="round" />
      </svg>
      <div className="flex justify-between font-mono text-[10px] text-ink-3">
        <span>0 s{w.warmupS > 0 ? ` · warm-up ${fmtNum(w.warmupS)} s (excluded)` : ""}</span>
        <span>{fmtNum(w.durationS)} s</span>
      </div>
    </figure>
  );
}

const PRESETS = [50, 200, 500, 1000, 2000];

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
  const ramp = workload.pattern.kind === "ramp";
  const reads = Math.round(workload.readRatio * 100);

  return (
    <div className="space-y-6 p-4">
      <div>
        <p className="microlabel">Traffic</p>
        <p className="mt-1 text-xs leading-relaxed text-ink-2">
          Requests generated by the Clients component. Load is <b className="text-ink">open-loop</b>: clients keep sending at
          this rate even when the system slows down.
        </p>
      </div>

      <section className="rounded-2xl border border-line p-3.5" style={{ background: "var(--brand-soft)" }}>
        <div className="flex items-center gap-1.5">
          <Label htmlFor={rpsId} className="microlabel">
            {ramp ? "Baseline (unused by ramp)" : "Requests per second"}
          </Label>
          <AssumptionTag />
        </div>
        <div className="mt-1 flex items-baseline gap-2">
          <NumberInput
            id={rpsId}
            value={workload.rps}
            validate={(v) => (v < 0 ? "Must be ≥ 0" : v > 100000 ? "Must be ≤ 100,000" : null)}
            onCommit={(v) => setWorkload({ rps: v }, "rps")}
            className="w-36 [&_input]:h-11 [&_input]:font-mono [&_input]:text-2xl [&_input]:font-semibold"
          />
          <span className="font-mono text-sm text-ink-3">req/s</span>
        </div>
        <div className="mt-2 flex flex-wrap gap-1">
          {PRESETS.map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => setWorkload({ rps: p }, "rps")}
              className={cn(
                "rounded-full border px-2 py-0.5 font-mono text-[11px] transition-colors",
                workload.rps === p ? "border-accent bg-accent-soft text-ink" : "border-line text-ink-2 hover:border-line-strong hover:text-ink",
              )}
            >
              {compact(p)}
            </button>
          ))}
        </div>
      </section>

      <Section
        title={
          <span className="flex items-center gap-1.5">
            Read / write mix <AssumptionTag />
          </span>
        }
      >
        <div className="space-y-2">
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
          <div className="flex h-2 overflow-hidden rounded-full" aria-hidden>
            <span style={{ width: `${reads}%`, background: REQ_COLOR.read }} />
            <span style={{ width: `${100 - reads}%`, background: REQ_COLOR.write }} />
          </div>
          <div className="flex justify-between font-mono text-[11px]">
            <span style={{ color: REQ_COLOR.read }}>{reads}% reads</span>
            <span style={{ color: REQ_COLOR.write }}>{100 - reads}% writes</span>
          </div>
        </div>
      </Section>

      <Section title="Shape over time">
        <div className="space-y-3">
          <PatternEditor value={workload.pattern} durationS={workload.durationS} onChange={(pattern) => setWorkload({ pattern }, "pattern")} />
          <LoadPreview w={workload} />
        </div>
      </Section>

      <Section title="Run length">
        <div className="space-y-3">
          {renderField("durationS")}
          {renderField("warmupS")}
          <p className={cn("font-mono text-[11px]", estimate > MAX_REQUESTS ? "text-danger-ink" : "text-ink-3")}>
            ≈ {fmtNum(estimate)} requests simulated
            {estimate > MAX_REQUESTS ? ` — above the default limit of ${fmtNum(MAX_REQUESTS)}` : ""}
          </p>
        </div>
      </Section>

      <details className="group rounded-2xl border border-line">
        <summary className="flex cursor-pointer list-none items-center gap-2 px-3.5 py-2.5 text-xs font-medium text-ink-2 hover:text-ink">
          <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" />
          Advanced: arrivals, seed, keyspace
        </summary>
        <div className="space-y-4 border-t border-line px-3.5 py-4">
          {renderField("arrivalProcess")}
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
          <p className="text-[11px] text-ink-3">Same model + same seed = identical results.</p>
          {renderField("drainS")}
          <p className="microlabel pt-1">Keyspace · used by TTL caches</p>
          {renderField("keyCount")}
          {renderField("keySkew")}
        </div>
      </details>
    </div>
  );
}
