import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { LEAVE_PATIENCE_MS, leaveWithoutSaving, saveBeforeLeaving, saveDesign, saveOnClose, whenClosed } from "./save";

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
const NOT_CHOSEN = "This project was changed elsewhere, and you have not chosen which version to keep.";

const edit = (replicas: number) => useEditor.getState().setNodeConfig("api", "replicas", replicas);
const sentReplicas = (call: unknown[]) =>
  (call[1] as { document: ArchitectureDocument }).document.architecture.nodes.find((n) => n.id === "api")?.config.replicas;

/** The API's answer to a save, held back until the test lets it go. */
function heldAnswer(update: { mockReturnValueOnce: (value: Promise<Project>) => unknown }) {
  let answer!: (saved: Project) => void;
  update.mockReturnValueOnce(new Promise<Project>((resolve) => (answer = resolve)));
  return (revision: number) => answer({ ...project, revision });
}

beforeEach(() => {
  vi.restoreAllMocks();
  useEditor.getState().load(structuredClone(project), catalog);
});

describe("a save's failure", () => {
  it.each([
    ["the network is down", new ApiError("Network error: cannot reach the ArchSim web server.", 0), true],
    ["the server is busy", new ApiError("The server is busy.", 503), true],
    ["the person is asked to wait", new ApiError("Too many requests. Try again in 20 s.", 429), true],
    ["the person may not save here", new ApiError("You need the editor role in this workspace.", 403), false],
    ["the workspace is full", new ApiError("This workspace stores as much as it may (100 MB).", 409), false],
    ["the API cannot read the design", new ApiError("The document is not a valid ArchSim document.", 422), false],
  ])("saveDesign_when %s_saysWhetherTryingAgainMayHelp", async (_why, refusal, retry) => {
    edit(3);
    vi.spyOn(api, "updateProject").mockRejectedValue(refusal);

    const result = await saveDesign();

    expect(result).toEqual({ status: "failed", message: refusal.message, retry });
    expect(useEditor.getState()).toMatchObject({ dirty: true, saving: false, saveError: { message: refusal.message, retry } });
  });

  it("saveDesign_thatGoesThroughAfterAFailure_clearsTheFailure", async () => {
    edit(3);
    vi.spyOn(api, "updateProject").mockRejectedValueOnce(new ApiError("The server is busy.", 503)).mockResolvedValue({ ...project, revision: 4 });
    await saveDesign();

    await saveDesign();

    expect(useEditor.getState()).toMatchObject({ dirty: false, saveError: null, revision: 4 });
  });
});

describe("a saved name", () => {
  it("saveDesign_keepsTheNameAsTyped_thoughTheApiSavesItTrimmed", async () => {
    useEditor.getState().setMetadata({ name: "Checkout " });
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, name: "Checkout", revision: 4 });

    await saveDesign();

    expect(update).toHaveBeenCalledWith("p1", expect.objectContaining({ name: "Checkout" }));
    expect(useEditor.getState().metadata.name).toBe("Checkout ");
    expect(useEditor.getState().dirty).toBe(false);
  });

  it("saveDesign_ofADesignWithNoName_leavesTheFieldEmpty", async () => {
    useEditor.getState().setMetadata({ name: "" });
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, name: "Untitled architecture", revision: 4 });

    await saveDesign();

    expect(update).toHaveBeenCalledWith("p1", expect.objectContaining({ name: "Untitled architecture" }));
    expect(useEditor.getState().metadata.name).toBe("");
  });
});

