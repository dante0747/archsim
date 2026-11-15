import { siGithub } from "simple-icons";
import { cn } from "@/lib/utils";

/** GitHub's mark (Simple Icons, CC0), drawn in the text colour. */
export function GitHubMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={cn("size-4 shrink-0", className)} fill="currentColor" aria-hidden>
      <path d={siGithub.path} />
    </svg>
  );
}
