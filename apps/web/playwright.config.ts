/**
 * End-to-end tests against a production build (`npm run build` first, then `npm run e2e`).
 * Playwright starts the API (e2e/api-server.mjs: the Java API and the Java worker, on a fresh
 * PostgreSQL database, with shortened copies of the templates) and
 * `next start` pointed at it, on spare ports so a running stack is left alone. It needs
 * PostgreSQL (docker compose up -d postgres) and the API's jar (./gradlew :archsim-app:bootJar).
 * The capture spec (README images and demo GIF) has its own config: playwright.capture.config.ts.
 */
import { defineConfig, devices, type PlaywrightTestConfig } from "@playwright/test";

const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 3002);
const API_PORT = Number(process.env.E2E_API_PORT ?? 8002);
const baseURL = `http://127.0.0.1:${WEB_PORT}`;

/** The API and the web server; `runSeconds` is the simulated length of every template (0: as is). */
export function servers(runSeconds: number): PlaywrightTestConfig["webServer"] {
  return [
    {
      command: "node e2e/api-server.mjs",
      url: `http://127.0.0.1:${API_PORT}/api/v1/health`,
      env: { E2E_API_PORT: String(API_PORT), E2E_RUN_SECONDS: String(runSeconds) },
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: "pipe",
    },
    {
      // `next start` warns that the standalone output (used by the Docker image) has its own
      // server; it still serves the same build, which is what these tests need.
      command: `node scripts/run-next.mjs start -H 127.0.0.1 -p ${WEB_PORT}`,
      url: baseURL,
      env: { ARCHSIM_API_URL: `http://127.0.0.1:${API_PORT}` },
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ];
}

export default defineConfig({
  testDir: "./e2e",
  testIgnore: ["capture.spec.ts"],
  outputDir: "./test-results",
  // One shared database: tests run one at a time.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    // Wide enough that the top bar shows every control.
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: servers(20),
});
