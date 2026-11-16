import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { projectTitle, useDocumentTitle } from "./document-title";

describe("the browser tab's title", () => {
  it("names the open project, then ArchSim", () => {
    expect(projectTitle("Checkout")).toBe("Checkout · ArchSim");
  });

  it("falls back to the design's default name for a project without one", () => {
    expect(projectTitle("  ")).toBe("Untitled architecture · ArchSim");
  });

  it("is set while the page shows it, and put back when it closes", () => {
    document.title = "ArchSim — architecture simulator";

    const { rerender, unmount } = renderHook(({ title }) => useDocumentTitle(title), { initialProps: { title: projectTitle("Checkout") } });
    expect(document.title).toBe("Checkout · ArchSim");
    rerender({ title: projectTitle("Checkout v2") });
    expect(document.title).toBe("Checkout v2 · ArchSim");
    unmount();

    expect(document.title).toBe("ArchSim — architecture simulator");
  });

  it("stays the page's own until there is a title to show", () => {
    document.title = "ArchSim — architecture simulator";

    renderHook(() => useDocumentTitle(null));

    expect(document.title).toBe("ArchSim — architecture simulator");
  });
});
