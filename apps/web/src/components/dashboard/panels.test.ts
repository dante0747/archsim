import { describe, expect, it } from "vitest";
import { fixtureNode, fixtureNodes, fixtureReport, N } from "@/test/fixtures/dashboard-report";
import {
  OTHER_COLOR,
  SERIES_COLORS,
  type StatSpec,
  type TableSpec,
  type TimeSeriesSpec,
  buildDashboard,
  componentColors,
  componentPanels,
  groupRowsByKind,
  layoutSpans,
  narrowSpan,
  overviewStats,
  panelTable,
  perComponentSeries,
  rankNodes,
  statReadout,
  thresholdBands,
  toCsv,
} from "./panels";

const groupsOf = (panels: TimeSeriesSpec[]) => panels.map((p) => p.id.split(":").slice(2).join(":"));
const byId = (nodes = fixtureNodes()) => Object.fromEntries(nodes.map((n) => [n.id, n]));

describe("component colours", () => {
  it("gives servers the first colours, in report order, and is stable", () => {
    const nodes = fixtureNodes();
    const a = componentColors(nodes);
    const b = componentColors(fixtureNodes());
    expect([...a.entries()]).toEqual([...b.entries()]);
    expect(a.get("lb")).toBe(SERIES_COLORS[0]);
    expect(a.get("api")).toBe(SERIES_COLORS[1]);
    // The client comes after every server.
    expect(a.get("clients")).toBe(SERIES_COLORS[5]);
  });

  it("never cycles: components past the eighth get no colour", () => {
    const nodes = Array.from({ length: 11 }, (_, i) => fixtureNode(`s${i}`, "service", "compute", {}));
    const map = componentColors(nodes);
    expect(new Set([...map.values()].filter(Boolean)).size).toBe(8);
    expect(map.get("s8")).toBeNull();
    expect(map.get("s10")).toBeNull();
  });

  it("folds uncoloured components into one Other series", () => {
    const nodes = Array.from({ length: 10 }, (_, i) =>
      fixtureNode(`s${i}`, "service", "compute", { rejectedRps: Array(N).fill(1), utilization: Array(N).fill(i / 10) }),
    );
    const colors = componentColors(nodes);
    const rates = perComponentSeries(nodes, "rejectedRps", colors);
    expect(rates).toHaveLength(9);
    const other = rates[8];
    expect(other.color).toBe(OTHER_COLOR);
    expect(other.label).toBe("Other (2)");
    expect(other.data[0]).toBe(2); // rates are summed
    const util = perComponentSeries(nodes, "utilization", colors);
    expect(util[8].label).toBe("Other (2, max)");
    expect(util[8].data[0]).toBeCloseTo(0.9); // ratios take the max
  });

  it("keeps a component's colour when the filter hides others", () => {
    const report = fixtureReport();
    const colors = componentColors(report.nodes);
    const model = buildDashboard({ report, objectives: {}, cost: null, colors, hidden: new Set(["lb", "cache"]) });
    const util = model.rows.flatMap((r) => r.panels).find((p) => p.id === "sys:utilization") as TimeSeriesSpec;
    expect(util.series.map((s) => s.id)).toEqual(["api", "db", "events"]);
    expect(util.series.find((s) => s.id === "api")?.color).toBe(colors.get("api"));
  });
});

describe("layout", () => {
  it("fills 24 columns per line with spans of 6, 8, 12 or 24", () => {
    for (let n = 1; n <= 12; n++) {
      const spans = layoutSpans(n);
      expect(spans).toHaveLength(n);
      expect(spans.every((s) => [6, 8, 12, 24].includes(s))).toBe(true);
      expect(spans.reduce((a, b) => a + b, 0) % 24).toBe(0);
    }
    expect(layoutSpans(4)).toEqual([6, 6, 6, 6]);
    expect(layoutSpans(5)).toEqual([8, 8, 8, 12, 12]);
    expect(narrowSpan(4)).toBe(8);
    expect(narrowSpan(8)).toBe(12);
  });

  it("builds threshold bands in both directions", () => {
    const above = thresholdBands({ direction: "above", steps: [{ value: 0.7, status: "warning" }, { value: 0.95, status: "critical" }] }, 1);
    expect(above).toEqual([
      { from: 0.7, to: 0.95, status: "warning" },
      { from: 0.95, to: 1, status: "critical" },
    ]);
    const below = thresholdBands({ direction: "below", steps: [{ value: 0.8, status: "warning" }, { value: 0.5, status: "critical" }] }, 1);
    expect(below).toEqual([
      { from: 0.5, to: 0.8, status: "warning" },
      { from: 0, to: 0.5, status: "critical" },
    ]);
  });
});

