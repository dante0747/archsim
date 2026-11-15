/**
 * The public website's checks in a browser (ADR-0078): every page of the export, on a phone and a
 * desktop, in both themes. Build it first (`npm run build:site`), then `npm run e2e:site`, which
 * serves out/ as GitHub Pages does (scripts/serve-site.mjs). SITE_SCREENSHOTS=1 also saves
 * full-page screenshots of the main pages under test-results/site/.
 */
import { defineConfig, devices } from "@playwright/test";

const PORT = Number(process.env.SITE_PORT ?? 4010);

export default defineConfig({
  testDir: "./e2e-site",
  outputDir: "./test-results/site-run",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 5 * 60_000,
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: { ...devices["Desktop Chrome"], baseURL: `http://127.0.0.1:${PORT}` },
  webServer: {
    command: "node scripts/serve-site.mjs",
    env: { PORT: String(PORT) },
    url: `http://127.0.0.1:${PORT}/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
