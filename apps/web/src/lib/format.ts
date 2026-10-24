/** Number formatting with explicit units. Missing values render as an en dash. */

const DASH = "–";

export function fmtNum(v: number | null | undefined, digits = 0): string {
  if (v == null || !Number.isFinite(v)) return DASH;
  return v.toLocaleString("en-US", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

export function compact(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return DASH;
  return new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 }).format(v);
}

export function fmtRps(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return DASH;
  return `${v >= 100 ? fmtNum(v, 0) : fmtNum(v, 1)} req/s`;
}

export function fmtMs(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return DASH;
  if (v >= 10_000) return `${(v / 1000).toFixed(1)} s`;
  if (v >= 1000) return `${(v / 1000).toFixed(2)} s`;
  if (v >= 100) return `${v.toFixed(0)} ms`;
  if (v >= 10) return `${v.toFixed(1)} ms`;
  return `${v.toFixed(2)} ms`;
}

export function fmtPct(v: number | null | undefined, digits = 1): string {
  if (v == null || !Number.isFinite(v)) return DASH;
  return `${(v * 100).toFixed(digits)}%`;
}

export function fmtValue(v: unknown, unit?: string): string {
  if (v == null) return "none";
  if (typeof v === "number") return `${fmtNum(v, Number.isInteger(v) ? 0 : 3)}${unit ? ` ${unit}` : ""}`;
  if (typeof v === "boolean") return v ? "on" : "off";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

export function fmtRelativeTime(iso: string, now = Date.now()): string {
  const t = new Date(iso).getTime();
  const s = Math.round((now - t) / 1000);
  if (s < 10) return "just now";
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

export function shortDigest(d: string | null | undefined): string {
  return d ? d.slice(0, 10) : DASH;
}
