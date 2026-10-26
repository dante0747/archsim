import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ThemeMenu, applyTheme, toggleTheme } from "./theme-toggle";

const root = () => document.documentElement;

describe("themes", () => {
  afterEach(() => {
    root().classList.remove("dark");
    delete root().dataset.theme;
    localStorage.clear();
  });

  it("renders the picker on a page without a tooltip provider", () => {
    render(<ThemeMenu />);
    expect(screen.getByRole("button", { name: "Choose a theme" })).toBeTruthy();
  });

  it("applies and remembers dark and light, and the toggle flips between them", () => {
    applyTheme("dark");
    expect(root().classList.contains("dark")).toBe(true);
    expect(localStorage.getItem("archsim-theme-studio")).toBe("dark");

    toggleTheme();
    expect(root().classList.contains("dark")).toBe(false);
    expect(localStorage.getItem("archsim-theme-studio")).toBe("light");

    toggleTheme();
    expect(root().classList.contains("dark")).toBe(true);
  });

  it("follows the system when asked and clears the retired palette attribute", () => {
    root().dataset.theme = "nord";
    applyTheme("system");
    expect(localStorage.getItem("archsim-theme-studio")).toBe("system");
    expect(root().dataset.theme).toBeUndefined();
    // jsdom has no matchMedia, so "system" resolves to light here.
    expect(root().classList.contains("dark")).toBe(false);
  });
});
