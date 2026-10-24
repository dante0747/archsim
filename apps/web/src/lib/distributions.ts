import { fmtNum } from "./format";
import type { Distribution } from "./types";

export const DISTRIBUTION_KINDS: { kind: Distribution["kind"]; label: string; help: string }[] = [
  { kind: "constant", label: "Constant", help: "Always the same value." },
  { kind: "exponential", label: "Exponential", help: "Memoryless; high variance (stddev = mean)." },
  { kind: "uniform", label: "Uniform", help: "Evenly spread between min and max." },
  { kind: "lognormal", label: "Log-normal", help: "Realistic long tail, set by median and p95." },
];

export function isDistribution(v: unknown): v is Distribution {
  if (typeof v !== "object" || v === null || !("kind" in v)) return false;
  const kind = (v as { kind: unknown }).kind;
  if (kind === "constant") return "value" in v; // a constant traffic pattern has no value
  return kind === "exponential" || kind === "uniform" || kind === "lognormal";
}

export function meanOf(d: Distribution): number {
  switch (d.kind) {
    case "constant":
      return d.value;
    case "exponential":
      return d.mean;
    case "uniform":
      return (d.min + d.max) / 2;
    case "lognormal": {
      const mu = Math.log(d.median);
      const sigma = Math.log(d.p95 / d.median) / 1.6448536269514722;
      return Math.exp(mu + (sigma * sigma) / 2);
    }
  }
}

export function describeDistribution(d: Distribution, unit = "ms"): string {
  const n = (v: number) => fmtNum(v, v < 10 && !Number.isInteger(v) ? 2 : v < 100 && !Number.isInteger(v) ? 1 : 0);
  switch (d.kind) {
    case "constant":
      return `${n(d.value)} ${unit}`;
    case "exponential":
      return `exp, mean ${n(d.mean)} ${unit}`;
    case "uniform":
      return `${n(d.min)}–${n(d.max)} ${unit}`;
    case "lognormal":
      return `p50 ${n(d.median)} / p95 ${n(d.p95)} ${unit}`;
  }
}

/** Switch kind while keeping roughly the same mean. */
export function convertDistribution(d: Distribution, kind: Distribution["kind"]): Distribution {
  const m = Math.max(meanOf(d), 0.001);
  const r = (v: number) => Math.round(v * 1000) / 1000;
  switch (kind) {
    case "constant":
      return { kind, value: r(m) };
    case "exponential":
      return { kind, mean: r(m) };
    case "uniform":
      return { kind, min: r(m * 0.5), max: r(m * 1.5) };
    case "lognormal":
      return { kind, median: r(m * 0.8), p95: r(m * 2.4) };
  }
}

export function validateDistribution(d: Distribution): string | null {
  const vals = Object.entries(d).filter(([k]) => k !== "kind");
  for (const [k, v] of vals) {
    if (typeof v !== "number" || !Number.isFinite(v)) return `${k} must be a number`;
    if (v < 0) return `${k} must be ≥ 0`;
  }
  if (d.kind === "exponential" && d.mean <= 0) return "mean must be > 0";
  if (d.kind === "lognormal") {
    if (d.median <= 0) return "median must be > 0";
    if (d.p95 < d.median) return "p95 must be ≥ median";
  }
  if (d.kind === "uniform" && d.max < d.min) return "max must be ≥ min";
  return null;
}
