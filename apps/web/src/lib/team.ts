/**
 * Pure helpers for reviewing together: comments grouped into threads, open-thread counts per
 * part, comments whose part was deleted (detached), sentences for the history, and the review
 * state of a run from its sign-offs.
 */
import type { ActivityEvent, AnchorKind, Signoff, TeamComment } from "./team-types";

export interface Thread {
  root: TeamComment;
  replies: TeamComment[];
  resolved: boolean;
  /** Its part or run is gone (from the design as drawn now, for parts). */
  detached: boolean;
}

/** What is on the canvas now: node and edge ids (unsaved changes included). */
export interface LiveParts {
  nodes: ReadonlySet<string>;
  edges: ReadonlySet<string>;
}

/** Detached against the live design for parts (an unsaved deletion counts; so does an unsaved
 * part being there), and as the API says for runs. */
export function isDetached(c: TeamComment, live?: LiveParts | null): boolean {
  if (live && c.anchorId) {
    if (c.anchorKind === "node") return !live.nodes.has(c.anchorId);
    if (c.anchorKind === "edge") return !live.edges.has(c.anchorId);
  }
  return c.detached;
}

/** Comments as threads, oldest thread first; replies in order. A reply whose first comment is
 * missing (deleted meanwhile) is dropped. */
export function threadsOf(comments: TeamComment[], live?: LiveParts | null): Thread[] {
  const byRoot = new Map<string, TeamComment[]>();
  for (const c of comments) {
    if (!c.parentId) continue;
    const list = byRoot.get(c.parentId) ?? [];
    list.push(c);
    byRoot.set(c.parentId, list);
  }
  const order = (a: TeamComment, b: TeamComment) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id);
  return comments
    .filter((c) => !c.parentId)
    .sort(order)
    .map((root) => ({
      root,
      replies: (byRoot.get(root.id) ?? []).sort(order),
      resolved: !!root.resolvedAt,
      detached: isDetached(root, live),
    }));
}

/** Threads on one part, run or the project. */
export function threadsOn(threads: Thread[], kind: AnchorKind, anchorId: string | null): Thread[] {
  return threads.filter((t) => t.root.anchorKind === kind && (kind === "project" || t.root.anchorId === anchorId));
}

/** "node:cache" -> open threads there; for the badges on the canvas. */
export function openCounts(threads: Thread[]): Map<string, number> {
  const out = new Map<string, number>();
  for (const t of threads) {
    if (t.resolved) continue;
    const key = anchorKey(t.root.anchorKind, t.root.anchorId);
    out.set(key, (out.get(key) ?? 0) + 1);
  }
  return out;
}

export function anchorKey(kind: AnchorKind, id: string | null): string {
  return `${kind}:${id ?? ""}`;
}

// ---- sign-offs -------------------------------------------------------------------------------

export type ReviewState = "approved" | "changes_requested" | "stale" | "none";

/** Each reviewer's latest sign-off of a run (sign-offs come newest first). */
export function latestByReviewer(signoffs: Signoff[], runId: string): Signoff[] {
  const seen = new Set<string>();
  const out: Signoff[] = [];
  for (const s of signoffs) {
    if (s.runId !== runId) continue;
    const who = s.reviewer?.id ?? `gone:${s.id}`;
    if (seen.has(who)) continue;
    seen.add(who);
    out.push(s);
  }
  return out;
}

/** A run's review: changes requested if any current reviewer asks for them, else approved if
 * anyone approved the design as saved now, else stale if every sign-off predates it. */
export function reviewState(signoffs: Signoff[], runId: string): { state: ReviewState; current: Signoff[]; latest: Signoff[] } {
  const latest = latestByReviewer(signoffs, runId);
  const current = latest.filter((s) => !s.stale);
  if (current.some((s) => s.decision === "changes_requested")) return { state: "changes_requested", current, latest };
  if (current.some((s) => s.decision === "approved")) return { state: "approved", current, latest };
  return { state: latest.length ? "stale" : "none", current, latest };
}

