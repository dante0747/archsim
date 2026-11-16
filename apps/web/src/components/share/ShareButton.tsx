"use client";

/**
 * Results → Share: a link to the run shown in Results that anyone can open without signing in,
 * a frozen copy of its verdict, numbers and findings (no request traces). Lists the project's
 * links that still work, to copy or revoke them. A sandbox's guest makes none: share links need an
 * account (ADR-0079).
 */
import { Check, Copy, Link2, LoaderCircle, Share2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { runLabel, useDecideRun } from "@/components/decide/shared";
import { Button } from "@/components/ui/button";
import { Input, Label, NativeSelect } from "@/components/ui/input";
import { Dialog, DialogContent, DialogTrigger } from "@/components/ui/overlays";
import { fmtNum, fmtRelativeTime } from "@/lib/format";
import { isGuest } from "@/lib/sandbox";
import { signInPage } from "@/lib/session";
import { shareApi, shareUrl } from "@/lib/share-api";
import { SHARE_EXPIRY_CHOICES, type Share } from "@/lib/share-types";
import { useAuth } from "@/store/auth";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

function expiryText(share: Share): string {
  if (!share.expiresAt) return "never expires";
  const d = new Date(share.expiresAt);
  return `until ${d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })}`;
}

function ShareRow({ share, onRevoked }: { share: Share; onRevoked: (id: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const url = shareUrl(share);
  const copy = async () => {
    if (await copyText(url)) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } else toast.error("Could not copy the link", { description: url });
  };
  const revoke = async () => {
    setBusy(true);
    try {
      await shareApi.revoke(share.id);
      onRevoked(share.id);
      toast.success("Link revoked", { description: "It stopped working for everyone." });
    } catch (e) {
      setBusy(false);
      toast.error("Could not revoke the link", { description: e instanceof Error ? e.message : String(e) });
    }
  };
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1.5 py-2.5" data-testid="share-row">
      <div className="min-w-0 flex-1">
        <a href={share.path} target="_blank" rel="noreferrer" className="block truncate text-[13px] font-medium text-ink hover:underline">
          {share.title}
        </a>
        <p className="truncate font-mono text-[11px] text-ink-3">
          {fmtRelativeTime(share.createdAt)} · {fmtNum(share.viewCount)} {share.viewCount === 1 ? "view" : "views"} · {expiryText(share)}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button size="sm" variant="ghost" onClick={() => void copy()} aria-label={`Copy link to ${share.title}`}>
          {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
        </Button>
        {confirming ? (
          <>
            <Button size="sm" variant="danger" onClick={() => void revoke()} disabled={busy}>
              {busy && <LoaderCircle className="animate-spin" />} Revoke
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>
              Keep
            </Button>
          </>
        ) : (
          <Button size="sm" variant="ghost" onClick={() => setConfirming(true)}>
            Revoke…
          </Button>
        )}
      </div>
    </li>
  );
}

export function ShareButton() {
  const projectId = useRuns((s) => s.projectId);
  const projectName = useEditor((s) => s.metadata.name);
  const { run } = useDecideRun();
  const guest = useAuth((s) => isGuest(s.me));
  const [open, setOpen] = useState(false);
  const [shares, setShares] = useState<Share[] | null>(null);
  const [title, setTitle] = useState("");
  const [days, setDays] = useState<string>("30");
  const [creating, setCreating] = useState(false);

  const load = async () => {
    if (!projectId) return;
    try {
      setShares(await shareApi.list(projectId));
    } catch {
      setShares([]);
    }
  };

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) void load();
  };

  const create = async () => {
    if (!run) return;
    setCreating(true);
    try {
      const made = await shareApi.create(run.id, { title: title.trim() || null, expiresInDays: days === "never" ? null : Number(days) });
      setShares((list) => [made, ...(list ?? [])]);
      setTitle("");
      const url = shareUrl(made);
      if (await copyText(url)) toast.success("Link copied", { description: "Anyone with it can open these results." });
      else toast.success("Link made", { description: url });
    } catch (e) {
      toast.error("Could not make a link", { description: e instanceof Error ? e.message : String(e) });
    } finally {
      setCreating(false);
    }
  };

  const active = (shares ?? []).filter((s) => s.status === "active");
  const ended = (shares ?? []).length - active.length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button size="sm" variant="ghost" data-testid="share-button">
          <Share2 /> Share
        </Button>
      </DialogTrigger>
      <DialogContent
        title="Share these results"
        description="Anyone with the link can open them, without signing in, on any screen. It is a frozen copy of the verdict, numbers and findings (no request traces), and they can open a copy of the design."
      >
        <div className="space-y-5">
          {guest ? (
            <p className="text-[13px] text-ink-2" data-testid="share-needs-account">
              Share links need an account: a sandbox&apos;s results stay private, and go with it.{" "}
              <a href={signInPage()} className="font-medium text-ink underline underline-offset-2 hover:text-accent">
                Sign in to share
              </a>
            </p>
          ) : run ? (
            <div className="space-y-3 rounded-md border border-line bg-surface-2/40 p-3">
              <p className="font-mono text-[11px] text-ink-3">
                {runLabel(run)} · seed {run.seed} · {fmtRelativeTime(run.createdAt)}
              </p>
              <div className="grid gap-3 sm:grid-cols-[1fr_auto]">
                <div className="space-y-1">
                  <Label htmlFor="share-title">Title</Label>
                  <Input id="share-title" value={title} maxLength={200} placeholder={projectName || "Untitled architecture"} onChange={(e) => setTitle(e.target.value)} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="share-expiry">Works for</Label>
                  <NativeSelect id="share-expiry" value={days} onChange={(e) => setDays(e.target.value)}>
                    {SHARE_EXPIRY_CHOICES.map((c) => (
                      <option key={c.label} value={c.days == null ? "never" : String(c.days)}>
                        {c.label}
                      </option>
                    ))}
                  </NativeSelect>
                </div>
              </div>
              <Button variant="primary" onClick={() => void create()} disabled={creating} data-testid="share-create">
                {creating ? <LoaderCircle className="animate-spin" /> : <Link2 />} Create link and copy
              </Button>
            </div>
          ) : (
            <p className="text-[13px] text-ink-2">Simulate the design first: a link shares one finished run.</p>
          )}

          <div>
            <p className="microlabel">Links that work</p>
            {shares == null ? (
              <p className="mt-2 flex items-center gap-2 font-mono text-xs text-ink-3" role="status">
                <LoaderCircle className="size-3.5 animate-spin" /> loading…
              </p>
            ) : active.length === 0 ? (
              <p className="mt-2 text-[13px] text-ink-3">None yet.</p>
            ) : (
              <ul className="divide-y divide-line" aria-label="Share links">
                {active.map((s) => (
                  <ShareRow key={s.id} share={s} onRevoked={(id) => setShares((list) => (list ?? []).map((x) => (x.id === id ? { ...x, status: "revoked" } : x)))} />
                ))}
              </ul>
            )}
            {ended > 0 && (
              <p className="mt-1 text-xs text-ink-3">
                {ended} revoked or expired {ended === 1 ? "link no longer works" : "links no longer work"}.
              </p>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
