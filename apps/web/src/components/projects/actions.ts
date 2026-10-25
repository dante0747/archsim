"use client";

/** Project actions shared by the home page and the projects page: create, import, duplicate, delete. */
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ApiError, api } from "@/lib/api";
import type { ProjectSummary } from "@/lib/types";
import { readJsonFile } from "@/lib/utils";

export type CreateBody = Parameters<typeof api.createProject>[0];

export function useProjectActions(onChanged?: () => void) {
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);

  const create = async (body: CreateBody, key: string) => {
    setBusy(key);
    try {
      const p = await api.createProject(body);
      router.push(`/projects/${p.id}`);
    } catch (e) {
      toast.error("Could not create project", { description: e instanceof Error ? e.message : String(e) });
      setBusy(null);
    }
  };

  /** Open a template: reuses the copy made last time unless it has been saved since. */
  const openTemplate = (exampleId: string) => create({ exampleId, reuseUnedited: true }, exampleId);

  const importFile = async (file: File) => {
    setBusy("import");
    try {
      const doc = await readJsonFile(file);
      const p = await api.importProject(doc);
      toast.success(`Imported “${p.name}”`);
      router.push(`/projects/${p.id}`);
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : e instanceof SyntaxError ? "The file is not valid JSON." : String(e);
      toast.error("Import failed", { description: msg });
      setBusy(null);
    }
  };

  const remove = async (p: ProjectSummary) => {
    if (!window.confirm(`Delete “${p.name}” and all its simulation runs? This cannot be undone.`)) return;
    try {
      await api.deleteProject(p.id);
      toast.success("Project deleted");
      onChanged?.();
    } catch (e) {
      toast.error("Delete failed", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  const duplicate = async (p: ProjectSummary) => {
    try {
      await api.duplicateProject(p.id);
      onChanged?.();
    } catch (e) {
      toast.error("Duplicate failed", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  return { busy, create, openTemplate, importFile, remove, duplicate };
}
