import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { saveDesign, saveOnClose, whenClosed } from "@/lib/save";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { LeaveDialog } from "./LeaveDialog";

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

const dialog = () => screen.queryByRole("dialog", { name: "Your changes are not saved" });

/** The dialog over an edited design whose way out was held back for `reason`. */
function show(reason: string, conflict = false) {
  const go = vi.fn();
  useEditor.getState().load(structuredClone(project), catalog);
  useEditor.getState().setNodeConfig("api", "replicas", 3);
  useUi.setState({ leaveHeld: { reason, conflict, go }, saveConflictOpen: false });
  render(<LeaveDialog />);
  return go;
}

describe("LeaveDialog", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("says why the changes are not saved", () => {
    show("The database is not reachable.");

    expect(dialog()).toHaveAccessibleDescription("The database is not reachable.");
  });

  it("Stay keeps the editor open and the changes unsaved", async () => {
    const go = show("The database is not reachable.");

    await userEvent.click(screen.getByRole("button", { name: "Stay" }));

    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(go).not.toHaveBeenCalled();
    expect(useEditor.getState().dirty).toBe(true);
  });

  it("Leave without saving makes the move, and the closing editor saves nothing", async () => {
    const go = show("The database is not reachable.");
    const update = vi.spyOn(api, "updateProject");

    await userEvent.click(screen.getByRole("button", { name: "Leave without saving" }));
    saveOnClose(null, vi.fn());
    await whenClosed();

    expect(go).toHaveBeenCalledTimes(1);
    expect(update).not.toHaveBeenCalled();
    expect(useUi.getState().leaveHeld).toBeNull();
  });

  it("for a project changed elsewhere, offers the choice of version instead of staying", async () => {
    const go = show("This project was changed elsewhere, and you have not chosen which version to keep.", true);

    expect(screen.queryByRole("button", { name: "Stay" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Choose a version" }));

    expect(useUi.getState().saveConflictOpen).toBe(true);
    expect(useUi.getState().leaveHeld).toBeNull();
    expect(go).not.toHaveBeenCalled();
  });

  it("goes ahead by itself when the save goes through after all", async () => {
    const go = show("The save is taking a long time.");
    vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });

    await act(() => saveDesign());

    await waitFor(() => expect(go).toHaveBeenCalledTimes(1));
    expect(dialog()).not.toBeInTheDocument();
    expect(useUi.getState().leaveHeld).toBeNull();
  });

  it("closing it stays", async () => {
    const go = show("The database is not reachable.");

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(go).not.toHaveBeenCalled();
  });
});