describe("component panels", () => {
  const nodes = byId();

  it("picks a service's panels from its series", () => {
    const panels = componentPanels(nodes.api);
    expect(groupsOf(panels)).toEqual(["requests", "utilization", "queue", "componentLatency", "instances", "coldStarts", "pauses", "custom:fooBarMs"]);
    const util = panels.find((p) => p.id.endsWith(":utilization"))!;
    expect(util.series.map((s) => s.id)).toEqual(["utilization", "cpuUtilization"]);
    expect(util.yMax).toBe(1);
    expect(util.bands.map((b) => b.from)).toEqual([0.7, 0.85, 0.95]);
    const lat = panels.find((p) => p.id.endsWith(":componentLatency"))!;
    expect(lat.title).toBe("Latency and queue wait");
    expect(panels.find((p) => p.id.endsWith(":queue"))!.title).toBe("Queue and in flight");
    expect(lat.series.map((s) => s.label)).toEqual(["Queue wait", "p50", "p95"]);
    const pauses = panels.find((p) => p.id.endsWith(":pauses"))!;
    expect(pauses.series.map((s) => s.id)).toEqual(["gcPauseMs", "eventLoopLagMs"]);
    // Different thresholds on one panel: no bands rather than the wrong ones.
    expect(pauses.bands).toEqual([]);
    const custom = panels.find((p) => p.id.endsWith(":custom:fooBarMs"))!;
    expect(custom.title).toBe("Foo bar");
    expect(custom.unit).toBe("ms");
  });

  it("picks cache, database, queue and load balancer panels", () => {
    expect(groupsOf(componentPanels(nodes.cache))).toEqual(["requests", "utilization", "hitRatio", "evictions"]);
    // forkPauseMs has no data in the fixture, so no empty pauses panel.
    expect(groupsOf(componentPanels(nodes.db))).toEqual(["requests", "utilization", "queue", "waits", "reads", "capacityUnits", "limits"]);
    expect(groupsOf(componentPanels(nodes.events))).toEqual(["requests", "utilization", "pauses", "backlog", "delivery"]);
    expect(groupsOf(componentPanels(nodes.lb))).toEqual(["requests", "utilization", "limits"]);
    expect(groupsOf(componentPanels(nodes.clients))).toEqual(["requests"]);
    // Titles follow what is there: the db reports a queue but nothing in flight.
    expect(componentPanels(nodes.db).find((p) => p.id.endsWith(":queue"))!.title).toBe("Queue depth");
    expect(componentPanels(nodes.cache).find((p) => p.id.endsWith(":evictions"))!.title).toBe("Evictions");
    const mem = componentPanels(nodes.cache).find((p) => p.id.endsWith(":utilization"))!;
    expect(mem.series.map((s) => s.id)).toEqual(["utilization", "memoryUsedRatio"]);
  });

  it("keeps a metric's colour across components", () => {
    const a = componentPanels(nodes.api)[0].series.find((s) => s.id === "rejectedRps");
    const b = componentPanels(nodes.db)[0].series.find((s) => s.id === "rejectedRps");
    expect(a?.color).toBe(b?.color);
  });

  it("lays every component row out on full lines", () => {
    for (const node of Object.values(nodes)) {
      const total = componentPanels(node).reduce((s, p) => s + p.span, 0);
      expect(total % 24).toBe(0);
    }
  });
});

