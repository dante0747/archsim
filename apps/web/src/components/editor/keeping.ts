"use client";

/**
 * Keeps the open design's unsaved work (ADR-0113). The editor saves it by itself after a short
 * pause; saves it before a link or a command leaves, and asks when that save fails; saves it as
 * the editor closes, for the ways out nothing can hold back; and lets the browser ask before the
 * page is unloaded with some.
 */
import { type Viewport, useReactFlow } from "@xyflow/react";
import { useEffect } from "react";
import { toast } from "sonner";
import { startAutosave } from "@/lib/autosave";
import { UNTITLED } from "@/lib/document";
import { setLeaveGuard } from "@/lib/leave";
import { saveBeforeLeaving, saveDesign, saveOnClose } from "@/lib/save";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

/**
 * Saves nobody asked for. They say nothing when they work. A failure is said once, however often
 * the save is tried again: at once when trying again cannot help, and at the second failure in a
 * row when it can, since a busy server or a network blip usually passes before anyone need know.
 * The top bar says "Not saved" meanwhile.
 */
function quietSaves(viewport: () => Viewport): () => void {
  let failures = 0;
  let told = false;
  return () => {
    void saveDesign({ viewport: viewport() }).then((result) => {
      if (result.status === "saved") {
        failures = 0;
        told = false;
      } else if (result.status === "failed") {
        failures += 1;
        if (told || (result.retry && failures < 2)) return;
        told = true;
        toast.error("Not saved", { description: result.retry ? `${result.message} Trying again shortly.` : result.message });
      } else if (result.status === "conflict") {
        toast.warning("This project was changed elsewhere", {
          description: "Your changes are not saved yet.",
          action: { label: "Choose", onClick: () => useUi.getState().setSaveConflictOpen(true) },
        });
      }
    });
  };
}

export function useKeepUnsavedWork() {
  const rf = useReactFlow();

  useEffect(() => startAutosave(quietSaves(() => rf.getViewport())), [rf]);

  useEffect(() => {
    let saving = false;
    setLeaveGuard((go) => {
      const s = useEditor.getState();
      if (!s.dirty && !s.saving) return true;
      // a second click while the first one's save is on its way waits for that one
      if (saving) return false;
      saving = true;
      void saveBeforeLeaving(rf.getViewport()).then((unsaved) => {
        saving = false;
        if (unsaved) useUi.getState().setLeaveHeld({ ...unsaved, go });
        else go();
      });
      return false;
    });
    return () => setLeaveGuard(null);
  }, [rf]);

  useEffect(
    () => () =>
      saveOnClose(rf.getViewport(), (name, reason) =>
        toast.error(`The last changes to “${name.trim() || UNTITLED}” were not saved`, { description: reason }),
      ),
    [rf],
  );

  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (useEditor.getState().dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, []);
}
