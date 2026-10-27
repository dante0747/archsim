"use client";

/** /invite/<token>: who invited you to which workspace, and a button to join it. */
import { ArrowRight, LoaderCircle, LogIn, Users } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { authApi } from "@/lib/auth-api";
import { ROLE_HELP, type InvitationPreview } from "@/lib/auth-types";
import { signInPage } from "@/lib/session";
import { useAuth, useAuthState } from "@/store/auth";

export function InvitePage({ token }: { token: string }) {
  const { status, me } = useAuthState();
  const router = useRouter();
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);

  useEffect(() => {
    if (status !== "ready") return;
    let alive = true;
    authApi.previewInvitation(token).then(
      (p) => alive && setPreview(p),
      (e: unknown) => alive && setProblem(e instanceof ApiError ? e.message : String(e)),
    );
    return () => {
      alive = false;
    };
  }, [status, token]);

  const join = async () => {
    setJoining(true);
    try {
      const ws = await authApi.acceptInvitation(token);
      await useAuth.getState().load(true);
      useAuth.getState().switchWorkspace(ws.id);
      toast.success(`You joined “${ws.name}”`);
      router.push("/projects");
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
      setJoining(false);
    }
  };

  let body: React.ReactNode;
  if (status === "signed-out") {
    body = (
      <>
        <p className="text-sm text-ink-2">Sign in first, then you can accept the invitation.</p>
        <Button variant="primary" size="lg" className="w-full" asChild>
          <a href={signInPage(`/invite/${encodeURIComponent(token)}`)}>
            <LogIn /> Sign in to accept
          </a>
        </Button>
      </>
    );
  } else if (problem) {
    body = <p className="text-sm text-ink-2">{problem}</p>;
  } else if (!preview) {
    body = (
      <p className="flex items-center gap-2 text-sm text-ink-2" role="status">
        <LoaderCircle className="size-4 animate-spin" /> Checking the invitation…
      </p>
    );
  } else {
    body = (
      <>
        <p className="text-sm text-ink-2">
          {preview.invitedBy ?? "Someone"} invited {me ? <strong className="font-medium text-ink">{me.user.name}</strong> : "you"} to{" "}
          <strong className="font-medium text-ink">{preview.workspaceName}</strong> as {preview.role === "owner" ? "an" : "a"}{" "}
          <strong className="font-medium text-ink">{preview.role}</strong>. {ROLE_HELP[preview.role]}.
        </p>
        <Button variant="primary" size="lg" className="w-full" onClick={() => void join()} disabled={joining}>
          {joining ? <LoaderCircle className="animate-spin" /> : <ArrowRight />} Join {preview.workspaceName}
        </Button>
      </>
    );
  }

  return (
    <div className="flex min-h-full flex-col bg-page">
      <header className="border-b border-line">
        <div className="mx-auto flex h-14 max-w-6xl items-center px-4 sm:px-6">
          <Logo />
        </div>
      </header>
      <main className="flex flex-1 items-start justify-center px-4 py-16 sm:py-24">
        <div className="w-full max-w-sm space-y-4 rounded-xl border border-line-strong bg-surface p-6 shadow-float">
          <Users className="size-6 text-ink-3" aria-hidden />
          <h1 className="font-display text-[28px] leading-tight text-ink">Workspace invitation</h1>
          {body}
        </div>
      </main>
    </div>
  );
}
