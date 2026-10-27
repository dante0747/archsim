/**
 * Share links end to end: a finished run shared from Results, opened at phone width (375 px)
 * without the editor, reached from the editor's phone screen, and revoked (404 at once).
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";

const PHONE = { width: 375, height: 812 };

interface Shared {
  projectId: string;
  runId: string;
  slug: string;
  shareId: string;
}

/** A project from the starter template with one finished run, through the web server's /api proxy. */
async function finishedRun(request: APIRequestContext): Promise<{ projectId: string; runId: string }> {
  const project = await request.post("/api/v1/projects", { data: { exampleId: "cache-aside-starter" } });
  expect(project.status()).toBe(201);
  const projectId = (await project.json()).id as string;
  const run = await request.post(`/api/v1/projects/${projectId}/runs`, { data: {} });
  expect(run.status()).toBe(202);
  const runId = (await run.json()).id as string;
  await expect
    .poll(async () => (await (await request.get(`/api/v1/runs/${runId}`)).json()).status, { timeout: 60_000, intervals: [500] })
    .toBe("succeeded");
  return { projectId, runId };
}

async function sharedRun(request: APIRequestContext): Promise<Shared> {
  const { projectId, runId } = await finishedRun(request);
  const res = await request.post(`/api/v1/runs/${runId}/shares`, { data: {} });
  expect(res.status()).toBe(201);
  const share = await res.json();
  return { projectId, runId, slug: share.slug, shareId: share.id };
}

function watchCsp(page: Page): string[] {
  const violations: string[] = [];
  page.on("console", (m) => {
    if (m.type() === "error" && /Content Security Policy/i.test(m.text())) violations.push(m.text());
  });
  return violations;
}

test.describe("on a phone", () => {
  test.use({ viewport: PHONE });

  test("a share opens at 375 px, from the link or the editor, and stops at once when revoked", async ({ page, request }) => {
    const csp = watchCsp(page);
    const shared = await sharedRun(request);

    const res = await page.goto(`/r/${shared.slug}`);
    expect(res?.status()).toBe(200);
    await expect(page.getByTestId("share-verdict")).toHaveText(/Yes, it holds up\.|No, it doesn’t hold up\.|No objectives set|Not enough data yet\./);
    await expect(page.getByTestId("confidence-badge")).toBeVisible();
    await expect(page.getByRole("region", { name: "Key numbers" })).toContainText("req/s");
    await expect(page.getByRole("region", { name: "Objectives" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Latency over time" }).locator("svg.recharts-surface")).toBeVisible();
    await expect(page.getByTestId("open-copy")).toBeEnabled();
    // one column, nothing wider than the phone
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(PHONE.width);
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
    await expect(page.locator('meta[property="og:image"]')).toHaveAttribute("content", new RegExp(`/r/${shared.slug}/opengraph-image`));
    const image = await request.get(`/r/${shared.slug}/opengraph-image`);
    expect(image.status()).toBe(200);
    expect(image.headers()["content-type"]).toContain("image/png");

    // the editor's phone screen offers the latest share
    await page.goto(`/projects/${shared.projectId}`);
    await expect(page.getByRole("heading", { name: "Continue on a larger screen" })).toBeVisible();
    await page.getByTestId("view-latest-results").click();
    await page.waitForURL(`**/r/${shared.slug}`);
    await expect(page.getByTestId("share-verdict")).toBeVisible();

    // revoked: 404 straight away, for the page and its preview
    expect((await request.delete(`/api/v1/shares/${shared.shareId}`)).status()).toBe(204);
    const gone = await page.reload();
    expect(gone?.status()).toBe(404);
    await expect(page.getByRole("heading", { name: "This link does not work any more" })).toBeVisible();
    expect((await request.get(`/r/${shared.slug}/opengraph-image`)).status()).toBe(404);
    expect(csp).toEqual([]);
  });

  test("Open a copy makes a project from the shared design", async ({ page, request }) => {
    const shared = await sharedRun(request);
    await page.goto(`/r/${shared.slug}`);
    await page.getByTestId("open-copy").click();
    await page.waitForURL(/\/projects\/[^/]+$/);
    expect(page.url()).not.toContain(shared.projectId);
    await expect(page.getByText(/\(copy\)” is saved/)).toBeVisible();
  });
});

test("Results → Share makes a link, lists it and revokes it", async ({ page, request }) => {
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  const { projectId } = await finishedRun(request);
  await page.goto(`/projects/${projectId}`);
  await page.getByTestId("workspace-decide").filter({ visible: true }).click();
  await page.getByTestId("share-button").click();
  const dialog = page.getByRole("dialog", { name: "Share these results" });
  await dialog.getByLabel("Title").fill("For the platform review");
  await dialog.getByTestId("share-create").click();
  const row = dialog.getByTestId("share-row");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("For the platform review");
  const href = await row.getByRole("link").getAttribute("href");
  expect(href).toMatch(/^\/r\/[0-9A-Za-z]{22}$/);
  expect((await request.get(href!)).status()).toBe(200);

  await row.getByRole("button", { name: "Revoke…" }).click();
  await row.getByRole("button", { name: "Revoke", exact: true }).click();
  await expect(dialog.getByTestId("share-row")).toHaveCount(0);
  await expect(dialog).toContainText("1 revoked or expired link no longer works.");
  expect((await request.get(href!)).status()).toBe(404);
});
