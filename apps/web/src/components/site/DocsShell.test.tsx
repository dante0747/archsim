import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { NavSection } from "@/lib/site/nav";
import { DocsShell } from "./DocsShell";

const nav: NavSection[] = [
  {
    title: "Start here",
    items: [
      { title: "Getting started", href: "/docs/getting-started/", description: "Run it", external: false },
      { title: "Features", href: "/docs/features/#workspaces", description: "Every workspace", external: false },
    ],
  },
  { title: "Running a server", items: [{ title: "Security policy", href: "https://github.com/dante0747/archsim/blob/main/SECURITY.md", description: "", external: true }] },
];

const doc = {
  path: "docs/getting-started.md",
  title: "Getting started",
  description: "Run it.",
  html: "<p>Body text.</p>",
  headings: [
    { id: "what-you-need", text: "What you need", depth: 2 },
    { id: "ports", text: "Ports", depth: 3 },
  ],
  frontMatter: {},
};

describe("DocsShell", () => {
  beforeEach(() => vi.stubEnv("__NEXT_TRAILING_SLASH", "true"));
  afterEach(() => vi.unstubAllEnvs());

  it("lists the docs as the index does and marks the page being read", () => {
    render(<DocsShell nav={nav} doc={doc} route="/docs/getting-started/" />);

    const docsNav = screen.getAllByRole("navigation", { name: "Documentation" })[0];
    expect(within(docsNav).getByRole("link", { name: "Getting started" })).toHaveAttribute("aria-current", "page");
    expect(within(docsNav).getByRole("link", { name: "Features" })).not.toHaveAttribute("aria-current");
    expect(within(docsNav).getByRole("link", { name: /Security policy/ })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/SECURITY.md");
  });

  it("shows the title, the body, the page's contents and its source on GitHub", () => {
    render(<DocsShell nav={nav} doc={doc} route="/docs/getting-started/" />);

    expect(screen.getByRole("heading", { level: 1, name: "Getting started" })).toBeInTheDocument();
    expect(screen.getByText("Body text.")).toBeInTheDocument();
    const toc = screen.getByRole("navigation", { name: "On this page" });
    expect(within(toc).getByRole("link", { name: "Ports" })).toHaveAttribute("href", "#ports");
    expect(screen.getByRole("link", { name: /View on GitHub/ })).toHaveAttribute("href", "https://github.com/dante0747/archsim/blob/main/docs/getting-started.md");
  });

  it("shows a decision record's status and date", () => {
    render(<DocsShell nav={nav} doc={{ ...doc, path: "docs/adr/0078-x.md", frontMatter: { status: "proposed", date: "2026-11-15" } }} route="/docs/adr/0078-x/" />);

    expect(screen.getByText("Proposed")).toBeInTheDocument();
    expect(screen.getByText("15 Nov 2026")).toBeInTheDocument();
  });

  it("leaves out a date that is not one, as in the records' template", () => {
    const { container } = render(<DocsShell nav={nav} doc={{ ...doc, path: "docs/adr/template.md", frontMatter: { status: "proposed", date: "YYYY-MM-DD" } }} route="/docs/adr/template/" />);

    expect(container.textContent).not.toMatch(/Invalid Date|YYYY/);
  });
});
