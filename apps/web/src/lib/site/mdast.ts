/** Reading Markdown's structure (its syntax tree), for the parts of the site built from it. */
import type { Nodes, Root, RootContent } from "mdast";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

export function parseMarkdown(source: string): Root {
  return unified().use(remarkParse).use(remarkGfm).parse(source);
}

/** The text a node shows, inline code included. */
export function mdText(node: Nodes): string {
  if ("value" in node && typeof node.value === "string") return node.value;
  if ("children" in node) return node.children.map((c) => mdText(c)).join("");
  return "";
}

/** The blocks under each second-level heading, by the heading's text. */
export function sections(root: Root): Map<string, RootContent[]> {
  const out = new Map<string, RootContent[]>();
  let current: RootContent[] | null = null;
  for (const node of root.children) {
    if (node.type === "heading" && node.depth <= 2) {
      current = node.depth === 2 ? [] : null;
      if (current) out.set(mdText(node).trim(), current);
    } else if (current) {
      current.push(node);
    }
  }
  return out;
}

/** The source text a block was parsed from. */
export function sourceOf(source: string, node: RootContent): string {
  const start = node.position?.start.offset;
  const end = node.position?.end.offset;
  if (start === undefined || end === undefined) throw new Error(`no position for a ${node.type}`);
  return source.slice(start, end);
}
