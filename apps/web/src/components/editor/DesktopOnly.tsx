"use client";

/**
 * Shown instead of the editor on phones: the design is saved, so hand it over to a computer. Someone
 * signed in copies the link. A sandbox's guest cannot: their session belongs to this browser, so the
 * link would answer 401 anywhere else, and they take the design's JSON instead.
 */
import Link from "next/link";
import { toast } from "sonner";
import { LogoMark } from "@/components/brand";
import { downloadDesign } from "@/components/editor/actions";
import { LatestResultsLink } from "@/components/share/LatestResultsLink";
import { Button } from "@/components/ui/button";
import { isGuest } from "@/lib/sandbox";
import { useAuthState } from "@/store/auth";
import { useEditor } from "@/store/editor";

export function DesktopOnly({ projectId, onOpenAnyway }: { projectId: string; onOpenAnyway: () => void }) {
  const name = useEditor((s) => s.metadata.name);
  const guest = isGuest(useAuthState().me);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      toast.success("Link copied", { description: "Open it on a computer to carry on." });
    } catch {
      toast.error("Could not copy the link", { description: window.location.href });
    }
  };
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-5 bg-page px-6 py-12 text-center">
      <LogoMark className="size-10" />
      <div className="space-y-2">
        <h1 className="font-display text-[26px] font-semibold leading-tight tracking-[-0.03em] text-ink">Continue on a larger screen</h1>
        <p className="mx-auto max-w-sm text-[15px] leading-relaxed text-ink-2">
          {name ? <>“{name}” is saved. </> : null}The editor needs a computer or a tablet in landscape.{" "}
          {guest
            ? "Your sandbox belongs to this browser only, so a link would not open it anywhere else: export the design as JSON and import it there."
            : "Copy the link and open it there to pick up where you are."}
        </p>
      </div>
      <div className="flex flex-col items-center gap-2">
        {guest ? (
          <Button variant="primary" size="lg" onClick={() => downloadDesign()}>
            Export JSON
          </Button>
        ) : (
          <Button variant="primary" size="lg" onClick={() => void copy()}>
            Copy link
          </Button>
        )}
        <LatestResultsLink projectId={projectId} />
        <Button variant="ghost" size="sm" onClick={onOpenAnyway}>
          Open the editor anyway
        </Button>
        <Link href="/projects" className="mt-1 text-[13px] text-ink-3 hover:text-ink">
          Back to projects
        </Link>
      </div>
    </main>
  );
}
