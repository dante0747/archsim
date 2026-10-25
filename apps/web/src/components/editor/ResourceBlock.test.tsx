import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
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

function show(id: string) {
  const n = node(id);
  return render(<ResourceBlock nodeId={id} comp={comp(n.data.type)} config={n.data.config} />);
}

describe("ResourceBlock", () => {
  beforeEach(() => useEditor.getState().load(structuredClone(project), catalog));
  afterEach(cleanup);

  it("starts unset and applies a common size in one step", () => {
    show("api");
    expect(screen.getByLabelText(/CPU/)).toHaveProperty("value", "");
    fireEvent.click(screen.getByRole("button", { name: "4·16" }));
    expect(node("api").data.config).toMatchObject({ cpuCores: 4, memoryGib: 16 });
    useEditor.getState().undo();
    expect(node("api").data.config.cpuCores).toBeUndefined();
  });

  it("shows storage for databases, a price, and clears back to unset", () => {
    useEditor.getState().setNodeConfig("db", "cpuCores", 2);
    useEditor.getState().setNodeConfig("db", "memoryGib", 8);
    show("db");
    expect(screen.getByLabelText(/Storage/)).toBeTruthy();
    expect(screen.getByText(/for this size/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Use as cost" }));
    expect(node("db").data.config.costPerHour).toBeCloseTo(0.0964, 4);
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(node("db").data.config).toMatchObject({ cpuCores: null, memoryGib: null, storageGib: null });
  });

  it("an empty box means not set", () => {
    useEditor.getState().setNodeConfig("api", "cpuCores", 2);
    show("api");
    fireEvent.change(screen.getByLabelText(/CPU/), { target: { value: "" } });
    expect(node("api").data.config.cpuCores).toBeNull();
  });
});
