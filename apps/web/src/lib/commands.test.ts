import { describe, expect, it } from "vitest";
import { rankCommands } from "./commands";

const cmds = [
  { label: "Add Service / API Servers", group: "Add component", keywords: "service new", hint: "Stateless servers; often sit behind a cache" },
  { label: "Add Cache (Redis)", group: "Add component", keywords: "cache new", hint: "Cache-aside reads" },
  { label: "Run simulation", group: "Simulate", keywords: "start go", hint: "design as drawn" },
  { label: "Export JSON", group: "Project", keywords: "download file" },
];

describe("rankCommands", () => {
  it("keeps everything for an empty query", () => {
    expect(rankCommands(cmds, "  ")).toEqual(cmds);
  });

  it("ranks label matches above description matches", () => {
    expect(rankCommands(cmds, "add cache").map((c) => c.label)).toEqual(["Add Cache (Redis)", "Add Service / API Servers"]);
  });

  it("requires every word to match and searches keywords", () => {
    expect(rankCommands(cmds, "download").map((c) => c.label)).toEqual(["Export JSON"]);
    expect(rankCommands(cmds, "run nothing-here")).toEqual([]);
  });
});
