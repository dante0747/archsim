/**
 * The facts the home page takes from the repository's README at build time (ADR-0078, ADR-0097):
 * the screenshots with their alt text, titles and captions, the quick start's commands and first
 * step, how accurate ArchSim is in the README's own words, and who to ask for a commercial license. A section the site reads that has gone fails the
 * build, naming it, rather than leaving a gap on the page.
 */
import type { Element, Root as HastRoot } from "hast";
import type { Paragraph, RootContent } from "mdast";
import rehypeParse from "rehype-parse";
import { unified } from "unified";
import { visit } from "unist-util-visit";
import { type LinkContext, resolveHref } from "./links";
import { renderMarkdown, textOf } from "./markdown";
import { mdText, parseMarkdown, sections, sourceOf } from "./mdast";

export interface Feature {
  /** The screenshot, where the site serves it. */
  image: string;
  alt: string;
  title: string;
  caption: string;
}

export interface ReadmeContent {
  features: Feature[];
  quickStart: {
    /** What you need ("You need Docker with Compose, nothing else."). */
    needs: string;
    commands: string;
    /** The first sentence after the commands, the step that follows them, as inline HTML. */
    next: string;
  };
  /**
   * "How accurate is it?", as the README words it: the experiments (a paragraph leading into the
   * table), the table, the paragraph after it and the caveat, each of the last two up to its first
   * link, whose files the home page links in its own words. HTML, the last two inline.
   */
  accuracy: { intro: string; table: string; notes: string; caveat: string };
  /** The License section's mailto: link. */
  commercialContact: string;
}

function fail(what: string): never {
  throw new Error(`README.md has no ${what}, which the website's home page shows (docs/website.md)`);
}

function featureGrid(blocks: RootContent[], ctx: LinkContext): Feature[] {
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

/** A paragraph's first sentence, as Markdown: up to the first full stop in its own text, not in a link or code. */
function firstSentence(source: string, paragraph: Paragraph): string {
  const start = paragraph.position?.start.offset ?? 0;
  for (const child of paragraph.children) {
    const at = child.type === "text" ? child.value.search(/\.(\s|$)/) : -1;
    const offset = child.position?.start.offset;
    if (at >= 0 && offset !== undefined) return source.slice(start, offset + at + 1);
  }
  return sourceOf(source, paragraph);
}

/**
 * What the home page shows inside a paragraph of its own (`html`, one rendered paragraph): its
 * content, or the build fails, naming the README's section, since a block inside a <p> makes the
 * browser rebuild the page and breaks its hydration.
 */
export function paragraphContent(html: string, section: string): string {
  const content = /^<p>([\s\S]*)<\/p>$/.exec(html.trim())?.[1];
  if (content === undefined || /<\/?(?:p|blockquote|ul|ol|li|table|pre|div|h[1-6])\b/.test(content)) {
    throw new Error(`README.md's "${section}" section is more than one paragraph where the home page shows one (docs/website.md)`);
  }
  return content;
}

/** A paragraph's sentences before its first link (all of it when it has none), as Markdown. */
function beforeFirstLink(source: string, paragraph: Paragraph): string {
  const text = sourceOf(source, paragraph);
  const link = paragraph.children.find((c) => c.type === "link");
  const start = paragraph.position?.start.offset;
  const at = link?.position?.start.offset;
  if (start === undefined || at === undefined) return text;
  const before = source.slice(start, at);
  const end = before.search(/[.!?]\s+[^.!?]*$/);
  return end >= 0 ? before.slice(0, end + 1) : before;
}

export function readmeContent(source: string, ctx: LinkContext): ReadmeContent {
  const all = sections(parseMarkdown(source));
  const section = (name: string) => all.get(name) ?? fail(`"${name}" section`);
  const inline = (markdown: string, name: string) => paragraphContent(renderMarkdown(markdown, ctx).html, name);

  const features = featureGrid(section("Features"), ctx);
  if (features.length === 0) fail('screenshot table under "Features"');

  const quick = section("Quick start");
  const codeAt = quick.findIndex((b) => b.type === "code");
  const code = quick[codeAt];
  const needs = quick.find((b) => b.type === "paragraph");
  const next = quick[codeAt + 1];
  if (code?.type !== "code" || !needs || next?.type !== "paragraph") fail('"Quick start" paragraph, commands and next step');

  const accuracy = section("How accurate is it?");
  const table = accuracy.find((b) => b.type === "table");
  const intro = accuracy[accuracy.indexOf(table ?? accuracy[0]) - 1];
  const notes = accuracy[accuracy.indexOf(table ?? accuracy[0]) + 1];
  const quote = accuracy.find((b) => b.type === "blockquote");
  const caveat = quote?.type === "blockquote" ? quote.children.find((c) => c.type === "paragraph") : undefined;
  if (!table || intro?.type !== "paragraph" || notes?.type !== "paragraph" || caveat?.type !== "paragraph") {
    fail('"How accurate is it?" paragraph, table, paragraph after it and quoted caveat');
  }

  const contacts: string[] = [];
  visit({ type: "root", children: section("License") }, "link", (link) => {
    if (link.url.startsWith("mailto:")) contacts.push(link.url);
  });

  return {
    features,
    quickStart: { needs: mdText(needs).trim(), commands: code.value, next: inline(firstSentence(source, next), "Quick start") },
    accuracy: {
      intro: inline(sourceOf(source, intro), "How accurate is it?"),
      table: renderMarkdown(sourceOf(source, table), ctx).html,
      notes: inline(beforeFirstLink(source, notes), "How accurate is it?"),
      // the quote's own lines, without the "> " that marks each of them
      caveat: inline(beforeFirstLink(source, caveat).replace(/\n[ \t]*>[ \t]?/g, "\n"), "How accurate is it?"),
    },
    commercialContact: contacts[0] ?? fail('mailto: link under "License"'),
  };
}
