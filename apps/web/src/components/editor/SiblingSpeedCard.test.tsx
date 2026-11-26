import { fireEvent, render, screen } from "@testing-library/react";
import { Tooltip } from "radix-ui";
import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, FieldDescriptor, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { SiblingSpeedCard } from "./SiblingSpeedCard";

/** The design-wide setting as the engine's catalog describes it (schema 1.11). */
const siblingSpeed: FieldDescriptor = {
  key: "siblingSpeed",
  label: "vCPU sibling speed",
  kind: "number",
  nullable: true,
  description: "The speed of a vCPU whose hyperthread sibling is busy, for every part that sets none.",
  default: null,
  assumption: false,
  headline: false,
  min: 0,
  exclusiveMin: true,
  max: 1,
  group: "Machines",
  step: 0.05,
};
const catalog = { ...(catalogJson as unknown as Catalog), architectureFields: [siblingSpeed] };

/** The starter as a project, saved at ``schemaVersion`` (the fixture is a 1.3 design). */
function opened(schemaVersion: string, withCatalog: Catalog = catalog) {
  const document = { ...structuredClone(starterJson), schemaVersion } as unknown as ArchitectureDocument;
  const project: Project = {
    id: "p1",
    name: "Starter",
    description: null,
    revision: 1,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    nodeCount: 5,
    scenarioCount: 0,
    document,
  };
  useEditor.getState().load(project, withCatalog);
  return render(
    <Tooltip.Provider>
      <SiblingSpeedCard />
    </Tooltip.Provider>,
  );
}

const saved = () => useEditor.getState().toDocument().architecture;

describe("SiblingSpeedCard", () => {
  it("shows a design saved before schema 1.11 pinned to whole cores", () => {
    opened("1.10");

    expect(screen.getByLabelText("vCPU sibling speed")).toHaveValue(1);
    expect(screen.getByText(/Now: whole cores, set here or kept from before schema 1\.11/)).toBeInTheDocument();
  });

  it("moves a pinned design onto the default in one click", () => {
    opened("1.10");

    fireEvent.click(screen.getByRole("button", { name: "Use the default" }));

    expect(saved()).not.toHaveProperty("siblingSpeed");
    expect(useEditor.getState().dirty).toBe(true);
    expect(screen.getByText(/Now: each part's instance family decides \(x86 types count as hyperthread pairs\)/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Use the default" })).toBeNull();
  });

  it("writes a sibling speed the user sets", () => {
    opened("1.11");
    fireEvent.click(screen.getByLabelText("vCPU sibling speed: Auto"));
    const input = screen.getByLabelText("vCPU sibling speed");

    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "0.55" } });

    expect(saved().siblingSpeed).toBe(0.55);
  });

  it("renders nothing for a server whose catalog has no design-wide settings", () => {
    const { container } = opened("1.10", { ...catalog, architectureFields: undefined });

    expect(container).toBeEmptyDOMElement();
  });
});
