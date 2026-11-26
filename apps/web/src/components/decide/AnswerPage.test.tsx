import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Confidence, Project, Report, Run } from "@/lib/types";
import { useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useTeam } from "@/store/team";
import { AnswerPage } from "./Scorecard";

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

const LOW: Confidence = {
  level: "low",
  reason: "28 of 42 inputs are illustrative defaults",
  reasons: ["28 of 42 inputs are illustrative defaults"],
  drivers: [],
  weightedBy: "latency",
  howToRaise: "Replace the defaults on the busiest parts with measured values.",
};

const RUN: Run = {
  id: "r1",
  projectId: "p1",
  label: "Current design",
  scenarioId: null,
  scenarioName: null,
  status: "succeeded",
  progress: 1,
  error: null,
  seed: 42,
  engineVersion: "1.6.0",
  inputDigest: null,
  resultDigest: null,
  summary: { offeredRps: 950, throughputRps: 937, successRate: 0.988, timeoutRate: 0, errorRate: 0.012, p50: 120, p95: 561, p99: 900, bottleneck: "Checkout API", bottleneckNodeId: "api" },
  wallSeconds: null,
  createdAt: "2026-01-01T00:00:00Z",
  startedAt: null,
  finishedAt: null,
};

const BOTTLENECK_DETAIL = "On average 79% of its workers were busy, peaking at 100%. The queue averaged 72.4 requests (peak 400).";

function report(extra: Record<string, unknown> = {}): Report {
  return {
    reportVersion: "1.17",
    summary: { offeredRps: 950, throughputRps: 937, successRate: 0.988, latencyMs: { p50: 120, p95: 561, p99: 900 }, latencyWithFailuresMs: { p95: 608 } },
    findings: [
      { severity: "critical", kind: "bottleneck", nodeId: "api", title: "Checkout API is the primary bottleneck", detail: BOTTLENECK_DETAIL, suggestion: "Add replicas or workers to Checkout API.", evidence: {} },
      { severity: "warning", kind: "failures", nodeId: "api", title: "Main cause of failed requests", detail: "1,287 requests failed at Checkout API: queue was full.", evidence: {} },
      { severity: "info", kind: "hot", nodeId: "db", title: "Orders DB is warm", detail: "Its connections were 60% busy.", evidence: {} },
    ],
    nodes: [],
    assumptions: { modelNotes: [], userInputs: [], components: {}, validationWarnings: [], technologies: [{ nodeId: "gw", label: "API gateway", technology: "kong", tier: "experimental" }] },
    provenance: { resultDigest: "0123456789abcdef", inputDigest: "abc", seed: 42 },
    confidence: LOW,
    cost: {
      monthly: 844,
      perMillionRequests: 0.96,
      byNode: [
        { id: "db", label: "Orders DB", type: "database", technology: "postgresql", instances: 3, unitPrice: 0.26, priceSource: "snapshot", unit: "hour", monthly: 569, basis: "1 primary + 2 replicas" },
        { id: "api", label: "Checkout API", type: "service", technology: "go", instances: 3, unitPrice: 0.12, priceSource: "snapshot", unit: "hour", monthly: 275, basis: "3 replicas" },
      ],
      notes: [],
      hasCost: true,
    },
    ...extra,
  } as unknown as Report;
}

function show() {
  return render(
    <TooltipProvider>
      <AnswerPage />
    </TooltipProvider>,
  );
}