describe("saving before the editor is left", () => {
  it("saveBeforeLeaving_withNothingUnsaved_sendsNothing", async () => {
    const update = vi.spyOn(api, "updateProject");

    expect(await saveBeforeLeaving()).toBeNull();

    expect(update).not.toHaveBeenCalled();
  });

  it("saveBeforeLeaving_withUnsavedWork_savesIt", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });

    expect(await saveBeforeLeaving()).toBeNull();

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 4 });
  });

  it("saveBeforeLeaving_whileASaveIsOnItsWay_waitsForItAndSavesWhatWasEditedSince", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject");
    const answer = heldAnswer(update);
    update.mockResolvedValue({ ...project, revision: 5 });
    const first = saveDesign();
    edit(4);

    const leaving = saveBeforeLeaving();
    answer(4);
    await first;

    expect(await leaving).toBeNull();
    expect(update).toHaveBeenCalledTimes(2);
    expect(sentReplicas(update.mock.calls[1])).toBe(4);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 5 });
  });

  it("saveBeforeLeaving_whenTheSaveIsRefused_saysWhy", async () => {
    edit(3);
    vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("You need the editor role in this workspace.", 403));

    expect(await saveBeforeLeaving()).toEqual({ reason: "You need the editor role in this workspace.", conflict: false });

    expect(useEditor.getState().dirty).toBe(true);
  });

  it("saveBeforeLeaving_whenTheProjectWasSavedElsewhere_saysTheChoiceIsOpen", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject").mockRejectedValue(CHANGED_ELSEWHERE);

    expect(await saveBeforeLeaving()).toEqual({ reason: NOT_CHOSEN, conflict: true });
    // and again, without asking the API a second time
    expect(await saveBeforeLeaving()).toEqual({ reason: NOT_CHOSEN, conflict: true });

    expect(update).toHaveBeenCalledTimes(1);
  });

  it("saveBeforeLeaving_whenTheSaveTakesTooLong_stopsWaiting", async () => {
    vi.useFakeTimers();
    try {
      edit(3);
      vi.spyOn(api, "updateProject").mockReturnValue(new Promise<Project>(() => {}));

      const leaving = saveBeforeLeaving();
      await vi.advanceTimersByTimeAsync(LEAVE_PATIENCE_MS);

      expect(await leaving).toEqual({ reason: "The save is taking a long time.", conflict: false });
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("saving as the editor closes", () => {
  const lost = vi.fn();

  afterEach(async () => {
    await whenClosed();
    lost.mockReset();
  });

  it("saveOnClose_withUnsavedWork_savesIt_andOpeningAProjectWaitsForThat", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject");
    const answer = heldAnswer(update);
    let reopened = false;

    saveOnClose(null, lost);
    const opening = whenClosed().then(() => (reopened = true));
    await Promise.resolve();
    expect(reopened).toBe(false);
    answer(4);
    await opening;

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 4 });
    expect(lost).not.toHaveBeenCalled();
  });

  it("saveOnClose_withNothingUnsaved_sendsNothing", async () => {
    const update = vi.spyOn(api, "updateProject");

    saveOnClose(null, lost);
    await whenClosed();

    expect(update).not.toHaveBeenCalled();
  });

  it("saveOnClose_whenTheSaveIsRefused_saysWhatWasNotSaved", async () => {
    edit(3);
    vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("Network error: cannot reach the ArchSim web server.", 0));

    saveOnClose(null, lost);
    await whenClosed();

    expect(lost).toHaveBeenCalledWith("Starter", "Network error: cannot reach the ArchSim web server.");
  });

  it("saveOnClose_ofAProjectInConflict_sendsNothingAndSaysSo", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject").mockRejectedValueOnce(CHANGED_ELSEWHERE);
    await saveDesign();

    saveOnClose(null, lost);
    await whenClosed();

    expect(update).toHaveBeenCalledTimes(1);
    expect(lost).toHaveBeenCalledWith("Starter", NOT_CHOSEN);
  });

  it("saveOnClose_afterThePersonChoseToLeaveWithoutSaving_sendsNothing", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject");

    leaveWithoutSaving();
    saveOnClose(null, lost);
    await whenClosed();

    expect(update).not.toHaveBeenCalled();
    expect(lost).not.toHaveBeenCalled();
  });

  it("saveOnClose_afterThePersonStayedAndASaveWentThrough_savesWhatWasEditedSince", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });
    leaveWithoutSaving();
    await saveDesign();
    edit(4);

    saveOnClose(null, lost);
    await whenClosed();

    expect(update).toHaveBeenCalledTimes(2);
    expect(sentReplicas(update.mock.calls[1])).toBe(4);
  });

  it("saveOnClose_whenAnotherProjectOpensBeforeTheSaveOnItsWayEnds_doesNotSaveThatProject", async () => {
    edit(3);
    const update = vi.spyOn(api, "updateProject");
    const answer = heldAnswer(update);
    const first = saveDesign();
    edit(4);

    saveOnClose(null, lost);
    useEditor.getState().load({ ...structuredClone(project), id: "p2", name: "Other", revision: 9 }, catalog);
    answer(4);
    await first;
    await whenClosed();

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState()).toMatchObject({ projectId: "p2", revision: 9, dirty: false });
  });
});
