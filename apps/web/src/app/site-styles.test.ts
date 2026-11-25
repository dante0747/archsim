// @vitest-environment node
/**
 * The website's stylesheet (ADR-0100): Tailwind builds it from the files the website renders, not
 * from the whole app, and React Flow's styles stay with the app's editor, so each page of
 * archsim.io waits for less CSS before it paints. Each stylesheet the site imports is compiled the
 * way the build compiles it (@tailwindcss/turbopack): Tailwind's compiler, then its scanner over
 * the sources the stylesheet names.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { compile } from "@tailwindcss/node";
import { Scanner } from "@tailwindcss/oxide";
import { beforeAll, describe, expect, it } from "vitest";

const web = resolve(import.meta.dirname, "..", "..");
const src = join(web, "src");

/** The website's routes and layouts: the files `npm run build:site` reads (next.config.ts). */
function siteRoutes(dir = join(src, "app")): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return siteRoutes(path);
    return /\.site\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

const IMPORT = /(?:import|export)\s[^"']*?from\s*["']([^"']+)["']|import\s*\(?\s*["']([^"']+)["']/g;
const isFile = (path: string) => statSync(path, { throwIfNoEntry: false })?.isFile() ?? false;

/** The file an import names when it is one of the app's own (`@/…` or relative), else null. */
function resolveImport(importer: string, specifier: string): string | null {
  if (!specifier.startsWith("@/") && !specifier.startsWith(".")) return null;
  const base = specifier.startsWith("@/") ? join(src, specifier.slice(2)) : resolve(dirname(importer), specifier);
  return ["", ".ts", ".tsx", "/index.ts", "/index.tsx"].map((ext) => base + ext).find(isFile) ?? null;
}

/** Every file of the app's own the website's routes import, directly or through other modules. */
function siteFiles(): Set<string> {
  const files = new Set<string>();
  const pending = siteRoutes();
  for (let file = pending.pop(); file; file = pending.pop()) {
    if (files.has(file)) continue;
    files.add(file);
    if (!/\.tsx?$/.test(file)) continue;
    for (const [, from, bare] of readFileSync(file, "utf8").matchAll(IMPORT)) {
      const imported = resolveImport(file, from ?? bare);
      if (imported) pending.push(imported);
    }
  }
  return files;
}

interface Build {
  css: string;
  scanned: string[];
}

/** A stylesheet compiled as the build compiles it: the CSS, and the files Tailwind read classes from. */
async function build(stylesheet: string): Promise<Build> {
  const compiler = await compile(readFileSync(stylesheet, "utf8"), { base: dirname(stylesheet), from: stylesheet, onDependency: () => {} });
  // With no source() on its import, Tailwind reads every file under the working directory.
  const root = compiler.root === "none" ? [] : [{ ...(compiler.root ?? { base: web, pattern: "**/*" }), negated: false }];
  const scanner = new Scanner({ sources: [...root, ...compiler.sources] });
  return { css: compiler.build(scanner.scan()), scanned: scanner.files.map((file) => resolve(file)) };
}

const shown = (paths: Iterable<string>) => [...paths].map((path) => relative(web, path).replaceAll("\\", "/")).sort();

describe("the website's stylesheet", () => {
  const files = siteFiles();
  const stylesheets = [...files].filter((file) => file.endsWith(".css"));
  let builds: Build[];
  let scanned: Set<string>;

  beforeAll(async () => {
    builds = await Promise.all(stylesheets.map(build));
    scanned = new Set(builds.flatMap((b) => b.scanned));
  });

  it("leaves React Flow's styles to the app", () => {
    expect(shown(stylesheets.filter((_, i) => builds[i].css.includes(".react-flow")))).toEqual([]);
  });

  it("takes classes from every component the website renders", () => {
    // Classes are written in the components (.tsx); the .ts modules they import hold data and logic.
    expect(shown([...files].filter((file) => file.endsWith(".tsx") && !scanned.has(file)))).toEqual([]);
  });

  it("takes no classes from a file the website does not render", () => {
    expect(shown([...scanned].filter((file) => !files.has(file)))).toEqual([]);
  });
});
