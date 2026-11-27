import { ReactFlowProvider } from "@xyflow/react";
import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { useEditorActions } from "./actions";

const updateProject = vi.hoisted(() => vi.fn());
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), message: vi.fn() }));

vi.mock("@/lib/api", async (original) => {
  const actual = await original<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, updateProject } };
});
vi.mock("sonner", () => ({ toast }));

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

/** The API's answer to a save, held back until the test lets it go. */
function heldAnswer() {
  let answer!: (saved: Project) => void;
  updateProject.mockReturnValueOnce(new Promise<Project>((resolve) => (answer = resolve)));
  return (saved: Partial<Project>) => answer({ ...project, ...saved });
}

function actions() {
  return renderHook(() => useEditorActions(), { wrapper: ReactFlowProvider }).result.current;
}

const replicas = () => useEditor.getState().nodes.find((n) => n.id === "api")!.data.config.replicas;

describe("saving the design", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useEditor.getState().load(structuredClone(project), catalog);
    useUi.getState().resetForProject();
  });

  it("save_withNothingEditedMeanwhile_marksTheDesignSaved", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    const answer = heldAnswer();

    const saving = actions().save();
    answer({ revision: 4 });
    await saving;

    expect(useEditor.getState()).toMatchObject({ dirty: false, saving: false, revision: 4 });
  });

  it("save_withAnEditMadeWhileItIsOnItsWay_keepsThatEditUnsaved", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    const answer = heldAnswer();

    const saving = actions().save();
    useEditor.getState().setNodeConfig("api", "replicas", 4);
    answer({ revision: 4 });
    await saving;

    expect(replicas()).toBe(4);
    expect(useEditor.getState()).toMatchObject({ dirty: true, saving: false, revision: 4 });
  });

  it("save_withTheNameRetypedWhileItIsOnItsWay_keepsTheRetypedName", async () => {
    useEditor.getState().setMetadata({ name: "First" });
    const answer = heldAnswer();

    const saving = actions().save();
    useEditor.getState().setMetadata({ name: "Second" });
    answer({ revision: 4, name: "First" });
    await saving;

    expect(useEditor.getState().metadata.name).toBe("Second");
    expect(useEditor.getState().dirty).toBe(true);
  });

  it("save_withAnEditUndoneWhileItIsOnItsWay_marksTheDesignSaved", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    const answer = heldAnswer();

    const saving = actions().save();
    useEditor.getState().setNodeConfig("api", "replicas", 4);
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    answer({ revision: 4 });
    await saving;

    expect(useEditor.getState().dirty).toBe(false);
  });

  it("save_answeredAfterAnotherProjectWasOpened_leavesThatProjectAlone", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    const answer = heldAnswer();

    const saving = actions().save();
    useEditor.getState().load({ ...structuredClone(project), id: "p2", name: "Other", revision: 9 }, catalog);
    answer({ revision: 4 });
    await saving;

    expect(useEditor.getState()).toMatchObject({ projectId: "p2", revision: 9, dirty: false, saving: false });
    expect(useEditor.getState().metadata.name).toBe("Other");
  });

  it("save_refusedBecauseTheProjectChangedElsewhere_recordsTheConflictAndKeepsTheEdits", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    updateProject.mockRejectedValueOnce(
      new ApiError("Project was modified elsewhere (revision 5, you had 3). Reload before saving.", 409),
    );

    await actions().save();

    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true, saving: false, revision: 3 });
    expect(replicas()).toBe(3);
  });

  it("save_refusedBecauseTheProjectChangedElsewhere_asksWhichVersionToKeep", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    updateProject.mockRejectedValueOnce(
      new ApiError("Project was modified elsewhere (revision 5, you had 3). Reload before saving.", 409),
    );

    await actions().save();

    expect(useUi.getState().saveConflictOpen).toBe(true);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("save_ofADesignStillInConflict_asksAgainWithoutSendingIt", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    useEditor.setState({ conflict: true });

    await actions().save();

    expect(updateProject).not.toHaveBeenCalled();
    expect(useUi.getState().saveConflictOpen).toBe(true);
  });

  it("save_refusedForAnotherReason_saysSoAndRecordsNoConflict", async () => {
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    updateProject.mockRejectedValueOnce(new ApiError("This workspace stores as much as it may (100 MB).", 409));

    await actions().save();

    expect(toast.error).toHaveBeenCalledWith("Save failed", { description: "This workspace stores as much as it may (100 MB)." });
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: true, saving: false });
    expect(useUi.getState().saveConflictOpen).toBe(false);
  });
});
