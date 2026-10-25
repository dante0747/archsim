import { ReactFlowProvider } from "@xyflow/react";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import { fixtureReport } from "@/test/fixtures/dashboard-report";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { Dashboard } from "./Dashboard";
import { useCursor, useDashboard } from "./store";

class SizedObserver {
  constructor(private cb: ResizeObserverCallback) {}
  observe(el: Element) {
    Object.defineProperty(el, "clientWidth", { configurable: true, value: 600 });
    Object.defineProperty(el, "clientHeight", { configurable: true, value: 180 });
    this.cb([], this as unknown as ResizeObserver);
  }
  unobserve() {}
  disconnect() {}
}

function show() {
  return render(
    <ReactFlowProvider>
      <TooltipProvider>
        <Dashboard />
      </TooltipProvider>
    </ReactFlowProvider>,
  );
}

// Rendering ~30 panels in jsdom takes a second or two; leave room when the suite runs in parallel.
describe("Dashboard", { timeout: 20_000 }, () => {
  beforeAll(() => {
    globalThis.ResizeObserver = SizedObserver as unknown as typeof ResizeObserver;
  });
  beforeEach(() => {
    useRuns.setState({ runs: [], reports: {}, activeRunId: null, followRunId: null });
    useDashboard.setState({ range: null, rangeRunId: null, hidden: [], collapsed: {}, groupByKind: false });
    useUi.setState({ playhead: null, playing: false });
  });
  afterEach(cleanup);

  it("invites a first run when there is nothing to show", () => {
    show();
    expect(screen.getByText("Nothing to monitor yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Simulate/ })).toBeInTheDocument();
  });

  // Queries by selector: role queries walk the accessibility tree of ~30 panels and get slow.
  const region = (label: string) => document.querySelector(`section[aria-label="${label}"]`) as HTMLElement | null;
  const panel = (id: string) => document.querySelector(`[data-panel="${id}"]`) as HTMLElement | null;

  it("renders rows of panels for the active run", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    for (const row of ["Overview", "Traffic", "Latency", "Saturation", "Errors", "Api", "Db"]) {
      expect(region(row), row).not.toBeNull();
    }
    expect(panel("stat:throughput")).toHaveTextContent("475");
    expect(panel("sys:failures")).toHaveTextContent("queue was full");
    // The three busiest components start expanded, the rest collapsed.
    expect(panel("node:lb:requests")).toBeNull();
    expect(panel("node:api:requests")).not.toBeNull();
    fireEvent.click(region("Lb")!.querySelector("button[aria-expanded]")!);
    expect(panel("node:lb:limits")).toHaveTextContent("Limited and timed out");
  });

  it("shows the value under the shared crosshair in every legend", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    act(() => useCursor.getState().setCursor(9, "sys:traffic", 20));
    expect(panel("sys:traffic")).toHaveTextContent("at 8 s");
    expect(panel("sys:latency")).toHaveTextContent("at 8 s");
    act(() => useCursor.getState().setCursor(null, null));
    expect(panel("sys:latency")).not.toHaveTextContent("at 8 s");
  });

  it("shows the engine's calculation for a metric in the info popover", () => {
    const report = fixtureReport();
    report.nodes.find((n) => n.id === "api")!.explain = [
      { id: "util", title: "Busy workers", formula: "busy ÷ slots", substituted: "12.8 ÷ 16", result: 0.8, unit: "", note: null, source: null, metric: "utilization" },
    ];
    useRuns.setState({ reports: { r1: report }, activeRunId: "r1" });
    show();
    fireEvent.click(panel("node:api:utilization")!.querySelector('button[aria-label="About Utilization"]')!);
    expect(screen.getByText("12.8 ÷ 16")).toBeInTheDocument();
    expect(screen.getByText(/Utilization = busy slots ÷ total slots/)).toBeInTheDocument();
  });

  it("follows the zoomed range in stat panels", () => {
    useRuns.setState({ reports: { r1: fixtureReport() }, activeRunId: "r1" });
    show();
    act(() => useDashboard.getState().setRange("r1", { from: 0, to: 4 }));
    expect(panel("stat:throughput")).toHaveTextContent("mean over range");
    expect(document.querySelector('button[aria-label="Time range"]')).toHaveTextContent("0–4 s");
  });
});
