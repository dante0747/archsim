import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SimulateOptionItems } from "@/components/editor/SimulateOptions";
import { TooltipProvider } from "@/components/ui/misc";
import { Menu, MenuContent, MenuTrigger } from "@/components/ui/overlays";
import type { RunGroup } from "@/lib/seed-groups";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { Analysis, ArchitectureDocument, Catalog, Confidence, Project, Report, Run } from "@/lib/types";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useSeedGroups } from "@/store/seed-groups";
import { AnswerPage } from "./Scorecard";

const catalog = catalogJson as unknown as Catalog;
const analysis = analysisJson as unknown as Analysis;
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

const HIGH: Confidence = { level: "high", reason: "Most inputs are measured", reasons: ["Most inputs are measured"], drivers: [], weightedBy: "latency", howToRaise: "Nothing to do" };

function run(id: string, seed: number, p95: number | null, extra: Partial<Run> = {}): Run {
  return {
    id,
    projectId: "p1",
    label: id,
    scenarioId: null,
    scenarioName: null,
    status: p95 == null ? "running" : "succeeded",
    progress: p95 == null ? 0.5 : 1,
    error: null,
    seed,
    engineVersion: "0.12.0",
    inputDigest: null,
    resultDigest: null,
    summary: p95 == null ? null : { offeredRps: 400, throughputRps: 400, successRate: 1, timeoutRate: 0, errorRate: 0, p50: 40, p95, p99: p95 * 1.4, bottleneck: null, bottleneckNodeId: null },
    wallSeconds: null,
    createdAt: "2026-01-01T00:00:00Z",
    startedAt: null,
    finishedAt: null,
    ...extra,
  };
}

function report(p95: number, extra: Record<string, unknown> = {}): Report {
  return {
    reportVersion: "1.13",
    summary: { offeredRps: 400, throughputRps: 399, successRate: 1, latencyMs: { p50: 40, p95, p99: 130 }, latencyWithFailuresMs: { p95 } },
    findings: [],
    nodes: [],
    assumptions: { modelNotes: [], userInputs: [], components: {}, validationWarnings: [], technologies: [] },
    provenance: { resultDigest: "0123456789abcdef", inputDigest: "abc", seed: 42 },
    confidence: HIGH,
    ...extra,
  } as unknown as Report;
}

/** Put a group in the runs store and in the group cache, as if it had been fetched. */
function seedGroup(runs: Run[]): void {
  const group: RunGroup = {
    id: "g1",
    projectId: "p1",
    kind: "seeds",
    baseSeed: runs[0].seed,
    size: runs.length,
    mode: "full",
    scenarioId: null,
    label: "Current design",
    createdAt: "2026-01-01T00:00:00Z",
    status: "succeeded",
    runs,
    summary: {} as RunGroup["summary"],
  };
  const key = runs.map((r) => `${r.id}:${r.status}`).join(",");
  useSeedGroups.setState({ groups: { g1: group }, loadedAt: { g1: key } });
  useRuns.setState({ runs, reports: { [runs[0].id]: report(runs[0].summary?.p95 ?? 100) }, activeRunId: runs[0].id });
}

function show(ui: React.ReactElement) {
  return render(<TooltipProvider>{ui}</TooltipProvider>);
}

