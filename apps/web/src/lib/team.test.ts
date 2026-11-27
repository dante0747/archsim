import { describe, expect, it } from "vitest";
import {
  actorName,
  anchorKey,
  describeEvent,
  describeRow,
  foldSaves,
  isDetached,
  latestByReviewer,
  mergeActivity,
  openCounts,
  reviewState,
  threadsOf,
  threadsOn,
} from "./team";
import type { ActivityEvent, Signoff, TeamComment } from "./team-types";

let seq = 0;
function comment(over: Partial<TeamComment> = {}): TeamComment {
  seq += 1;
  return {
    id: `c${seq}`,
    projectId: "p1",
    anchorKind: "node",
    anchorId: "cache",
    runId: null,
    parentId: null,
    author: { id: "ann", name: "Ann Lee", avatarUrl: null },
    body: "Why?",
    createdAt: `2026-10-27T10:00:${String(seq).padStart(2, "0")}Z`,
    editedAt: null,
    resolvedAt: null,
    resolvedBy: null,
    detached: false,
    canEdit: true,
    canDelete: true,
    ...over,
  };
}

function signoff(over: Partial<Signoff> = {}): Signoff {
  seq += 1;
  return {
    id: `s${seq}`,
    projectId: "p1",
    runId: "r1",
    runLabel: "Current design",
    projectRevision: 2,
    currentRevision: 2,
    stale: false,
    inputDigest: "in",
    resultDigest: "out",
    reviewer: { id: "ann", name: "Ann", avatarUrl: null },
    decision: "approved",
    note: null,
    createdAt: "2026-10-27T10:00:00Z",
    canWithdraw: false,
    ...over,
  };
}

function event(over: Partial<ActivityEvent> = {}): ActivityEvent {
  seq += 1;
  return {
    id: seq,
    action: "comment.create",
    targetKind: "comment",
    targetId: "c1",
    projectId: "p1",
    actor: { id: "ann", name: "Ann", avatarUrl: null },
    actorKind: "user",
    meta: { anchorKind: "node", anchorId: "cache", excerpt: "Why 2 GB?" },
    ip: null,
    createdAt: "2026-10-27T10:00:00Z",
    ...over,
  };
}

describe("threads", () => {
  it("groups replies under their first comment, in order", () => {
    const root = comment();
    const later = comment({ anchorKind: "edge", anchorId: "api-db" });
    const r1 = comment({ parentId: root.id });
    const r2 = comment({ parentId: root.id });
    const orphan = comment({ parentId: "gone" });
    const threads = threadsOf([r2, later, orphan, root, r1]);
    expect(threads.map((t) => t.root.id)).toEqual([root.id, later.id]);
    expect(threads[0].replies.map((r) => r.id)).toEqual([r1.id, r2.id]);
    expect(threadsOn(threads, "edge", "api-db").map((t) => t.root.id)).toEqual([later.id]);
    expect(threadsOn(threads, "node", "db")).toEqual([]);
  });

  it("counts open threads per part for the canvas badges", () => {
    const open = comment();
    const resolved = comment({ resolvedAt: "2026-10-27T11:00:00Z" });
    const onDesign = comment({ anchorKind: "project", anchorId: null });
    const counts = openCounts(threadsOf([open, resolved, comment({ parentId: open.id }), onDesign]));
    expect(counts.get(anchorKey("node", "cache"))).toBe(1);
    expect(counts.get(anchorKey("project", null))).toBe(1);
  });

  it("judges parts against the canvas now and runs as the API says", () => {
    const live = { nodes: new Set(["api", "new-part"]), edges: new Set(["api-db"]) };
    expect(isDetached(comment({ anchorId: "cache" }), live)).toBe(true); // deleted, not saved yet
    expect(isDetached(comment({ anchorId: "new-part", detached: true }), live)).toBe(false); // added, not saved yet
    expect(isDetached(comment({ anchorKind: "edge", anchorId: "api-db" }), live)).toBe(false);
    expect(isDetached(comment({ anchorKind: "run", anchorId: "r1", detached: true }), live)).toBe(true);
    expect(isDetached(comment({ anchorKind: "project", anchorId: null }), live)).toBe(false);
    expect(isDetached(comment({ anchorId: "cache", detached: true }), null)).toBe(true);
  });
});

describe("sign-offs", () => {
  it("keeps each reviewer's latest decision on the run", () => {
    const newest = signoff({ decision: "changes_requested" });
    const older = signoff();
    const bob = signoff({ reviewer: { id: "bob", name: "Bob", avatarUrl: null } });
    const otherRun = signoff({ runId: "r2" });
    expect(latestByReviewer([newest, older, bob, otherRun], "r1").map((s) => s.id)).toEqual([newest.id, bob.id]);
  });

  it("asks for changes over approvals, ignores stale ones, and says when all are stale", () => {
    const ann = { id: "ann", name: "Ann", avatarUrl: null };
    const bob = { id: "bob", name: "Bob", avatarUrl: null };
    expect(reviewState([], "r1").state).toBe("none");
    expect(reviewState([signoff({ reviewer: ann })], "r1").state).toBe("approved");
    expect(reviewState([signoff({ reviewer: ann }), signoff({ reviewer: bob, decision: "changes_requested" })], "r1").state).toBe("changes_requested");
    // bob's request predates the last save: only ann's approval counts now
    expect(reviewState([signoff({ reviewer: ann }), signoff({ reviewer: bob, decision: "changes_requested", stale: true })], "r1").state).toBe("approved");
    const stale = reviewState([signoff({ stale: true })], "r1");
    expect(stale.state).toBe("stale");
    expect(stale.current).toEqual([]);
  });
});

