import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { lastCommitDates } from "./history";

let scratch: string;
let root: string;

// a repository of its own, free of the machine's git configuration (signing, hooks)
function git(cwd: string, args: string[], date = "2026-01-01T00:00:00+00:00") {
  const env = { ...process.env, GIT_CONFIG_GLOBAL: join(scratch, "gitconfig"), GIT_CONFIG_NOSYSTEM: "1", GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date };
  const run = spawnSync("git", ["-c", "user.name=Test", "-c", "user.email=test@example.com", ...args], { cwd, env, encoding: "utf8" });
  if (run.status !== 0) throw new Error(`git ${args.join(" ")}: ${run.stderr}`);
}

function commit(files: Record<string, string>, date: string) {
  for (const [path, text] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  git(root, ["add", "."]);
  git(root, ["commit", "-q", "-m", "change"], date);
}

beforeAll(() => {
  scratch = mkdtempSync(join(tmpdir(), "archsim-history-"));
  writeFileSync(join(scratch, "gitconfig"), "");
  root = join(scratch, "repo");
  mkdirSync(root);
  git(root, ["init", "-q"]);
  commit({ "docs/a.md": "a", "docs/b.md": "b", "README.md": "r" }, "2026-01-01T10:00:00+00:00");
  commit({ "docs/a.md": "a, again" }, "2026-02-01T10:00:00+01:00");
});

afterAll(() => rmSync(scratch, { recursive: true, force: true }));

describe("lastCommitDates", () => {
  it("dates each file by the last commit that changed it, in UTC", () => {
    const dates = lastCommitDates(root, ["docs/a.md", "docs/b.md"]);

    // in UTC, the same whichever way git or a committer's time zone writes the date
    expect(Object.fromEntries(dates)).toEqual({ "docs/a.md": "2026-02-01T09:00:00Z", "docs/b.md": "2026-01-01T10:00:00Z" });
  });

  it("knows nothing in a shallow clone, whose one commit would claim every file", () => {
    const clone = join(scratch, "shallow");
    git(scratch, ["clone", "-q", "--depth", "1", pathToFileURL(root).href, clone]);

    expect(lastCommitDates(clone, ["docs/a.md", "docs/b.md"]).size).toBe(0);
  });

  it("knows nothing outside a repository, or below its root", () => {
    const plain = join(scratch, "plain");
    mkdirSync(plain);

    expect(lastCommitDates(plain, ["docs/a.md"]).size).toBe(0);
    expect(lastCommitDates(join(root, "docs"), ["a.md"]).size).toBe(0);
  });
});
