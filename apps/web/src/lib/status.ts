import type { Finding, NodeStatus } from "./types";

export interface StatusMeta {
  label: string;
  /** CSS color variable of the status mark */
  color: string;
  soft: string;
  description: string;
}

/** Status colors are reserved for state and always shipped with a text label (never color alone). */
export const NODE_STATUS: Record<NodeStatus, StatusMeta> = {
  saturated: {
    label: "Saturated",
    color: "var(--status-critical)",
    soft: "var(--status-critical-soft)",
    description: "≥95% busy, ≥1% rejected, or queue kept growing",
  },
  hot: {
    label: "Hot",
    color: "var(--status-serious)",
    soft: "var(--status-serious-soft)",
    description: "85–95% busy: little headroom",
  },
  warm: {
    label: "Warm",
    color: "var(--status-warning)",
    soft: "var(--status-warning-soft)",
    description: "70–85% busy",
  },
  ok: {
    label: "OK",
    color: "var(--status-good)",
    soft: "var(--status-good-soft)",
    description: "Below 70% busy",
  },
  idle: {
    label: "Idle",
    color: "var(--ink-3)",
    soft: "var(--surface-2)",
    description: "Received no requests",
  },
};

export const SEVERITY: Record<Finding["severity"], StatusMeta> = {
  critical: { label: "Critical", color: "var(--status-critical)", soft: "var(--status-critical-soft)", description: "" },
  warning: { label: "Warning", color: "var(--status-serious)", soft: "var(--status-serious-soft)", description: "" },
  info: { label: "Info", color: "var(--accent)", soft: "var(--accent-soft)", description: "" },
  ok: { label: "Healthy", color: "var(--status-good)", soft: "var(--status-good-soft)", description: "" },
};

export const SERIES = [
  "var(--series-1)",
  "var(--series-2)",
  "var(--series-3)",
  "var(--series-4)",
  "var(--series-5)",
  "var(--series-6)",
  "var(--series-7)",
  "var(--series-8)",
];
