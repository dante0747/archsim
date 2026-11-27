import { ReactFlowProvider } from "@xyflow/react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { SaveConflictDialog } from "./SaveConflictDialog";

const push = vi.hoisted(() => vi.fn());
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), message: vi.fn() }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("sonner", () => ({ toast }));

const catalog = catalogJson as unknown as Catalog;
const project: Project = {
  id: "p1",
  name: "Checkout",
  description: null,
  revision: 3,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 6,
  document: starterJson as unknown as ArchitectureDocument,
};

const dialog = () => screen.queryByRole("dialog", { name: "This project was changed elsewhere" });
const choice = (name: string) => screen.getByRole("button", { name });

/** The dialog over a design whose save was refused because the project was saved elsewhere. */
function show() {
  useEditor.getState().load(structuredClone(project), catalog);
  useEditor.getState().setNodeConfig("api", "replicas", 3);
  useEditor.setState({ conflict: true });
  useUi.setState({ saveConflictOpen: true });
  render(
    <ReactFlowProvider>
      <SaveConflictDialog />
    </ReactFlowProvider>,
  );
}

describe("SaveConflictDialog", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
  });

  it("offers three ways out and says what each one loses", () => {
    show();

    expect(choice("Reload theirs")).toHaveAccessibleDescription("Open the version saved elsewhere in place of yours. Undo brings yours back.");
    expect(choice("Overwrite")).toHaveAccessibleDescription("Save your version over theirs. Their changes are lost.");
    expect(choice("Save a copy")).toHaveAccessibleDescription(
      "Keep both: yours becomes a new project, “Checkout (copy)”, and this one keeps theirs.",
    );
  });

  it("stays out of the way until a save meets a conflict", () => {
    show();
    useUi.setState({ saveConflictOpen: false });

    return waitFor(() => expect(dialog()).not.toBeInTheDocument());
  });

  it("Reload theirs opens the stored version and closes", async () => {
    show();
    vi.spyOn(api, "getProject").mockResolvedValue({ ...project, name: "Theirs", revision: 5 });

    await userEvent.click(choice("Reload theirs"));

    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: false, revision: 5 });
    expect(useEditor.getState().metadata.name).toBe("Theirs");
    expect(toast.success).toHaveBeenCalledWith("Reloaded revision 5", { description: "Undo brings your version back." });
  });

  it("Overwrite saves this version over theirs and closes", async () => {
    show();
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 6 });

    await userEvent.click(choice("Overwrite"));

    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(update.mock.calls[0][1]).not.toHaveProperty("expectedRevision");
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: false, revision: 6 });
    expect(toast.success).toHaveBeenCalledWith("Saved over their version", { description: "Revision 6" });
  });

  it("Save a copy stores this version as a new project and opens it", async () => {
    show();
    const create = vi.spyOn(api, "createProject").mockResolvedValue({ ...project, id: "p9", name: "Checkout (copy)", revision: 1 });

    await userEvent.click(choice("Save a copy"));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/projects/p9"));
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ name: "Checkout (copy)" }));
    expect(dialog()).not.toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith("Saved as a copy", { description: "Checkout (copy)" });
  });

  it("a choice that fails says why and leaves the choice open", async () => {
    show();
    vi.spyOn(api, "createProject").mockRejectedValue(new ApiError("This workspace holds as many projects as it may (100).", 409));

    await userEvent.click(choice("Save a copy"));

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith("Could not save a copy", { description: "This workspace holds as many projects as it may (100)." }),
    );
    expect(dialog()).toBeInTheDocument();
    expect(choice("Save a copy")).toBeEnabled();
    expect(push).not.toHaveBeenCalled();
    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true });
  });

  it("Overwrite that loses to a save made at the same moment asks again", async () => {
    show();
    vi.spyOn(api, "updateProject").mockRejectedValue(
      new ApiError("Project was modified elsewhere (revision 6, you had 5). Reload before saving.", 409),
    );

    await userEvent.click(choice("Overwrite"));

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith("Could not overwrite", { description: "It was saved elsewhere again just now. Choose again." }),
    );
    expect(dialog()).toBeInTheDocument();
    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true });
  });

  it("closing it chooses nothing: the design stays unsaved and in conflict", async () => {
    show();

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() => expect(dialog()).not.toBeInTheDocument());
    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true, revision: 3 });
  });
});
