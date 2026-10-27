/**
 * One experiment's capacity knee on a load axis: the measured knee (blue dot), ArchSim's
 * prediction (orange diamond), and, when the experiment was run more than once on the same
 * machine, the range the measured knee moved over (grey band, a tick per run). Plain HTML, so the
 * labels stay readable at any width; each mark has a native tooltip.
 */
import { type Experiment, kneeScale } from "@/lib/accuracy";
import { cn } from "@/lib/utils";

const fmt = (v: number) => Math.round(v).toLocaleString("en-US");

/** Keeps a label inside the track: left-aligned near 0, right-aligned near the end. */
function anchor(pct: number): string {
  return pct < 12 ? "translate-x-0" : pct > 88 ? "-translate-x-full" : "-translate-x-1/2";
}

function Mark({ pct, title, children }: { pct: number; title: string; children: React.ReactNode }) {
  // a 24px hit area around a small mark, for its tooltip
  return (
    <span title={title} className="absolute top-[38px] flex size-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center" style={{ left: `${pct}%` }}>
      {children}
    </span>
  );
}

export function KneeScale({ x }: { x: Experiment }) {
  const meas = x.knee.measured.kneeRps;
  const pred = x.knee.predicted.kneeRps;
  const runs = x.spread?.values ?? [];
  const values = [meas, pred, ...runs.map((r) => r.kneeRps)].filter((v): v is number => v !== null);
  if (!values.length) return null;
  const { max, ticks } = kneeScale(Math.max(...values));
  const at = (v: number) => (v / max) * 100;
  const label = [
    meas !== null ? `Measured knee ${x.knee.text.measured} req/s` : null,
    pred !== null ? `ArchSim predicted ${x.knee.text.predicted} req/s` : null,
    x.spread ? `over ${x.spread.runs} runs the measured knee ranged from ${x.spread.text}` : null,
  ]
    .filter(Boolean)
    .join("; ");

  return (
    <figure className="mt-4">
      <div className="relative mx-1 h-[76px]" role="img" aria-label={label}>
        {x.spread && (
          <div
            title={`Measured knee over ${x.spread.runs} runs: ${x.spread.text}`}
            className="absolute top-[30px] h-4 rounded-[4px] bg-ink/10"
            style={{ left: `${at(x.spread.minRps)}%`, width: `${Math.max(at(x.spread.maxRps) - at(x.spread.minRps), 0.5)}%` }}
          />
        )}
        <div className="absolute inset-x-0 top-[38px] h-px bg-line-strong" aria-hidden />
        {runs.map((r) => (
          <span
            key={r.run}
            title={`${r.run === "latest" ? "Latest run" : r.run.replace("/", "")} (${r.protocol}): measured knee ${r.text} req/s`}
            className="absolute top-[30px] h-4 w-0.5 -translate-x-1/2 rounded-full bg-ink-3"
            style={{ left: `${at(r.kneeRps)}%` }}
          />
        ))}
        {pred !== null && (
          <>
            <Mark pct={at(pred)} title={`ArchSim predicted ${x.knee.text.predicted} req/s`}>
              <span className="size-2.5 rotate-45 bg-[var(--pred)] ring-2 ring-surface" />
            </Mark>
            <span className={cn("absolute top-[54px] whitespace-nowrap text-[12px] text-ink-2", anchor(at(pred)))} style={{ left: `${at(pred)}%` }}>
              Predicted <span className="tabular font-medium text-ink">{x.knee.text.predicted}</span>
            </span>
          </>
        )}
        {meas !== null && (
          <>
            <Mark pct={at(meas)} title={`Measured knee ${x.knee.text.measured} req/s`}>
              <span className="size-3 rounded-full bg-[var(--meas)] ring-2 ring-surface" />
            </Mark>
            <span className={cn("absolute top-1 whitespace-nowrap text-[12px] text-ink-2", anchor(at(meas)))} style={{ left: `${at(meas)}%` }}>
              Measured <span className="tabular font-medium text-ink">{x.knee.text.measured}</span>
            </span>
          </>
        )}
      </div>
      <div className="relative mx-1 h-4 text-[11px] text-ink-3" aria-hidden>
        {ticks.map((t) => (
          <span key={t} className={cn("tabular absolute top-0", anchor(at(t)))} style={{ left: `${at(t)}%` }}>
            {fmt(t)}
          </span>
        ))}
      </div>
      <figcaption className="mt-1 text-right text-[11px] text-ink-3">offered load, req/s</figcaption>
    </figure>
  );
}

/** The marks the scales use, once above them. */
export function KneeLegend() {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2 text-[13px] text-ink-2">
      <li className="flex items-center gap-2">
        <span className="size-3 rounded-full bg-[var(--meas)]" aria-hidden /> Measured knee
      </li>
      <li className="flex items-center gap-2">
        <span className="mx-px size-2.5 rotate-45 bg-[var(--pred)]" aria-hidden /> ArchSim&apos;s prediction, made before measuring
      </li>
      <li className="flex items-center gap-2">
        <span className="relative h-3 w-6 rounded-[3px] bg-ink/10" aria-hidden>
          <span className="absolute left-1.5 top-0 h-3 w-0.5 rounded-full bg-ink-3" />
          <span className="absolute right-2 top-0 h-3 w-0.5 rounded-full bg-ink-3" />
        </span>
        Measured knee in each run on the same machine
      </li>
    </ul>
  );
}
