import { describe, expect, it } from "vitest";
import { docsNav } from "./nav";

const index = `# ArchSim documentation

Everything about using ArchSim.

## Start here

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | Run it in Docker or from source |
| [Features](features.md#workspaces) | Every workspace |

## Running a server

| Page | What it covers |
| --- | --- |
| [Security policy](../SECURITY.md) | How to report a vulnerability |
| [HTTP API](api.md) | Every endpoint under \`/api/v1\` |
`;

describe("docsNav", () => {
  it("reads the sections of docs/README.md, each table row a page", () => {
    const nav = docsNav(index, { from: "docs/README.md", isFolder: () => false });

    expect(nav).toEqual([
      {
        title: "Start here",
        items: [
          { title: "Getting started", href: "/docs/getting-started/", description: "Run it in Docker or from source", external: false },
          { title: "Features", href: "/docs/features/#workspaces", description: "Every workspace", external: false },
        ],
      },
      {
        title: "Running a server",
        items: [
          { title: "Security policy", href: "https://github.com/dante0747/archsim/blob/main/SECURITY.md", description: "How to report a vulnerability", external: true },
          { title: "HTTP API", href: "/docs/api/", description: "Every endpoint under /api/v1", external: false },
        ],
      },
    ]);
  });

  it("fails when the index has no sections, rather than build a site without navigation", () => {
    expect(() => docsNav("# Docs\n\nNothing here.\n", { from: "docs/README.md", isFolder: () => false })).toThrow(/docs\/README.md/);
  });
});
