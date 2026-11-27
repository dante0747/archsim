/**
 * Saving the open design, for the editor's Save and for an agent's. A save counts as saved only
 * what it sent: an edit made while the request is on its way stays unsaved. A save the API refuses
 * because the project was saved elsewhere first leaves a conflict, which only a choice ends: take
 * their version, overwrite it, or save this one as a copy (ADR-0112).
 */
import type { Viewport } from "@xyflow/react";
import { api, isRevisionConflict } from "@/lib/api";
import type { Project } from "@/lib/types";
import { useEditor } from "@/store/editor";

const UNTITLED = "Untitled architecture";
const MAX_NAME = 120;
const COPY_SUFFIX = " (copy)";

export type SaveResult =
  /** Stored as `revision`. */
  | { status: "saved"; revision: number }
  /** The project was saved elsewhere first: nothing was stored, and the editor's conflict is set. */
  | { status: "conflict" }
  /** Refused for another reason, in the API's words. */
  | { status: "failed"; message: string }
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
    const saved = await api.updateProject(s.projectId, {
      name: s.metadata.name.trim() || UNTITLED,
      document: s.toDocument(viewport),
      ...(overwrite ? {} : { expectedRevision: s.revision }),
    });
    useEditor.getState().markSaved(sent, saved);
    return { status: "saved", revision: saved.revision };
  } catch (e) {
    const conflict = isRevisionConflict(e);
    useEditor.getState().saveFailed(sent, conflict);
    return conflict ? { status: "conflict" } : { status: "failed", message: e instanceof Error ? e.message : String(e) };
  }
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
