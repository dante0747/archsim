import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { applyTheme } from "@/components/ui/theme-toggle";
import { THEME_COLORS, THEME_SCRIPT } from "./theme-script";

const meta = () => document.querySelector('meta[name="theme-color"]')?.getAttribute("content");

describe("the browser bar's colour (theme-color) follows the theme the page shows", () => {
  beforeEach(() => {
    document.head.innerHTML = `<meta name="theme-color" content="${THEME_COLORS.dark}">`;
    document.documentElement.className = "";
    localStorage.clear();
  });
  afterEach(() => {
    document.head.innerHTML = "";
    document.documentElement.className = "";
    localStorage.clear();
  });

  it("is dark before first paint by default, whatever the operating system prefers", () => {
    new Function(THEME_SCRIPT)();

    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(meta()).toBe(THEME_COLORS.dark);
  });

  it("is light before first paint when light was chosen", () => {
    localStorage.setItem("archsim-theme-studio", "light");

    new Function(THEME_SCRIPT)();

    expect(document.documentElement.classList.contains("dark")).toBe(false);
    expect(meta()).toBe(THEME_COLORS.light);
  });

  it("changes when the theme is switched", () => {
    applyTheme("light");
    expect(meta()).toBe(THEME_COLORS.light);

    applyTheme("dark");
    expect(meta()).toBe(THEME_COLORS.dark);
  });
});
