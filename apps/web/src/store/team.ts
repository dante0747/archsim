"use client";

/**
 * Reviewing together in the editor: the project's comments and sign-offs (polled while the tab is
 * visible; no websockets), the history shown in the Activity drawer, and the calls that change
 * them. Without sign-in everything works as the one local user.
 */
import { useEffect } from "react";
import { toast } from "sonner";
import { create } from "zustand";
import { teamApi } from "@/lib/team-api";
import { mergeActivity } from "@/lib/team";
import type { ActivityEvent, CommentDraft, Decision, Signoff, TeamComment } from "@/lib/team-types";
import { useEditor } from "@/store/editor";

export const TEAM_POLL_MS = 10_000;
const HISTORY_PAGE = 50;

export type ActivityTab = "comments" | "history";

interface TeamState {
  projectId: string | null;
  comments: TeamComment[];
  signoffs: Signoff[];
  loaded: boolean;
  error: string | null;
  activityOpen: boolean;
  activityTab: ActivityTab;
  history: ActivityEvent[];
  /** Pass to load older history; null when all is loaded. */
  historyBefore: number | null;
  historyLoading: boolean;

  setProject: (projectId: string | null) => void;
  refresh: () => Promise<void>;
  loadHistory: (older?: boolean) => Promise<void>;
  openActivity: (tab?: ActivityTab) => void;
  setActivityOpen: (open: boolean) => void;
  setActivityTab: (tab: ActivityTab) => void;
  addComment: (draft: CommentDraft) => Promise<TeamComment | null>;
  editComment: (id: string, body: string) => Promise<boolean>;
  deleteComment: (id: string) => Promise<boolean>;
  resolve: (id: string, resolved: boolean) => Promise<void>;
  signOff: (runId: string, decision: Decision, note?: string | null) => Promise<boolean>;
  withdraw: (id: string) => Promise<void>;
}

function failed(title: string, e: unknown): void {
  toast.error(title, { description: e instanceof Error ? e.message : String(e) });
}

function upsert(list: TeamComment[], c: TeamComment): TeamComment[] {
  const i = list.findIndex((x) => x.id === c.id);
  if (i === -1) return [...list, c];
  const copy = list.slice();
  copy[i] = c;
  return copy;
}

export const useTeam = create<TeamState>()((set, get) => ({
  projectId: null,
  comments: [],
  signoffs: [],
  loaded: false,
  error: null,
  activityOpen: false,
  activityTab: "comments",
  history: [],
  historyBefore: null,
  historyLoading: false,

  setProject: (projectId) => {
    if (get().projectId === projectId) return;
    set({ projectId, comments: [], signoffs: [], loaded: false, error: null, history: [], historyBefore: null, activityOpen: false });
  },

  refresh: async () => {
    const projectId = get().projectId;
    if (!projectId) return;
    try {
      const [comments, signoffs] = await Promise.all([teamApi.comments(projectId), teamApi.signoffs(projectId)]);
      if (get().projectId !== projectId) return;
      set({ comments, signoffs, loaded: true, error: null });
    } catch (e) {
      if (get().projectId !== projectId) return;
      set({ error: e instanceof Error ? e.message : String(e), loaded: true });
    }
    if (get().activityOpen && get().activityTab === "history") await get().loadHistory();
  },

  loadHistory: async (older = false) => {
    const { projectId, historyBefore, history } = get();
    if (!projectId || (older && historyBefore == null)) return;
    set({ historyLoading: true });
    try {
      const page = await teamApi.activity(projectId, older ? historyBefore : null, HISTORY_PAGE);
      if (get().projectId !== projectId) return;
      if (older) set({ history: mergeActivity(get().history, page.items), historyBefore: page.nextBefore });
      // the first page again (polling): keep what was loaded below it
      else set({ history: mergeActivity(history, page.items), historyBefore: history.length ? get().historyBefore : page.nextBefore });
    } catch (e) {
      failed("Could not load the history", e);
    } finally {
      set({ historyLoading: false });
    }
  },

  openActivity: (tab) => {
    set({ activityOpen: true, ...(tab ? { activityTab: tab } : {}) });
    if ((tab ?? get().activityTab) === "history") void get().loadHistory();
  },
  setActivityOpen: (open) => set({ activityOpen: open }),
  setActivityTab: (tab) => {
    set({ activityTab: tab });
    if (tab === "history") void get().loadHistory();
  },

  addComment: async (draft) => {
    const projectId = get().projectId;
    if (!projectId) return null;
    try {
      const c = await teamApi.addComment(projectId, draft);
      set({ comments: upsert(get().comments, c) });
      return c;
    } catch (e) {
      failed("Could not post the comment", e);
      return null;
    }
  },

  editComment: async (id, body) => {
    try {
      const c = await teamApi.editComment(id, body);
      set({ comments: upsert(get().comments, c) });
      return true;
    } catch (e) {
      failed("Could not edit the comment", e);
      return false;
    }
  },

  deleteComment: async (id) => {
    try {
      await teamApi.deleteComment(id);
      set({ comments: get().comments.filter((c) => c.id !== id && c.parentId !== id) });
      return true;
    } catch (e) {
      failed("Could not delete the comment", e);
      return false;
    }
  },

  resolve: async (id, resolved) => {
    try {
      const root = await teamApi.resolve(id, resolved);
      set({ comments: upsert(get().comments, root) });
    } catch (e) {
      failed(resolved ? "Could not resolve the thread" : "Could not reopen the thread", e);
    }
  },

  signOff: async (runId, decision, note) => {
    try {
      const s = await teamApi.signOff(runId, decision, note);
      set({ signoffs: [s, ...get().signoffs] });
      toast.success(decision === "approved" ? "Approved" : "Changes requested", {
        description: `Recorded against revision ${s.projectRevision} of the design.`,
      });
      return true;
    } catch (e) {
      failed("Could not sign off", e);
      return false;
    }
  },

  withdraw: async (id) => {
    try {
      await teamApi.withdraw(id);
      set({ signoffs: get().signoffs.filter((s) => s.id !== id) });
    } catch (e) {
      failed("Could not withdraw the sign-off", e);
    }
  },
}));

/** Keeps comments and sign-offs of the open project current: loads them, then polls every
 * TEAM_POLL_MS while the page is visible, and again when it becomes visible or the design is
 * saved (sign-offs may go stale). Mount once, in the editor. */
export function useTeamSync(): void {
  const projectId = useEditor((s) => s.projectId);
  const revision = useEditor((s) => s.revision);
  useEffect(() => {
    useTeam.getState().setProject(projectId);
    if (!projectId) return;
    const tick = () => {
      if (typeof document === "undefined" || document.visibilityState === "visible") void useTeam.getState().refresh();
    };
    tick();
    const timer = window.setInterval(tick, TEAM_POLL_MS);
    document.addEventListener("visibilitychange", tick);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [projectId]);
  useEffect(() => {
    if (revision > 0) void useTeam.getState().refresh();
  }, [revision]);
}
