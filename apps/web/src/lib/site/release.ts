/**
 * Where the home page's version badge goes (ADR-0097): the changelog's entry for that release
 * ("## [1.3.0] - 2026-11-17"), not the top of the changelog, which opens with what is unreleased.
 */
import type { Heading } from "./markdown";

export function releaseNotes(headings: Heading[], version: string): string {
  // "[1.3.0] - 2026-11-17", or "1.3.0 - 2026-11-17" where the version is a link to its comparison
  const entry = headings.find((h) => h.depth === 2 && h.text.replace(/^\[([^\]]*)\]/, "$1").split(" ")[0] === version);
  if (!entry) throw new Error(`CHANGELOG.md has no entry for ${version}, the version the home page names (apps/web/package.json)`);
  return `/changelog/#${entry.id}`;
}
