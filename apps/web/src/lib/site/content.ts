/**
 * The repository as the website reads it at build time (ADR-0078): the documents under docs/ and
 * their images, the docs index's navigation, the changelog, the README's copy and the templates.
 * Nothing here runs in a browser; the site's pages call it while `next build` renders them.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { type LinkContext, docRoute, docSlug, isDocAsset } from "./links";
import { type RenderedMarkdown, renderMarkdown } from "./markdown";
import { type NavSection, docsNav } from "./nav";
import { type ReadmeContent, readmeContent } from "./readme";

export type Doc = RenderedMarkdown & { path: string };

export class Repository {
  constructor(readonly root: string) {}

  read(path: string): string {
    return readFileSync(join(this.root, path), "utf8");
  }

  bytes(path: string): Buffer {
    return readFileSync(join(this.root, path));
  }

  private context(from: string): LinkContext {
    return {
      from,
      isFolder: (p) => {
        try {
          return statSync(join(this.root, p)).isDirectory();
        } catch {
          return false;
        }
      },
    };
  }

  private files(dir: string): string[] {
    return readdirSync(join(this.root, dir), { recursive: true, withFileTypes: true })
      .filter((e) => e.isFile())
      .map((e) => `${dir}/${join(e.parentPath, e.name).slice(join(this.root, dir).length + 1).replaceAll("\\", "/")}`)
      .sort();
  }

  /** Every Markdown document under docs/, each a page of the site. */
  docFiles(): string[] {
    return this.files("docs").filter((p) => p.endsWith(".md"));
  }

  /** Every image under docs/, which the site serves under /docs-assets. */
  docAssets(): string[] {
    return this.files("docs").filter(isDocAsset);
  }

  render(path: string): RenderedMarkdown {
    return renderMarkdown(this.read(path), this.context(path));
  }

  /** The document whose page is /docs/<slug>/. */
  doc(slug: string[]): Doc {
    const path = this.docFiles().find((p) => docSlug(p)?.join("/") === slug.join("/"));
    if (!path) throw new Error(`no document under docs/ has the page /docs/${slug.join("/")}`);
    return { ...this.render(path), path };
  }

  nav(): NavSection[] {
    return docsNav(this.read("docs/README.md"), this.context("docs/README.md"));
  }

  changelog(): RenderedMarkdown {
    return this.render("CHANGELOG.md");
  }

  readme(): ReadmeContent {
    return readmeContent(this.read("README.md"), this.context("README.md"));
  }

  /** NOTICE's copyright line. */
  copyright(): string {
    const line = /^Copyright .*$/m.exec(this.read("NOTICE"))?.[0];
    if (!line) throw new Error("NOTICE has no copyright line, which the website's footer shows");
    return line.trim();
  }

  /** The templates the app ships, examples/*.json, with how many scenarios they bring. */
  templates(): { count: number; scenarios: number } {
    const files = readdirSync(join(this.root, "examples")).filter((f) => f.endsWith(".json"));
    const scenarios = files.reduce((n, f) => n + ((JSON.parse(this.read(`examples/${f}`)) as { scenarios?: unknown[] }).scenarios?.length ?? 0), 0);
    return { count: files.length, scenarios };
  }

  /** Every page of the site, for the sitemap. */
  pages(): string[] {
    const docs = this.docFiles()
      .map((p) => docRoute(p))
      .filter((r): r is string => r !== null && r !== "/docs/");
    return ["/", "/docs/", ...docs, "/accuracy/", "/changelog/"];
  }
}

let shared: Repository | null = null;

/** The repository the site is built from: two levels above apps/web, where `next build` runs. */
export function repository(): Repository {
  shared ??= new Repository(resolve(process.cwd(), "..", ".."));
  return shared;
}
