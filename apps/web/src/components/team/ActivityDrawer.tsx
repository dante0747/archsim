"use client";

import { Dialog as RDialog } from "radix-ui";
import { History, LoaderCircle, MessageSquare, X } from "lucide-react";
import { useMemo, useState } from "react";
import { Avatar } from "@/components/auth/UserMenu";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/misc";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { fmtRelativeTime } from "@/lib/format";
import { type HistoryRow, type PartName, type Thread, actorName, describeRow, foldSaves } from "@/lib/team";
import { cn } from "@/lib/utils";
import { type ActivityTab, useTeam } from "@/store/team";
import { showPart, usePartName, useThreads } from "./hooks";
import { Composer, ThreadView } from "./ThreadView";

/** Threads grouped for the drawer: the design, each part, runs, then parts deleted since. */
function groupThreads(threads: Thread[], partName: PartName): { key: string; title: string; threads: Thread[] }[] {
  const groups = new Map<string, { key: string; title: string; threads: Thread[]; rank: number }>();
  for (const t of threads) {
    const { anchorKind, anchorId } = t.root;
    const detached = t.detached;
    const key = detached ? "detached" : anchorKind === "project" ? "project" : anchorKind === "run" ? "runs" : `${anchorKind}:${anchorId}`;
    const title = detached ? "On parts and runs deleted since" : anchorKind === "project" ? "The design" : anchorKind === "run" ? "Runs" : partName(anchorKind, anchorId);
    const rank = detached ? 3 : anchorKind === "project" ? 0 : anchorKind === "run" ? 2 : 1;
    const g = groups.get(key) ?? { key, title, threads: [], rank };
    g.threads.push(t);
    groups.set(key, g);
  }
  return [...groups.values()].sort((a, b) => a.rank - b.rank || a.title.localeCompare(b.title));
}

function CommentsTab() {
  const threads = useThreads();
  const partName = usePartName();
  const addComment = useTeam((s) => s.addComment);
  const setOpen = useTeam((s) => s.setActivityOpen);
  const [filter, setFilter] = useState<"open" | "all">("open");
  const shown = useMemo(() => (filter === "open" ? threads.filter((t) => !t.resolved) : threads), [threads, filter]);
  const groups = useMemo(() => groupThreads(shown, partName), [shown, partName]);
  const resolvedCount = threads.filter((t) => t.resolved).length;
  return (
    <div className="space-y-4 p-4">
      <Composer placeholder="Comment on the design" onSubmit={async (body) => !!(await addComment({ anchorKind: "project", body }))} />
      <div className="flex items-center gap-1 text-xs" role="group" aria-label="Show">
        {(["open", "all"] as const).map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={filter === f}
            onClick={() => setFilter(f)}
            className={cn("rounded-md px-2 py-1 font-medium", filter === f ? "bg-surface-2 text-ink" : "text-ink-3 hover:text-ink")}
          >
            {f === "open" ? "Open" : `All${resolvedCount ? ` (${resolvedCount} resolved)` : ""}`}
          </button>
        ))}
      </div>
      {groups.length === 0 && (
        <Empty icon={<MessageSquare />} title={threads.length ? "Nothing open" : "No comments yet"}>
          Select a part on the canvas to comment on it, or write about the design as a whole above. Everyone in the workspace can comment, including viewers.
        </Empty>
      )}
      {groups.map((g) => (
        <section key={g.key} className="space-y-2" aria-label={g.title}>
          <h3 className="microlabel">{g.title}</h3>
          {g.threads.map((t) => {
            const { anchorKind, anchorId } = t.root;
            const isPart = anchorKind === "node" || anchorKind === "edge";
            return (
              <ThreadView
                key={t.root.id}
                thread={t}
                where={g.key === "detached" || g.key === "runs" ? partName(anchorKind, anchorId) : undefined}
                onShowPart={isPart ? () => (setOpen(false), showPart(anchorKind, anchorId)) : undefined}
              />
            );
          })}
          {g.key.includes(":") && (
            <button
              type="button"
              className="text-[11px] font-medium text-ink-2 hover:text-ink"
              onClick={() => {
                setOpen(false);
                showPart(g.threads[0].root.anchorKind, g.threads[0].root.anchorId);
              }}
            >
              Show on canvas
            </button>
          )}
        </section>
      ))}
    </div>
  );
}

