import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { copyName, reloadTheirs, saveCopy, saveDesign } from "./save";

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
const CHANGED_ELSEWHERE = new ApiError("Project was modified elsewhere (revision 5, you had 3). Reload before saving.", 409);

const replicas = () => useEditor.getState().nodes.find((n) => n.id === "api")!.data.config.replicas;

/** The version saved elsewhere: revision 5, renamed, with seven API replicas. */
function theirs(): Project {
  const document = structuredClone(project.document);
  document.architecture.nodes.find((n) => n.id === "api")!.config.replicas = 7;
  return { ...project, name: "Theirs", revision: 5, document };
}

/** An editor whose save was just refused because the project was saved elsewhere. */
async function inConflict() {
  useEditor.getState().setNodeConfig("api", "replicas", 3);
  const refused = vi.spyOn(api, "updateProject").mockRejectedValueOnce(CHANGED_ELSEWHERE);
  await saveDesign();
  refused.mockRestore();
  expect(useEditor.getState().conflict).toBe(true);
}

describe("saving a design, before and after the project was saved elsewhere", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useEditor.getState().load(structuredClone(project), catalog);
  });

  it("saveDesign_sendsTheRevisionItWasEditedFrom", async () => {
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });

    const result = await saveDesign();

    expect(result).toEqual({ status: "saved", revision: 4 });
    expect(update).toHaveBeenCalledWith("p1", expect.objectContaining({ name: "Starter", expectedRevision: 3 }));
  });

  it("saveDesign_inConflict_isNotSentAgain", async () => {
    await inConflict();
    const update = vi.spyOn(api, "updateProject");

    const result = await saveDesign();

    expect(result).toEqual({ status: "conflict" });
    expect(update).not.toHaveBeenCalled();
  });

  it("saveDesign_toOverwrite_savesOverWhateverIsStoredAndEndsTheConflict", async () => {
    await inConflict();
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 6 });

    const result = await saveDesign({ overwrite: true });

    expect(result).toEqual({ status: "saved", revision: 6 });
    expect(update.mock.calls[0][1]).not.toHaveProperty("expectedRevision");
    expect(update.mock.calls[0][1].document?.architecture.nodes.find((n) => n.id === "api")?.config.replicas).toBe(3);
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: false, revision: 6 });
  });

  it("saveDesign_toOverwriteAndRefused_keepsTheConflict", async () => {
    await inConflict();
    vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("This workspace stores as much as it may (100 MB).", 409));

    const result = await saveDesign({ overwrite: true });

    expect(result).toEqual({ status: "failed", message: "This workspace stores as much as it may (100 MB)." });
    expect(useEditor.getState()).toMatchObject({ conflict: true, dirty: true, saving: false, revision: 3 });
  });

  it("reloadTheirs_opensTheStoredVersionSavedAndOutOfConflict", async () => {
    await inConflict();
    vi.spyOn(api, "getProject").mockResolvedValue(theirs());

    const revision = await reloadTheirs();

    expect(revision).toBe(5);
    expect(replicas()).toBe(7);
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: false, revision: 5 });
    expect(useEditor.getState().metadata.name).toBe("Theirs");
  });

  it("reloadTheirs_thenUndo_bringsThisVersionBackUnsaved", async () => {
    await inConflict();
    vi.spyOn(api, "getProject").mockResolvedValue(theirs());
    await reloadTheirs();

    useEditor.getState().undo();

    expect(replicas()).toBe(3);
    expect(useEditor.getState().metadata.name).toBe("Starter");
    // on top of their revision: saving it now is an ordinary save, not a conflict
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: true, revision: 5 });
  });

  it("reloadTheirs_answeredAfterAnotherProjectWasOpened_leavesThatProjectAlone", async () => {
    await inConflict();
    let answer!: (theirs: Project) => void;
    vi.spyOn(api, "getProject").mockReturnValue(new Promise<Project>((resolve) => (answer = resolve)));

    const reloading = reloadTheirs();
    useEditor.getState().load({ ...structuredClone(project), id: "p2", name: "Other", revision: 9 }, catalog);
    answer(theirs());

    expect(await reloading).toBeNull();
    expect(useEditor.getState()).toMatchObject({ projectId: "p2", revision: 9 });
    expect(useEditor.getState().metadata.name).toBe("Other");
  });

  it("saveCopy_storesThisVersionAsANewProjectAndLeavesTheOpenOneAsItIs", async () => {
    await inConflict();
    const create = vi.spyOn(api, "createProject").mockResolvedValue({ ...project, id: "p9", name: "Starter (copy)", revision: 1 });

    const copy = await saveCopy();

    expect(copy.id).toBe("p9");
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ name: "Starter (copy)" }));
    expect(create.mock.calls[0][0].document?.architecture.nodes.find((n) => n.id === "api")?.config.replicas).toBe(3);
    expect(useEditor.getState()).toMatchObject({ projectId: "p1", conflict: true, dirty: true, revision: 3 });
  });
});

describe("a copy's name", () => {
  it("copyName_addsCopyToTheName", () => {
    expect(copyName("Checkout")).toBe("Checkout (copy)");
    expect(copyName("  Checkout ")).toBe("Checkout (copy)");
  });

  it("copyName_ofAnUnnamedDesign_namesIt", () => {
    expect(copyName("  ")).toBe("Untitled architecture (copy)");
  });

  it("copyName_ofTheLongestName_staysWithinTheLongestName", () => {
    const name = copyName("x".repeat(120));

    expect(name).toHaveLength(120);
    expect(name.endsWith(" (copy)")).toBe(true);
  });
});
