"use client";

import { BadgeCheck, CircleAlert, History } from "lucide-react";
import { useState } from "react";
import { Avatar } from "@/components/auth/UserMenu";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { Badge, type Tone } from "@/components/ui/misc";
import { fmtRelativeTime } from "@/lib/format";
import { DECISION_LABEL, type ReviewState, reviewState } from "@/lib/team";
import type { Decision } from "@/lib/team-types";
import type { Run } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useTeam } from "@/store/team";

const STATE: Record<ReviewState, { label: string; tone: Tone }> = {
  approved: { label: "Approved", tone: "good" },
  changes_requested: { label: "Changes requested", tone: "warning" },
  stale: { label: "Design changed since review", tone: "neutral" },
  none: { label: "Not reviewed", tone: "neutral" },
};

function who(names: (string | undefined)[]): string {
  const list = names.map((n) => n ?? "a former member");
  return list.length <= 2 ? list.join(" and ") : `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]}`;
}

/** The review of a run on the Results page: each reviewer's latest decision (stale once the
 * design is saved again), and Approve / Request changes with a note. Viewers sign off too. */
export function SignOffPanel({ run }: { run: Run }) {
  const signoffs = useTeam((s) => s.signoffs);
  const signOff = useTeam((s) => s.signOff);
  const withdraw = useTeam((s) => s.withdraw);
  const dirty = useEditor((s) => s.dirty);
  const revision = useEditor((s) => s.revision);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState<Decision | null>(null);
  const { state, latest } = reviewState(signoffs, run.id);
  const earlier = signoffs.filter((s) => s.runId === run.id).length - latest.length;

  const decide = async (decision: Decision) => {
    setBusy(decision);
    const ok = await signOff(run.id, decision, note.trim() || null);
    setBusy(null);
    if (ok) setNote("");
  };

  return (
    <section aria-label="Review" data-testid="signoff-panel" className="rounded-xl border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[15px] font-semibold text-ink">Review</h3>
        <Badge tone={STATE[state].tone} data-testid="signoff-state">
          {STATE[state].label}
        </Badge>
      </div>
      {latest.length > 0 && (
        <ul className="mt-3 space-y-2.5">
          {latest.map((s) => (
            <li key={s.id} className="flex gap-2.5" data-testid="signoff-entry" data-decision={s.decision} data-stale={s.stale || undefined}>
              <Avatar name={s.reviewer?.name ?? "?"} className="mt-0.5 size-6 text-[10px]" />
              <div className="min-w-0 flex-1 text-[13px]">
                <p className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
                  <span className="font-semibold text-ink">{s.reviewer?.name ?? "Former member"}</span>
                  <Badge tone={s.stale ? "neutral" : s.decision === "approved" ? "good" : "warning"}>{DECISION_LABEL[s.decision]}</Badge>
                  <span className="text-[11px] text-ink-3">
                    revision {s.projectRevision} · {fmtRelativeTime(s.createdAt)}
                  </span>
                </p>
                {s.stale && (
                  <p className="mt-0.5 flex items-center gap-1 text-xs text-ink-2">
                    <History className="size-3" aria-hidden /> Stale: the design was saved since (now revision {s.currentRevision}).
                  </p>
                )}
                {s.note && <p className="mt-0.5 whitespace-pre-wrap break-words text-xs text-ink-2">{s.note}</p>}
              </div>
              {s.canWithdraw && (
                <Button size="sm" variant="ghost" onClick={() => void withdraw(s.id)}>
                  Withdraw
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {earlier > 0 && <p className="mt-2 text-[11px] text-ink-3">{earlier} earlier sign-off{earlier === 1 ? "" : "s"} in the history.</p>}
      <div className="mt-3 space-y-2">
        <Textarea
          rows={2}
          maxLength={2000}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Note for the author (optional)"
          aria-label="Review note"
        />
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" variant="primary" disabled={!!busy || run.status !== "succeeded"} onClick={() => void decide("approved")} data-testid="signoff-approve">
            <BadgeCheck /> Approve
          </Button>
          <Button size="sm" disabled={!!busy || run.status !== "succeeded"} onClick={() => void decide("changes_requested")} data-testid="signoff-changes">
            <CircleAlert /> Request changes
          </Button>
          <p className="text-xs text-ink-3">
            {dirty
              ? `Covers the design as saved (revision ${revision}), not your unsaved changes.`
              : `Covers this run and revision ${revision} of the design.`}
          </p>
        </div>
      </div>
    </section>
  );
}

/** A run's review at a glance, for run lists. */
export function SignOffBadge({ runId }: { runId: string }) {
  const signoffs = useTeam((s) => s.signoffs);
  const { state, current, latest } = reviewState(signoffs, runId);
  if (state === "none") return null;
  const title =
    state === "stale"
      ? `Reviewed by ${who(latest.map((s) => s.reviewer?.name))}; the design changed since`
      : `${STATE[state].label} by ${who(current.filter((s) => s.decision === state).map((s) => s.reviewer?.name))}`;
  const Icon = state === "changes_requested" ? CircleAlert : BadgeCheck;
  const color = state === "approved" ? "var(--status-good)" : state === "changes_requested" ? "var(--status-serious)" : "var(--ink-3)";
  return (
    <span className="inline-flex items-center gap-1 text-[11px] font-medium text-ink-2" title={title} data-testid={`signoff-badge-${runId}`} data-state={state}>
      <Icon className="size-3.5" style={{ color }} aria-hidden />
      {state === "stale" ? "review stale" : STATE[state].label.toLowerCase()}
    </span>
  );
}
