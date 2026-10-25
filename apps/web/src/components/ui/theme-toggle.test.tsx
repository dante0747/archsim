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

  it("applies and remembers a dark theme, and the toggle returns to it", () => {
    applyTheme("nord");
    expect(root().classList.contains("dark")).toBe(true);
    expect(root().dataset.theme).toBe("nord");
    expect(localStorage.getItem("archsim-theme")).toBe("nord");

    toggleTheme();
    expect(root().classList.contains("dark")).toBe(false);
    expect(root().dataset.theme).toBeUndefined();
    expect(localStorage.getItem("archsim-theme")).toBe("light");

    toggleTheme();
    expect(root().dataset.theme).toBe("nord");
  });
});
