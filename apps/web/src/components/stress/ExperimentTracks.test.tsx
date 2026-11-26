import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TimelineProvider } from "@/components/timeline/TimelineContext";
import { TooltipProvider } from "@/components/ui/misc";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { ExperimentTracks } from "./ExperimentTracks";

const catalog = catalogJson as unknown as Catalog;
const project: Project = {
  id: "p1",
  name: "Starter",
  description: null,
  revision: 1,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 0,
  document: starterJson as unknown as ArchitectureDocument,
};

describe("Traffic lane", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setWorkload({ durationS: 120, pattern: { kind: "ramp", startRps: 300, endRps: 1500 } });
  });
  afterEach(cleanup);

  it("prints its scale before the drain band, so the two never run together", () => {
    // a 2-minute run on a 122 s axis: the last 2 s are the drain
    render(
      <TooltipProvider>
        <TimelineProvider durationS={122} plotWidth={1000}>
          <ExperimentTracks />
        </TimelineProvider>
      </TooltipProvider>,
    );

    const plot = screen.getByRole("img", { name: "Offered load over the run" });
    const texts = [...plot.querySelectorAll("text")];
    const drain = texts.find((t) => t.textContent === "drain")!;
    const scale = texts.find((t) => t.textContent?.endsWith("/s"))!;
    expect(scale.getAttribute("text-anchor")).toBe("end");
    expect(Number(scale.getAttribute("x"))).toBeLessThan(Number(drain.getAttribute("x")));
  });
});
