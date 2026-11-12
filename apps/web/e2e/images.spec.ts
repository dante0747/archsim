/**
 * The web server has no image optimizer: next.config.ts turns it off, as the app shows no image
 * through next/image, so the server never loads sharp (and the web image ships none). Its icons
 * are served as they are.
 */
import { expect, test } from "@playwright/test";

test("the image optimizer is off, and the icons are served as they are", async ({ request }) => {
  expect((await request.get("/_next/image?url=%2Fapple-icon.png&w=64&q=75")).status()).toBe(404);
  expect((await request.get("/apple-icon.png")).status()).toBe(200);
});
