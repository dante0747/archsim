/**
 * Seed groups read from the API (`GET /run-groups/{id}`), cached per group and refreshed when
 * the statuses of the group's runs change (the runs store polls those runs).
 */
import { useEffect, useMemo } from "react";
import { create } from "zustand";
import { type RunGroup, getRunGroup } from "@/lib/seed-groups";
import type { Run } from "@/lib/types";
import { useRuns } from "./runs";

interface SeedGroupsState {
  groups: Record<string, RunGroup>;
  /** The status key each group was loaded at, so a refresh happens once per change. */
  loadedAt: Record<string, string>;
  load: (id: string, key: string) => Promise<void>;
}

const inFlight = new Set<string>();

export const useSeedGroups = create<SeedGroupsState>()((set, get) => ({
  groups: {},
  loadedAt: {},
  load: async (id, key) => {
    const token = `${id}@${key}`;
    if (get().loadedAt[id] === key || inFlight.has(token)) return;
    inFlight.add(token);
    try {
      const group = await getRunGroup(id);
      set((s) => ({ groups: { ...s.groups, [id]: group }, loadedAt: { ...s.loadedAt, [id]: key } }));
    } catch {
      /* the group is gone or unreachable: the caller shows the run alone */
    } finally {
      inFlight.delete(token);
    }
  },
}));

/** The group a run belongs to, with its runs as fresh as the runs store has them (null: none). */
export function useSeedGroup(groupId: string | null | undefined): RunGroup | null {
  const statuses = useRuns((s) => (groupId ? s.runs.filter((r) => r.groupId === groupId).map((r) => `${r.id}:${r.status}`).join(",") : ""));
  const storeRuns = useRuns((s) => s.runs);
  const group = useSeedGroups((s) => (groupId ? (s.groups[groupId] ?? null) : null));
  const load = useSeedGroups((s) => s.load);
  useEffect(() => {
    if (groupId) void load(groupId, statuses);
  }, [groupId, statuses, load]);
  return useMemo(() => {
    if (!group) return null;
    const byId = new Map<string, Run>(storeRuns.map((r) => [r.id, r]));
    return { ...group, runs: group.runs.map((r) => byId.get(r.id) ?? r) };
  }, [group, storeRuns]);
}
