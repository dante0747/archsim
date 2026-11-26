import { render, screen } from "@testing-library/react";
import { Tooltip } from "radix-ui";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { Analysis, ArchitectureDocument, Catalog, FieldDescriptor, Project, SizingEntry } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { Inspector, partHints } from "./Inspector";

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
const database = catalog.components.find((c) => c.type === "database")!;
const hyperthreads: SizingEntry["vcpus"] = {
  siblingSpeed: 0.6,
  basis: "default",
  text: "hyperthread pairs at a sibling speed of 0.6, a busy pair doing 1.2 cores' work: the default, as on most x86 instances",
};

/** The starter's analysis, with the database's vCPUs counted as ``vcpus`` (undefined: not counted). */
function analysisCounting(vcpus: SizingEntry["vcpus"]): Analysis {
  const a = structuredClone(analysisJson) as unknown as Analysis;
  a.sizing.nodes.db = { ...a.sizing.nodes.db, vcpus };
  return a;
}

describe("partHints", () => {
  it("adds what the last analysis counted the part's vCPUs as to its technology's defaults", () => {
    const sizing = analysisCounting(hyperthreads).sizing.nodes.db;

    const hints = partHints(database, { technology: "postgresql" }, sizing);

    expect(hints?.siblingSpeed).toMatchObject({ value: 0.6, text: "0.6 (hyperthread pairs, the default)" });
    expect(hints?.replicaApplyWorkers).toMatchObject({ value: 1, source: "PostgreSQL" });
  });

  it("gives no vCPU hint when the analysis does not count the part's vCPUs", () => {
    const sizing = analysisCounting(undefined).sizing.nodes.db;

    expect(partHints(database, { technology: "postgresql" }, sizing)).not.toHaveProperty("siblingSpeed");
    expect(partHints(database, {}, null)).toBeUndefined();
  });
});

describe("Inspector", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    useEditor.getState().setNodeConfig("db", "technology", "postgresql");
    useEditor.getState().select({ nodeId: "db" });
  });
  afterEach(() => vi.restoreAllMocks());

  it("shows a part's empty sibling speed as what the last analysis counted", () => {
    useEditor.getState().setAnalysis(analysisCounting(hyperthreads));

    render(
      <Tooltip.Provider>
        <Inspector />
      </Tooltip.Provider>,
    );

    expect(screen.getByText("0.6 (hyperthread pairs, the default)")).toBeInTheDocument();
    // the technology's defaults still stand behind its other empty settings
    expect(screen.getAllByText(/\(PostgreSQL, (documented|illustrative)\)$/).length).toBeGreaterThan(0);
  });

  it("tags each of a part's inputs with where the design's analysis says its value comes from", () => {
    const analysis = analysisCounting(hyperthreads);
    analysis.inputs = [
      { scope: "node", id: "db", label: "PostgreSQL", field: "readServiceTimeMs", fieldLabel: "Read query time", value: "p50 5 ms", provenance: { kind: "illustrative", source: "ArchSim default" } },
      { scope: "node", id: "api", label: "API Servers", field: "serviceTimeMs", fieldLabel: "Service time", value: "p50 8 ms", provenance: { kind: "assumed" } },
    ];
    useEditor.getState().setAnalysis(analysis);

    render(
      <Tooltip.Provider>
        <Inspector />
      </Tooltip.Provider>,
    );

    const readTime = document.querySelector('[data-field="readServiceTimeMs"]') as HTMLElement;
    expect(readTime.querySelector('[data-testid="input-source"]')).toHaveTextContent("default");
    // another part's inputs are not this part's
    expect(document.querySelectorAll('[data-testid="input-source"]')).toHaveLength(1);
  });

  it("shows the design's sibling speed in the overview, with nothing selected", () => {
    const field: FieldDescriptor = {
      key: "siblingSpeed",
      label: "vCPU sibling speed",
      kind: "number",
      nullable: true,
      description: "",
      default: null,
      assumption: false,
      headline: false,
      min: 0,
      exclusiveMin: true,
      max: 1,
      group: "Machines",
      step: 0.05,
    };
    vi.spyOn(api, "pricing").mockResolvedValue({ sources: [] });
    useEditor.setState({ catalog: { ...catalog, architectureFields: [field] } });
    useEditor.getState().select({});

    render(
      <Tooltip.Provider>
        <Inspector />
      </Tooltip.Provider>,
    );

    expect(screen.getByLabelText("vCPU sibling speed")).toHaveValue(1);
  });
});
