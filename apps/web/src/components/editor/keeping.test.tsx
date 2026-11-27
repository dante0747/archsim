import { ReactFlowProvider } from "@xyflow/react";
import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import { AUTOSAVE_PAUSE_MS, AUTOSAVE_RETRY_MS } from "@/lib/autosave";
import { leave } from "@/lib/leave";
import { whenClosed } from "@/lib/save";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { useKeepUnsavedWork } from "./keeping";

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), message: vi.fn() }));
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
const BUSY = new ApiError("The server is busy.", 503);

const edit = (replicas = 3) => useEditor.getState().setNodeConfig("api", "replicas", replicas);
const editorOpen = () => renderHook(() => useKeepUnsavedWork(), { wrapper: ReactFlowProvider });

describe("keeping unsaved work while the editor is open", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    useEditor.getState().load(structuredClone(project), catalog);
    useUi.getState().resetForProject();
  });
  afterEach(() => vi.useRealTimers());

  it("saves an edit by itself after a pause, without a word", async () => {
    vi.useFakeTimers();
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });
    editorOpen();

    edit();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS);

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState().dirty).toBe(false);
    expect(toast.success).not.toHaveBeenCalled();
  });

  it("says nothing about a save made by itself that fails once and goes through at the next try", async () => {
    vi.useFakeTimers();
    const update = vi.spyOn(api, "updateProject").mockRejectedValueOnce(BUSY).mockResolvedValue({ ...project, revision: 4 });
    editorOpen();

    edit();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS + AUTOSAVE_RETRY_MS[0]);

    expect(update).toHaveBeenCalledTimes(2);
    expect(useEditor.getState().dirty).toBe(false);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("says once that saves made by themselves keep failing, however often they are tried again", async () => {
    vi.useFakeTimers();
    const update = vi.spyOn(api, "updateProject").mockRejectedValue(BUSY);
    editorOpen();

    edit();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS);
    expect(toast.error).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(120_000);

    expect(update.mock.calls.length).toBeGreaterThan(3);
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Not saved", { description: "The server is busy. Trying again shortly." });
  });

  it("says at once that a save made by itself was refused for a reason that will not pass", async () => {
    vi.useFakeTimers();
    vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("You need the editor role in this workspace.", 403));
    editorOpen();

    edit();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS);
    edit(4);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS);

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Not saved", { description: "You need the editor role in this workspace." });
  });

  it("says once that a save made by itself met a version saved elsewhere, and offers the choices", async () => {
    vi.useFakeTimers();
    const update = vi
      .spyOn(api, "updateProject")
      .mockRejectedValue(new ApiError("Project was modified elsewhere (revision 5, you had 3). Reload before saving.", 409));
    editorOpen();

    edit();
    await vi.advanceTimersByTimeAsync(AUTOSAVE_PAUSE_MS);
    edit(4);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(update).toHaveBeenCalledTimes(1);
    expect(toast.warning).toHaveBeenCalledTimes(1);
    const [title, options] = toast.warning.mock.calls[0] as [string, { action: { label: string; onClick: () => void } }];
    expect(title).toBe("This project was changed elsewhere");
    options.action.onClick();
    expect(useUi.getState().saveConflictOpen).toBe(true);
  });

  it("lets a link or a command leave at once when nothing is unsaved", () => {
    const update = vi.spyOn(api, "updateProject");
    const go = vi.fn();
    editorOpen();

    leave(go);

    expect(go).toHaveBeenCalledTimes(1);
    expect(update).not.toHaveBeenCalled();
  });

  it("saves unsaved work before a link or a command leaves, then leaves", async () => {
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });
    const go = vi.fn();
    editorOpen();
    edit();

    leave(go);
    expect(go).not.toHaveBeenCalled();

    await waitFor(() => expect(go).toHaveBeenCalledTimes(1));
    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState().dirty).toBe(false);
    expect(useUi.getState().leaveHeld).toBeNull();
  });

  it("asks instead of leaving when the work cannot be saved", async () => {
    vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError("You need the editor role in this workspace.", 403));
    const go = vi.fn();
    editorOpen();
    edit();

    leave(go);

    await waitFor(() => expect(useUi.getState().leaveHeld).toMatchObject({ reason: "You need the editor role in this workspace.", conflict: false }));
    expect(go).not.toHaveBeenCalled();
    useUi.getState().leaveHeld!.go();
    expect(go).toHaveBeenCalledTimes(1);
  });

  it("leaves once for two clicks made while the first one's save is on its way", async () => {
    let answer!: (saved: Project) => void;
    vi.spyOn(api, "updateProject").mockReturnValue(new Promise<Project>((resolve) => (answer = resolve)));
    const first = vi.fn();
    const second = vi.fn();
    editorOpen();
    edit();

    leave(first);
    leave(second);
    answer({ ...project, revision: 4 });

    await waitFor(() => expect(first).toHaveBeenCalledTimes(1));
    expect(second).not.toHaveBeenCalled();
  });

  it("saves unsaved work as the editor closes, for the ways out it cannot hold back", async () => {
    const update = vi.spyOn(api, "updateProject").mockResolvedValue({ ...project, revision: 4 });
    const { unmount } = editorOpen();
    edit();

    unmount();
    await whenClosed();

    expect(update).toHaveBeenCalledTimes(1);
    expect(useEditor.getState()).toMatchObject({ dirty: false, revision: 4 });
  });

  it("says which project's changes were lost when the closing save fails", async () => {
    vi.spyOn(api, "updateProject").mockRejectedValue(BUSY);
    const { unmount } = editorOpen();
    edit();

    unmount();
    await whenClosed();

    expect(toast.error).toHaveBeenCalledWith("The last changes to “Starter” were not saved", { description: "The server is busy." });
  });

  it("lets the browser ask before the page is unloaded with unsaved work, and not without", () => {
    editorOpen();
    const clean = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(clean);
    edit();
    const unsaved = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(unsaved);

    expect(clean.defaultPrevented).toBe(false);
    expect(unsaved.defaultPrevented).toBe(true);
  });
});