function EventRow({ row, partName }: { row: HistoryRow; partName: PartName }) {
  const e = row.event;
  const { text, quote } = describeRow(row, partName);
  const who = actorName(e);
  return (
    <li className="flex gap-2.5 py-2" data-testid="activity-event" data-action={e.action}>
      <Avatar name={e.actor?.name ?? who} className="mt-0.5 size-6 text-[10px]" />
      <div className="min-w-0 flex-1 text-[13px] leading-snug">
        <p className="text-ink-2">
          <span className="font-semibold text-ink">{who}</span> {text}
        </p>
        {quote && <p className="mt-0.5 line-clamp-2 border-l-2 border-line pl-2 text-xs text-ink-2">{quote}</p>}
        <p className="mt-0.5 text-[11px] text-ink-3" title={new Date(e.createdAt).toLocaleString() + (e.ip ? ` · from ${e.ip}` : "")}>
          {fmtRelativeTime(e.createdAt)}
        </p>
      </div>
    </li>
  );
}

function HistoryTab() {
  const history = useTeam((s) => s.history);
  const before = useTeam((s) => s.historyBefore);
  const loading = useTeam((s) => s.historyLoading);
  const loadHistory = useTeam((s) => s.loadHistory);
  const partName = usePartName();
  if (!history.length) {
    return loading ? (
      <p className="flex items-center gap-2 p-4 text-xs text-ink-2">
        <LoaderCircle className="size-3.5 animate-spin" /> Loading the history…
      </p>
    ) : (
      <Empty icon={<History />} title="No history yet">
        Saves, runs, comments and sign-offs show up here.
      </Empty>
    );
  }
  return (
    <div className="px-4 pb-4">
      <ul className="divide-y divide-line">
        {foldSaves(history).map((row) => (
          <EventRow key={row.event.id} row={row} partName={partName} />
        ))}
      </ul>
      {before != null && (
        <Button size="sm" variant="ghost" className="mt-2 w-full" disabled={loading} onClick={() => void loadHistory(true)}>
          {loading ? <LoaderCircle className="animate-spin" /> : null} Load older
        </Button>
      )}
    </div>
  );
}

/** Comments on the whole project and its history, in a panel on the right. */
export function ActivityDrawer() {
  const open = useTeam((s) => s.activityOpen);
  const setOpen = useTeam((s) => s.setActivityOpen);
  const tab = useTeam((s) => s.activityTab);
  const setTab = useTeam((s) => s.setActivityTab);
  return (
    <RDialog.Root open={open} onOpenChange={setOpen}>
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-50 bg-black/25" />
        <RDialog.Content
          className="surface-sheen fixed inset-y-0 right-0 z-50 flex w-[400px] max-w-[100vw] flex-col border-l border-line-strong bg-surface shadow-float focus:outline-none"
          data-testid="activity-drawer"
        >
          <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
            <RDialog.Title className="text-[15px] font-semibold text-ink">Activity</RDialog.Title>
            <RDialog.Description className="sr-only">Comments on this design and the history of changes</RDialog.Description>
            <RDialog.Close className="rounded-md p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Close">
              <X className="size-4" />
            </RDialog.Close>
          </div>
          <Tabs value={tab} onValueChange={(v) => setTab(v as ActivityTab)} className="flex min-h-0 flex-1 flex-col">
            <TabsList>
              <TabsTrigger value="comments">
                <MessageSquare /> Comments
              </TabsTrigger>
              <TabsTrigger value="history">
                <History /> History
              </TabsTrigger>
            </TabsList>
            <TabsContent value="comments" className="min-h-0 flex-1 overflow-y-auto">
              <CommentsTab />
            </TabsContent>
            <TabsContent value="history" className="min-h-0 flex-1 overflow-y-auto">
              <HistoryTab />
            </TabsContent>
          </Tabs>
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}
