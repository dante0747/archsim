"use client";

import { useReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import { toast } from "sonner";
import { ApiError, api } from "@/lib/api";
import { slugify } from "@/lib/document";
import type { RunMode } from "@/lib/seed-groups";
import type { ArchitectureDocument } from "@/lib/types";
import { downloadJson, readJsonFile } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Editor-level commands shared by the top bar and keyboard shortcuts. */
export function useEditorActions() {
  const rf = useReactFlow();

  const save = useCallback(async () => {
    const s = useEditor.getState();
    if (!s.projectId || s.saving) return;
    s.setSaving(true);
    try {
      const doc = s.toDocument(rf.getViewport());
      const p = await api.updateProject(s.projectId, {
        name: s.metadata.name.trim() || "Untitled architecture",
        document: doc,
        expectedRevision: s.revision,
      });
      useEditor.getState().markSaved(p.revision, p.name);
      toast.success("Saved", { description: `Revision ${p.revision}` });
    } catch (e) {
      useEditor.getState().setSaving(false);
      const msg = e instanceof Error ? e.message : String(e);
      toast.error(e instanceof ApiError && e.status === 409 ? "Changed elsewhere" : "Save failed", {
        description: msg,
      });
    }
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

  const exportJson = useCallback(() => {
    const s = useEditor.getState();
    const doc = s.toDocument(rf.getViewport());
    downloadJson(`${slugify(s.metadata.name || "architecture")}.archsim.json`, doc);
  }, [rf]);

  const importJson = useCallback(async (file: File) => {
    try {
      const raw = (await readJsonFile(file)) as ArchitectureDocument;
      if (!raw || typeof raw !== "object" || raw.kind !== "archsim.architecture") {
        throw new Error("This file is not an ArchSim architecture document.");
      }
      // 1.0 -> 1.7 only add optional fields, so all open in place
      if (!(["1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7"] as unknown[]).includes(raw.schemaVersion)) {
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
