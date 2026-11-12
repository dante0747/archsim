/**
 * Fails when Next.js's standalone output, which the web image ships, holds sharp, its @img
 * binaries or libvips anywhere in it. next.config.ts turns off the image optimizer, their only
 * user, and leaves them out of the output; this catches a Next.js release that brings them back.
 * Run after `npm run build`: `node scripts/check-standalone.mjs` (the Dockerfile and CI do).
 */
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const standalone = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", ".next", "standalone");

if (!existsSync(standalone)) {
  console.error(`no standalone output at ${standalone}: run \`npm run build\` first`);
  process.exit(1);
}

// parentPath is Node 20.12's name for what Node 20.9 calls path
const found = readdirSync(standalone, { recursive: true, withFileTypes: true })
  .map((entry) => path.join(entry.parentPath ?? entry.path, entry.name))
  .filter((file) => {
    const name = path.basename(file);
    const parent = path.basename(path.dirname(file));
    return (parent === "node_modules" && (name === "sharp" || name === "@img")) || name.startsWith("libvips");
  });

if (found.length > 0) {
  console.error(`sharp or libvips is in the standalone output (see next.config.ts):\n${found.join("\n")}`);
  process.exit(1);
}
console.log(`no sharp, @img or libvips in ${path.relative(process.cwd(), standalone) || "."}`);
