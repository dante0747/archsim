"use client";

import { Play } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { thClass } from "@/components/results/charts";
import { Button } from "@/components/ui/button";
import { type PriceSource, formatUsd, paretoFrontier, priceSource } from "@/lib/cost";
import { fmtMs, fmtPct } from "@/lib/format";
import { evaluateSlo, failureAllowance, tooManyFailures } from "@/lib/slo";
import { cn } from "@/lib/utils";
import { ensureReports, useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { EmptyPanel, StatusMark, niceTicks, runAllScenariosNow, runDesignNow, runLabel, useElementWidth, useSucceededRuns } from "./shared";

interface Point {
  runId: string;
  label: string;
  seed: number;
  cost: number;
  perMillion: number | null;
  /** Where the run's prices come from ("AWS us-east-1 · Oct 2026", "illustrative rates"). */
  source: PriceSource;
  p95: number;
  success: number | null;
  pass: boolean | null;
  frontier: boolean;
}

const H = 400;
const M = { top: 20, right: 28, bottom: 44, left: 64 };

/** A point's direct label: longer ones end in an ellipsis. */
const LABEL_CHARS = 40;
/** The width of a character of the labels' 11 px text. */
const CHAR_W = 6.4;
/** A marker's half-width: a point is drawn 7 px across its centre, and a pixel is kept around it. */
const MARKER_R = 8;
/** The half-width of the current run's marker, whose ring is drawn 11 px across its centre. */
const ACTIVE_R = 12;
/** From the edge of a point's marker to its label. */
const LABEL_PAD = 3;
/**
 * A label sits this far above its point: the frontier leaves each of its points to the right, at
 * the point's height, and would strike through a label beside it.
 */
const LABEL_LIFT = 8;
/** A line of the labels' text, which is how tall a label is. */
const LINE = 12;
/** The objective line's label. */
const SLO_LABEL = "misses p95 objective ↑";
/** Lines above or below its first place that a label may move before it is left out. */
const MAX_LINES = 4;
/** Points this close are drawn at one place. */
const SAME_PLACE = 1;

/** Where a point's label sits on the map, and what it says. */
export interface PlacedLabel {
  x: number;
  y: number;
  anchor: "start" | "end";
  text: string;
}

/** A part of the map a label must not cover. */
export interface Box {
  x0: number;
  x1: number;
  y0: number;
  y1: number;
}

/** A point to label: its marker's half-width `r` is the usual 8 px unless given. */
interface LabelledPoint {
  id: string;
  label: string;
  x: number;
  y: number;
  r?: number;
}

/** A label cut to `chars` characters with an ellipsis, after its last whole word when that keeps most of it. */
function shorten(label: string, chars: number): string {
  if (label.length <= chars) return label;
  const cut = label.slice(0, Math.max(chars - 1, 1));
  const words = cut.replace(/[\s,;:/·–-]+\S*$/, "");
  return `${(words.length >= chars * 0.6 ? words : cut).trimEnd()}…`;
}

const overlap = (a: Box, b: Box) => a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;

/** What a point's marker covers: `r` either side of its centre, the usual 8 px unless given. */
export function markerBox(p: { x: number; y: number; r?: number }): Box {
  const r = p.r ?? MARKER_R;
  return { x0: p.x - r, x1: p.x + r, y0: p.y - r, y1: p.y + r };
}

function labelBox(l: PlacedLabel): Box {
  const width = l.text.length * CHAR_W;
  const x0 = l.anchor === "start" ? l.x : l.x - width;
  return { x0, x1: x0 + width, y0: l.y - LINE / 2, y1: l.y + LINE / 2 };
}

/** The points drawn at one place, as one point whose label names each of them, in their order. */
function atOnePlace(points: LabelledPoint[]): LabelledPoint[] {
  const places: LabelledPoint[][] = [];
  for (const p of points) {
    const place = places.find(([q]) => Math.abs(q.x - p.x) <= SAME_PLACE && Math.abs(q.y - p.y) <= SAME_PLACE);
    if (place) place.push(p);
    else places.push([p]);
  }
  return places.map((place) => ({
    ...place[0],
    label: [...new Set(place.map((p) => shorten(p.label, LABEL_CHARS)))].join(" · "),
    r: Math.max(...place.map((p) => p.r ?? MARKER_R)),
  }));
}

/**
 * Where a point's label may go, best first. Just above the point: right of it, or left of it
 * when the right would run past the plot (cut to the wider side when neither fits), then on its
 * other side if the label fits there whole. Then the same a line lower, a line higher, two lines
 * lower and so on, inside the plot's top and bottom.
 */
function placesFor(p: LabelledPoint, plot: { left: number; right: number; top?: number; bottom?: number }): PlacedLabel[] {
  const gap = (p.r ?? MARKER_R) + LABEL_PAD;
  const room = { start: plot.right - (p.x + gap), end: p.x - gap - plot.left };
  const width = p.label.length * CHAR_W;
  const first: PlacedLabel["anchor"] = width <= room.start || (width > room.end && room.start >= room.end) ? "start" : "end";
  const other: PlacedLabel["anchor"] = first === "start" ? "end" : "start";
  const sides = width <= room[other] ? [first, other] : [first];
  const places: PlacedLabel[] = [];
  for (let k = 0; k <= 2 * MAX_LINES; k++) {
    const y = p.y - LABEL_LIFT + (k % 2 === 0 ? k / 2 : -(k + 1) / 2) * LINE;
    if (y - LINE / 2 < (plot.top ?? -Infinity) || y + LINE / 2 > (plot.bottom ?? Infinity)) continue;
    for (const anchor of sides) {
      const text = width <= room[anchor] ? p.label : shorten(p.label, Math.max(Math.floor(room[anchor] / CHAR_W), 1));
      places.push({ x: anchor === "start" ? p.x + gap : p.x - gap, y, anchor, text });
    }
  }
  return places;
}

/**
 * The points' direct labels, placed top first, each in the first of its places (see `placesFor`)
 * that covers no point's marker, no label already placed and none of the `keepClear` boxes. A
 * label with no such place is left out: the table and the hover card still name its point. Points
 * drawn at one place share one label, on the first of them, that names each.
 */
export function placeLabels(
  points: LabelledPoint[],
  plot: { left: number; right: number; top?: number; bottom?: number },
  keepClear: Box[] = [],
): Map<string, PlacedLabel> {
  const taken = [...keepClear, ...points.map(markerBox)];
  const labels = new Map<string, PlacedLabel>();
  for (const p of atOnePlace(points).sort((a, b) => a.y - b.y)) {
    const label = placesFor(p, plot).find((place) => !taken.some((box) => overlap(labelBox(place), box)));
    if (!label) continue;
    taken.push(labelBox(label));
    labels.set(p.id, label);
  }
  return labels;
}

function Cross({ x, y, r, color }: { x: number; y: number; r: number; color: string }) {
  return (
    <g stroke={color} strokeWidth={2.25} strokeLinecap="round">
      <line x1={x - r} y1={y - r} x2={x + r} y2={y + r} />
      <line x1={x - r} y1={y + r} x2={x + r} y2={y - r} />
    </g>
  );
}


/**
 * Cost axis fitted to the data (a scatter needs no zero baseline): options usually differ by a
 * few percent, and a [0, max] axis would stack them on top of each other.
 */
function costAxis(costs: number[]): { min: number; max: number; ticks: number[] } {
  const lo0 = Math.min(...costs);
  const hi0 = Math.max(...costs, 1);
  const pad = Math.max((hi0 - lo0) * 0.2, hi0 * 0.03);
  const lo = Math.max(0, lo0 - pad);
  const hi = hi0 + pad;
  const raw = (hi - lo) / 5;
  const mag = 10 ** Math.floor(Math.log10(Math.max(raw, 1e-9)));
  const r = raw / mag;
  const step = (r <= 1 ? 1 : r <= 2 ? 2 : r <= 2.5 ? 2.5 : r <= 5 ? 5 : 10) * mag;
  const min = Math.floor(lo / step) * step;
  const max = Math.max(Math.ceil(hi / step) * step, min + step);
  const ticks: number[] = [];
  for (let v = min; v <= max + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6);
  return { min, max, ticks };
}
export function TradeoffMap() {
  const runs = useSucceededRuns();
  const reports = useRuns((s) => s.reports);
  const activeRunId = useRuns((s) => s.activeRunId);
  const showRun = useRuns((s) => s.showRun);
  const objectives = useEditor((s) => s.objectives);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const setSection = useDecide((s) => s.setSection);
  const boxRef = useRef<HTMLDivElement>(null);
  const width = useElementWidth(boxRef, 720);
  const [hover, setHover] = useState<string | null>(null);

  const ids = useMemo(() => runs.map((r) => r.id), [runs]);
  useEffect(() => {
    void ensureReports(ids);
  }, [ids]);

  const points = useMemo<Point[]>(() => {
    const pts: Omit<Point, "frontier">[] = [];
    for (const r of runs) {
      const rep = reports[r.id];
      const p95 = rep?.summary.latencyMs.p95;
      if (!rep || p95 == null) continue;
      // priced by the engine with the run; runs from before reports had cost are left out
      const cost = rep.cost;
      if (!cost) continue;
      pts.push({
        runId: r.id,
        label: runLabel(r),
        seed: r.seed,
        cost: cost.monthly,
        perMillion: cost.perMillionRequests,
        source: priceSource(cost),
        p95,
        success: rep.summary.successRate,
        pass: evaluateSlo(objectives, rep).pass,
      });
    }
    // p95 covers successful requests only: a run that failed too many requests is not an option
    const front = new Set(paretoFrontier(pts.filter((p) => !tooManyFailures(p.success, objectives))).map((p) => p.runId));
    return pts.map((p) => ({ ...p, frontier: front.has(p.runId) }));
  }, [runs, reports, objectives]);

  const hasCost = points.some((p) => p.cost > 0);
  // every dollar figure names its source: once for the map when the runs agree, per row when not
  const sources = [...new Set(points.map((p) => p.source.label))];
  const mixedSources = sources.length > 1;
  const loading = runs.length > points.length && runs.some((r) => !reports[r.id]);

  // Compare (the parent tab) owns the title row and the "Simulate all scenarios" action.
  const header = (
    <p className="px-6 pt-3 text-[13px] text-ink-3">Each dot is a run. Lower left is better.</p>
  );

  if (runs.length === 0) {
    return (
      <div>
        {header}
        <div className="p-6">
          <EmptyPanel
            icon={<Play />}
            title="No runs to compare yet"
            action={
              <Button variant="primary" onClick={scenarioCount ? runAllScenariosNow : runDesignNow}>
                <Play className="fill-current" /> {scenarioCount ? "Simulate all scenarios" : "Simulate"}
              </Button>
            }
          >
            Each run becomes a dot on the map.
          </EmptyPanel>
        </div>
      </div>
    );
  }

  const xs = costAxis(points.map((p) => p.cost));
  const yMaxRaw = Math.max(...points.map((p) => p.p95), objectives.latencyP95Ms ?? 0, 1) * 1.1;
  const ys = niceTicks(yMaxRaw);
  const iw = Math.max(width - M.left - M.right, 80);
  const ih = H - M.top - M.bottom;
  const sx = (v: number) => M.left + ((v - xs.min) / (xs.max - xs.min)) * iw;
  const sy = (v: number) => M.top + ih - (v / ys.max) * ih;
  const frontier = points.filter((p) => p.frontier).sort((a, b) => a.cost - b.cost);
  const frontierPath =
    frontier.length > 1
      ? frontier.map((p, i) => (i === 0 ? `M${sx(p.cost).toFixed(1)},${sy(p.p95).toFixed(1)}` : `H${sx(p.cost).toFixed(1)} V${sy(p.p95).toFixed(1)}`)).join(" ")
      : null;
  const slo = objectives.latencyP95Ms;
  const hovered = points.find((p) => p.runId === hover) ?? null;
  const showLabels = points.length <= 8;
  const markers = points.map((p) => ({ id: p.runId, label: p.label, x: sx(p.cost), y: sy(p.p95), r: p.runId === activeRunId ? ACTIVE_R : MARKER_R }));
  // the objective line's own label, "misses p95 objective ↑", at the plot's right end
  const sloLabel: Box[] =
    slo != null && slo <= ys.max ? [{ x0: M.left + iw - SLO_LABEL.length * CHAR_W, x1: M.left + iw, y0: sy(slo) - 6 - LINE, y1: sy(slo) - 3 }] : [];
  // Direct labels for a handful of options; a hovered one shows its label however many there are.
  const labels = placeLabels(
    markers.filter((m) => showLabels || m.id === hover),
    { left: M.left, right: width, top: 0, bottom: M.top + ih },
    [...sloLabel, ...markers.map(markerBox)],
  );
  const tick = "font-mono text-[11px]";

  const select = (id: string) => void showRun(id);

  return (
    <div>
      {header}
      <div className="space-y-4 p-6">
        {!hasCost && points.length > 0 && (
          <p className="rounded-md border border-dashed border-line-strong px-3 py-2 text-xs text-ink-2">
            No prices set: add <b className="text-ink">Cost per hour</b> on parts to spread the map out.
          </p>
        )}
        {points.length < 2 && !loading && (
          <p className="rounded-md border border-dashed border-line-strong px-3 py-2 text-xs text-ink-2">
            Only one option so far. {scenarioCount ? "Simulate all scenarios" : "Add scenarios in Design and simulate them"} to compare.
          </p>
        )}

        <figure className="rounded-md border border-line bg-surface p-4">
          <figcaption className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-[11px] text-ink-2" aria-label="Legend">
            <span className="flex items-center gap-1.5">
              <svg width="12" height="12" aria-hidden>
                <circle cx="6" cy="6" r="4.5" fill="var(--accent)" />
              </svg>
              meets objectives
            </span>
            <span className="flex items-center gap-1.5">
              <svg width="12" height="12" aria-hidden>
                <Cross x={6} y={6} r={3.5} color="var(--status-critical)" />
              </svg>
              misses objectives
            </span>
            <span className="flex items-center gap-1.5">
              <svg width="18" height="8" aria-hidden>
                <path d="M1,7 H9 V1 H17" fill="none" stroke="var(--ink-2)" strokeWidth="1.5" />
              </svg>
              Pareto frontier (leaves out runs with more than {fmtPct(failureAllowance(objectives), 1)} failed)
            </span>
            {slo != null && (
              <span className="flex items-center gap-1.5">
                <svg width="18" height="4" aria-hidden>
                  <line x1="0" y1="2" x2="18" y2="2" stroke="var(--status-critical)" strokeWidth="1.5" strokeDasharray="4 3" />
                </svg>
                p95 objective {fmtMs(slo)}
              </span>
            )}
            {hasCost && sources.length > 0 && (
              <span className="text-ink-3" data-testid="map-price-source" title={mixedSources ? undefined : points[0]?.source.detail}>
                prices: {mixedSources ? "differ between runs (see the table)" : sources[0]}
              </span>
            )}
            {loading && <span className="text-ink-3">loading reports…</span>}
          </figcaption>
          <div ref={boxRef} className="relative" style={{ height: H }}>
            <svg width={width} height={H} role="img" aria-label="Scatter of monthly cost against p95 latency per run" className="overflow-visible">
              {/* region that misses the latency objective */}
              {slo != null && slo < ys.max && <rect x={M.left} y={M.top} width={iw} height={sy(slo) - M.top} fill="var(--warmup-wash)" />}
              {ys.ticks.map((v) => (
                <g key={`y${v}`}>
                  <line x1={M.left} x2={M.left + iw} y1={sy(v)} y2={sy(v)} stroke="var(--grid)" />
                  <text x={M.left - 8} y={sy(v)} dy="0.32em" textAnchor="end" className={tick} fill="var(--muted-axis)">
                    {v >= 1000 ? `${v / 1000}s` : `${v}ms`}
                  </text>
                </g>
              ))}
              {xs.ticks.map((v) => (
                <text key={`x${v}`} x={sx(v)} y={M.top + ih + 18} textAnchor="middle" className={tick} fill="var(--muted-axis)">
                  {formatUsd(v, { compact: true })}
                </text>
              ))}
              <line x1={M.left} x2={M.left + iw} y1={M.top + ih} y2={M.top + ih} stroke="var(--axis)" />
              <text x={M.left + iw / 2} y={H - 6} textAnchor="middle" className="font-mono text-[11px]" fill="var(--ink-3)">
                monthly cost →
              </text>
              <text transform={`translate(14 ${M.top + ih / 2}) rotate(-90)`} textAnchor="middle" className="font-mono text-[11px]" fill="var(--ink-3)">
                latency p95 →
              </text>
              {slo != null && slo <= ys.max && (
                <g>
                  <line x1={M.left} x2={M.left + iw} y1={sy(slo)} y2={sy(slo)} stroke="var(--status-critical)" strokeWidth={1.5} strokeDasharray="5 4" />
                  <text x={M.left + iw} y={sy(slo) - 6} textAnchor="end" className="font-mono text-[11px]" fill="var(--ink-2)">
                    {SLO_LABEL}
                  </text>
                </g>
              )}
              {frontierPath && <path d={frontierPath} fill="none" stroke="var(--ink-2)" strokeWidth={1.5} />}
              {points.map((p) => {
                const x = sx(p.cost);
                const y = sy(p.p95);
                const active = p.runId === activeRunId;
                const label = labels.get(p.runId);
                const side = label?.anchor === "end" ? -1 : 1;
                // a label moved off its first line is tied to its point, from the marker's edge
                const edge = (active ? 11 : 7) * side;
                return (
                  <g key={p.runId}>
                    {active && <circle cx={x} cy={y} r={11} fill="none" stroke="var(--accent)" strokeWidth={1.5} />}
                    {p.pass === false ? (
                      <>
                        <circle cx={x} cy={y} r={7} fill="var(--surface)" />
                        <Cross x={x} y={y} r={4.5} color="var(--status-critical)" />
                      </>
                    ) : (
                      <circle cx={x} cy={y} r={6} fill={p.pass === true ? "var(--accent)" : "var(--ink-3)"} stroke="var(--surface)" strokeWidth={2} />
                    )}
                    {label && (
                      <>
                        {Math.abs(label.y - (y - LABEL_LIFT)) > 3 && (
                          <path d={`M${x + edge},${y} L${label.x - 2 * side},${label.y}`} stroke="var(--line-strong)" strokeWidth={1} fill="none" />
                        )}
                        {/* the halo keeps grid and objective lines from running through the text */}
                        <text
                          x={label.x}
                          y={label.y}
                          dy="0.32em"
                          textAnchor={label.anchor}
                          className="font-mono text-[11px]"
                          fill="var(--ink-2)"
                          stroke="var(--surface)"
                          strokeWidth={3}
                          strokeLinejoin="round"
                          paintOrder="stroke"
                        >
                          {label.text}
                        </text>
                      </>
                    )}
                    <circle
                      cx={x}
                      cy={y}
                      r={14}
                      fill="transparent"
                      className="cursor-pointer"
                      onMouseEnter={() => setHover(p.runId)}
                      onMouseLeave={() => setHover((h) => (h === p.runId ? null : h))}
                      onClick={() => select(p.runId)}
                    >
                      <title>{p.label}</title>
                    </circle>
                  </g>
                );
              })}
            </svg>
            {hovered && (
              <div
                className="pointer-events-none absolute z-10 w-56 rounded-md border border-line-strong bg-surface px-2.5 py-2 font-mono text-xs shadow-float"
                style={{
                  left: Math.min(Math.max(sx(hovered.cost) + 14, 0), width - 230),
                  top: Math.max(sy(hovered.p95) - 96, 0),
                }}
                role="status"
              >
                <p className="mb-1 truncate text-xs font-semibold text-ink">{hovered.label}</p>
                <p className="flex justify-between text-ink-2">
                  cost <span className="tabular text-ink">{formatUsd(hovered.cost)}/mo</span>
                </p>
                <p className="truncate text-right text-[11px] text-ink-3" title={hovered.source.detail}>
                  {hovered.source.label}
                </p>
                <p className="flex justify-between text-ink-2">
                  p95 <span className="tabular text-ink">{fmtMs(hovered.p95)}</span>
                </p>
                <p className="flex justify-between text-ink-2">
                  success <span className="tabular text-ink">{fmtPct(hovered.success, 2)}</span>
                </p>
                <p className="mt-1 flex items-center justify-between border-t border-line pt-1">
                  <StatusMark pass={hovered.pass} />
                  {hovered.frontier && <span className="text-accent">Frontier</span>}
                </p>
              </div>
            )}
          </div>
        </figure>

        {points.length > 0 && (
          <div className="overflow-x-auto rounded-md border border-line bg-surface">
            <table className="tabular w-full min-w-[640px] text-xs">
              <thead>
                <tr className="border-b border-line text-left">
                  <th className={cn(thClass, "px-3 py-2")}>Option</th>
                  <th className={cn(thClass, "px-3 py-2 text-right")}>Per month</th>
                  <th className={cn(thClass, "px-3 py-2 text-right")}>Per 1M req</th>
                  <th className={cn(thClass, "px-3 py-2 text-right")}>p95</th>
                  <th className={cn(thClass, "px-3 py-2 text-right")}>Success</th>
                  <th className={cn(thClass, "px-3 py-2")}>Objectives</th>
                  <th className={cn(thClass, "px-3 py-2")} aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {[...points]
                  .sort((a, b) => a.cost - b.cost || a.p95 - b.p95)
                  .map((p) => (
                    <tr
                      key={p.runId}
                      className={cn("border-b border-line last:border-0", p.runId === activeRunId && "bg-accent-soft", hover === p.runId && "bg-surface-2")}
                      onMouseEnter={() => setHover(p.runId)}
                      onMouseLeave={() => setHover(null)}
                    >
                      <td className="px-3 py-2">
                        <button type="button" onClick={() => select(p.runId)} className="text-left text-ink hover:underline">
                          {p.label}
                        </button>
                        {p.frontier && <span className="ml-2 font-mono text-[11px] text-accent">Frontier</span>}
                        <span className="ml-2 font-mono text-[11px] text-ink-3">seed {p.seed}</span>
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-ink">
                        {formatUsd(p.cost)}
                        {mixedSources && (
                          <span className="block text-[11px] text-ink-3" title={p.source.detail}>
                            {p.source.label}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-ink-2">{formatUsd(p.perMillion)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink">{fmtMs(p.p95)}</td>
                      <td className="px-3 py-2 text-right font-mono text-ink">{fmtPct(p.success, 2)}</td>
                      <td className="px-3 py-2">
                        <StatusMark pass={p.pass} />
                      </td>
                      <td className="px-3 py-2 text-right">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            select(p.runId);
                            setSection("answer");
                          }}
                        >
                          Open
                        </Button>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
