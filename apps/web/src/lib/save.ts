/**
 * Saving the open design, for the editor's Save, for the saves it makes by itself and for an
 * agent's. A save counts as saved only what it sent: an edit made while the request is on its way
 * stays unsaved. A save the API refuses because the project was saved elsewhere first leaves a
 * conflict, which only a choice ends: take their version, overwrite it, or save this one as a
 * copy (ADR-0112). The editor also saves on its way out, so that leaving loses nothing (ADR-0113).
 */
import type { Viewport } from "@xyflow/react";
import { ApiError, api, isRevisionConflict } from "@/lib/api";
import { UNTITLED } from "@/lib/document";
import type { Project } from "@/lib/types";
import { useEditor } from "@/store/editor";

const MAX_NAME = 120;
const COPY_SUFFIX = " (copy)";

/** The opening of a project whose unsaved work the person chose to leave behind. */
let abandoned: number | null = null;
/** The save the editor made as it last closed. */
let closing: Promise<void> = Promise.resolve();

/** How long leaving the editor waits for a save before it asks instead. */
export const LEAVE_PATIENCE_MS = 8_000;
/** How long opening a project waits for the save the editor made as it closed. */
const CLOSE_PATIENCE_MS = 5_000;

const SLOW = "The save is taking a long time.";
const CHANGED_ELSEWHERE = "This project was changed elsewhere, and you have not chosen which version to keep.";

export type SaveResult =
  /** Stored as `revision`. */
  | { status: "saved"; revision: number }
  /** The project was saved elsewhere first: nothing was stored, and the editor's conflict is set. */
  | { status: "conflict" }
  /** Refused for another reason, in the API's words; `retry`: the reason may pass by itself. */
  | { status: "failed"; message: string; retry: boolean }
  /** No project is open, or a save is already on its way. */
  | { status: "skipped" };

/**
 * Saves the open design as its project's next revision, the canvas at `viewport` if given.
 * `overwrite` saves over whatever is stored: the choice that ends a conflict in this design's
 * favour. Without it, a design in conflict is not sent again: the API would refuse it again.
 */
export async function saveDesign({ viewport, overwrite = false }: { viewport?: Viewport | null; overwrite?: boolean } = {}): Promise<SaveResult> {
  const s = useEditor.getState();
  if (!s.projectId || s.saving) return { status: "skipped" };
  if (s.conflict && !overwrite) return { status: "conflict" };
  const sent = s.beginSave();
  try {
    const document = s.toDocument(viewport);
    const saved = await api.updateProject(s.projectId, {
      name: document.metadata.name,
      document,
      ...(overwrite ? {} : { expectedRevision: s.revision }),
    });
    useEditor.getState().markSaved(sent, saved);
    // the person stayed after all, and the design saves again: nothing is left behind any more
    abandoned = null;
    return { status: "saved", revision: saved.revision };
  } catch (e) {
    if (isRevisionConflict(e)) {
      useEditor.getState().saveFailed(sent, { conflict: true });
      return { status: "conflict" };
    }
    const error = { message: e instanceof Error ? e.message : String(e), retry: mayPass(e) };
    useEditor.getState().saveFailed(sent, { conflict: false, error });
    return { status: "failed", ...error };
  }
}

/**
 * Whether a refused save may go through later with nothing changed: the network was down, or the
 * server was busy, restarting or asked to wait. A refusal of the request itself (no role, no room,
 * a design the API cannot read) stays one until something is edited.
 */
function mayPass(e: unknown): boolean {
  if (!(e instanceof ApiError)) return true;
  return e.status === 0 || e.status === 408 || e.status === 429 || e.status >= 500;
}

/**
 * Ends a conflict in the other version's favour: opens what is stored now in place of the design
 * on the canvas, which one undo brings back.
 *
 * @returns the revision opened, or null when another project was opened before the answer came
 */
export async function reloadTheirs(): Promise<number | null> {
  const { projectId, loadId } = useEditor.getState();
  if (!projectId) return null;
  const theirs = await api.getProject(projectId);
  if (useEditor.getState().loadId !== loadId) return null;
  useEditor.getState().takeTheirs(theirs);
  return theirs.revision;
}

