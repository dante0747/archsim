/**
 * Builds the public website, archsim.io (ADR-0078), into out/: `next build` with ARCHSIM_SITE=1,
 * which exports the *.site.tsx routes as static files (next.config.ts), then fixes up a Windows
 * build's prefetch files (scripts/site-export.mjs) and checks the export (scripts/site-check.mjs),
 * failing on any broken link.
 *
 * Next.js builds a static export in .next, where the app's own build lives, and would replace it.
 * The app's build is set aside while the site builds and put back afterwards, so `npm run start`
 * and the end-to-end tests still find it. Telemetry is off unless NEXT_TELEMETRY_DISABLED says
 * otherwise, as for the app (scripts/run-next.mjs).
 */
import { spawnSync } from "node:child_process";
import { existsSync, renameSync, rmSync } from "node:fs";
import { createRequire } from "node:module";
import { join, resolve } from "node:path";
import { checkSite } from "./site-check.mjs";
import { flattenSegmentFiles } from "./site-export.mjs";

const web = resolve(import.meta.dirname, "..");
const build = join(web, ".next");
const parked = join(web, ".next-app");
const out = join(web, "out");

rmSync(parked, { recursive: true, force: true });
const hadAppBuild = existsSync(build);
try {
  if (hadAppBuild) renameSync(build, parked);
} catch (e) {
  console.error(`Cannot set .next aside (${e.code}): stop any Next.js server running from apps/web (npm run dev, dev:site or start) and try again.`);
  process.exit(1);
}
let status;
try {
  const next = createRequire(import.meta.url).resolve("next/dist/bin/next");
  const env = { ...process.env, ARCHSIM_SITE: "1", NEXT_TELEMETRY_DISABLED: process.env.NEXT_TELEMETRY_DISABLED ?? "1" };
  status = spawnSync(process.execPath, [next, "build"], { cwd: web, env, stdio: "inherit" }).status;
} finally {
  rmSync(build, { recursive: true, force: true });
  if (hadAppBuild) renameSync(parked, build);
}
if (status !== 0) process.exit(status ?? 1);

flattenSegmentFiles(out);
const problems = checkSite({ out, repository: resolve(web, "..", "..") });
for (const p of problems) console.error(`${p.page}: ${p.url}: ${p.problem}`);
if (problems.length) {
  console.error(`\nThe website has ${problems.length} broken link${problems.length === 1 ? "" : "s"} (above).`);
  process.exit(1);
}
console.log(`The website is in ${out}, every link checked.`);