describe("Results across seeds", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setAnalysis(structuredClone(analysis));
    useEditor.getState().setObjectives({ latencyP95Ms: 120, availability: 0.999 });
    useDecide.setState({ scorecardRunId: null, section: "answer" });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useRuns.setState({ runs: [], reports: {}, activeRunId: null, followRunId: null, pending: [] });
    useSeedGroups.setState({ groups: {}, loadedAt: {} });
  });

  it("says how many seeds meet the objectives, flags disagreement and caps confidence", () => {
    const g = { groupId: "g1" };
    seedGroup([run("a", 42, 98.2, g), run("b", 43, 104.4, g), run("c", 44, 131, g), run("d", 45, 101, g), run("e", 46, 110.3, g)]);
    show(<AnswerPage />);
    const line = screen.getByTestId("seed-range");
    expect(line).toHaveTextContent("Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46");
    expect(line.dataset.verdict).toBe("borderline");
    expect(within(line).getByTestId("borderline-badge")).toHaveTextContent("Borderline");
    expect(screen.getByTestId("confidence-badge").dataset.level).toBe("medium");
    expect(screen.getByTestId("confidence-reason")).toHaveTextContent("The runs disagree on the verdict, so it is borderline");
    expect(screen.queryByTestId("check-seeds")).toBeNull();
  });

  it("keeps the confidence when every seed agrees, and counts runs still going", () => {
    const g = { groupId: "g1" };
    seedGroup([run("a", 42, 90, g), run("b", 43, 95, g), run("c", 44, null, g)]);
    show(<AnswerPage />);
    const line = screen.getByTestId("seed-range");
    expect(line).toHaveTextContent("Meets the objectives in 2 of 2 runs · p95 90–95 ms (median 93) · seeds 42–44");
    expect(line).toHaveTextContent("(2 of 3 finished)");
    expect(screen.queryByTestId("borderline-badge")).toBeNull();
    expect(screen.getByTestId("confidence-badge").dataset.level).toBe("high");
  });

  it("offers to check 5 seeds when one run's p95 is near the objective, and starts the group", async () => {
    useRuns.setState({ projectId: "p1", runs: [run("r1", 42, 110)], reports: { r1: report(110) }, activeRunId: "r1" });
    const calls: { url: string; body: unknown }[] = [];
    const group = [run("n1", 42, null, { groupId: "g9", seedIndex: 0 }), run("n2", 43, null, { groupId: "g9", seedIndex: 1 })];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : null });
      const json = url.endsWith("/runs/r1/input") ? starterJson : url.endsWith("/projects/p1/runs") ? group[0] : url.includes("/run-groups/g9") ? { id: "g9", runs: group } : {};
      return new Response(JSON.stringify(json), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    show(<AnswerPage />);
    const offer = screen.getByTestId("check-seeds");
    expect(offer).toHaveTextContent("One seed (42), and p95 is within 15% of the objective");
    fireEvent.click(within(offer).getByRole("button", { name: "Check 5 seeds" }));
    await waitFor(() => expect(useRuns.getState().pending).toEqual(["n1", "n2"]));
    const create = calls.find((c) => c.url.endsWith("/projects/p1/runs"));
    expect(create?.body).toMatchObject({ seeds: 5, mode: "full", scenarioId: null });
    expect((create?.body as { document: ArchitectureDocument }).document.metadata.name).toBe((starterJson as unknown as ArchitectureDocument).metadata.name);
    expect(useRuns.getState().followRunId).toBe("n1");
  });

  it("does not offer more seeds when the answer is clear", () => {
    useRuns.setState({ runs: [run("r1", 42, 60)], reports: { r1: report(60) }, activeRunId: "r1" });
    show(<AnswerPage />);
    expect(screen.queryByTestId("check-seeds")).toBeNull();
    expect(screen.queryByTestId("seed-range")).toBeNull();
  });

  it("labels a quick check's answer as estimated", () => {
    const note = "Answered from a steady 125 s sample after the 10 s warm-up: the 300 s run was not simulated whole.";
    const sampled = report(60, { simulationMode: { kind: "sampled", requestedDurationS: 300, simulatedDurationS: 135, note } });
    useRuns.setState({ runs: [run("r1", 42, 60, { mode: "sampled" })], reports: { r1: sampled }, activeRunId: "r1" });
    show(<AnswerPage />);
    expect(screen.getByTestId("estimated-badge")).toHaveTextContent("Estimated");
    expect(screen.getByTestId("estimated-line")).toHaveTextContent("Answered from a sample of the run");

    fireEvent.click(screen.getByRole("button", { name: "About the quick check" }));

    expect(screen.getByText(note)).toBeInTheDocument();
  });
});

describe("Simulate menu", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
  });
  afterEach(cleanup);

  function menu(onRun: (o: unknown) => void) {
    return show(
      <Menu open>
        <MenuTrigger>open</MenuTrigger>
        <MenuContent>
          <SimulateOptionItems onRun={onRun} />
        </MenuContent>
      </Menu>,
    );
  }

  it("disables the quick check and says why when the design does not qualify", () => {
    const reason = "The run is too short to gain from a quick check: the warm-up and a steady sample (135 s) would be more than half of its 60 s. Runs of 270 s or longer qualify.";
    useEditor.getState().setAnalysis({ ...structuredClone(analysis), quickCheck: { eligible: false, reason, requestedS: 60, simulatedS: null, sampleS: null } } as Analysis);
    const onRun = vi.fn();
    menu(onRun);
    const item = screen.getByTestId("quick-check-item");
    expect(item).toHaveAttribute("data-disabled");
    expect(screen.getByTestId("quick-check-reason")).toHaveTextContent(reason);
    expect(screen.getByTestId("seeds-item")).toHaveTextContent("Check 5 seeds");
    expect(screen.getByTestId("seeds-item")).toHaveTextContent("seeds 42–46");
    fireEvent.click(screen.getByTestId("seeds-item"));
    expect(onRun).toHaveBeenCalledWith({ seeds: 5 });
  });

  it("runs a quick check when the engine says the design qualifies", () => {
    const reason = "Simulates the 10 s warm-up and a 125 s steady sample instead of all 300 s, then answers from the sample if it is steady.";
    useEditor.getState().setAnalysis({ ...structuredClone(analysis), quickCheck: { eligible: true, reason, requestedS: 300, simulatedS: 135, sampleS: 125 } } as Analysis);
    const onRun = vi.fn();
    menu(onRun);
    const item = screen.getByTestId("quick-check-item");
    expect(item).not.toHaveAttribute("data-disabled");
    expect(item).toHaveTextContent(reason);
    fireEvent.click(item);
    expect(onRun).toHaveBeenCalledWith({ mode: "sampled" });
  });
});
