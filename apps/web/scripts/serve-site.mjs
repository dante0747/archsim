/**
 * Serves the exported website (out/, after `npm run build:site`) as GitHub Pages does: a folder's
 * index.html, a path without its slash redirected to the folder, and 404.html with status 404
 * for anything else. For looking at the site locally: http://127.0.0.1:4000 (PORT moves it).
 */
import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize, resolve } from "node:path";

const out = resolve(import.meta.dirname, "..", "out");
const port = Number(process.env.PORT ?? 4000);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".xml": "application/xml",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

const isFile = (p) => existsSync(p) && statSync(p).isFile();

function send(res, status, file) {
  res.writeHead(status, { "Content-Type": TYPES[extname(file)] ?? "application/octet-stream" });
  createReadStream(file).pipe(res);
}

createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url ?? "/", "http://localhost").pathname);
  const local = normalize(join(out, path));
  if (!local.startsWith(out)) return send(res, 404, join(out, "404.html"));
  if (isFile(local)) return send(res, 200, local);
  if (isFile(join(local, "index.html"))) {
    if (path.endsWith("/")) return send(res, 200, join(local, "index.html"));
    res.writeHead(301, { Location: `${path}/` });
    return res.end();
  }
  send(res, 404, join(out, "404.html"));
}).listen(port, "127.0.0.1", () => console.log(`The website (out/) at http://127.0.0.1:${port}`));
