import { describe, expect, it } from "vitest";
import { cpuBusyText } from "./utilization";
import type { NodeReportSummary } from "./types";

const summary = (over: Partial<NodeReportSummary>) => over as NodeReportSummary;

describe("CPU busy", () => {
  it("is the part's cores busy", () => {
    expect(cpuBusyText(summary({ cpuCoreUtilization: 0.264 }))).toBe("26%");
  });

  it("adds a database's busiest instance, which sets its CPU ceiling", () => {
    expect(cpuBusyText(summary({ cpuCoreUtilization: 0.156, busiestInstanceCpuUtilization: 0.326 }))).toBe("16% · busiest 33%");
  });

  it("is nothing for a part without a CPU size", () => {
    expect(cpuBusyText(summary({ cpuUtilization: 0.4 }))).toBe("–");
  });
});
