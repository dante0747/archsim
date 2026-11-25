import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { applyTheme } from "@/components/ui/theme-toggle";
import { THEME_COLORS, THEME_COLOR_SCRIPT, THEME_SCRIPT } from "./theme-script";

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

describe("the website's browser bar colour is a tag of its theme script's own", () => {
  const tags = () => document.querySelectorAll('meta[name="theme-color"]');

  beforeEach(() => {
    document.head.innerHTML = "";
    document.documentElement.className = "";
    localStorage.clear();
  });
  afterEach(() => {
    document.head.innerHTML = "";
    document.documentElement.className = "";
    localStorage.clear();
  });

  it("adds the one theme-color tag, dark by default", () => {
    new Function(THEME_SCRIPT + THEME_COLOR_SCRIPT)();

    expect(tags()).toHaveLength(1);
    expect(meta()).toBe(THEME_COLORS.dark);
  });

  it("gives it the light theme's colour when light was chosen", () => {
    localStorage.setItem("archsim-theme-studio", "light");

    new Function(THEME_SCRIPT + THEME_COLOR_SCRIPT)();

    expect(tags()).toHaveLength(1);
    expect(meta()).toBe(THEME_COLORS.light);
  });

  it("leaves a page whose HTML starts dark dark by default", () => {
    document.documentElement.className = "dark";

    new Function(THEME_SCRIPT + THEME_COLOR_SCRIPT)();

    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("turns a page whose HTML starts dark light when light was chosen", () => {
    document.documentElement.className = "dark";
    localStorage.setItem("archsim-theme-studio", "light");

    new Function(THEME_SCRIPT + THEME_COLOR_SCRIPT)();

    expect(document.documentElement.classList.contains("dark")).toBe(false);
    expect(meta()).toBe(THEME_COLORS.light);
  });

  it("keeps the one tag when the theme is switched", () => {
    new Function(THEME_SCRIPT + THEME_COLOR_SCRIPT)();

    applyTheme("light");

    expect(tags()).toHaveLength(1);
    expect(meta()).toBe(THEME_COLORS.light);
  });
});
