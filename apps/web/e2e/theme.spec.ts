/**
 * The app shows its theme from the first frame (app/layout.tsx): dark by default and light for a
 * reader who chose light, even before any of the page's script files has loaded, as on a slow
 * connection. The theme script must not wait for Next.js's own scripts; when it did, the page
 * showed the light theme first and its colour transitions animated the change to dark. For a light
 * reader the script changes the HTML the server sent, so each kind of page must still hydrate
 * without an error and keep the theme chosen.
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";
import { THEME_COLORS } from "../src/lib/theme-script";

type Theme = keyof typeof THEME_COLORS;

/** Each theme and how a reader gets it: dark is the default, light a stored choice. */
const READERS: { theme: Theme; stored: string | null; who: string }[] = [
  { theme: "dark", stored: null, who: "by default" },
  { theme: "light", stored: "light", who: "for a reader who chose light" },
];

/** The projects home, the editor and the accounts pages. */
const PAGES: { name: string; path: (request: APIRequestContext) => Promise<string> }[] = [
  { name: "the projects home", path: async () => "/" },
  { name: "the editor", path: async (request) => `/projects/${await project(request)}` },
  { name: "the sign-in page", path: async () => "/signin" },
  { name: "the settings page", path: async () => "/settings" },
];

let created: Promise<string> | null = null;

/** A project for the editor to open, made once. */
function project(request: APIRequestContext): Promise<string> {
  created ??= (async () => {
    const res = await request.post("/api/v1/projects", { data: { exampleId: "cache-aside-starter", name: "Theme check" } });
    expect(res.status()).toBe(201);
    return (await res.json()).id as string;
  })();
  return created;
}

/** Stores the reader's theme before any of the page's code runs, and skips the editor's tour. */
async function arrive(page: Page, stored: string | null) {
  await page.addInitScript((theme) => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
      if (theme) localStorage.setItem("archsim-theme-studio", theme);
    } catch {
      /* storage unavailable */
    }
  }, stored);
}

/** The page's background in a theme as the browser reports it: rgb(28, 28, 30) for #1c1c1e. */
function background(theme: Theme): string {
  const hex = THEME_COLORS[theme];
  return `rgb(${[1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16)).join(", ")})`;
}

/** React's hydration errors; a production build reports them as #418 and uncaught. */
function watchHydration(page: Page): string[] {
  const errors: string[] = [];
  const record = (text: string) => {
    if (/react\.dev\/errors\/418|hydrat/i.test(text)) errors.push(text);
  };
  page.on("pageerror", (e) => record(e.message));
  page.on("console", (m) => m.type() === "error" && record(m.text()));
  return errors;
}

/** Waits until React has hydrated the page: until then its theme picker does not open. */
async function waitForHydration(page: Page) {
  const menu = page.getByRole("menu");
  await expect(async () => {
    if (!(await menu.isVisible())) await page.getByRole("button", { name: "Choose a theme" }).filter({ visible: true }).click();
    await expect(menu).toBeVisible({ timeout: 1_000 });
  }).toPass();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
}

// Wide enough that the editor's top bar shows its theme picker.
test.use({ viewport: { width: 1600, height: 900 } });

for (const { name, path } of PAGES) {
  for (const { theme, stored, who } of READERS) {
    test(`${name} is ${theme} ${who} before any script file has loaded`, async ({ page, request }) => {
      const url = await path(request);
      await arrive(page, stored);
      await page.route("**/*.js", (route) => route.abort());

      await page.goto(url);

      await expect(page.locator("body")).toHaveCSS("background-color", background(theme));
    });

    test(`${name} hydrates without an error and stays ${theme} ${who}`, async ({ page, request }) => {
      const url = await path(request);
      const errors = watchHydration(page);
      await arrive(page, stored);

      await page.goto(url);
      await waitForHydration(page);

      expect(errors).toEqual([]);
      await expect(page.locator("body")).toHaveCSS("background-color", background(theme));
    });
  }
}
