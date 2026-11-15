import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

// vitest runs in apps/web (under jsdom, import.meta.url is not a file URL)
const repository = resolve(process.cwd(), "../..");

function read(file: string): string {
  return readFileSync(resolve(repository, file), "utf8");
}

describe("the package's version", () => {
  // ArchSim's version is said once, as Gradle's `version` in gradle.properties (ADR-0076)
  it("is ArchSim's, in the package and in its lock file", () => {
    const archsim = /^\s*version\s*[=:]\s*(.*)$/m.exec(read("gradle.properties"))?.[1]?.trim();
    const manifest = JSON.parse(read("apps/web/package.json"));
    const lock = JSON.parse(read("apps/web/package-lock.json"));

    expect(archsim).toMatch(/^\d+\.\d+\.\d+/);
    expect([manifest.version, lock.version, lock.packages[""].version]).toEqual([archsim, archsim, archsim]);
  });
});
