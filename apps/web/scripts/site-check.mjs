/**
 * Checks the exported website (out/, ADR-0078) the way a reader would find it broken: every link,
 * anchor, image and script of every page must resolve within the export, every link to the
 * repository on GitHub must name a file or folder that exists, nothing may load from another
 * origin, and every page the sitemap lists must exist. `npm run build:site` runs it after the
 * build; a problem fails the build.
 */
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const SITE = "https://archsim.io";
const REPOSITORY_PATH = /^https:\/\/github\.com\/dante0747\/archsim\/(?:blob|tree)\/main\/([^#?]*)/;
const TAGS = /<(a|link|img|script|source|iframe|meta)\b([^>]*)>/gi;
const ATTRIBUTES = /([\w:-]+)="([^"]*)"/g;
const RESOURCE_TAGS = new Set(["img", "script", "source", "iframe"]);

const decode = (s) =>
  s.replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

function htmlFiles(dir) {
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith(".html"))
    .map((e) => join(e.parentPath, e.name))
    .sort();
}

/** The URL path a file of the export is served at: out/docs/a/index.html is /docs/a/. */
function servedAt(out, file) {
  const rel = relative(out, file).split(sep).join("/");
  if (rel === "index.html") return "/";
  return `/${rel.endsWith("/index.html") ? rel.slice(0, -"index.html".length) : rel}`;
}

/** The ids and the references (links, images, scripts) of a page; inline scripts' text is skipped. */
function scan(html) {
  const markup = html.replace(/<script\b([^>]*)>[\s\S]*?<\/script>/gi, "<script$1></script>");
  const ids = new Set([...markup.matchAll(/\sid="([^"]*)"/g)].map((m) => decode(m[1])));
  const refs = [];
  for (const [, tag, rest] of markup.matchAll(TAGS)) {
    const attrs = Object.fromEntries([...rest.matchAll(ATTRIBUTES)].map(([, k, v]) => [k.toLowerCase(), decode(v)]));
    const name = tag.toLowerCase();
    if (name === "meta") {
      if (attrs.content?.startsWith(`${SITE}/`)) refs.push({ url: attrs.content, resource: false });
      continue;
    }
    const url = attrs.href ?? attrs.src;
    if (url === undefined) continue;
    const resource = RESOURCE_TAGS.has(name) || (name === "link" && !/\b(canonical|alternate)\b/.test(attrs.rel ?? ""));
    refs.push({ url, resource });
  }
  return { ids, refs };
}

/** The file of the export a path is served from, as GitHub Pages finds it, or null. */
function fileFor(out, path) {
  const local = join(out, decodeURIComponent(path));
  if (path.endsWith("/")) return existsSync(join(local, "index.html")) ? join(local, "index.html") : null;
  if (existsSync(local) && statSync(local).isFile()) return local;
  return existsSync(join(local, "index.html")) ? join(local, "index.html") : null;
}

/** Where a reference points within the site ("/docs/a/#b"), or null when it leaves the site. */
function internal(url, page) {
  if (url === SITE || url.startsWith(`${SITE}/`)) return url.slice(SITE.length) || "/";
  if (url.startsWith("//") || /^[a-z][a-z0-9+.-]*:/i.test(url)) return null;
  const u = new URL(url, `${SITE}${page}`);
  return u.pathname + u.hash;
}

/**
 * @param {{ out: string, repository: string }} where the export and the repository's root
 * @returns {{ page: string, url: string, problem: string }[]}
 */
export function checkSite({ out, repository }) {
  const problems = [];
  const idsOf = new Map();
  const pages = htmlFiles(out).map((file) => {
    const page = { file, path: servedAt(out, file), ...scan(readFileSync(file, "utf8")) };
    idsOf.set(file, page.ids);
    return page;
  });

  for (const page of pages) {
    for (const { url, resource } of page.refs) {
      if (!url || /^(mailto|tel|data):/i.test(url)) continue;
      const report = (problem) => problems.push({ page: page.path, url, problem });
      const target = internal(url, page.path);
      if (target === null) {
        if (resource) report("loads a resource from another origin");
        const inRepository = REPOSITORY_PATH.exec(url);
        if (inRepository && !existsSync(join(repository, decodeURIComponent(inRepository[1])))) report("no such file in the repository");
        continue;
      }
      const { pathname, hash } = new URL(target, SITE);
      const file = fileFor(out, pathname);
      if (!file) {
        report("no such page");
        continue;
      }
      const ids = idsOf.get(file);
      if (hash.length > 1 && ids && !ids.has(decodeURIComponent(hash.slice(1)))) report("no such anchor");
    }
  }

  const sitemap = join(out, "sitemap.xml");
  if (existsSync(sitemap)) {
    for (const [, loc] of readFileSync(sitemap, "utf8").matchAll(/<loc>([^<]*)<\/loc>/g)) {
      const url = decode(loc);
      const target = internal(url, "/");
      if (target === null || !fileFor(out, new URL(target, SITE).pathname)) problems.push({ page: "/sitemap.xml", url, problem: "no such page" });
    }
  }
  return problems;
}