/** The name a copy of a design called `name` gets: "Name (copy)", within the length a name may have. */
export function copyName(name: string): string {
  return (name.trim() || UNTITLED).slice(0, MAX_NAME - COPY_SUFFIX.length).trimEnd() + COPY_SUFFIX;
}

/**
 * Ends a conflict without losing either version: stores the design on the canvas as a new project,
 * in the workspace new projects go to, and leaves the open project as it is stored.
 */
export function saveCopy(viewport?: Viewport | null): Promise<Project> {
  const s = useEditor.getState();
  return api.createProject({ name: copyName(s.metadata.name), document: s.toDocument(viewport) });
}

/** Why a design that is being left is not saved: the person then stays, or leaves without it. */
export interface Unsaved {
  reason: string;
  /** The project was saved elsewhere, and the person has not chosen which version to keep. */
  conflict: boolean;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, Math.max(0, ms)));

/** Resolves once no save is on its way, or after `patienceMs`. */
function idle(patienceMs: number): Promise<void> {
  return new Promise((resolve) => {
    if (!useEditor.getState().saving) return resolve();
    const done = () => {
      clearTimeout(timer);
      unsubscribe();
      resolve();
    };
    const timer = setTimeout(done, Math.max(0, patienceMs));
    const unsubscribe = useEditor.subscribe((s) => {
      if (!s.saving) done();
    });
  });
}

/**
 * Saves what is unsaved before the editor is left by a link or a command, which waits for it.
 *
 * @returns null once the design is saved, else why it is not
 */
export async function saveBeforeLeaving(viewport?: Viewport | null, patienceMs = LEAVE_PATIENCE_MS): Promise<Unsaved | null> {
  const deadline = Date.now() + patienceMs;
  // an edit made while a save is on its way needs a save of its own, so one pass may not be enough
  for (let pass = 0; pass < 3; pass++) {
    await idle(deadline - Date.now());
    const s = useEditor.getState();
    if (s.saving) return { reason: SLOW, conflict: false };
    if (!s.dirty) return null;
    if (s.conflict) return { reason: CHANGED_ELSEWHERE, conflict: true };
    const result = await Promise.race([saveDesign({ viewport }), sleep(deadline - Date.now()).then(() => null)]);
    if (result === null) return { reason: SLOW, conflict: false };
    if (result.status === "failed") return { reason: result.message, conflict: false };
    if (result.status === "conflict") return { reason: CHANGED_ELSEWHERE, conflict: true };
  }
  return useEditor.getState().dirty ? { reason: SLOW, conflict: false } : null;
}

/** The person leaves the open design's unsaved work behind: the editor does not save it as it closes. */
export function leaveWithoutSaving(): void {
  abandoned = useEditor.getState().loadId;
}

/**
 * Saves what is unsaved as the editor closes, for the ways out nothing can hold back (the
 * browser's Back and Forward buttons). It cannot ask, so `onFailure` is told what was not saved.
 */
export function saveOnClose(viewport: Viewport | null | undefined, onFailure: (name: string, reason: string) => void): void {
  const { dirty, conflict, loadId, metadata } = useEditor.getState();
  if (!dirty || loadId === abandoned) return;
  if (conflict) return onFailure(metadata.name, CHANGED_ELSEWHERE);
  const save = async () => {
    for (let pass = 0; pass < 3; pass++) {
      await idle(LEAVE_PATIENCE_MS);
      const s = useEditor.getState();
      // another project is open by now: this one's state is gone, and with it what was left to save
      if (s.loadId !== loadId || !s.dirty) return;
      const result = await saveDesign({ viewport });
      if (result.status === "failed") return onFailure(metadata.name, result.message);
      if (result.status === "conflict") return onFailure(metadata.name, CHANGED_ELSEWHERE);
    }
  };
  // whatever becomes of it, opening a project must not wait for ever
  closing = save().catch(() => {});
}

/**
 * Resolves once the save the editor made as it closed has been answered (or after a few seconds):
 * a project opened again right after it was left must read what that save stored.
 */
export function whenClosed(): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, CLOSE_PATIENCE_MS);
    void closing.then(() => {
      clearTimeout(timer);
      resolve();
    });
  });
}
