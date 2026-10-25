"use client";

/**
 * What every panel of the dashboard shares: the time window on screen, the replay playhead and the
 * annotations (warm-up, fault windows, detected events).
 */
import { createContext, useContext } from "react";
import type { RecorderEvent } from "@/lib/recorder";
import type { Fault, Report } from "@/lib/types";
import type { TimeRange } from "./range";

export interface FaultAnnotation {
  fault: Fault;
  label: string;
}

export interface DashView {
  report: Report;
  /** Window on screen: the zoom, or the whole run. */
  range: TimeRange;
  full: TimeRange;
  measured: TimeRange;
  zoomed: boolean;
  bucketS: number;
  /** Buckets in the recording. */
  n: number;
  /** Replay position (null = whole run, nothing hidden). */
  playhead: number | null;
  events: RecorderEvent[];
  faults: FaultAnnotation[];
  setRange: (r: TimeRange | null) => void;
  zoomOut: () => void;
}

const Ctx = createContext<DashView | null>(null);

export const DashViewProvider = Ctx.Provider;

export function useDashView(): DashView {
  const v = useContext(Ctx);
  if (!v) throw new Error("useDashView must be used inside the dashboard");
  return v;
}
