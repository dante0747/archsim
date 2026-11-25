/**
 * Where a link in the repository's Markdown goes on the public website (ADR-0078). The documents
 * link to each other and to code by relative paths, as GitHub reads them; the site keeps those
 * links working: a document under docs/ becomes a page under /docs, CHANGELOG.md the changelog
 * page, an image under docs/ is served from /docs-assets, and any other file or folder links to
 * the repository on GitHub, at main. A link to the site itself becomes a path on it, since the site
 * is served at more than one address (ADR-0098).
 */
import { posix } from "node:path";
import { repositoryUrl } from "@/lib/repository";
import { SITE_URL } from "./site";

/** The document a link is in, from the repository's root, and how to tell a folder from a file. */
export interface LinkContext {
  from: string;
  isFolder: (repoPath: string) => boolean;
}

const IMAGE = /\.(png|jpe?g|gif|svg|webp)$/i;

/** The images the site serves: those under docs/. */
export function isDocAsset(repoPath: string): boolean {
  return repoPath.startsWith("docs/") && IMAGE.test(repoPath);
}

/** A document's page path under /docs: `[]` for docs/README.md, null for anything else. */
export function docSlug(repoPath: string): string[] | null {
  if (!repoPath.startsWith("docs/") || !repoPath.endsWith(".md")) return null;
  const parts = repoPath.slice("docs/".length, -".md".length).split("/");
  return parts[parts.length - 1] === "README" ? parts.slice(0, -1) : parts;
}

/** The page a Markdown file of the repository has on the site, or null when it has none. */
export function docRoute(repoPath: string): string | null {
  if (repoPath === "CHANGELOG.md") return "/changelog/";
  const slug = docSlug(repoPath);
  if (slug === null) return null;
  return slug.length ? `/docs/${slug.join("/")}/` : "/docs/";
}

/** Where the site serves an image of docs/, or null when it does not serve it. */
export function assetRoute(repoPath: string): string | null {
  return isDocAsset(repoPath) ? `/docs-assets/${repoPath.slice("docs/".length)}` : null;
}

/** A link to the site itself as a path on it, or null for a link anywhere else. */
function sitePath(href: string): string | null {
  if (href !== SITE_URL && !href.startsWith(`${SITE_URL}/`)) return null;
  const { pathname, search, hash } = new URL(href);
  return pathname + search + hash;
}

/** A link as the site needs it, from a link as a document in the repository writes it. */
export function resolveHref(href: string, ctx: LinkContext): string {
  const path = sitePath(href);
  if (path !== null) return path;
  if (href.startsWith("#") || /^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith("//")) return href;
  const hashAt = href.indexOf("#");
  const target = hashAt < 0 ? href : href.slice(0, hashAt);
  const hash = hashAt < 0 ? "" : href.slice(hashAt);
  const joined = posix.normalize(posix.join(posix.dirname(ctx.from), decodeURI(target)));
  if (joined === ".." || joined.startsWith("../")) throw new Error(`${ctx.from}: the link ${href} points outside the repository`);
  const repoPath = joined === "." ? "" : joined.replace(/\/$/, "");
  const page = docRoute(repoPath);
  if (page) return page + hash;
  const asset = assetRoute(repoPath);
  if (asset) return asset;
  const folder = target.endsWith("/") || ctx.isFolder(repoPath);
  return repositoryUrl(repoPath + (target.endsWith("/") ? "/" : ""), folder ? "folder" : "file") + hash;
}
