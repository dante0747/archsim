import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SiteHeader } from "./SiteHeader";

describe("SiteHeader", () => {
  // as the site's build does (trailingSlash in next.config.ts), so links keep their slash
  beforeEach(() => vi.stubEnv("__NEXT_TRAILING_SLASH", "true"));
  afterEach(() => vi.unstubAllEnvs());

  it("links the site's pages and the repository, and hides Try it live without a demo", () => {
    render(<SiteHeader demoUrl={null} />);

    const nav = screen.getByRole("navigation", { name: "Main" });
    for (const [name, href] of [
      ["Features", "/#features"],
      ["Accuracy", "/accuracy/"],
      ["Docs", "/docs/"],
      ["Changelog", "/changelog/"],
    ]) {
      expect(within(nav).getByRole("link", { name })).toHaveAttribute("href", href);
    }
    expect(screen.getByRole("link", { name: /GitHub/ })).toHaveAttribute("href", "https://github.com/dante0747/archsim");
    expect(screen.queryByRole("link", { name: /Try it live/ })).toBeNull();
  });

  it("shows Try it live when the build names a demo", () => {
    render(<SiteHeader demoUrl="https://app.archsim.io/" />);

    expect(screen.getByRole("link", { name: /Try it live/ })).toHaveAttribute("href", "https://app.archsim.io/");
  });
});
