import { ReactFlowProvider } from "@xyflow/react";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import { api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { type SentDesign, useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { SaveStatus } from "./SaveStatus";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), message: vi.fn() } }));

const catalog = catalogJson as unknown as Catalog;
const project: Project = {
  id: "p1",
  name: "Starter",
  description: null,
  revision: 3,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 6,
  document: starterJson as unknown as ArchitectureDocument,
};

const status = () => screen.getByRole("status");
const edit = () => act(() => useEditor.getState().setNodeConfig("api", "replicas", 3));

function show() {
  render(
    <ReactFlowProvider>
      <TooltipProvider>
        <SaveStatus />
      </TooltipProvider>
    </ReactFlowProvider>,
  );
}

describe("SaveStatus", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useEditor.getState().load(structuredClone(project), catalog);
    useUi.getState().resetForProject();
  });

  it("says Saved for a design as it was opened", () => {
    show();

    expect(status()).toHaveTextContent(/^Saved$/);
  });

  it("says Unsaved changes once the design is edited", () => {
    show();

    edit();

    expect(status()).toHaveTextContent(/^Unsaved changes$/);
  });

  it("says Saving while a save is on its way, then Saved", () => {
    show();
    edit();

    let sent!: SentDesign;
    act(() => void (sent = useEditor.getState().beginSave()));
    expect(status()).toHaveTextContent(/^Saving…$/);
    act(() => useEditor.getState().markSaved(sent, { revision: 4 }));

    expect(status()).toHaveTextContent(/^Saved$/);
  });

  it("says Not saved when a save failed, and saves again when pressed", async () => {
    show();
    edit();
    act(() => useEditor.getState().saveFailed(useEditor.getState().beginSave(), { conflict: false, error: { message: "The server is busy.", retry: true } }));
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });

    await userEvent.click(screen.getByRole("button", { name: "Not saved" }));

    expect(update).toHaveBeenCalledTimes(1);
    expect(status()).toHaveTextContent(/^Saved$/);
  });

  it("says Changed elsewhere for a project in conflict, and opens the choices when pressed", async () => {
    show();
    edit();
    act(() => useEditor.getState().saveFailed(useEditor.getState().beginSave(), { conflict: true }));

    await userEvent.click(screen.getByRole("button", { name: "Changed elsewhere" }));

    expect(useUi.getState().saveConflictOpen).toBe(true);
  });
});
