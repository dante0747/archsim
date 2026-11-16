/**
 * When the repository's files last changed, from its git history, for the website's sitemap
 * (ADR-0078). Nothing here runs in a browser; the sitemap's route calls it at build time.
 */
import { spawnSync } from "node:child_process";

function git(root: string, args: string[]): string | null {
  const run = spawnSync("git", ["-c", "core.quotePath=false", ...args], { cwd: root, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  return run.status === 0 ? run.stdout : null;
}

/**
 * The date of the last commit that changed each of `paths` (relative to `root`, the repository's
 * root), in W3C/ISO 8601 form in UTC (`2026-01-01T10:00:00Z`), whatever the committer's time zone
 * and however the installed git writes it. Empty when there is no full history to read: outside a
 * repository, below its root, or in a shallow clone, whose oldest commit would claim every file it
 * holds.
 */
export function lastCommitDates(root: string, paths: string[]): Map<string, string> {
  const dates = new Map<string, string>();
  const [shallow, prefix] = (git(root, ["rev-parse", "--is-shallow-repository", "--show-prefix"]) ?? "").split("\n");
  if (shallow !== "false" || prefix !== "" || paths.length === 0) return dates;
  // newest first: a NUL, the commit's date, then the files it changed
  const log = git(root, ["log", "--format=%x00%cI", "--name-only", "--no-renames", "--", ...paths]) ?? "";
  for (const commit of log.split("\0").slice(1)) {
    const [date, ...files] = commit.split("\n").filter((line) => line !== "");
    for (const file of files) if (!dates.has(file)) dates.set(file, inUtc(date));
  }
  return dates;
}

/** An ISO 8601 date with any offset, as the same instant in UTC to the second. */
function inUtc(date: string): string {
  return new Date(date).toISOString().replace(/\.\d{3}Z$/, "Z");
}
