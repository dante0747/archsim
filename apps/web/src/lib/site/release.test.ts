import { describe, expect, it } from "vitest";
import { releaseNotes } from "./release";

const headings = [
  { id: "unreleased", text: "[Unreleased]", depth: 2 },
  { id: "changed", text: "Changed", depth: 3 },
  { id: "130---2026-11-17", text: "[1.3.0] - 2026-11-17", depth: 2 },
  { id: "120---2026-11-16", text: "[1.2.0] - 2026-11-16", depth: 2 },
];

describe("releaseNotes", () => {
  it("links the changelog's entry for the version, not its top", () => {
    expect(releaseNotes(headings, "1.3.0")).toBe("/changelog/#130---2026-11-17");
  });

  it("finds the entry whose version is a link, as Keep a Changelog writes it ([1.3.0] read as 1.3.0)", () => {
    const linked = [{ id: "130---2026-11-17", text: "1.3.0 - 2026-11-17", depth: 2 }, { id: "13---2026-01-01", text: "1.3 - 2026-01-01", depth: 2 }];

    expect(releaseNotes(linked, "1.3.0")).toBe("/changelog/#130---2026-11-17");
    expect(releaseNotes(linked, "1.3")).toBe("/changelog/#13---2026-01-01");
  });

  it("fails the build for a version the changelog has no entry for", () => {
    expect(() => releaseNotes(headings, "1.4.0")).toThrow(/CHANGELOG.md has no entry for 1.4.0/);
  });
});
