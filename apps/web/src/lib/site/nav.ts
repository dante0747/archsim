/**
 * The documentation's navigation, read from docs/README.md (ADR-0078): each second-level heading
 * is a section, each row of its table a page, so the site lists the docs exactly as the index does.
 */
import { type LinkContext, resolveHref } from "./links";
import { mdText, parseMarkdown, sections } from "./mdast";

export interface NavItem {
  title: string;
  /** The page on the site, or the file on GitHub when the index links outside docs/. */
  href: string;
  description: string;
  external: boolean;
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

export function docsNav(source: string, ctx: LinkContext): NavSection[] {
  const nav: NavSection[] = [];
  for (const [title, blocks] of sections(parseMarkdown(source))) {
    const items: NavItem[] = [];
    for (const table of blocks) {
      if (table.type !== "table") continue;
      for (const row of table.children.slice(1)) {
        const [page, what] = row.children;
        const link = page?.children.find((c) => c.type === "link");
        if (!link) continue;
        const href = resolveHref(link.url, ctx);
        items.push({ title: mdText(link).trim(), href, description: what ? mdText(what).trim() : "", external: /^https?:/.test(href) });
      }
    }
    if (items.length) nav.push({ title, items });
  }
  if (nav.length === 0) throw new Error(`${ctx.from} has no section with a table of pages, which the website's navigation is read from`);
  return nav;
}
