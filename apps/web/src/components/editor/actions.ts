"use client";

import { type Viewport, useReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import { slugify } from "@/lib/document";
import { saveDesign } from "@/lib/save";
import type { RunMode } from "@/lib/seed-groups";
import type { ArchitectureDocument } from "@/lib/types";
import { downloadJson, readJsonFile } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Downloads the design as its JSON document, `<name>.archsim.json`, at `viewport` if given. */
export function downloadDesign(viewport?: Viewport) {
  const s = useEditor.getState();
  downloadJson(`${slugify(s.metadata.name || "architecture")}.archsim.json`, s.toDocument(viewport));
}

/** Editor-level commands shared by the top bar and keyboard shortcuts. */
export function useEditorActions() {
  const rf = useReactFlow();

  /** Save the design. When the project was saved elsewhere first, ask which version to keep. */
  const save = useCallback(async () => {
    const result = await saveDesign({ viewport: rf.getViewport() });
    if (result.status === "saved") toast.success("Saved", { description: `Revision ${result.revision}` });
    else if (result.status === "conflict") useUi.getState().setSaveConflictOpen(true);
    else if (result.status === "failed") toast.error("Save failed", { description: result.message });
  }, [rf]);

  /** Simulate the design (or the chosen scenario); ``opts``: a seed group or a quick check. */
  const run = useCallback(async (opts?: { seeds?: number; mode?: RunMode }) => {
    const s = useEditor.getState();
    const firstError = s.issues.find((i) => i.severity === "error");
    if (firstError) {
      toast.error("Fix the model before simulating", { description: firstError.message });
      s.setRightTab("inspect");
      s.select({ nodeId: firstError.nodeId ?? null, edgeId: firstError.edgeId ?? null });
      return;
    }
    const r = useRuns.getState();
    const scenarioId = s.scenarios.some((x) => x.id === r.selectedScenarioId) ? r.selectedScenarioId : null;
    // Stay where the user is: results land on the canvas; Test replays them on demand.
    const ui = useUi.getState();
    ui.setPlaying(false);
    ui.setPlayhead(null);
    await r.startRun(s.toDocument(rf.getViewport()), scenarioId, opts);
  }, [rf]);

  const exportJson = useCallback(() => downloadDesign(rf.getViewport()), [rf]);

  const importJson = useCallback(async (file: File) => {
    try {
      const raw = (await readJsonFile(file)) as ArchitectureDocument;
      if (!raw || typeof raw !== "object" || raw.kind !== "archsim.architecture") {
        throw new Error("This file is not an ArchSim architecture document.");
      }
      // 1.0 -> 1.8 and 1.10 only add optional fields, 1.9 reads an API price of 0 as unset and 1.11 pins a
      // saved design's vCPUs to whole cores (architecture.siblingSpeed: 1); the editor's fromDocument applies
      // both steps, so every older design opens in place
      if (!(["1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "1.10", "1.11"] as unknown[]).includes(raw.schemaVersion)) {
        throw new Error(
          `Schema ${String(raw.schemaVersion)} cannot be opened in place. Use “Import JSON” on the projects page, which migrates older documents.`,
        );
      }
      const check = await api.validate(raw);
      useEditor.getState().replaceDocument(raw);
      const errors = check.issues.filter((i) => i.severity === "error").length;
      toast.success("Imported into this project", {
        description: errors ? `${errors} problem(s) to fix before simulating. Save to keep it.` : "Save to keep it.",
      });
    } catch (e) {
      toast.error("Import failed", {
        description: e instanceof SyntaxError ? "The file is not valid JSON." : e instanceof Error ? e.message : String(e),
      });
    }
  }, []);

  return { save, run, exportJson, importJson };
}
