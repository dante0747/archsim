/**
 * The capture run (`npm run e2e:capture`): README images and the demo GIF from e2e/capture.spec.ts.
 * Same servers as playwright.config.ts, but the templates keep their full length.
 */
import { defineConfig } from "@playwright/test";
import base, { servers } from "./playwright.config";

export default defineConfig({
  ...base,
  testIgnore: [],
  testMatch: "capture.spec.ts",
  outputDir: "./test-results/capture",
  retries: 0,
  timeout: 3 * 60_000,
  use: { ...base.use, trace: "off", screenshot: "off" },
  // CAPTURE_RUN_SECONDS shortens the templates, for trying out changes to the capture spec.
  webServer: servers(Number(process.env.CAPTURE_RUN_SECONDS ?? 0)),
});
