import { ReactFlowProvider } from "@xyflow/react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import { api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project, Report, Run } from "@/lib/types";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import catalogJson from "@/test/fixtures/catalog.json";
import { fixtureReport } from "@/test/fixtures/dashboard-report";
import starterJson from "@/test/fixtures/starter.json";
import { DecideWorkspace } from "./DecideWorkspace";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), message: vi.fn() } }));

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

function run(id: string, label: string, minute: number, status: Run["status"] = "succeeded"): Run {
  return {
    id,
    projectId: "p1",
    label,
    scenarioId: null,
    scenarioName: null,
    status,
    progress: status === "succeeded" ? 1 : 0,
    error: null,
    seed: 42,
    engineVersion: "1.6.0",
    inputDigest: null,
    resultDigest: null,
    summary: null,
    wallSeconds: null,
    createdAt: `2026-01-01T00:0${minute}:00Z`,
    startedAt: null,
    finishedAt: null,
  } as Run;
}

const FIRST = run("run-first", "First run", 1);
const SECOND = run("run-second", "Second run", 2);
const THIRD = run("run-third", "Third run", 3);

/** The run the Results header names: Answer, Calculations, Request traces, Report and Share read it. */
const inResults = () => (screen.getByRole("combobox", { name: "Run" }) as HTMLSelectElement).value;
/** The run on the canvas: the Dashboard, Summary, Over time and Per component read it. */
const onCanvas = () => useRuns.getState().activeRunId;

/** Results (the list of runs) of a project with two finished runs, the second one current. */
function openResults() {
  useEditor.getState().load(structuredClone(project), catalog);
  useDecide.setState({ section: "compare", compareView: "runs" });
  useRuns.setState({
    projectId: "p1",
    runs: [SECOND, FIRST],
    reports: { [FIRST.id]: fixtureReport(), [SECOND.id]: fixtureReport() },
    activeRunId: SECOND.id,
    pending: [],
    followRunId: null,
    lastFinishedRunId: null,
  });
  render(
    <ReactFlowProvider>
      <TooltipProvider>
        <DecideWorkspace />
      </TooltipProvider>
    </ReactFlowProvider>,
  );
}

/** Pick a run in the Results header, as someone comparing runs does. */
async function pick(picked: Run) {
  await userEvent.selectOptions(screen.getByRole("combobox", { name: "Run" }), picked.id);
  await waitFor(() => expect(onCanvas()).toBe(picked.id));
}

describe("the current run", () => {
  // Results scrolls to its top when the section changes, which jsdom's elements cannot do
  beforeAll(() => {
    Element.prototype.scrollTo = () => {};
  });
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, "getReport").mockResolvedValue(fixtureReport() as Report);
  });

  it("is the run on the canvas when the project opens", () => {
    openResults();

    expect(inResults()).toBe(SECOND.id);
    expect(onCanvas()).toBe(SECOND.id);
  });

  it("is the run picked in the Results header, on the canvas too", async () => {
    openResults();

    await pick(FIRST);

    expect(inResults()).toBe(FIRST.id);
  });

  it("becomes the followed run when it finishes, wherever another run was picked before", async () => {
    openResults();
    await pick(FIRST);
    vi.spyOn(api, "createRun").mockResolvedValue({ ...THIRD, status: "queued", progress: 0 });
    vi.spyOn(api, "getRun").mockResolvedValue(THIRD);

    await useRuns.getState().startRun(useEditor.getState().toDocument(), null);
    await waitFor(() => expect(onCanvas()).toBe(THIRD.id), { timeout: 5_000 });

    expect(inResults()).toBe(THIRD.id);
  });

  it("becomes the run viewed from the list of runs, wherever another run was picked before", async () => {
    openResults();
    await pick(FIRST);

    await useRuns.getState().showRun(SECOND.id);

    await waitFor(() => expect(inResults()).toBe(SECOND.id));
    expect(onCanvas()).toBe(SECOND.id);
  });

  it("becomes the newest run left when the run it was is deleted", async () => {
    openResults();
    vi.spyOn(api, "deleteRun").mockResolvedValue(undefined);

    await useRuns.getState().remove(SECOND.id);

    await waitFor(() => expect(inResults()).toBe(FIRST.id));
    expect(onCanvas()).toBe(FIRST.id);
  });

  it("stays the run it is when another run is deleted", async () => {
    openResults();
    vi.spyOn(api, "deleteRun").mockResolvedValue(undefined);

    await useRuns.getState().remove(FIRST.id);

    expect(onCanvas()).toBe(SECOND.id);
    expect(inResults()).toBe(SECOND.id);
  });

  it("is none once the last run is deleted, in Results as on the canvas", async () => {
    openResults();
    vi.spyOn(api, "deleteRun").mockResolvedValue(undefined);

    await useRuns.getState().remove(SECOND.id);
    await waitFor(() => expect(onCanvas()).toBe(FIRST.id));
    await useRuns.getState().remove(FIRST.id);

    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Run" })).not.toBeInTheDocument());
    expect(onCanvas()).toBeNull();
  });
});
