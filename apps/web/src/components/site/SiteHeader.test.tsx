import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SiteHeader } from "./SiteHeader";

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

describe("SiteHeader", () => {
  // as the site's build does (trailingSlash in next.config.ts), so links keep their slash
  beforeEach(() => vi.stubEnv("__NEXT_TRAILING_SLASH", "true"));
  afterEach(() => vi.unstubAllEnvs());

  it("links the product, the accuracy report and the docs, then GitHub, with one call to action", () => {
    render(<SiteHeader demoUrl={null} />);

    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getAllByRole("link").map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      ["Product", "/#features"],
      ["Accuracy", "/accuracy/"],
      ["Docs", "/docs/"],
    ]);
    expect(screen.getByRole("link", { name: "GitHub" })).toHaveAttribute("href", "https://github.com/dante0747/archsim");
    expect(screen.getByRole("link", { name: "Try ArchSim" })).toHaveAttribute("href", "/#quick-start");
  });

  it("sends Try ArchSim to the hosted demo when the build names one", () => {
    render(<SiteHeader demoUrl="https://app.archsim.io/" />);

    expect(screen.getByRole("link", { name: "Try ArchSim" })).toHaveAttribute("href", "https://app.archsim.io/");
  });

  it("opens a menu on a phone that closes with Escape, giving focus back to its button", async () => {
    const user = userEvent.setup();
    render(<SiteHeader demoUrl="https://app.archsim.io/" />);

    const button = screen.getByRole("button", { name: "Open menu" });
    await user.click(button);
    const menu = screen.getByRole("dialog", { name: "Menu" });
    expect(within(menu).getAllByRole("link").map((a) => a.textContent)).toEqual(["Product", "Accuracy", "Docs", "GitHub", "Try ArchSim"]);
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(button).toHaveFocus();
  });

  it("closes the menu once a link in it is followed", async () => {
    const user = userEvent.setup();
    render(<SiteHeader demoUrl={null} />);

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    await user.click(within(screen.getByRole("dialog", { name: "Menu" })).getByRole("link", { name: "Product" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
