import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/misc";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Project, Run } from "@/lib/types";
import type { Signoff, TeamComment } from "@/lib/team-types";
import { useEditor } from "@/store/editor";
import { useTeam } from "@/store/team";
import { NodeCommentBadge } from "./CommentBadge";
import { CommentsSection } from "./CommentsSection";
import { SignOffBadge, SignOffPanel } from "./SignOff";

const project: Project = {
  id: "p1",
  name: "Starter",
  description: null,
  revision: 3,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 0,
  document: starterJson as unknown as ArchitectureDocument,
};

const ann = { id: "ann", name: "Ann Lee", avatarUrl: null };
const bob = { id: "bob", name: "Bob Ray", avatarUrl: null };

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
    author: ann,
    body: `Comment ${seq}`,
    createdAt: new Date(Date.UTC(2026, 9, 27, 10, 0, seq)).toISOString(),
    editedAt: null,
    resolvedAt: null,
    resolvedBy: null,
    detached: false,
    canEdit: false,
    canDelete: false,
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
    projectRevision: 3,
    currentRevision: 3,
    stale: false,
    inputDigest: "in",
    resultDigest: "out",
    reviewer: ann,
    decision: "approved",
    note: null,
    createdAt: new Date().toISOString(),
    canWithdraw: false,
    ...over,
  };
}

const run: Run = {
  id: "r1",
  projectId: "p1",
  label: "Current design",
  scenarioId: null,
  scenarioName: null,
  status: "succeeded",
  progress: 1,
  error: null,
  seed: 42,
  engineVersion: "0.12.0",
  inputDigest: "in",
  resultDigest: "out",
  summary: null,
  wallSeconds: 1,
  createdAt: "2026-10-27T10:00:00Z",
  startedAt: null,
  finishedAt: null,
};

type Call = { url: string; method: string; body: unknown };
let calls: Call[] = [];

function mockFetch(answer: (c: Call) => unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const c: Call = { url, method: init?.method ?? "GET", body: init?.body ? JSON.parse(String(init.body)) : null };
      calls.push(c);
      return new Response(JSON.stringify(answer(c)), { status: c.method === "POST" ? 201 : 200, headers: { "Content-Type": "application/json" } });
    }),
  );
}

function renderIn(ui: React.ReactNode) {
  return render(<TooltipProvider>{ui}</TooltipProvider>);
}

