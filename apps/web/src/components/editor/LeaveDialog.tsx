"use client";

/**
 * Asks before the editor is left with work that could not be saved (ADR-0113). Leaving saves
 * first and asks nothing when that works, so this shows only when there is something to decide:
 * stay, or leave without the unsaved changes.
 */
import { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/overlays";
import { leaveWithoutSaving } from "@/lib/save";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

export function LeaveDialog() {
  const held = useUi((s) => s.leaveHeld);
  const setHeld = useUi((s) => s.setLeaveHeld);
  const setSaveConflictOpen = useUi((s) => s.setSaveConflictOpen);
  const saved = useEditor((s) => !s.dirty);

  // The save went through after all (it was slow, or it was tried again): nothing is left to
  // decide, so the move that was held back goes ahead.
  useEffect(() => {
    if (!held || !saved) return;
    setHeld(null);
    held.go();
  }, [held, saved, setHeld]);

  const leave = () => {
    if (!held) return;
    setHeld(null);
    leaveWithoutSaving();
    held.go();
  };
  const chooseVersion = () => {
    setHeld(null);
    setSaveConflictOpen(true);
  };

  return (
    <Dialog open={held !== null} onOpenChange={(open) => !open && setHeld(null)}>
      <DialogContent title="Your changes are not saved" description={held?.reason}>
        <p className="text-[13px] leading-relaxed text-ink-2">
          {held?.conflict ? "Choose a version to keep them, or leave and lose them." : "Stay to keep them and try again, or leave and lose them."}
        </p>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button variant="danger" onClick={leave}>
            Leave without saving
          </Button>
          {held?.conflict ? (
            <Button variant="primary" onClick={chooseVersion}>
              Choose a version
            </Button>
          ) : (
            <Button variant="primary" onClick={() => setHeld(null)}>
              Stay
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
