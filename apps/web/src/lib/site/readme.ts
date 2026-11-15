/**
 * The home page's copy, read from the repository's README at build time (ADR-0078), so the
 * website says what the README says: why ArchSim, the feature grid and its screenshots, the quick
 * start, how it works and who to ask for a commercial license. A section the site reads that has
 * gone fails the build, naming it, rather than leaving a gap on the page.
 */
import type { Element, Root as HastRoot } from "hast";
import type { RootContent } from "mdast";
import rehypeParse from "rehype-parse";
import { unified } from "unified";
import { visit } from "unist-util-visit";
import { type LinkContext, resolveHref } from "./links";
import { renderMarkdown, textOf } from "./markdown";
import { mdText, parseMarkdown, sections, sourceOf } from "./mdast";

export interface Reason {
  title: string;
  text: string;
}

export interface Feature {
  /** The screenshot, where the site serves it. */
  image: string;
  alt: string;
  title: string;
  caption: string;
}

export interface ReadmeContent {
  why: Reason[];
  features: Feature[];
  /** "And beyond the canvas": the list, as HTML. */
  beyond: string;
  quickStart: {
    /** What you need ("You need Docker with Compose, nothing else."). */
    needs: string;
    commands: string;
    /** The paragraph after the commands, as HTML. */
    next: string;
  };
  /** The prose of "How it works", without its diagram, as HTML. */
  howItWorks: string;
  /** The License section's mailto: link. */
  commercialContact: string;
  /** The License section, as HTML. */
  license: string;
  /** The Contributing section, as HTML. */
  contributing: string;
}

function fail(what: string): never {
  throw new Error(`README.md has no ${what}, which the website's home page shows (docs/website.md)`);
}

function features(blocks: RootContent[], ctx: LinkContext): Feature[] {
  const html = blocks.flatMap((b) => (b.type === "html" ? [b.value] : [])).join("\n");
  const tree: HastRoot = unified().use(rehypeParse, { fragment: true }).parse(html);
  const out: Feature[] = [];
  visit(tree, "element", (td: Element) => {
    if (td.tagName !== "td") return;
    const img = td.children.find((c): c is Element => c.type === "element" && c.tagName === "img");
    const title = td.children.find((c): c is Element => c.type === "element" && c.tagName === "b");
    if (!img || !title) return;
    const after = td.children.slice(td.children.indexOf(title) + 1);
    out.push({
      image: resolveHref(String(img.properties.src), ctx),
      alt: String(img.properties.alt ?? ""),
      title: textOf(title).trim(),
      caption: after.map((c) => textOf(c)).join("").trim(),
    });
    return "skip";
  });
  return out;
}

export function readmeContent(source: string, ctx: LinkContext): ReadmeContent {
  const all = sections(parseMarkdown(source));
  const section = (name: string) => all.get(name) ?? fail(`"${name}" section`);
  const html = (blocks: RootContent[]) => renderMarkdown(blocks.map((b) => sourceOf(source, b)).join("\n\n"), ctx).html;

  const whyList = section("Why ArchSim").find((b) => b.type === "list") ?? fail('list under "Why ArchSim"');
  const why = whyList.children.map((item) => {
    const paragraph = item.children.find((c) => c.type === "paragraph") ?? fail('reason under "Why ArchSim"');
    const [lead, ...rest] = paragraph.children;
    if (lead?.type !== "strong") fail('bold title on each reason under "Why ArchSim"');
    return { title: mdText(lead).trim().replace(/\.$/, ""), text: rest.map((c) => mdText(c)).join("").trim() };
  });

  const featureBlocks = section("Features");
  const grid = features(featureBlocks, ctx);
  if (grid.length === 0) fail('screenshot table under "Features"');
  const beyond = featureBlocks.find((b) => b.type === "list") ?? fail('list under "Features" ("And beyond the canvas")');

  const quick = section("Quick start");
  const codeAt = quick.findIndex((b) => b.type === "code");
  const code = quick[codeAt];
  const needs = quick.find((b) => b.type === "paragraph");
  const next = quick[codeAt + 1];
  if (code?.type !== "code" || !needs || next?.type !== "paragraph") fail('"Quick start" paragraph, commands and next step');

  const prose = section("How it works").filter((b) => b.type === "paragraph");
  if (prose.length === 0) fail('paragraph under "How it works"');

  const license = section("License");
  const contacts: string[] = [];
  visit({ type: "root", children: license }, "link", (link) => {
    if (link.url.startsWith("mailto:")) contacts.push(link.url);
  });

  return {
    why,
    features: grid,
    beyond: html([beyond]),
    quickStart: { needs: mdText(needs).trim(), commands: code.value, next: html([next]) },
    howItWorks: html(prose),
    commercialContact: contacts[0] ?? fail('mailto: link under "License"'),
    license: html(license),
    contributing: html(section("Contributing")),
  };
}
