import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AssumptionsTab } from "@/components/results/AssumptionsTab";
import { TooltipProvider } from "@/components/ui/misc";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { Analysis, ArchitectureDocument, Catalog, Project, Report, Run } from "@/lib/types";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
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

function runOf(id: string, status: Run["status"], scenarioId: string | null = null): Run {
  return {
    id,
    projectId: "p1",
    label: id,
    scenarioId,
    scenarioName: null,
    status,
    progress: status === "succeeded" ? 1 : 0.3,
    error: null,
    seed: 42,
    engineVersion: "0.12.0",
    inputDigest: null,
    resultDigest: null,
    summary: null,
    wallSeconds: null,
    createdAt: "2026-01-01T00:00:00Z",
    startedAt: null,
    finishedAt: null,
  };
}

/** A starter run as the Results header reads it, with the engine's estimate and confidence. */
function report(withConfidence = true): Report {
  return {
    reportVersion: withConfidence ? "1.13" : "1.12",
    summary: {
      offeredRps: 400,
      throughputRps: 399,
      successRate: 1,
      latencyMs: { p50: 40, p95: 90.8, p99: 130 },
      latencyWithFailuresMs: { p95: 90.8 },
    },
    findings: [{ severity: "info", kind: "bottleneck", nodeId: "api", title: "API Servers is the primary bottleneck", detail: "", evidence: {} }],
    nodes: [{ id: "api", summary: { utilization: 0.698, cpuUtilization: 0.51 } }],
    assumptions: { modelNotes: [], userInputs: [], components: {}, validationWarnings: [], technologies: analysis.technologies },
    provenance: { resultDigest: "0123456789abcdef", inputDigest: "abc", seed: 42 },
    ...(withConfidence ? { confidence: analysis.confidence, estimate: analysis.estimate } : {}),
  } as unknown as Report;
}

function show(ui: React.ReactElement) {
  return render(<TooltipProvider>{ui}</TooltipProvider>);
}

describe("Results header", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setAnalysis(structuredClone(analysis));
    useEditor.getState().setObjectives({ latencyP95Ms: 300, availability: 0.999 });
    useDecide.setState({ scorecardRunId: null, section: "answer" });
  });
  afterEach(() => {
    cleanup();
    useRuns.setState({ runs: [], reports: {}, activeRunId: null, followRunId: null });
  });

  it("carries the confidence badge and its reason with every verdict, linked to the inputs", () => {
    useRuns.setState({ runs: [runOf("r1", "succeeded")], reports: { r1: report() }, activeRunId: "r1" });
    show(<AnswerPage />);
    expect(screen.getByRole("heading", { name: "Yes, it holds up." })).toBeTruthy();
    const line = screen.getByTestId("confidence-line");
    expect(within(line).getByTestId("confidence-badge")).toHaveTextContent("Low confidence");
    expect(within(line).getByTestId("confidence-badge").dataset.level).toBe("low");
    expect(within(line).getByTestId("confidence-reason")).toHaveTextContent("31 of 35 inputs are illustrative defaults");
    fireEvent.click(within(line).getByRole("button", { name: "how to raise it" }));
    expect(useDecide.getState().section).toBe("details");
    expect(useDecide.getState().detailsView).toBe("assumptions");
  });

  it("sets the run's estimate against what it simulated", () => {
    useRuns.setState({ runs: [runOf("r1", "succeeded")], reports: { r1: report() }, activeRunId: "r1" });
    show(<AnswerPage />);
    expect(screen.getByTestId("estimate-vs-sim")).toHaveTextContent("Queueing formulas: p95 ≈ 97 ms (simulated 91 ms); API Servers 70% of its slots busy (simulated 70%).");
  });

  it("names what the formulas leave out as a link, not a stray label", () => {
    useRuns.setState({ runs: [runOf("r1", "succeeded")], reports: { r1: report() }, activeRunId: "r1" });
    show(<AnswerPage />);
    const line = screen.getByTestId("estimate-vs-sim");

    expect(within(line).getByRole("button", { name: "What the formulas leave out" })).toBeInTheDocument();
    expect(within(line).queryByRole("button", { name: "not simulated" })).toBeNull();
  });

  it("falls back to the design's confidence for runs made before it was recorded", () => {
    useRuns.setState({ runs: [runOf("r1", "succeeded")], reports: { r1: report(false) }, activeRunId: "r1" });
    show(<AnswerPage />);
    expect(screen.getByTestId("confidence-reason")).toHaveTextContent("31 of 35 inputs are illustrative defaults (current design; simulate again");
    expect(screen.queryByTestId("estimate-vs-sim")).toBeNull();
  });

  it("shows the instant estimate while a run is queued or running", () => {
    useRuns.setState({ runs: [runOf("r2", "running")], reports: {}, followRunId: "r2" });
    show(<AnswerPage />);
    expect(screen.getByTestId("estimate-pending")).toHaveTextContent(/^Estimate from queueing formulas \(not simulated\): p95 ≈ \d+ ms; busiest API Servers 70%\./);
  });

  it("does not borrow the base design's estimate for a scenario's run", () => {
    useRuns.setState({ runs: [runOf("r3", "queued", "one-api-replica")], reports: {}, followRunId: "r3" });
    show(<AnswerPage />);
    expect(screen.queryByTestId("estimate-pending")).toBeNull();
  });
});

describe("inputs list", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setAnalysis(structuredClone(analysis));
  });
  afterEach(cleanup);

  it("explains the level: why, what to replace first, and the rules", () => {
    show(<AssumptionsTab />);
    const section = document.getElementById("confidence")!;
    expect(within(section).getByTestId("confidence-badge")).toHaveTextContent("Low confidence");
    expect(within(section).getByText("31 of 35 inputs are illustrative defaults.")).toBeTruthy();
    const first = within(section).getAllByRole("listitem").find((li) => li.textContent?.includes("Service time"));
    expect(first?.textContent).toContain("API Servers");
    expect(section.textContent).toContain("High needs 80% of that weight measured");
    expect(screen.getByText(/0 documented by the vendor/)).toBeTruthy();
  });
});
