/**
 * The Test workspace's timeline. The latency lane's SLO label stays readable wherever the p95 curve
 * goes.
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";

/** Black Friday's own length; the e2e server shortens every template to 20 s. */
const FULL_LENGTH = { durationS: 120, warmupS: 10 };

/** A Black Friday project with one finished run of its design, optionally at another length. */
async function blackFriday(request: APIRequestContext, length?: typeof FULL_LENGTH): Promise<string> {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "black-friday-kubernetes" } });
  expect(created.status()).toBe(201);
  const project = await created.json();
  if (length) {
    const document = { ...project.document, workload: { ...project.document.workload, ...length } };
    expect((await request.put(`/api/v1/projects/${project.id}`, { data: { document } })).status()).toBe(200);
  }
  const run = await request.post(`/api/v1/projects/${project.id}/runs`, { data: {} });
  expect(run.status()).toBe(202);
  const runId = (await run.json()).id as string;
  await expect
    .poll(async () => (await (await request.get(`/api/v1/runs/${runId}`)).json()).status, { timeout: 60_000, intervals: [500] })
    .toBe("succeeded");
  return project.id;
}

/** Open the Test workspace of a new Black Friday project with its run on the timeline. */
async function openTimeline(page: Page, length?: typeof FULL_LENGTH) {
  const project = await blackFriday(page.request, length);
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto(`/projects/${project}`);
  await expect(page.getByTestId("transport-lcd")).not.toHaveAttribute("aria-label", "No run yet");
  await page.getByTestId("workspace-stress").filter({ visible: true }).click();
  await expect(page.getByRole("img", { name: "Offered load over the run" })).toBeVisible();
}

test("the latency lane's SLO label reads whole where Black Friday's p95 spikes at the end of its own 120 s", async ({ page }) => {
  await openTimeline(page, FULL_LENGTH);
  const strip = page.locator('[data-lane="latency"] svg');
  const label = strip.locator("text", { hasText: "SLO 500 ms" });
  await page.mouse.move(0, 0);

  const plot = await strip.boundingBox();
  const box = await label.boundingBox();
  if (!plot || !box) throw new Error("the latency lane or its SLO label is not on screen");
  const asDrawn = await page.screenshot({ clip: box });
  await strip.locator("path").evaluate((curve) => curve.setAttribute("visibility", "hidden"));
  const withoutCurve = await page.screenshot({ clip: box });

  expect(box.x, "the label starts inside the lane").toBeGreaterThanOrEqual(plot.x);
  expect(box.x + box.width, "the label ends inside the lane").toBeLessThanOrEqual(plot.x + plot.width);
  expect(box.y, "the label's top is inside the lane").toBeGreaterThanOrEqual(plot.y);
  expect(box.y + box.height, "the label's bottom is inside the lane").toBeLessThanOrEqual(plot.y + plot.height);
  expect(asDrawn.equals(withoutCurve), "the p95 curve leaves the label as it would be without the curve").toBe(true);
});
