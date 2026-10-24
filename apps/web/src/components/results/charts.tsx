"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { compact } from "@/lib/format";
import type { Series } from "@/lib/types";
import { cn } from "@/lib/utils";

/** A lone series wears the accent (4.6:1 on the light surface, 10.8:1 on the dark one). */
export const SINGLE_SERIES = "var(--accent)";

/**
 * Ordinal ramp for p50 → p95 → p99: one hue (the data-viz blue ramp), monotone lightness, the most
 * extreme percentile most salient. Validated with `validate_palette.js --ordinal` on both surfaces;
 * the variables are defined by VIZ_VARS on each chart figure.
 */
export const PERCENTILE_SERIES = ["var(--viz-ord-1)", "var(--viz-ord-2)", "var(--viz-ord-3)"] as const;
export const VIZ_VARS =
  "[--viz-ord-1:#86b6ef] [--viz-ord-2:#2a78d6] [--viz-ord-3:#104281] dark:[--viz-ord-1:#1c5cab] dark:[--viz-ord-2:#3987e5] dark:[--viz-ord-3:#86b6ef]";

/** Table header cells: mono, uppercase, muted. */
export const thClass = "font-mono text-[10px] font-normal uppercase tracking-[0.08em] text-ink-3";

export interface SeriesDef {
  key: string;
  label: string;
  color: string;
  values: Series;
}

export interface TimeSeriesChartProps {
  title: string;
  subtitle?: string;
  t: number[];
  series: SeriesDef[];
  format: (v: number) => string;
  warmupS?: number;
  durationS?: number;
  height?: number;
  yMax?: number;
}

function ChartTooltip({
  active,
  payload,
  label,
  format,
}: {
  active?: boolean;
  payload?: { dataKey: string; value: number | null; color: string; name: string }[];
  label?: number;
  format: (v: number) => string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-md border border-line-strong bg-surface px-2 py-1.5 font-mono text-[11px] shadow-float">
      <p className="mb-1 text-ink-3">t = {label}s</p>
      {payload.map((p) => (
        <p key={p.dataKey} className="flex items-center gap-2 text-ink-2">
          <span className="inline-block h-0.5 w-3 rounded" style={{ background: p.color }} aria-hidden />
          <span>{p.name}</span>
          <span className="tabular ml-auto pl-3 text-ink">{p.value == null ? "–" : format(p.value)}</span>
        </p>
      ))}
    </div>
  );
}

export function Legend({ series }: { series: Pick<SeriesDef, "key" | "label" | "color">[] }) {
  if (series.length < 2) return null;
  return (
    <ul className="flex flex-wrap gap-x-3 gap-y-1" aria-label="Legend">
      {series.map((s) => (
        <li key={s.key} className="flex items-center gap-1.5 font-mono text-[10.5px] text-ink-2">
          <span className="inline-block h-0.5 w-3.5 rounded" style={{ background: s.color }} aria-hidden />
          {s.label}
        </li>
      ))}
    </ul>
  );
}

export function TimeSeriesChart({
  title,
  subtitle,
  t,
  series,
  format,
  warmupS = 0,
  durationS,
  height = 170,
  yMax,
}: TimeSeriesChartProps) {
  const data = t.map((tt, i) => {
    const row: Record<string, number | null> = { t: tt };
    for (const s of series) row[s.key] = s.values[i] ?? null;
    return row;
  });
  const tickStyle = { fill: "var(--muted-axis)", fontSize: 10.5, fontFamily: "var(--font-mono)" };
  return (
    <figure className={cn("min-w-0 rounded-md border border-line bg-surface p-3", VIZ_VARS)}>
      <figcaption className="mb-2 space-y-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="text-xs font-semibold text-ink">{title}</span>
          {subtitle && <span className="truncate font-mono text-[10.5px] text-ink-3">{subtitle}</span>}
        </div>
        <Legend series={series} />
      </figcaption>
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            {warmupS > 0 && (
              <ReferenceArea
                x1={0}
                x2={warmupS}
                fill="var(--warmup-wash)"
                fillOpacity={1}
                strokeOpacity={0}
                ifOverflow="hidden"
                label={{ value: "warm-up", position: "insideTopLeft", fill: "var(--ink-3)", fontSize: 10, fontFamily: "var(--font-mono)" }}
              />
            )}
            {durationS != null && t.length > 0 && t[t.length - 1] > durationS && (
              <ReferenceLine
                x={durationS}
                stroke="var(--line-strong)"
                label={{ value: "arrivals stop", position: "insideTopRight", fill: "var(--ink-3)", fontSize: 10, fontFamily: "var(--font-mono)" }}
              />
            )}
            <XAxis
              dataKey="t"
              type="number"
              domain={["dataMin", "dataMax"]}
              tickFormatter={(v: number) => `${v}s`}
              stroke="var(--axis)"
              tick={tickStyle}
              tickLine={false}
              minTickGap={24}
            />
            <YAxis
              width={44}
              tickFormatter={(v: number) => format(v)}
              tick={tickStyle}
              tickLine={false}
              axisLine={false}
              domain={[0, yMax ?? "auto"]}
              allowDecimals
            />
            <Tooltip
              content={<ChartTooltip format={format} />}
              cursor={{ stroke: "var(--line-strong)", strokeWidth: 1 }}
              isAnimationActive={false}
            />
            {series.map((s) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.label}
                stroke={s.color}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }}
                isAnimationActive={false}
                connectNulls={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </figure>
  );
}

export const fmtAxisRps = (v: number) => compact(v);
export const fmtAxisMs = (v: number) => (v >= 1000 ? `${compact(v / 1000)}s` : `${compact(v)}ms`);
export const fmtAxisPct = (v: number) => `${Math.round(v * 100)}%`;
export const fmtAxisCount = (v: number) => compact(v);