beforeEach(() => {
  calls = [];
  useEditor.getState().load(project, catalogJson as unknown as Catalog);
  useTeam.setState({ projectId: "p1", comments: [], signoffs: [], loaded: true, error: null });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("comments in the side panel", () => {
  it("shows a part's open threads with replies, and folds resolved ones away", () => {
    const root = comment({ body: "Why 2 GB of memory?" });
    const reply = comment({ parentId: root.id, author: bob, body: "Hit ratio needs it" });
    const done = comment({ body: "Old question", resolvedAt: "2026-10-27T11:00:00Z", resolvedBy: bob });
    const elsewhere = comment({ anchorId: "db", body: "Not here" });
    useTeam.setState({ comments: [root, reply, done, elsewhere] });
    renderIn(<CommentsSection anchorKind="node" anchorId="cache" />);
    const section = screen.getByTestId("comments-section");
    expect(within(section).getByText("Why 2 GB of memory?")).toBeInTheDocument();
    expect(within(section).getByText("Hit ratio needs it")).toBeInTheDocument();
    expect(within(section).queryByText("Not here")).toBeNull();
    expect(within(section).queryByText("Old question")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /1 resolved thread/ }));
    expect(within(section).getByText("Old question")).toBeInTheDocument();
    expect(screen.getByText(/1 open/)).toBeInTheDocument();
  });

  it("posts a comment on the part and a reply into its thread", async () => {
    const root = comment();
    useTeam.setState({ comments: [root] });
    mockFetch((c) => comment({ ...(c.body as object), id: `new-${calls.length}` }));
    renderIn(<CommentsSection anchorKind="node" anchorId="cache" />);
    const box = screen.getByLabelText("Comment on this part");
    fireEvent.change(box, { target: { value: "  Is the TTL right?  " } });
    const editorShortcut = vi.fn();
    window.addEventListener("keydown", editorShortcut);
    fireEvent.keyDown(box, { key: "Enter", ctrlKey: true });
    window.removeEventListener("keydown", editorShortcut);
    expect(editorShortcut).not.toHaveBeenCalled(); // Ctrl Enter posts; it does not also simulate
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toMatchObject({ url: "/api/v1/projects/p1/comments", method: "POST", body: { anchorKind: "node", anchorId: "cache", body: "Is the TTL right?" } });
    await waitFor(() => expect(screen.getByLabelText("Comment on this part")).toHaveValue(""));

    fireEvent.click(within(screen.getByTestId(`thread-${root.id}`)).getByRole("button", { name: "Reply" }));
    fireEvent.change(screen.getByLabelText("Reply"), { target: { value: "Yes, 5 min" } });
    fireEvent.click(within(screen.getByTestId(`thread-${root.id}`)).getByRole("button", { name: "Reply" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].body).toMatchObject({ parentId: root.id, body: "Yes, 5 min" });
  });

  it("resolves a thread for everyone", async () => {
    const root = comment();
    useTeam.setState({ comments: [root] });
    mockFetch(() => ({ ...root, resolvedAt: "2026-10-27T12:00:00Z", resolvedBy: bob }));
    renderIn(<CommentsSection anchorKind="node" anchorId="cache" />);
    fireEvent.click(screen.getByTestId(`resolve-${root.id}`));
    await waitFor(() => expect(useTeam.getState().comments[0].resolvedAt).not.toBeNull());
    expect(calls[0]).toMatchObject({ url: `/api/v1/comments/${root.id}/resolve`, body: { resolved: true } });
  });

  it("keeps comments on deleted parts in the overview, marked detached", () => {
    useTeam.setState({
      comments: [
        comment({ anchorKind: "node", anchorId: "gone", body: "On a deleted part" }),
        comment({ anchorKind: "project", anchorId: null, body: "On the whole design" }),
      ],
    });
    renderIn(<CommentsSection anchorKind="project" />);
    expect(screen.getByText("On the whole design")).toBeInTheDocument();
    expect(screen.getByText("On parts deleted since")).toBeInTheDocument();
    expect(screen.getByText("On a deleted part")).toBeInTheDocument();
    expect(screen.getByText("detached")).toBeInTheDocument();
  });

  it("counts open threads on a part's badge", () => {
    useTeam.setState({ comments: [comment(), comment(), comment({ resolvedAt: "2026-10-27T11:00:00Z" }), comment({ anchorId: "db" })] });
    renderIn(<NodeCommentBadge nodeId="cache" />);
    expect(screen.getByTestId("comment-badge-cache")).toHaveAccessibleName("2 open comment threads");
    cleanup();
    renderIn(<NodeCommentBadge nodeId="lb" />);
    expect(screen.queryByTestId("comment-badge-lb")).toBeNull();
  });
});

describe("sign-off", () => {
  it("approves the run with a note, against the saved revision", async () => {
    mockFetch((c) => signoff({ decision: (c.body as { decision: Signoff["decision"] }).decision, note: "Ship it", reviewer: bob }));
    renderIn(<SignOffPanel run={run} />);
    expect(screen.getByTestId("signoff-state")).toHaveTextContent("Not reviewed");
    expect(screen.getByText(/Covers this run and revision 3/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Review note"), { target: { value: " Ship it " } });
    fireEvent.click(screen.getByTestId("signoff-approve"));
    await waitFor(() => expect(screen.getByTestId("signoff-state")).toHaveTextContent("Approved"));
    expect(calls[0]).toMatchObject({ url: "/api/v1/runs/r1/signoffs", method: "POST", body: { decision: "approved", note: "Ship it" } });
    expect(screen.getByText("Bob Ray")).toBeInTheDocument();
  });

  it("shows stale sign-offs as stale, and changes requested over approvals", () => {
    useTeam.setState({
      signoffs: [
        signoff({ reviewer: bob, decision: "changes_requested", note: "Add a replica" }),
        signoff({ reviewer: ann, stale: true, projectRevision: 2 }),
      ],
    });
    renderIn(<SignOffPanel run={run} />);
    expect(screen.getByTestId("signoff-state")).toHaveTextContent("Changes requested");
    expect(screen.getByText("Add a replica")).toBeInTheDocument();
    expect(screen.getByText(/Stale: the design was saved since \(now revision 3\)/)).toBeInTheDocument();
  });

  it("says when unsaved changes are not part of the review", () => {
    useEditor.setState({ dirty: true });
    renderIn(<SignOffPanel run={run} />);
    expect(screen.getByText(/not your unsaved changes/)).toBeInTheDocument();
  });

  it("marks run rows with their review", () => {
    useTeam.setState({ signoffs: [signoff({ runId: "r1" }), signoff({ runId: "r2", stale: true })] });
    renderIn(
      <>
        <SignOffBadge runId="r1" />
        <SignOffBadge runId="r2" />
        <SignOffBadge runId="r3" />
      </>,
    );
    expect(screen.getByTestId("signoff-badge-r1")).toHaveAttribute("data-state", "approved");
    expect(screen.getByTestId("signoff-badge-r1")).toHaveAttribute("title", "Approved by Ann Lee");
    expect(screen.getByTestId("signoff-badge-r2")).toHaveTextContent("review stale");
    expect(screen.queryByTestId("signoff-badge-r3")).toBeNull();
  });
});
