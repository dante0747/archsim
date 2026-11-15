/**
 * A fix-up of the website's static export (ADR-0078) for builds on Windows.
 *
 * The client router prefetches each page's segments from files named like
 * `__next.docs.$c$slug.__PAGE__.txt` beside the page. Next.js 16.4's export names them from
 * `path.relative()`, which on Windows joins with backslashes, so the dots become folders there
 * (`__next.docs/$c$slug/__PAGE__.txt`) and every prefetch is a 404. Builds on Linux, as the site's
 * workflow does, are already flat, and this leaves them alone.
 */
import { readdirSync, renameSync, rmSync } from "node:fs";
import { join, relative, sep } from "node:path";

export function flattenSegmentFiles(out) {
  const nested = readdirSync(out, { recursive: true, withFileTypes: true }).filter((e) => e.isDirectory() && e.name.startsWith("__next."));
  // the outermost folders only: a nested one is flattened with its parent
  const roots = nested.map((e) => join(e.parentPath, e.name)).filter((dir, _, all) => !all.some((other) => dir !== other && dir.startsWith(other + sep)));
  for (const dir of roots) {
    const files = readdirSync(dir, { recursive: true, withFileTypes: true }).filter((e) => e.isFile());
    for (const f of files) {
      const parts = relative(dir, join(f.parentPath, f.name)).split(sep);
      renameSync(join(f.parentPath, f.name), `${dir}.${parts.join(".")}`);
    }
    rmSync(dir, { recursive: true, force: true });
  }
}
