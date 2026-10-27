"use client";

import { Check, MoreHorizontal, Pencil, RotateCcw, Trash2, Unlink } from "lucide-react";
import { useState } from "react";
import { Avatar } from "@/components/auth/UserMenu";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { Badge } from "@/components/ui/misc";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/overlays";
import { fmtRelativeTime } from "@/lib/format";
import type { Thread } from "@/lib/team";
import type { CommentDraft, TeamComment } from "@/lib/team-types";
import { cn } from "@/lib/utils";
import { useTeam } from "@/store/team";

export const MAX_COMMENT = 4000;

/** A text box that posts with the button or Ctrl/⌘ Enter. */
export function Composer({
  placeholder,
  submitLabel = "Comment",
  initial = "",
  autoFocus,
  onSubmit,
  onCancel,
  testId,
}: {
  placeholder: string;
  submitLabel?: string;
  initial?: string;
  autoFocus?: boolean;
  onSubmit: (text: string) => Promise<boolean>;
  onCancel?: () => void;
  testId?: string;
}) {
  const [text, setText] = useState(initial);
  const [busy, setBusy] = useState(false);
  const trimmed = text.trim();
  const submit = async () => {
    if (!trimmed || busy || trimmed.length > MAX_COMMENT) return;
    setBusy(true);
    const ok = await onSubmit(trimmed);
    setBusy(false);
    if (ok) setText("");
  };
  return (
    <div className="space-y-1.5" data-testid={testId}>
      <Textarea
        value={text}
        autoFocus={autoFocus}
        rows={2}
        maxLength={MAX_COMMENT}
        placeholder={placeholder}
        aria-label={placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
            // posts the comment; the editor's own Ctrl Enter (Simulate) must not see it
            e.preventDefault();
            e.stopPropagation();
            void submit();
          }
          if (e.key === "Escape" && onCancel) {
            e.stopPropagation();
            onCancel();
          }
        }}
      />
      <div className="flex items-center justify-end gap-1.5">
        {text.length > MAX_COMMENT * 0.9 && <span className="mr-auto font-mono text-[11px] text-ink-3">{text.length}/{MAX_COMMENT}</span>}
        {onCancel && (
          <Button size="sm" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button size="sm" variant="primary" disabled={!trimmed || busy} onClick={() => void submit()}>
          {submitLabel}
        </Button>
      </div>
    </div>
  );
}

function CommentItem({ c, first }: { c: TeamComment; first: boolean }) {
  const editComment = useTeam((s) => s.editComment);
  const deleteComment = useTeam((s) => s.deleteComment);
  const [editing, setEditing] = useState(false);
  const name = c.author?.name ?? "Former member";
  return (
    <li className="flex gap-2" data-testid={`comment-${c.id}`}>
      <Avatar name={name} className="mt-0.5 size-6 text-[10px]" />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-1.5">
          <span className="truncate text-xs font-semibold text-ink">{name}</span>
          <span className="shrink-0 text-[11px] text-ink-3" title={new Date(c.createdAt).toLocaleString()}>
            {fmtRelativeTime(c.createdAt)}
            {c.editedAt && " · edited"}
          </span>
          {(c.canEdit || c.canDelete) && !editing && (
            <Menu>
              <MenuTrigger asChild>
                <button type="button" className="ml-auto rounded p-0.5 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label={`Actions for ${name}'s comment`}>
                  <MoreHorizontal className="size-3.5" />
                </button>
              </MenuTrigger>
              <MenuContent className="min-w-36">
                {c.canEdit && (
                  <MenuItem onSelect={() => setEditing(true)}>
                    <Pencil /> Edit
                  </MenuItem>
                )}
                {c.canDelete && (
                  <MenuItem
                    className="text-danger-ink"
                    onSelect={() => {
                      if (!first || window.confirm("Delete this comment and its replies?")) void deleteComment(c.id);
                    }}
                  >
                    <Trash2 /> Delete
                  </MenuItem>
                )}
              </MenuContent>
            </Menu>
          )}
        </div>
        {editing ? (
          <div className="mt-1">
            <Composer
              placeholder="Edit your comment"
              submitLabel="Save"
              initial={c.body}
              autoFocus
              onCancel={() => setEditing(false)}
              onSubmit={async (text) => {
                const ok = await editComment(c.id, text);
                if (ok) setEditing(false);
                return ok;
              }}
            />
          </div>
        ) : (
          <p className="whitespace-pre-wrap break-words text-[13px] leading-snug text-ink">{c.body}</p>
        )}
      </div>
    </li>
  );
}

/** One thread: its comments, then reply and resolve. `where` names its part (in lists that mix
 * parts). */
export function ThreadView({ thread, where, onShowPart }: { thread: Thread; where?: string; onShowPart?: () => void }) {
  const addComment = useTeam((s) => s.addComment);
  const resolve = useTeam((s) => s.resolve);
  const [replying, setReplying] = useState(false);
  const { root, replies, resolved, detached } = thread;
  return (
    <div
      className={cn("rounded-xl border border-line bg-surface p-2.5", resolved && "bg-surface-2/50")}
      data-testid={`thread-${root.id}`}
      data-resolved={resolved || undefined}
    >
      {(where || detached || resolved) && (
        <div className="mb-1.5 flex flex-wrap items-center gap-1.5">
          {where &&
            (onShowPart && !detached ? (
              <button type="button" onClick={onShowPart} className="truncate text-[11px] font-medium text-ink-2 underline-offset-2 hover:text-ink hover:underline">
                {where}
              </button>
            ) : (
              <span className="truncate text-[11px] font-medium text-ink-2">{where}</span>
            ))}
          {detached && (
            <Badge tone="warning" title="The part or run this thread is about was deleted. The comments stay.">
              <Unlink className="size-3" /> detached
            </Badge>
          )}
          {resolved && (
            <Badge tone="good" title={root.resolvedBy ? `Resolved by ${root.resolvedBy.name}` : "Resolved"}>
              <Check className="size-3" /> resolved
            </Badge>
          )}
        </div>
      )}
      <ul className="space-y-2">
        <CommentItem c={root} first />
        {replies.map((r) => (
          <CommentItem key={r.id} c={r} first={false} />
        ))}
      </ul>
      {replying ? (
        <div className="mt-2">
          <Composer
            placeholder="Reply"
            submitLabel="Reply"
            autoFocus
            onCancel={() => setReplying(false)}
            onSubmit={async (text) => {
              const draft: CommentDraft = { anchorKind: root.anchorKind, anchorId: root.anchorId, parentId: root.id, body: text };
              const ok = !!(await addComment(draft));
              if (ok) setReplying(false);
              return ok;
            }}
          />
        </div>
      ) : (
        <div className="mt-1.5 flex items-center gap-1">
          <Button size="sm" variant="ghost" onClick={() => setReplying(true)}>
            Reply
          </Button>
          <Button size="sm" variant="ghost" className="ml-auto" onClick={() => void resolve(root.id, !resolved)} data-testid={`resolve-${root.id}`}>
            {resolved ? (
              <>
                <RotateCcw /> Reopen
              </>
            ) : (
              <>
                <Check /> Resolve
              </>
            )}
          </Button>
        </div>
      )}
    </div>
  );
}
