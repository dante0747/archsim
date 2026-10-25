import { describe, expect, it } from "vitest";
import {
  DERIVED_METRICS,
  NODE_METRICS,
  PANEL_GROUPS,
  SYSTEM_METRICS,
  formatTick,
  formatValue,
  guessUnit,
  humanizeKey,
  metricDef,
  thresholdStatus,
} from "./metrics-catalog";

const EXISTING_NODE_KEYS = [
  "arrivalRps",
  "completedRps",
  "rejectedRps",
  "failedRps",
  "queueMean",
  "queueMax",
  "queueWaitMs",
  "utilization",
  "cpuUtilization",
  "hitRatio",
  "replicas",
  "lag",
];

const NEW_NODE_KEYS = [
  "inflightMean",
  "latencyP50Ms",
  "latencyP95Ms",
  "lockWaitMs",
  "commitWaitMs",
  "replicationLagMs",
  "staleReadRps",
  "diskReadRps",
  "consumedReadUnits",
  "consumedWriteUnits",
  "throttledRps",
  "evictionRps",
  "memoryUsedRatio",
  "forkPauseMs",
  "partitionLagMax",
  "redeliveryRps",
  "droppedRps",
  "publishBlockedMs",
  "gcPauseMs",
  "warmingReplicas",
  "coldStartRps",
  "eventLoopLagMs",
  "upstreamTimeoutRps",
  "rateLimitedRps",
];

const SYSTEM_KEYS = ["offeredRps", "throughputRps", "errorRps", "timeoutRps", "attemptRps", "latencyP50Ms", "latencyP95Ms", "latencyP99Ms"];

const GROUP_IDS = new Set(PANEL_GROUPS.map((g) => g.id));

describe("metric registry", () => {
  it("documents every component series, existing and new", () => {
    for (const key of [...EXISTING_NODE_KEYS, ...NEW_NODE_KEYS]) {
      const d = NODE_METRICS[key];
      expect(d, key).toBeDefined();
      expect(d.key).toBe(key);
      expect(d.scope).toBe("node");
      expect(d.label.length, key).toBeGreaterThan(2);
      expect(d.description.length, key).toBeGreaterThan(20);
      expect(d.howCalculated.length, key).toBeGreaterThan(20);
      expect(GROUP_IDS.has(d.panel), key).toBe(true);
      expect(["rate", "ratio", "gauge", "latency", "count"]).toContain(d.kind);
    }
  });

  it("documents every system series in its own scope", () => {
    for (const key of SYSTEM_KEYS) {
      const d = SYSTEM_METRICS[key];
      expect(d, key).toBeDefined();
      expect(d.scope).toBe("system");
      expect(GROUP_IDS.has(d.panel)).toBe(true);
    }
    // Same key, different meaning: end to end for the system, inside one part for a component.
    expect(SYSTEM_METRICS.latencyP95Ms.description).not.toBe(NODE_METRICS.latencyP95Ms.description);
    expect(metricDef("latencyP95Ms", "system").panel).toBe("latency");
    expect(metricDef("latencyP95Ms", "node").panel).toBe("componentLatency");
  });

  it("gives every metric of a panel its own colour slot", () => {
    for (const scope of [NODE_METRICS, SYSTEM_METRICS, DERIVED_METRICS]) {
      const byPanel = new Map<string, number[]>();
      for (const d of Object.values(scope)) {
        expect(d.slot).toBeGreaterThanOrEqual(1);
        expect(d.slot).toBeLessThanOrEqual(8);
        byPanel.set(d.panel, [...(byPanel.get(d.panel) ?? []), d.slot]);
      }
      for (const [panel, slots] of byPanel) {
        if (panel === "overview") continue;
        expect(new Set(slots).size, panel).toBe(slots.length);
      }
    }
  });

  it("puts ratios on a 0..1 scale and units on every metric", () => {
    for (const d of [...Object.values(NODE_METRICS), ...Object.values(SYSTEM_METRICS)]) {
      if (d.unit === "ratio") expect(d.max, d.key).toBe(1);
      if (d.kind === "latency") expect(d.unit, d.key).toBe("ms");
    }
  });

  it("falls back to a humanized label for unknown keys", () => {
    const d = metricDef("bufferPoolMissRps");
    expect(d.fallback).toBe(true);
    expect(d.label).toBe("Buffer pool miss");
    expect(d.unit).toBe("/s");
    expect(metricDef("walFlushMs").unit).toBe("ms");
    expect(metricDef("diskUsedRatio").max).toBe(1);
    expect(humanizeKey("p99QueueWait")).toBe("P99 queue wait");
    expect(humanizeKey("queue_depth")).toBe("Queue depth");
    expect(guessUnit("connections")).toBe("count");
    expect(metricDef("utilization").fallback).toBeUndefined();
  });

  it("evaluates thresholds in both directions", () => {
    const util = NODE_METRICS.utilization.thresholds;
    expect(thresholdStatus(0.5, util)).toBe("good");
    expect(thresholdStatus(0.7, util)).toBe("warning");
    expect(thresholdStatus(0.9, util)).toBe("serious");
    expect(thresholdStatus(0.97, util)).toBe("critical");
    const below = { direction: "below" as const, steps: [{ value: 0.999, status: "warning" as const }, { value: 0.99, status: "critical" as const }] };
    expect(thresholdStatus(0.9995, below)).toBe("good");
    expect(thresholdStatus(0.995, below)).toBe("warning");
    expect(thresholdStatus(0.98, below)).toBe("critical");
    expect(thresholdStatus(null, util)).toBeNull();
    expect(thresholdStatus(1, undefined)).toBeNull();
  });

  it("formats values with their unit", () => {
    expect(formatValue(412.4, "req/s")).toBe("412 req/s");
    expect(formatValue(1234, "req/s")).toBe("1.2k req/s");
    expect(formatValue(3.25, "/s")).toBe("3.25/s");
    expect(formatValue(123.4, "ms")).toBe("123 ms");
    expect(formatValue(1500, "ms")).toBe("1.50 s");
    expect(formatValue(0.953, "ratio")).toBe("95%");
    expect(formatValue(0.9997, "ratio")).toBe("99.97%");
    expect(formatValue(0.012, "ratio")).toBe("1.2%");
    expect(formatValue(300, "messages")).toBe("300 msgs");
    expect(formatValue(null, "ms")).toBe("–");
    expect(formatTick(0.25, "ratio")).toBe("25%");
    expect(formatTick(2000, "ms")).toBe("2 s");
    expect(formatTick(0.01, "ms")).toBe("0.01 ms");
    expect(formatTick(0.02, "ms")).toBe("0.02 ms");
    expect(formatTick(250, "ms")).toBe("250 ms");
    expect(formatTick(0.005, "ratio")).toBe("0.5%");
    expect(formatValue(0.004, "ms")).toBe("0.004 ms");
    expect(formatTick(2500, "req/s")).toBe("2.5k");
  });
});
