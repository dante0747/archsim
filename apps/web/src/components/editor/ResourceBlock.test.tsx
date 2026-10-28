import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { Analysis, ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { ResourceBlock } from "./ResourceBlock";

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

const node = (id: string) => useEditor.getState().nodes.find((n) => n.id === id)!;
const comp = (type: string) => catalog.components.find((c) => c.type === type)!;

/** The starter's analysis as it reads at generic rates: no instance types, illustrative prices. */
function genericAnalysis(): Analysis {
  const a = structuredClone(analysisJson) as unknown as Analysis;
  a.cost.pricing = { ...a.cost.pricing!, provider: "generic", region: null, snapshotDate: null, illustrative: true, sources: [], sha256: null };
  for (const entry of Object.values(a.sizing.nodes)) entry.sku = null;
  return a;
}

function show(id: string) {
  const n = node(id);
  return render(<ResourceBlock nodeId={id} comp={comp(n.data.type)} config={n.data.config} />);
}

describe("ResourceBlock", () => {
  beforeEach(() => {
    useEditor.getState().load(structuredClone(project), catalog);
    // the engine's analysis of the starter (what POST /analyze returns), as the editor receives it
    useEditor.getState().setAnalysis(analysisJson as unknown as Analysis);
  });
  afterEach(cleanup);

  it("shows what a part needs beside what its instance has", () => {
    show("api");
    const lines = screen.getByTestId("need-vs-instance").textContent ?? "";
    // memory: the typical need, the most with full queues, and the instance's real memory
    expect(lines).toMatch(/needs \d+ MiB \(up to \d+ MiB when queues are full\) · c6g\.xlarge has 8 GiB/);
    expect(lines).toMatch(/needs 3\.39 vCPU \(2\.03 busy at the peak, at a 60% target\) · c6g\.xlarge has 4 vCPU/);
  });

  it("picking a size clears an instance type set on the part", () => {
    useEditor.getState().setNodeConfig("api", "instanceType", "m7g.2xlarge");
    show("api");
    fireEvent.click(screen.getByRole("button", { name: "2·4" }));
    expect(node("api").data.config).toMatchObject({ cpuCores: 2, memoryGib: 4, instanceType: null });
  });

  it("starts unset and applies a common size in one step", () => {
    show("api");
    expect(screen.getByLabelText(/CPU/)).toHaveProperty("value", "");
    fireEvent.click(screen.getByRole("button", { name: "4·16" }));
    expect(node("api").data.config).toMatchObject({ cpuCores: 4, memoryGib: 16 });
    useEditor.getState().undo();
    expect(node("api").data.config.cpuCores).toBeUndefined();
  });

  it("shows storage for databases, a price, and goes back to auto", () => {
    useEditor.getState().setNodeConfig("db", "cpuCores", 2);
    useEditor.getState().setNodeConfig("db", "memoryGib", 8);
    show("db");
    expect(screen.getByLabelText(/Storage/)).toBeTruthy();
    expect(screen.getByText(/the cost estimate uses it/)).toBeTruthy();
    cleanup();
    useEditor.getState().setNodeConfig("db", "costPerHour", 0.5);
    show("db");
    fireEvent.click(screen.getByRole("button", { name: "Use size price" }));
    expect(node("db").data.config.costPerHour).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Back to auto" }));
    expect(node("db").data.config).toMatchObject({ cpuCores: null, memoryGib: null, storageGib: null });
  });

  it("does not call a technology preset's price the user's", () => {
    useEditor.getState().setNodeConfig("db", "cpuCores", 2);
    useEditor.getState().setNodeConfig("db", "memoryGib", 8);
    useEditor.getState().setNodeConfig("db", "technology", "mysql");
    useEditor.getState().setNodeConfig("db", "costPerHour", 0.33); // the MySQL preset's price
    // at generic rates the preset's illustrative price is used
    useEditor.getState().setAnalysis(genericAnalysis());
    show("db");
    expect(screen.queryByText(/your price/)).toBeNull();
    expect(screen.getByText(/the technology preset's illustrative price of \$0\.33\/h/)).toBeTruthy();
    cleanup();
    // with a price snapshot the engine reprices it at the instance type: the estimate uses that
    const analysis = structuredClone(analysisJson) as unknown as Analysis;
    analysis.sizing.nodes.db = { ...analysis.sizing.nodes.db, sku: "db.gp.2x8", pricePerHour: 0.18 };
    analysis.cost.pricing = { ...analysis.cost.pricing!, provider: "example-cloud", region: "test-1", snapshotDate: "2026-10-01", illustrative: false };
    useEditor.getState().setAnalysis(analysis);
    show("db");
    expect(screen.queryByText(/your price/)).toBeNull();
    expect(screen.queryByText(/preset/)).toBeNull();
    expect(screen.getByText(/the cost estimate uses it/)).toBeTruthy();
    cleanup();
    // a price the user typed is still theirs
    useEditor.getState().setNodeConfig("db", "costPerHour", 0.5);
    show("db");
    expect(screen.getByText(/your price of \$0\.50\/h/)).toBeTruthy();
  });

  it("shows the auto size in empty boxes, with how it was worked out", () => {
    show("api");
    const cpu = screen.getByLabelText(/CPU/) as HTMLInputElement;
    expect(cpu.value).toBe("");
    expect(Number(cpu.placeholder)).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("auto").length).toBeGreaterThan(0);
    expect(screen.getByText("Auto-sized")).toBeTruthy();
    expect(screen.getByText(/req\/s per replica at peak/)).toBeTruthy();
    // the starter pins AWS prices: the auto size is a real instance type, priced from the snapshot
    expect(screen.getByText("c6g.xlarge")).toBeTruthy();
    expect(screen.getByText(/on-demand, aws us-east-1, prices of 2026-10-28/)).toBeTruthy();
    cleanup();
    // at generic rates, the size's illustrative price
    useEditor.getState().setAnalysis(genericAnalysis());
    show("api");
    expect(screen.getByText(/for this size/)).toBeTruthy();
  });

  it("an empty box means auto", () => {
    useEditor.getState().setNodeConfig("api", "cpuCores", 2);
    show("api");
    fireEvent.change(screen.getByLabelText(/CPU/), { target: { value: "" } });
    expect(node("api").data.config.cpuCores).toBeNull();
  });
});