describe("dashboard", () => {
  it("lists system rows, then one row per component, busiest three expanded", () => {
    const report = fixtureReport();
    const model = buildDashboard({ report, objectives: { latencyP95Ms: 50 }, cost: null, colors: componentColors(report.nodes) });
    expect(model.rows.map((r) => r.id)).toEqual([
      "overview",
      "traffic",
      "latency",
      "saturation",
      "errors",
      "node:clients",
      "node:lb",
      "node:api",
      "node:cache",
      "node:db",
      "node:events",
    ]);
    const expanded = model.rows.filter((r) => r.nodeId && !r.defaultCollapsed).map((r) => r.nodeId);
    expect(expanded).toEqual(["api", "db", "events"]);
    expect(rankNodes(report.nodes)[0].id).toBe("api");
    const api = model.rows.find((r) => r.id === "node:api")!;
    expect(api.panels.map((p) => p.nodeId)).toEqual(api.panels.map(() => "api"));
    // Clients send traffic but stay out of the shared panels.
    const queue = model.rows.flatMap((r) => r.panels).find((p) => p.id === "sys:queue") as TimeSeriesSpec;
    expect(queue.series.map((s) => s.id)).toEqual(["api", "db"]);

    const latency = model.rows.find((r) => r.id === "latency")!.panels as TimeSeriesSpec[];
    expect(latency.map((p) => p.id)).toEqual(["sys:latency", "sys:node-p95", "sys:queue-wait"]);
    expect(latency[0].thresholds).toEqual([{ value: 50, status: "critical", label: "p95 objective" }]);

    const sat = model.rows.find((r) => r.id === "saturation")!.panels.map((p) => p.id);
    expect(sat).toEqual(["sys:utilization", "sys:queue", "sys:cpu", "sys:inflight", "sys:replicas"]);

    const errors = model.rows.find((r) => r.id === "errors")!.panels;
    const rejected = errors[0] as TimeSeriesSpec;
    expect(rejected.style).toBe("bars");
    const table = errors[1] as TableSpec;
    expect(table.rows.map((r) => r.reason)).toEqual(["queue_full", "client_timeout"]);
  });

  it("groups component rows by kind", () => {
    const report = fixtureReport();
    const model = buildDashboard({ report, objectives: {}, cost: null, colors: componentColors(report.nodes) });
    const ids = groupRowsByKind(model.rows).filter((r) => r.nodeId).map((r) => r.nodeId);
    expect(ids).toEqual(["lb", "api", "cache", "db", "events", "clients"]);
  });

  it("exports a panel's data in range as CSV", () => {
    const report = fixtureReport();
    const panel = componentPanels(report.nodes.find((n) => n.id === "db")!).find((p) => p.id.endsWith(":capacityUnits"))!;
    const table = panelTable(panel, 2, { from: 4, to: 8 });
    expect(table.header).toEqual(["t (s)", "RCU", "WCU"]);
    expect(table.rows).toEqual([
      [4, 120, 40],
      [6, 120, 40],
    ]);
    expect(toCsv({ header: ["t", "a,b"], rows: [[0, null]] })).toBe('t,"a,b"\n0,');
  });
});

describe("overview stats", () => {
  const report = fixtureReport();
  const stats = overviewStats({ report, objectives: { latencyP95Ms: 60, availability: 0.99 }, cost: { monthly: 1234, perMillionRequests: 0.42, hasCost: true } });
  const stat = (id: string) => stats.find((s) => s.id === id) as StatSpec;
  const view = { range: { from: 0, to: 40 }, measured: { from: 4, to: 36 }, full: { from: 0, to: 40 }, n: N, bucketS: 2, playhead: null };

  it("covers the six headline numbers", () => {
    expect(stats.map((s) => s.id)).toEqual(["stat:throughput", "stat:p95", "stat:errors", "stat:success", "stat:saturation", "stat:cost"]);
    expect(stats.reduce((s, p) => s + p.span, 0)).toBe(24);
    expect(stat("stat:p95").note).toBe("objective 60 ms");
    expect(stat("stat:errors").summary).toBeCloseTo(0.05);
    expect(stat("stat:cost").note).toBe("$0.42 per million requests");
  });

  it("reads the summary for the whole run, the series for a zoom and the playhead in replay", () => {
    expect(statReadout(stat("stat:throughput"), view)).toEqual({ value: 475, owner: null, caption: "measured window" });
    const zoom = statReadout(stat("stat:throughput"), { ...view, range: { from: 0, to: 4 } });
    expect(zoom.caption).toBe("mean over range");
    expect(zoom.value).toBeCloseTo(500 - 50 / 19 / 2);
    const live = statReadout(stat("stat:throughput"), { ...view, playhead: 1 });
    expect(live.value).toBe(500);
    expect(live.caption).toBe("at 1.0 s");
    expect(statReadout(stat("stat:cost"), { ...view, playhead: 5 }).value).toBe(1234);
  });

  it("names the busiest component for max saturation", () => {
    const s = stat("stat:saturation");
    expect(statReadout(s, view)).toMatchObject({ value: 0.8, owner: "Api" });
    // During the db outage window nothing changes in the fixture, but at the start the db is busier.
    expect(statReadout(s, { ...view, playhead: 1 })).toMatchObject({ value: 0.5, owner: "Db" });
  });
});

describe("Kubernetes panels", () => {
  const s = (v: number) => Array.from({ length: N }, () => v);
  it("gives a cluster only its own panels and services on it a Pods panel", () => {
    const cluster = fixtureNode("k8s", "kubernetes_cluster", "compute", {
      arrivalRps: s(0),
      queueMean: s(0),
      nodes: s(3),
      nodesProvisioning: s(1),
      podsRunning: s(8),
      podsPending: s(2),
      cpuRequestedRatio: s(0.9),
      memoryRequestedRatio: s(0.4),
    });
    expect(groupsOf(componentPanels(cluster))).toEqual(["pods", "clusterNodes", "requested"]);
    const api = fixtureNode("api", "service", "compute", { arrivalRps: s(100), podsReady: s(4), podsStarting: s(1), podsPending: s(2) });
    expect(groupsOf(componentPanels(api))).toEqual(["requests", "pods"]);
  });
});
