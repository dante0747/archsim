import { describe, expect, it } from "vitest";
import type { WorkspaceInfo } from "./auth-types";
import { projectCountText } from "./project-count";

const workspace = (kind: WorkspaceInfo["kind"], name: string, role: WorkspaceInfo["role"] = "owner"): WorkspaceInfo => ({
  id: `${kind}-1`,
  name,
  kind,
  role,
  maxConcurrentRuns: null,
  maxRunsPerHour: null,
  createdAt: "2026-11-16T09:00:00Z",
});

describe("how many projects the projects page says there are", () => {
  it("counts a sandbox's projects against its cap", () => {
    expect(projectCountText(4, workspace("sandbox", "Sandbox"))).toBe("4 of 5 in Sandbox.");
  });

  it("names the cap while a sandbox's projects load", () => {
    expect(projectCountText(null, workspace("sandbox", "Sandbox"))).toBe("Up to 5 in Sandbox.");
  });

  it("counts a workspace without a cap as before", () => {
    expect(projectCountText(3, workspace("team", "Platform"))).toBe("3 in Platform.");
    expect(projectCountText(3, workspace("team", "Platform", "viewer"))).toBe("3 in Platform. You can open them; editors change them.");
  });

  it("counts a local install's projects in its database", () => {
    expect(projectCountText(2, null)).toBe("2 saved in your ArchSim database.");
    expect(projectCountText(null, null)).toBe("Saved in your ArchSim database.");
  });
});
