import { cleanup, render, screen, within } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { PartsPanel } from "./Palette";

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

function show() {
  return render(
    <ReactFlowProvider>
      <TooltipProvider>
        <PartsPanel />
      </TooltipProvider>
    </ReactFlowProvider>,
  );
}

describe("Parts library", () => {
  beforeEach(() => {
    localStorage.removeItem("archsim-parts");
    useEditor.getState().load(structuredClone(project), catalog);
  });
  afterEach(cleanup);

  it("lists each part by its full name, with what it is beneath", () => {
    show();

    const data = screen.getByRole("region", { name: "Data" });
    const postgres = within(data).getByRole("button", { name: /^PostgreSQL:/ });
    expect(postgres).toHaveTextContent("PostgreSQL");
    expect(within(postgres).getByText("Relational")).toBeVisible();
    const generic = within(data).getByRole("button", { name: /^Any database:/ });
    expect(within(generic).getByText("Any technology, set it up yourself")).toBeVisible();
  });
});
