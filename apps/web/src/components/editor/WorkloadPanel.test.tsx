import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Tooltip } from "radix-ui";
import { afterEach, describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { Catalog, TrafficPattern, Workload } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { WorkloadPanel } from "./WorkloadPanel";

const catalog = catalogJson as unknown as Catalog;

function shown(pattern: TrafficPattern) {
  const workload: Workload = {
    rps: 100,
    durationS: 60,
    warmupS: 10,
    seed: 42,
    readRatio: 0.9,
    arrivalProcess: "poisson",
    pattern,
    keyCount: 100000,
    keySkew: 0.99,
  };
  useEditor.setState({ catalog, workload });
  render(
    <Tooltip.Provider>
      <WorkloadPanel />
    </Tooltip.Provider>,
  );
}

describe("WorkloadPanel", () => {
  afterEach(cleanup);

  it("asks how many hours a month the peak lasts, for the monthly cost", () => {
    shown({ kind: "burst", burstRps: 1000, startS: 20, durationS: 10 });

    const hours = screen.getByLabelText("Peak hours a month");
    fireEvent.focus(hours);
    fireEvent.change(hours, { target: { value: "24" } });

    expect(useEditor.getState().workload.peakHoursPerMonth).toBe(24);
  });

  it("does not ask a constant load, which has no peak", () => {
    shown({ kind: "constant" });

    expect(screen.queryByLabelText("Peak hours a month")).toBeNull();
  });
});
