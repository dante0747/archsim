import { cn } from "@/lib/utils";

/**
 * HTML rendered at build time from the repository's Markdown (lib/site/markdown.ts), which drops
 * raw HTML, styled by site.css.
 */
export function Prose({ html, className }: { html: string; className?: string }) {
  return <div className={cn("doc-prose", className)} dangerouslySetInnerHTML={{ __html: html }} />;
}
