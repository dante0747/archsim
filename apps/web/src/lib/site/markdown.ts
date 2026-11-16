/**
 * The repository's Markdown as the website's HTML, at build time (ADR-0078): GitHub-flavoured
 * Markdown, GitHub's heading anchors, highlighted code, links rewritten for the site
 * (`./links.ts`) and raw HTML dropped. The first heading becomes the page's title and comes out of
 * the body, which the page draws under its own header.
 */
import type { Element, ElementContent, Root, RootContent } from "hast";
import rehypeHighlight from "rehype-highlight";
import rehypeSlug from "rehype-slug";
import rehypeStringify from "rehype-stringify";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";
import { visit } from "unist-util-visit";
import { repositoryUrl } from "@/lib/repository";
import { type LinkContext, resolveHref } from "./links";

export interface Heading {
  id: string;
  text: string;
  depth: number;
}

export interface RenderedMarkdown {
  /** The text of the first heading, or "" when there is none. */
  title: string;
  /** The first paragraph of prose, for the page's meta description. */
  description: string;
  /** The body, without the first heading. */
  html: string;
  /** The second- and third-level headings, for the page's contents. */
  headings: Heading[];
  /** The YAML front matter's `key: value` lines (a decision record's status and date). */
  frontMatter: Record<string, string>;
}

const FRONT_MATTER = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/;
/** Longer meta descriptions are cut short in search results. */
const DESCRIPTION_LENGTH = 155;

/** The text a node shows. */
export function textOf(node: RootContent | Root): string {
  if (node.type === "text") return node.value;
  if ("children" in node) return node.children.map((c) => textOf(c)).join("");
  return "";
}

function el(tagName: string, properties: Element["properties"], children: ElementContent[] = []): Element {
  return { type: "element", tagName, properties, children };
}

function classes(node: Element): string[] {
  const c = node.properties.className;
  return Array.isArray(c) ? c.map(String) : [];
}

function readFrontMatter(source: string): { body: string; frontMatter: Record<string, string> } {
  const match = FRONT_MATTER.exec(source);
  if (!match) return { body: source, frontMatter: {} };
  const frontMatter: Record<string, string> = {};
  for (const line of match[1].split(/\r?\n/)) {
    const pair = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line.trim());
    if (pair) frontMatter[pair[1]] = pair[2].trim();
  }
  return { body: source.slice(match[0].length), frontMatter };
}

/** A paragraph's sentences without the one ending in a colon that leads into a list or a table. */
function withoutLeadIn(text: string): string {
  if (!text.endsWith(":")) return text;
  const lastSentence = /[.!?]\s+[^.!?]*:$/.exec(text);
  return lastSentence ? text.slice(0, lastSentence.index + 1) : "";
}

/**
 * The text of the document's first paragraph of prose: a top-level paragraph (not a heading, list,
 * table, quote or code), less a closing line that only leads into what follows it. A document with
 * no other paragraph is described by its first.
 */
function firstProse(tree: Root): string {
  const paragraphs = tree.children
    .filter((n): n is Element => n.type === "element" && n.tagName === "p")
    .map((p) => textOf(p).replace(/\s+/g, " ").trim())
    .filter((text) => text !== "");
  return paragraphs.map(withoutLeadIn).find((text) => text !== "") ?? paragraphs[0] ?? "";
}

function summary(flat: string): string {
  if (flat.length <= DESCRIPTION_LENGTH) return flat;
  const cut = flat.slice(0, DESCRIPTION_LENGTH);
  return `${cut.slice(0, cut.lastIndexOf(" ")).replace(/[,;:.]$/, "")}…`;
}

/** Takes out the first heading, rewrites links, collects headings and shapes blocks for the site. */
function rehypeSite(ctx: LinkContext, out: Pick<RenderedMarkdown, "title" | "description" | "headings">) {
  return (tree: Root) => {
    const first = tree.children.findIndex((n) => n.type === "element" && n.tagName === "h1");
    if (first >= 0) {
      out.title = textOf(tree.children[first]).trim();
      tree.children.splice(first, 1);
    }
    out.description = summary(firstProse(tree));

    visit(tree, "element", (node, index, parent) => {
      const id = typeof node.properties.id === "string" ? node.properties.id : null;
      if (/^h[2-4]$/.test(node.tagName) && id) {
        const text = textOf(node).trim();
        const depth = Number(node.tagName[1]);
        if (depth <= 3) out.headings.push({ id, text, depth });
        node.children.push(el("a", { className: ["heading-anchor"], href: `#${id}`, ariaLabel: `Link to this section: ${text}` }, [{ type: "text", value: "#" }]));
      }
      if (node.tagName === "a" && typeof node.properties.href === "string") {
        node.properties.href = resolveHref(node.properties.href, ctx);
      }
      if (node.tagName === "img" && typeof node.properties.src === "string") {
        node.properties.src = resolveHref(node.properties.src, ctx);
        node.properties.loading = "lazy";
        node.properties.decoding = "async";
      }
      if (!parent || index === undefined) return;
      if (node.tagName === "table") {
        // the visit goes on into the table's cells, and on to the wrapper's next sibling
        parent.children[index] = el("div", { className: ["table-scroll"], tabIndex: 0 }, [node]);
        return;
      }
      if (node.tagName === "pre") {
        node.properties.tabIndex = 0;
        const code = node.children.find((c): c is Element => c.type === "element" && c.tagName === "code");
        if (code && classes(code).includes("language-mermaid")) {
          parent.children[index] = el("figure", { className: ["diagram"] }, [
            node,
            el("figcaption", {}, [
              { type: "text", value: "A Mermaid diagram, shown as its source. " },
              el("a", { href: repositoryUrl(ctx.from) }, [{ type: "text", value: "See it drawn on GitHub" }]),
              { type: "text", value: "." },
            ]),
          ]);
        }
        return "skip";
      }
    });
  };
}

/** Renders one Markdown document of the repository, `ctx.from` naming where it lives. */
export function renderMarkdown(source: string, ctx: LinkContext): RenderedMarkdown {
  const { body, frontMatter } = readFrontMatter(source);
  const out = { title: "", description: "", headings: [] as Heading[] };
  const html = unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype)
    .use(rehypeSlug)
    .use(rehypeHighlight, { detect: false, plainText: ["mermaid", "text", "txt"] })
    .use(() => rehypeSite(ctx, out))
    .use(rehypeStringify)
    .processSync(body)
    .toString();
  return { ...out, html, frontMatter };
}
