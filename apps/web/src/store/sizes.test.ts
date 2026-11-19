import { describe, expect, it } from "vitest";
import type { SizingEntry, VcpuCount } from "@/lib/types";
import { countedVcpus } from "./sizes";

const sized = (vcpus?: VcpuCount): SizingEntry => ({
  specs: { cpuCores: { value: 4, source: "auto" } },
  auto: { cpuCores: 4, memoryGib: 8, storageGib: null },
  peakRps: 100,
  why: {},
  pricePerHour: null,
  ...(vcpus ? { vcpus } : {}),
});

const counted = (siblingSpeed: number, basis: VcpuCount["basis"]) => countedVcpus(sized({ siblingSpeed, basis, text: "" }))?.text;

describe("countedVcpus", () => {
  it("says what the last analysis counted a part's vCPUs as, and what decided it", () => {
    expect(counted(0.6, "default")).toBe("0.6 (hyperthread pairs, the default)");
    expect(counted(1, "wholeCoreFamily")).toBe("1 (whole cores, its instance family)");
    expect(counted(1, "design")).toBe("1 (whole cores, set on the design)");
    expect(counted(0.55, "design")).toBe("0.55 (hyperthread pairs, set on the design)");
    expect(counted(1, "pod")).toBe("1 (whole cores, pods)");
    expect(counted(1, "serverless")).toBe("1 (whole cores, serverless)");
    expect(counted(0.7, "part")).toBe("0.7 (hyperthread pairs, set on the part)");
  });

  it("gives the sibling speed counted, as the value an empty setting stands for", () => {
    expect(countedVcpus(sized({ siblingSpeed: 0.6, basis: "default", text: "" }))?.value).toBe(0.6);
  });

  it("is null before an analysis and for a part whose vCPUs it does not count", () => {
    expect(countedVcpus(null)).toBeNull();
    expect(countedVcpus(sized())).toBeNull();
  });
});