describe("Results: the answer", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setObjectives({ latencyP95Ms: 500, availability: 0.995 });
    useDecide.setState({ scorecardRunId: null, section: "answer" });
    useTeam.setState({ projectId: "p1", comments: [], signoffs: [], loaded: true, error: null });
    useRuns.setState({ projectId: "p1", runs: [RUN], reports: { r1: report() }, activeRunId: "r1" });
  });
  afterEach(() => {
    cleanup();
    useRuns.setState({ runs: [], reports: {}, activeRunId: null, followRunId: null, pending: [] });
  });

  it("leads with the verdict, the objectives it misses and the bottleneck, without repeating the numbers", () => {
    show();

    const answer = screen.getByRole("region", { name: "Answer" });
    expect(within(answer).getByRole("heading", { level: 2 })).toHaveTextContent("No, it doesn’t hold up.");
    expect(within(answer).getByTestId("verdict-summary")).toHaveTextContent("Misses 2 of 2 objectives");
    expect(within(answer).getByTestId("verdict-cause")).toHaveTextContent("Checkout API is the primary bottleneck");
    expect(within(answer).getByRole("button", { name: "Show API Servers on the canvas" })).toBeInTheDocument();
    // each objective's figures appear once, in its tile
    expect(within(answer).getAllByText("561 ms")).toHaveLength(1);
    expect(within(answer).getByRole("group", { name: "Latency p95" })).toHaveTextContent("target ≤ 500 ms");
  });

  it("gathers the objectives without a target in one tile, and sets one from there", () => {
    show();

    const answer = screen.getByRole("region", { name: "Answer" });
    expect(within(answer).queryByRole("group", { name: "Throughput" })).toBeNull();

    fireEvent.click(within(answer).getByRole("button", { name: "Throughput" }));
    fireEvent.change(within(answer).getByRole("textbox", { name: "Throughput target" }), { target: { value: "900" } });
    fireEvent.click(within(answer).getByRole("button", { name: "Save target" }));

    expect(useEditor.getState().objectives.minThroughputRps).toBe(900);
    expect(within(answer).getByRole("group", { name: "Throughput" })).toHaveTextContent("target ≥ 900 req/s");
  });

  it("names each caveat in a few words and explains it on demand", () => {
    show();

    const caveats = screen.getByRole("list", { name: "How sure is this" });
    expect(within(caveats).getByTestId("confidence-badge").dataset.level).toBe("low");
    expect(within(caveats).getByTestId("confidence-reason")).toHaveTextContent("28 of 42 inputs are illustrative defaults");
    expect(within(caveats).getByText("1 experimental part")).toBeInTheDocument();
    expect(screen.queryByText(LOW.howToRaise)).toBeNull();

    fireEvent.click(within(caveats).getByRole("button", { name: "About the confidence" }));

    expect(screen.getByText(LOW.howToRaise)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Measured accuracy" })).toHaveAttribute("href", "/accuracy");
  });

  it("shows four key numbers: handled, latency, success and cost", () => {
    show();

    const numbers = screen.getByRole("list", { name: "Key numbers" });
    const items = within(numbers).getAllByRole("listitem");
    expect(items.map((i) => within(i).getByRole("term").textContent)).toEqual(["Handled", "Latency p95", "Success", "Cost per month"]);
    expect(items[0]).toHaveTextContent("937 req/s");
    expect(items[0]).toHaveTextContent("of 950 req/s offered");
    expect(items[1]).toHaveTextContent("608 ms counting failures");
    expect(items[2]).toHaveTextContent("98.8%");
    expect(items[3]).toHaveTextContent("$844");
  });

  it("lists what to fix first by title, with the details folded until asked for", () => {
    show();

    const fix = screen.getByRole("region", { name: "Fix first" });
    const first = within(fix).getAllByRole("listitem")[0];
    expect(first).toHaveTextContent("Checkout API is the primary bottleneck");
    expect(first).toHaveTextContent("Add replicas or workers to Checkout API.");
    expect(within(first).getByText(BOTTLENECK_DETAIL)).not.toBeVisible();

    fireEvent.click(within(first).getByText("Why"));

    expect(within(first).getByText(BOTTLENECK_DETAIL)).toBeVisible();
    expect(within(fix).getByRole("button", { name: /All 3 findings/ })).toBeInTheDocument();
  });

  it("splits the month's cost by part in one bar, the largest first", () => {
    show();

    const cost = screen.getByRole("region", { name: "Cost" });
    expect(cost).toHaveTextContent("$844");
    const parts = within(cost).getByRole("list", { name: "Cost by part" });
    expect(within(parts).getAllByRole("listitem").map((li) => li.textContent)).toEqual([expect.stringContaining("Orders DB"), expect.stringContaining("Checkout API")]);
  });
});
