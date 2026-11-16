"use client";

/**
 * A quiet strip under the header while you try ArchSim as a sandbox's guest: when the sandbox
 * and its designs are deleted, and the way to sign in (which starts a workspace of your own).
 * Nothing for anyone else.
 */
import { Clock } from "lucide-react";
import { isGuest, sandboxEndText } from "@/lib/sandbox";
import { signInPage } from "@/lib/session";
import { useAuthState } from "@/store/auth";

export function SandboxBanner() {
  const { me } = useAuthState();
  const expiresAt = me?.user.expiresAt;
  if (!isGuest(me) || !expiresAt) return null;
  return (
    <div role="note" aria-label="Sandbox" data-testid="sandbox-banner" className="shrink-0 border-b border-line bg-surface-2/60">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-2 gap-y-0.5 px-4 py-1.5 text-[12.5px] text-ink-2 sm:px-6">
        <Clock className="size-3.5 shrink-0 text-ink-3" aria-hidden />
        <span>You are trying ArchSim in a sandbox: it and its designs are deleted {sandboxEndText(expiresAt)}.</span>
        <a href={signInPage()} className="font-medium text-ink underline underline-offset-2 hover:text-accent">
          Sign in for a workspace of your own
        </a>
      </div>
    </div>
  );
}
