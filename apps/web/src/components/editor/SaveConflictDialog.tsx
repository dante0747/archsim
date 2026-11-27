"use client";

/**
 * What a save offers when the project was saved elsewhere first (ADR-0112): reload their version,
 * overwrite it, or save this one as a copy. Closing the dialog chooses nothing: the design stays
 * unsaved, and Save asks again.
 */
import { useReactFlow } from "@xyflow/react";
import { LoaderCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { toast } from "sonner";
import { Button, type ButtonProps } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/overlays";
import { copyName, reloadTheirs, saveCopy, saveDesign } from "@/lib/save";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

type Choice = "reload" | "overwrite" | "copy";

function ChoiceRow({
  label,
  variant,
  busy,
  disabled,
  onChoose,
  children,
}: {
  label: string;
  variant: ButtonProps["variant"];
  busy: boolean;
  disabled: boolean;
  onChoose: () => void;
  children: React.ReactNode;
}) {
  const detail = useId();
  return (
    <li className="flex items-center justify-between gap-4 rounded-lg border border-line bg-surface-2 p-3">
      <p id={detail} className="min-w-0 text-[13px] leading-relaxed text-ink-2">
        {children}
      </p>
      <Button variant={variant} onClick={onChoose} disabled={disabled} aria-describedby={detail} className="min-w-32">
        {busy && <LoaderCircle className="animate-spin" />}
        {label}
      </Button>
    </li>
  );
}

export function SaveConflictDialog() {
  const open = useUi((s) => s.saveConflictOpen);
  const setOpen = useUi((s) => s.setSaveConflictOpen);
  const name = useEditor((s) => s.metadata.name);
  const rf = useReactFlow();
  const router = useRouter();
  const [busy, setBusy] = useState<Choice | null>(null);

  /** Runs one choice; it closes the dialog when it says it is done, and says why when it fails. */
  const choose = async (choice: Choice, failure: string, act: () => Promise<boolean>) => {
    setBusy(choice);
    try {
      if (await act()) setOpen(false);
    } catch (e) {
      toast.error(failure, { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(null);
    }
  };

  const reload = () =>
    choose("reload", "Could not reload", async () => {
      const revision = await reloadTheirs();
      if (revision !== null) toast.success(`Reloaded revision ${revision}`, { description: "Undo brings your version back." });
      return true;
    });

  const overwrite = () =>
    choose("overwrite", "Could not overwrite", async () => {
      const result = await saveDesign({ viewport: rf.getViewport(), overwrite: true });
      if (result.status === "failed") throw new Error(result.message);
      // two saves at the same moment: the API stored the other one first
      if (result.status === "conflict") throw new Error("It was saved elsewhere again just now. Choose again.");
      if (result.status === "saved") toast.success("Saved over their version", { description: `Revision ${result.revision}` });
      return result.status === "saved";
    });

  const copy = () =>
    choose("copy", "Could not save a copy", async () => {
      const project = await saveCopy(rf.getViewport());
      toast.success("Saved as a copy", { description: project.name });
      router.push(`/projects/${project.id}`);
      return true;
    });

  return (
    <Dialog open={open} onOpenChange={(next) => !busy && setOpen(next)}>
      <DialogContent
        title="This project was changed elsewhere"
        description="It was saved in another tab, or by someone else, after you opened it. Your changes are not saved yet: choose what to keep."
      >
        <ul className="space-y-2">
          <ChoiceRow label="Reload theirs" variant="secondary" busy={busy === "reload"} disabled={busy !== null} onChoose={() => void reload()}>
            Open the version saved elsewhere in place of yours. Undo brings yours back.
          </ChoiceRow>
          <ChoiceRow label="Overwrite" variant="danger" busy={busy === "overwrite"} disabled={busy !== null} onChoose={() => void overwrite()}>
            Save your version over theirs. Their changes are lost.
          </ChoiceRow>
          <ChoiceRow label="Save a copy" variant="primary" busy={busy === "copy"} disabled={busy !== null} onChoose={() => void copy()}>
            Keep both: yours becomes a new project, “{copyName(name)}”, and this one keeps theirs.
          </ChoiceRow>
        </ul>
        <p className="mt-3 text-xs text-ink-3">Close this to keep editing. Nothing is saved until you choose.</p>
      </DialogContent>
    </Dialog>
  );
}