export const DECISION_LABEL: Record<Signoff["decision"], string> = {
  approved: "Approved",
  changes_requested: "Changes requested",
};

// ---- history ---------------------------------------------------------------------------------

/** Names a part for a sentence: the label on the canvas if it is still there. */
export type PartName = (kind: AnchorKind, id: string | null) => string;

export const defaultPartName: PartName = (kind, id) =>
  kind === "project" ? "the design" : kind === "run" ? "a run" : `${kind === "edge" ? "link" : ""} ${id ?? ""}`.trim();

function str(v: unknown): string | null {
  return typeof v === "string" && v ? v : null;
}

/** Who did it: their name, "(API token)" for a token, ArchSim for the system. */
export function actorName(e: ActivityEvent): string {
  if (e.actorKind === "system") return "ArchSim";
  const name = e.actor?.name ?? "Someone";
  return e.actorKind === "token" ? `${name} (API token)` : name;
}

/** One line of history after the actor's name ("commented on Cache"), plus a quote if any. */
export function describeEvent(e: ActivityEvent, partName: PartName = defaultPartName): { text: string; quote: string | null } {
  const m = e.meta ?? {};
  const part = () => partName((str(m.anchorKind) ?? "project") as AnchorKind, str(m.anchorId));
  const label = str(m.label) ?? "a run";
  const excerpt = str(m.excerpt);
  switch (e.action) {
    case "project.create":
      return { text: str(m.fromExample) ? `created the project from a template` : "created the project", quote: null };
    case "project.update":
      return { text: typeof m.revision === "number" ? `saved revision ${m.revision}` : "saved the design", quote: null };
    case "project.delete":
      return { text: `deleted the project ${str(m.name) ?? ""}`.trim(), quote: null };
    case "run.create":
      return { text: `simulated ${label}`, quote: null };
    case "run.cancel":
      return { text: `cancelled ${label}`, quote: null };
    case "run.delete":
      return { text: `deleted the run ${label}`, quote: null };
    case "comment.create":
      return { text: `${m.reply ? "replied on" : "commented on"} ${part()}`, quote: excerpt };
    case "comment.edit":
      return { text: `edited a comment on ${part()}`, quote: excerpt };
    case "comment.delete":
      return { text: `deleted a comment on ${part()}`, quote: null };
    case "comment.resolve":
      return { text: `resolved a thread on ${part()}`, quote: null };
    case "comment.reopen":
      return { text: `reopened a thread on ${part()}`, quote: null };
    case "signoff.create": {
      const rev = typeof m.revision === "number" ? ` (revision ${m.revision})` : "";
      return { text: `${m.decision === "approved" ? "approved the design" : "requested changes"}${rev}`, quote: str(m.note) };
    }
    case "signoff.withdraw":
      return { text: "withdrew a sign-off", quote: null };
    case "member.join":
      return { text: `joined as ${str(m.role) ?? "a member"}`, quote: null };
    case "member.role":
      return { text: `changed a member's role to ${str(m.to) ?? "?"}`, quote: null };
    case "member.remove":
      return { text: m.left ? "left the workspace" : "removed a member", quote: null };
    case "invitation.create":
      return { text: `invited someone as ${str(m.role) ?? "a member"}`, quote: null };
    case "invitation.revoke":
      return { text: "revoked an invitation", quote: null };
    case "workspace.create":
      return { text: "created the workspace", quote: null };
    case "workspace.rename":
      return { text: `renamed the workspace to ${str(m.name) ?? "?"}`, quote: null };
    default:
      return { text: e.action.replace(".", " "), quote: null };
  }
}

/** Merges a fresh first page into what is loaded (polling), newest first, without duplicates. */
export function mergeActivity(loaded: ActivityEvent[], fresh: ActivityEvent[]): ActivityEvent[] {
  const ids = new Set(loaded.map((e) => e.id));
  const added = fresh.filter((e) => !ids.has(e.id));
  return [...added, ...loaded].sort((a, b) => b.id - a.id);
}
