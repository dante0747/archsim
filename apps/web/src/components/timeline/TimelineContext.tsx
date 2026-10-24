"use client";

/**
 * Shared time axis for the Stress workspace's timeline dock. Input tracks (traffic shape, faults)
 * and output lanes (flight-recorder heat lanes) render inside one provider so they line up:
 *
 *   | label column (labelWidth) | plot area (plotWidth px  =  0 s … durationS) |
 */
import { createContext, useContext, useMemo } from "react";

export interface TimelineScale {
  /** Length of the time axis in simulated seconds. */
  durationS: number;
  /** Width of the fixed label column on the left of every track (px). */
  labelWidth: number;
  /** Width of the plot area (px). */
  plotWidth: number;
  /** Seconds → x inside the plot area (px). */
  x: (t: number) => number;
  /** x inside the plot area (px) → seconds, clamped to [0, durationS]. */
  t: (x: number) => number;
}

export const TIMELINE_LABEL_WIDTH = 132;

const Ctx = createContext<TimelineScale | null>(null);

export function TimelineProvider({
  durationS,
  plotWidth,
  labelWidth = TIMELINE_LABEL_WIDTH,
  children,
}: {
  durationS: number;
  plotWidth: number;
  labelWidth?: number;
  children: React.ReactNode;
}) {
  const value = useMemo<TimelineScale>(() => {
    const d = Math.max(durationS, 1e-6);
    const w = Math.max(plotWidth, 1);
    return {
      durationS: d,
      labelWidth,
      plotWidth: w,
      x: (t) => (t / d) * w,
      t: (x) => Math.min(d, Math.max(0, (x / w) * d)),
    };
  }, [durationS, plotWidth, labelWidth]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useTimeline(): TimelineScale {
  const v = useContext(Ctx);
  if (!v) throw new Error("useTimeline must be used inside <TimelineProvider>");
  return v;
}
