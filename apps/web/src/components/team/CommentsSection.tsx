"use client";

import { ChevronRight, MessageSquare } from "lucide-react";
import { useMemo, useState } from "react";
import { Section } from "@/components/ui/misc";
import { threadsOn } from "@/lib/team";
import type { AnchorKind } from "@/lib/team-types";
import { useTeam } from "@/store/team";
import { showPart, usePartName, useThreads } from "./hooks";
import { Composer, ThreadView } from "./ThreadView";

/**
 * Comments in the side panel: the threads on the selected part (or, with nothing selected, on the
 * design as a whole plus those whose part was deleted), a box for a new one, and the resolved
 * threads folded away.
 */
export function CommentsSection({ anchorKind, anchorId = null }: { anchorKind: AnchorKind; anchorId?: string | null }) {
  const threads = useThreads();
  const loaded = useTeam((s) => s.loaded);
  const addComment = useTeam((s) => s.addComment);
  const partName = usePartName();
  const [showResolved, setShowResolved] = useState(false);
  const here = useMemo(() => threadsOn(threads, anchorKind, anchorId), [threads, anchorKind, anchorId]);
  // with nothing selected, comments on parts deleted since have nowhere else to show
  const detached = useMemo(
    () => (anchorKind === "project" ? threads.filter((t) => t.detached && !t.resolved && (t.root.anchorKind === "node" || t.root.anchorKind === "edge")) : []),
    [threads, anchorKind],
  );
  const open = here.filter((t) => !t.resolved);
  const resolved = here.filter((t) => t.resolved);
  const what = anchorKind === "project" ? "the design" : anchorKind === "edge" ? "this link" : anchorKind === "run" ? "this run" : "this part";

  return (
    <Section
      title={
        <span className="inline-flex items-center gap-1.5">
          <MessageSquare className="size-3" aria-hidden /> Comments{open.length > 0 && ` · ${open.length} open`}
        </span>
      }
      className="scroll-mt-4"
    >
      <div id="comments-section" data-testid="comments-section" className="space-y-2">
        {open.map((t) => (
          <ThreadView key={t.root.id} thread={t} />
        ))}
        <Composer
          testId="comment-composer"
          placeholder={open.length ? `Comment on ${what}` : `Start a discussion about ${what}`}
          onSubmit={async (body) => !!(await addComment({ anchorKind, anchorId, body }))}
        />
        {!loaded && <p className="text-xs text-ink-3">Loading comments…</p>}
        {resolved.length > 0 && (
          <div>
            <button
              type="button"
              onClick={() => setShowResolved((v) => !v)}
              className="flex items-center gap-1 text-xs font-medium text-ink-2 hover:text-ink"
              aria-expanded={showResolved}
            >
              <ChevronRight className={`size-3.5 transition-transform ${showResolved ? "rotate-90" : ""}`} aria-hidden />
              {resolved.length} resolved {resolved.length === 1 ? "thread" : "threads"}
            </button>
            {showResolved && (
              <div className="mt-2 space-y-2">
                {resolved.map((t) => (
                  <ThreadView key={t.root.id} thread={t} />
                ))}
              </div>
            )}
          </div>
        )}
        {detached.length > 0 && (
          <div className="space-y-2 pt-1">
            <p className="text-xs text-ink-2">On parts deleted since</p>
            {detached.map((t) => (
              <ThreadView key={t.root.id} thread={t} where={partName(t.root.anchorKind, t.root.anchorId)} onShowPart={() => showPart(t.root.anchorKind, t.root.anchorId)} />
            ))}
          </div>
        )}
      </div>
    </Section>
  );
}
