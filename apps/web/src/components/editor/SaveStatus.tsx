"use client";

/**
 * Whether the design on screen is the saved one, in words, under the project's name (ADR-0113).
 * The two states that need the person are buttons: "Not saved" tries again, and "Changed
 * elsewhere" opens the choices that end a save conflict.
 */
import { Check, LoaderCircle, TriangleAlert } from "lucide-react";
import { useEditorActions } from "@/components/editor/actions";
import { Kbd, Tip } from "@/components/ui/misc";
import { useEditor } from "@/store/editor";
import { useUi } from "@/store/ui";

export type SaveState = "saved" | "unsaved" | "saving" | "failed" | "conflict";

/** The state the top bar shows; a conflict and a save on its way come before what they leave unsaved. */
export function useSaveState(): SaveState {
  return useEditor((s) => (s.conflict ? "conflict" : s.saving ? "saving" : !s.dirty ? "saved" : s.saveError ? "failed" : "unsaved"));
}

const WORDS = "flex items-center gap-1";
const ACTION = `${WORDS} rounded-sm font-medium text-ink hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-accent`;
const ICON = "size-3 shrink-0";

export function SaveStatus() {
  const state = useSaveState();
  const reason = useEditor((s) => s.saveError?.message ?? null);
  const setSaveConflictOpen = useUi((s) => s.setSaveConflictOpen);
  const { save } = useEditorActions();
  const warning = <TriangleAlert className={ICON} style={{ color: "var(--status-warning)" }} aria-hidden />;

  return (
    // As wide as its longest everyday wording, so that the tabs beside a short name stay where they
    // are while the words change. Only the two states that need the person are announced: a screen
    // reader would otherwise read "Unsaved changes, Saving, Saved" after every edit.
    <span
      role="status"
      aria-live={state === "failed" || state === "conflict" ? "polite" : "off"}
      data-testid="save-status"
      data-state={state}
      className="flex h-3.5 min-w-[6.5rem] items-center whitespace-nowrap text-[11px] leading-none text-ink-3"
    >
      {state === "saved" && (
        <Tip content="Every change is saved by itself after a short pause">
          <span className={WORDS}>
            <Check className={ICON} aria-hidden />
            Saved
          </span>
        </Tip>
      )}
      {state === "saving" && (
        <span className={WORDS}>
          <LoaderCircle className={`${ICON} animate-spin`} aria-hidden />
          Saving…
        </span>
      )}
      {state === "unsaved" && (
        <Tip content={<>Saved by itself in a moment, or now with <Kbd>Ctrl S</Kbd></>}>
          <span className={WORDS}>
            <span className="mx-[3px] size-1.5 shrink-0 rounded-full bg-warning" aria-hidden />
            Unsaved changes
          </span>
        </Tip>
      )}
      {state === "failed" && (
        <Tip content={<>{reason} Click to try again.</>}>
          <button type="button" onClick={() => void save()} className={ACTION}>
            {warning}
            Not saved
          </button>
        </Tip>
      )}
      {state === "conflict" && (
        <Tip content="Saved elsewhere since you opened it. Click to choose which version to keep.">
          <button type="button" onClick={() => setSaveConflictOpen(true)} className={ACTION}>
            {warning}
            Changed elsewhere
          </button>
        </Tip>
      )}
    </span>
  );
}
