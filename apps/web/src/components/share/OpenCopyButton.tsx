"use client";

/**
 * "Open a copy" on a share page: makes a project from the shared design in your current
 * workspace and opens it. Without sign-in on the server it lands in the local workspace; on a
 * signed-in server it asks you to sign in first and brings you back here.
 */
import { Copy, LoaderCircle, LogIn } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { signInPage } from "@/lib/session";
import { shareApi } from "@/lib/share-api";
import { cn } from "@/lib/utils";
import { useAuthState } from "@/store/auth";

export function OpenCopyButton({ slug, className }: { slug: string; className?: string }) {
  const router = useRouter();
  const { status, config, workspace } = useAuthState();
  const [busy, setBusy] = useState(false);
  const needsSignIn = config?.mode === "oauth" && status === "signed-out";

  if (needsSignIn) {
    return (
      <Button asChild variant="primary" size="lg" className={cn("w-full sm:w-auto", className)}>
        <a href={signInPage(`/r/${slug}`)} data-testid="open-copy">
          <LogIn /> Sign in to open a copy
        </a>
      </Button>
    );
  }

  const open = async () => {
    setBusy(true);
    try {
      const project = await shareApi.copy(slug);
      toast.success("Copy made", { description: workspace ? `In ${workspace.name}.` : undefined });
      router.push(`/projects/${project.id}`);
    } catch (e) {
      setBusy(false);
      if (e instanceof ApiError && e.status === 401) {
        window.location.assign(signInPage(`/r/${slug}`));
        return;
      }
      toast.error("Could not open a copy", { description: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <Button
      variant="primary"
      size="lg"
      className={cn("w-full sm:w-auto", className)}
      onClick={() => void open()}
      disabled={busy || status === "loading" || status === "idle"}
      data-testid="open-copy"
    >
      {busy ? <LoaderCircle className="animate-spin" /> : <Copy />} Open a copy
    </Button>
  );
}
