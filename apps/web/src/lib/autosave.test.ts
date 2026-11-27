import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { AUTOSAVE_AT_LATEST_MS, AUTOSAVE_PAUSE_MS, AUTOSAVE_RETRY_MS, startAutosave } from "./autosave";
import { saveDesign } from "./save";

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

const edit = (replicas: number) => useEditor.getState().setNodeConfig("api", "replicas", replicas);
const pass = (ms: number) => vi.advanceTimersByTimeAsync(ms);
/** The API's answer to each save: the next revision, with the name it was sent. */
function savesGoThrough() {
  let revision = project.revision;
  return vi.spyOn(api, "updateProject").mockImplementation(async (_id, body) => ({ ...project, name: body.name ?? project.name, revision: ++revision }));
}

describe("saving a design by itself", () => {
  let stop: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.restoreAllMocks();
    useEditor.getState().load(structuredClone(project), catalog);
    stop = startAutosave(() => saveDesign());
  });
  afterEach(() => {
    stop();
    vi.useRealTimers();
  });

  it("saves an edit once the design has been left alone for a short pause", async () => {
    const update = savesGoThrough();

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS - 1);
    expect(update).not.toHaveBeenCalled();
    await pass(1);

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 4 });
  });

  it("waits for the pause after the last of several edits", async () => {
    const update = savesGoThrough();

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS - 500);
    edit(4);
    await pass(AUTOSAVE_PAUSE_MS - 1);
    expect(update).not.toHaveBeenCalled();
    await pass(1);

    expect(update).toHaveBeenCalledTimes(1);
    expect(update.mock.calls[0][1].document?.architecture.nodes.find((n) => n.id === "api")?.config.replicas).toBe(4);
  });

  it("saves the oldest unsaved edit no later than it may wait, while edits keep coming", async () => {
    const update = savesGoThrough();

    for (let t = 0; t < AUTOSAVE_AT_LATEST_MS; t += 1_000) {
      edit(3 + t / 1_000);
      await pass(1_000);
    }

    expect(update).toHaveBeenCalledTimes(1);
  });

  it("saves nothing while the design is as saved, whatever is selected", async () => {
    const update = savesGoThrough();

    useEditor.getState().select({ nodeId: "api" });
    await pass(AUTOSAVE_AT_LATEST_MS);

    expect(update).not.toHaveBeenCalled();
  });

  it("saves a part being dragged once the drag has ended", async () => {
    const update = savesGoThrough();

    useEditor.getState().onNodesChange([{ id: "api", type: "position", position: { x: 10, y: 10 }, dragging: true }]);
    await pass(AUTOSAVE_PAUSE_MS * 3);
    expect(update).not.toHaveBeenCalled();
    useEditor.getState().onNodesChange([{ id: "api", type: "position", position: { x: 40, y: 40 }, dragging: false }]);
    await pass(AUTOSAVE_PAUSE_MS);

    expect(update).toHaveBeenCalledTimes(1);
  });

  it("saves again what was edited while a save was on its way", async () => {
    let answer!: (saved: Project) => void;
    const update = vi
      .spyOn(api, "updateProject")
      .mockReturnValueOnce(new Promise<Project>((resolve) => (answer = resolve)))
      .mockResolvedValue({ ...project, revision: 5 });

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS);
    edit(4);
    answer({ ...project, revision: 4 });
    await pass(0);
    expect(useEditor.getState()).toMatchObject({ dirty: true, revision: 4 });
    await pass(AUTOSAVE_PAUSE_MS);

    expect(update).toHaveBeenCalledTimes(2);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 5 });
  });

  it("tries again, later each time, a save that failed for a reason that may pass", async () => {
    const update = vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("Network error: cannot reach the ArchSim web server.", 0));

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS);
    expect(update).toHaveBeenCalledTimes(1);
    await pass(AUTOSAVE_RETRY_MS[0] - 1);
    expect(update).toHaveBeenCalledTimes(1);
    await pass(1);
    expect(update).toHaveBeenCalledTimes(2);
    await pass(AUTOSAVE_RETRY_MS[1]);

    expect(update).toHaveBeenCalledTimes(3);
    expect(useEditor.getState()).toMatchObject({ dirty: true, saveError: { retry: true } });
  });

  it("saves at the next try once the reason has passed", async () => {
    const update = vi
      .spyOn(api, "updateProject")
      .mockRejectedValueOnce(new ApiError("The server is busy: try again in a moment.", 503))
      .mockResolvedValue({ ...project, revision: 4 });

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS + AUTOSAVE_RETRY_MS[0]);

    expect(update).toHaveBeenCalledTimes(2);
    expect(useEditor.getState()).toMatchObject({ dirty: false, saveError: null, revision: 4 });
  });

  it("leaves a save refused for good until the next edit", async () => {
    const update = vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("You need the editor role in this workspace.", 403));

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS);
    await pass(AUTOSAVE_RETRY_MS[AUTOSAVE_RETRY_MS.length - 1] * 2);
    expect(update).toHaveBeenCalledTimes(1);
    edit(4);
    await pass(AUTOSAVE_PAUSE_MS);

    expect(update).toHaveBeenCalledTimes(2);
  });

  it("sends nothing while the project is in conflict, and saves once the choice is made", async () => {
    const update = vi
      .spyOn(api, "updateProject")
      .mockRejectedValueOnce(new ApiError("Project was modified elsewhere (revision 5, you had 3). Reload before saving.", 409))
      .mockResolvedValue({ ...project, revision: 6 });

    edit(3);
    await pass(AUTOSAVE_PAUSE_MS);
    edit(4);
    await pass(AUTOSAVE_AT_LATEST_MS);
    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState().conflict).toBe(true);
    await saveDesign({ overwrite: true });

    expect(update).toHaveBeenCalledTimes(2);
    expect(useEditor.getState()).toMatchObject({ conflict: false, dirty: false, revision: 6 });
  });

  it("stops when the editor closes", async () => {
    const update = savesGoThrough();

    edit(3);
    stop();
    await pass(AUTOSAVE_AT_LATEST_MS);

    expect(update).not.toHaveBeenCalled();
  });
});
