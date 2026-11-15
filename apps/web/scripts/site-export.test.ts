import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { flattenSegmentFiles } from "./site-export.mjs";

let out: string;

function file(path: string, text = path) {
  mkdirSync(dirname(join(out, path)), { recursive: true });
  writeFileSync(join(out, path), text);
}

beforeEach(() => {
  out = mkdtempSync(join(tmpdir(), "archsim-site-export-"));
});

afterEach(() => rmSync(out, { recursive: true, force: true }));

describe("flattenSegmentFiles", () => {
  it("names each prefetch file as the router asks for it, when a Windows build nested them in folders", () => {
    file("docs/roadmap/__next.docs/$c$slug/__PAGE__.txt", "page");
    file("docs/roadmap/__next._tree.txt", "tree");

    flattenSegmentFiles(out);

    expect(readFileSync(join(out, "docs/roadmap/__next.docs.$c$slug.__PAGE__.txt"), "utf8")).toBe("page");
    expect(existsSync(join(out, "docs/roadmap/__next.docs"))).toBe(false);
    expect(readFileSync(join(out, "docs/roadmap/__next._tree.txt"), "utf8")).toBe("tree");
  });

  it("leaves an export that is already flat as it is", () => {
    file("accuracy/__next.accuracy.__PAGE__.txt", "page");

    flattenSegmentFiles(out);

    expect(readFileSync(join(out, "accuracy/__next.accuracy.__PAGE__.txt"), "utf8")).toBe("page");
  });
});
