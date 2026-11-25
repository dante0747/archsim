/**
 * The home page in a browser (ADR-0097), at every width the redesign was checked at: it never
 * scrolls sideways, its calls to action line up as they are drawn, the header stays one bar, the
 * phone's menu and the product views work from the keyboard, the quick start's commands copy, and
 * every image keeps its space while it loads.
 */
import { type Locator, type Page, expect, test } from "@playwright/test";

const WIDTHS = [320, 360, 375, 390, 414, 430, 768, 1024, 1280, 1440, 1920];
const PHONES = [320, 375, 390, 430];
const WIDER = [768, 1024, 1440];

async function open(page: Page, width: number) {
  await page.setViewportSize({ width, height: width < 768 ? 812 : 900 });
  await page.goto("/");
}

const hero = (page: Page) => page.getByRole("region", { name: /Crash-test your architecture/ });

/** Equal as drawn: Firefox lays out in sixtieths of a pixel, so 48 can come back as 47.99997. */
const near = (a: number, b: number) => Math.abs(a - b) < 0.5;

async function box(locator: Locator) {
  const b = await locator.boundingBox();
  if (!b) throw new Error("not drawn");
  return b;
}

/** The hero's two calls to action, as drawn. */
async function actions(page: Page) {
  const area = hero(page);
  return Promise.all([box(area.getByRole("link", { name: "Try ArchSim" })), box(area.getByRole("link", { name: "View on GitHub" }))]);
}

test("the home page never scrolls sideways, at any width", async ({ page }) => {
  const wide: string[] = [];
  for (const width of WIDTHS) {
    await open(page, width);
    const { scroll, client } = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
    if (scroll > client) wide.push(`${width}: ${scroll} > ${client}`);
  }
  expect(wide, "widths that scroll sideways").toEqual([]);
});

test("on a phone the hero's actions stack at the same full width, 48 pixels tall and 12 apart", async ({ page }) => {
  for (const width of PHONES) {
    await open(page, width);

    const [primary, secondary] = await actions(page);

    expect(near(primary.height, 48) && near(secondary.height, 48), `${width}: heights ${primary.height}, ${secondary.height}`).toBe(true);
    expect(near(primary.width, secondary.width), `${width}: widths ${primary.width}, ${secondary.width}`).toBe(true);
    expect(near(primary.x, secondary.x), `${width}: left edges ${primary.x}, ${secondary.x}`).toBe(true);
    expect(near(secondary.y - (primary.y + primary.height), 12), `${width}: gap`).toBe(true);
  }
});

test("from a tablet up the hero's actions sit side by side, 48 pixels tall and 12 apart", async ({ page }) => {
  for (const width of WIDER) {
    await open(page, width);

    const [primary, secondary] = await actions(page);

    expect(near(primary.height, 48) && near(secondary.height, 48), `${width}: heights ${primary.height}, ${secondary.height}`).toBe(true);
    expect(near(primary.y, secondary.y), `${width}: tops ${primary.y}, ${secondary.y}`).toBe(true);
    expect(near(secondary.x - (primary.x + primary.width), 12), `${width}: gap`).toBe(true);
  }
});

test("the header stays one 56-pixel bar at every width", async ({ page }) => {
  for (const width of WIDTHS) {
    await open(page, width);

    expect((await box(page.locator("body > div > header"))).height, `${width}`).toBeLessThanOrEqual(57);
  }
});

test("the phone's menu keeps focus inside, closes with Escape, and closes when a link is followed", async ({ page }) => {
  await open(page, 375);
  const button = page.getByRole("button", { name: "Open menu" });
  const menu = page.getByRole("dialog", { name: "Menu" });

  await button.click();
  await expect(menu).toBeVisible();
  for (let i = 0; i < 8; i++) {
    await page.keyboard.press("Tab");
    expect(await page.evaluate(() => document.activeElement?.closest("[role=dialog]") !== null)).toBe(true);
  }
  await page.keyboard.press("Escape");

  await expect(menu).toBeHidden();
  await expect(button).toBeFocused();

  await button.click();
  await menu.getByRole("link", { name: "Accuracy" }).click();

  await expect(page).toHaveURL(/\/accuracy\/$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("the product views switch with the arrow keys, and switching moves nothing", async ({ page }) => {
  await open(page, 1440);
  const tabs = page.getByRole("tab");
  const section = page.locator("#tour");
  await expect(tabs).toHaveCount(3);
  const before = await box(section);

  await tabs.first().focus();
  await page.keyboard.press("ArrowRight");

  await expect(tabs.nth(1)).toHaveAttribute("aria-selected", "true");
  await expect(tabs.nth(1)).toBeFocused();
  await expect(page.getByRole("tabpanel", { name: "Results" }).getByRole("img")).toBeVisible();
  expect(near((await box(section)).height, before.height)).toBe(true);
});

test("the quick start's commands copy to the clipboard", async ({ page, context, browserName }) => {
  test.skip(browserName !== "chromium", "only Chromium lets a test grant clipboard access");
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page, 1440);
  const quick = page.getByRole("region", { name: "Run ArchSim locally." });

  await quick.getByRole("button", { name: "Copy" }).click();

  await expect(quick.getByRole("button", { name: "Copied" })).toBeVisible();
  // Windows' clipboard ends lines with \r\n
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, "\n");
  expect(copied).toMatch(/^git clone https:\/\/github\.com\/dante0747\/archsim\.git\ncd archsim\ndocker compose up/);
});

test("the hero's screenshot loads first, a phone gets its crop, and every image keeps its space", async ({ page }) => {
  await open(page, 390);
  const shot = hero(page).getByRole("img");

  await expect(shot).not.toHaveAttribute("loading", "lazy");
  await expect.poll(() => shot.evaluate((img: HTMLImageElement) => img.currentSrc)).toMatch(/editor-results-crop-\d+\.webp$/);
  const unsized = await page.evaluate(() => [...document.images].filter((img) => !img.getAttribute("width") || !img.getAttribute("height")).map((img) => img.alt));
  expect(unsized).toEqual([]);
});