describe("history", () => {
  it("writes a sentence for each action, naming parts by their label", () => {
    const name = (kind: string, id: string | null) => (kind === "node" && id === "cache" ? "Cache" : `${kind} ${id}`);
    expect(describeEvent(event(), name)).toEqual({ text: "commented on Cache", quote: "Why 2 GB?" });
    expect(describeEvent(event({ meta: { anchorKind: "node", anchorId: "cache", reply: true } }), name).text).toBe("replied on Cache");
    expect(describeEvent(event({ action: "project.update", meta: { name: "Shop", revision: 3 } })).text).toBe("saved revision 3");
    expect(describeEvent(event({ action: "run.create", meta: { label: "Lose Redis" } })).text).toBe("simulated Lose Redis");
    expect(describeEvent(event({ action: "share.create", meta: { label: "Baseline", title: "Starter" } }))).toEqual({ text: "shared Baseline as a link", quote: "Starter" });
    expect(describeEvent(event({ action: "share.revoke", meta: { title: "Starter" } })).text).toBe("revoked a share link");
    expect(describeEvent(event({ action: "comment.resolve", meta: { anchorKind: "project" } })).text).toBe("resolved a thread on the design");
    expect(describeEvent(event({ action: "signoff.create", meta: { decision: "approved", revision: 4, note: "Ship it" } }))).toEqual({
      text: "approved the design (revision 4)",
      quote: "Ship it",
    });
    expect(describeEvent(event({ action: "signoff.create", meta: { decision: "changes_requested" } })).text).toBe("requested changes");
    expect(describeEvent(event({ action: "member.role", meta: { from: "editor", to: "viewer" } })).text).toBe("changed a member's role to viewer");
    expect(describeEvent(event({ action: "something.new", meta: null })).text).toBe("something new");
  });

  it("names tokens and the system as actors", () => {
    expect(actorName(event())).toBe("Ann");
    expect(actorName(event({ actorKind: "token" }))).toBe("Ann (API token)");
    expect(actorName(event({ actorKind: "system", actor: null }))).toBe("ArchSim");
    expect(actorName(event({ actor: null }))).toBe("Someone");
  });

  it("merges a polled first page into loaded history without duplicates", () => {
    const loaded = [event({ id: 5 }), event({ id: 4 }), event({ id: 1 })];
    const merged = mergeActivity(loaded, [event({ id: 7 }), event({ id: 6 }), event({ id: 5 })]);
    expect(merged.map((e) => e.id)).toEqual([7, 6, 5, 4, 1]);
  });
});

describe("the history's saves", () => {
  const save = (id: number, revision: number, over: Partial<ActivityEvent> = {}) =>
    event({ id, action: "project.update", meta: { name: "Shop", revision }, ...over });
  const bob = { id: "bob", name: "Bob", avatarUrl: null };

  it("foldSaves_ofSavesInARowByOnePerson_makesOneRowThatNamesTheRevisions", () => {
    const rows = foldSaves([save(9, 7), save(8, 6), save(7, 5)]);

    expect(rows).toHaveLength(1);
    expect(rows[0].event.id).toBe(9);
    expect(describeRow(rows[0]).text).toBe("saved revisions 5 to 7");
  });

  it("foldSaves_ofOneSave_keepsItsOwnSentence", () => {
    const rows = foldSaves([save(9, 7)]);

    expect(rows).toEqual([{ event: expect.objectContaining({ id: 9 }), earliest: null }]);
    expect(describeRow(rows[0]).text).toBe("saved revision 7");
  });

  it("foldSaves_keepsSavesApartThatSomethingElseSeparates", () => {
    const rows = foldSaves([save(9, 7), save(8, 6), event({ id: 7, action: "run.create", meta: { label: "Baseline" } }), save(6, 5), save(5, 4)]);

    expect(rows.map((row) => describeRow(row).text)).toEqual(["saved revisions 6 to 7", "simulated Baseline", "saved revisions 4 to 5"]);
  });

  it("foldSaves_keepsSavesApartThatDifferentPeopleMade", () => {
    const rows = foldSaves([save(9, 7), save(8, 6, { actor: bob }), save(7, 5, { actor: bob }), save(6, 4, { actorKind: "token" })]);

    expect(rows.map((row) => [actorName(row.event), describeRow(row).text])).toEqual([
      ["Ann", "saved revision 7"],
      ["Bob", "saved revisions 5 to 6"],
      ["Ann (API token)", "saved revision 4"],
    ]);
  });

  it("foldSaves_leavesEveryOtherEventARowOfItsOwn", () => {
    const rows = foldSaves([event({ id: 3 }), event({ id: 2 }), event({ id: 1 })]);

    expect(rows.map((row) => row.event.id)).toEqual([3, 2, 1]);
    expect(describeRow(rows[0], (kind, id) => `${kind} ${id}`)).toEqual({ text: "commented on node cache", quote: "Why 2 GB?" });
  });
});
