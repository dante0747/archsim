/**
 * Saving the design by itself (ADR-0113): after a short pause in editing, and no later than a
 * fixed time after the first unsaved edit when the edits keep coming. A save that fails for a
 * reason that may pass (the network, a busy or restarting server) is tried again, at growing
 * intervals; one that fails for a reason that will not pass by itself waits for the next edit.
 * A conflict waits for the person's choice.
 */
import { useEditor } from "@/store/editor";

/** How long the design must have been left alone before it is saved. */
export const AUTOSAVE_PAUSE_MS = 2_000;
/** While edits keep coming, the oldest unsaved one waits no longer than this. */
export const AUTOSAVE_AT_LATEST_MS = 15_000;
/** Waits before a failed save is tried again; the last one repeats. */
export const AUTOSAVE_RETRY_MS = [5_000, 15_000, 30_000, 60_000];

/**
 * Starts saving the open design by itself with `save`, until the returned function is called.
 */
export function startAutosave(save: () => unknown): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let unsavedSince: number | null = null;
  let failures = 0;

  const clear = () => {
    if (timer) clearTimeout(timer);
    timer = null;
  };
  const arm = (ms: number) => {
    clear();
    timer = setTimeout(attempt, ms);
  };
  const attempt = () => {
    timer = null;
    const s = useEditor.getState();
    if (!s.dirty || s.conflict || s.saving) return;
    // a drag is one edit: it is saved when it ends
    if (s.dragging) return arm(AUTOSAVE_PAUSE_MS);
    void save();
  };
  /** After a pause from now, but no later than the oldest unsaved edit may wait. */
  const afterAPause = () => {
    const now = Date.now();
    unsavedSince ??= now;
    arm(Math.max(0, Math.min(AUTOSAVE_PAUSE_MS, unsavedSince + AUTOSAVE_AT_LATEST_MS - now)));
  };

  const unsubscribe = useEditor.subscribe((s, prev) => {
    if (!s.dirty || s.conflict) {
      clear();
      unsavedSince = null;
      if (!s.dirty) failures = 0;
      return;
    }
    if (s.saving) return clear();
    if (prev.saving) {
      // a save just ended with the design still unsaved: it failed, or it was edited meanwhile
      unsavedSince = null;
      if (!s.saveError) {
        failures = 0;
        afterAPause();
      } else if (s.saveError.retry) {
        arm(AUTOSAVE_RETRY_MS[Math.min(failures++, AUTOSAVE_RETRY_MS.length - 1)]);
      }
      return;
    }
    const edited =
      s.nodes !== prev.nodes ||
      s.edges !== prev.edges ||
      s.workload !== prev.workload ||
      s.scenarios !== prev.scenarios ||
      s.objectives !== prev.objectives ||
      s.metadata !== prev.metadata ||
      s.extras !== prev.extras;
    if (edited || !prev.dirty || prev.conflict) afterAPause();
  });

  // a design opened unsaved (the editor shown again over work a failed save left behind)
  if (useEditor.getState().dirty) afterAPause();

  return () => {
    unsubscribe();
    clear();
  };
}
