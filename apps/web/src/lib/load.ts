/**
 * The workload's peak rate, for template cards. The static load estimate behind auto-sizing and
 * the design review lives in the engine (archsim_engine/load.py).
 */
import type { Workload } from "./types";

/** Highest arrival rate of the workload pattern (req/s). */
export function peakRps(w: Workload): number {
  const p = w.pattern;
  if (p.kind === "ramp") return Math.max(p.startRps, p.endRps);
  if (p.kind === "burst") return Math.max(w.rps, p.burstRps);
  return w.rps;
}
