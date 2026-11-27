/**
 * The main path, end to end against a production build and a real API: open the cache-aside
 * starter template, simulate, read the verdict, hit the "lose the cache" chaos pad (which plans
 * the outage and simulates again), compare the two runs, find the design saved by itself, reload
 * and find it all still there.
 */
import { expect, type Page, test } from "@playwright/test";

const VERDICTS = /Yes, it holds up\.|No, it doesn’t hold up\.|No objectives yet|Not enough data yet\./;
const LOSE_CACHE = "Lose Redis Cache for 15 s";

/** Collect Content Security Policy violations: the CSP only allows same-origin requests. */
function watchCsp(page: Page): string[] {
  const violations: string[] = [];
  page.on("console", (m) => {
    if (m.type() === "error" && /Content Security Policy/i.test(m.text())) violations.push(m.text());
  });
  return violations;
}

/** A workspace tab in the top bar (it is drawn twice, one copy per screen width). */
function workspace(page: Page, key: "build" | "stress" | "decide" | "dashboard") {
  return page.getByTestId(`workspace-${key}`).filter({ visible: true });
}

async function openResults(page: Page) {
  await workspace(page, "decide").click();
  await expect(page.getByRole("tablist", { name: "Results" })).toBeVisible();
}

async function openCompare(page: Page) {
  await openResults(page);
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: "Compare" }).click();
  await page.getByRole("tab", { name: "Two runs" }).click();
}

/** Wait for the followed run to finish: the Simulate button comes back. */
async function waitForRun(page: Page) {
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeHidden({ timeout: 45_000 });
  await expect(page.getByTestId("run-button")).toBeEnabled();
}

test("starter: simulate, chaos, compare, saved by itself, reload", async ({ page }) => {
  const csp = watchCsp(page);
  // A first visit: skip the guided tour, which would cover the canvas.
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });

  // Open the starter from the gallery: the API makes a project from it and the editor loads it.
  await page.goto("/templates");
  await page.getByRole("button", { name: /Open the Cache-aside .* template/ }).first().click();
  await page.waitForURL(/\/projects\/[^/]+$/);
  const projectId = page.url().split("/").pop()!;
  await expect(page.getByTestId("node-cache")).toBeVisible();
  await expect(page.getByTestId("node-db")).toBeVisible();
  // 0 on a fresh database; a retry can reopen the unsaved copy a failed attempt left behind.
  const runsBefore = ((await (await page.request.get(`/api/v1/projects/${projectId}/runs`)).json()) as { status: string }[]).filter(
    (r) => r.status === "succeeded",
  ).length;

  // Simulate and read the verdict.
  await page.getByTestId("run-button").click();
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeVisible();
  await waitForRun(page);
  await openResults(page);
  const answer = page.getByRole("region", { name: "Answer" });
  await expect(answer.getByRole("heading", { level: 2 })).toHaveText(VERDICTS);

  // Back on the canvas, lose the cache: the pad plans the outage and simulates at once.
  await workspace(page, "build").click();
  const show = page.getByRole("button", { name: "Show chaos pads" });
  if (await show.isVisible()) await show.click();
  const pads = page.getByRole("region", { name: "Chaos pads" });
  const loseCache = pads.getByRole("button", { name: LOSE_CACHE });
  await loseCache.click();
  await expect(loseCache).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeVisible();
  await waitForRun(page);

  // Compare the two runs: the input differences show the planned outage.
  await openCompare(page);
  const runA = page.getByRole("combobox", { name: "Run A" });
  const runB = page.getByRole("combobox", { name: "Run B" });
  // the placeholder and the finished runs, newest first
  await expect(runA.locator("option")).toHaveCount(runsBefore + 3);
  const [newer, older] = await runA
    .locator("option")
    .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
  await runA.selectOption(older);
  await runB.selectOption(newer);
  await expect(page.getByText("What changed and why it matters")).toBeVisible();
  await expect(page.getByText(/^Input differences \([1-9]\d*\)$/)).toBeVisible();
  await expect(page.getByText("Key metrics (after warm-up)")).toBeVisible();

  // The design saved itself with its planned outage while the run went on: reload.
  await expect(page.getByTestId("save-status")).toHaveText("Saved");
  await page.reload();
  await expect(page.getByTestId("node-cache")).toBeVisible();
  await expect(page.getByTestId("save-status")).toHaveText("Saved");

  // Still saved: the pad is still lit (the API filled in the fault's defaults, which the pad
  // ignores), and the stored document holds the outage.
  await workspace(page, "build").click();
  if (await show.isVisible()) await show.click();
  await expect(pads.getByRole("button", { name: "Clear" })).toBeVisible();
  await expect(loseCache).toHaveAttribute("aria-pressed", "true");
  const res = await page.request.get(`/api/v1/projects/${projectId}`);
  expect(res.ok()).toBe(true);
  const project = await res.json();
  expect(project.revision).toBeGreaterThan(1);
  expect(project.document.workload.faults).toEqual([expect.objectContaining({ kind: "outage", target: "cache" })]);

  // ...and both runs are still there to compare.
  await openCompare(page);
  await expect(runA.locator("option")).toHaveCount(runsBefore + 3);

  expect(csp, "CSP violations").toEqual([]);
});

test("starter: quick check says why not, and a close call is checked with 5 seeds", async ({ page }) => {
  test.setTimeout(180_000);
  const csp = watchCsp(page);
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto("/templates");
  await page.getByRole("button", { name: /Open the Cache-aside .* template/ }).first().click();
  await page.waitForURL(/\/projects\/[^/]+$/);
  const projectId = page.url().split("/").pop()!;
  await expect(page.getByTestId("node-cache")).toBeVisible();

  // The starter's run is too short to answer from a sample: the menu item is off and says why.
  await page.getByRole("button", { name: "Choose what to simulate" }).click();
  await expect(page.getByTestId("quick-check-item")).toHaveAttribute("data-disabled", "");
  await expect(page.getByTestId("quick-check-reason")).toContainText("too short to gain from a quick check");
  await page.keyboard.press("Escape");

  // One run, then a p95 objective just above what it measured: one seed is not enough to tell.
  await page.getByTestId("run-button").click();
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeVisible();
  await waitForRun(page);
  const runs = (await (await page.request.get(`/api/v1/projects/${projectId}/runs`)).json()) as { status: string; summary: { p95: number } }[];
  const p95 = runs.find((r) => r.status === "succeeded")!.summary.p95;
  await openResults(page);
  const answer = page.getByRole("region", { name: "Answer" });
  await page.getByRole("button", { name: "Edit Latency p95 target" }).click();
  await page.getByLabel("Latency p95 target").fill(String(Math.ceil(p95 * 1.05)));
  await page.getByLabel("Latency p95 target").press("Enter");
  const offer = answer.getByTestId("check-seeds");
  await expect(offer).toContainText("is within 15% of the");

  // Check 5 seeds: the answer reads them together.
  await offer.getByRole("button", { name: "Check 5 seeds" }).click();
  const range = answer.getByTestId("seed-range");
  await expect(range).toContainText(/(Meets|Misses) the objectives in \d of 5 runs · p95 [\d.]+(–[\d.]+ ms \(median [\d.]+\)| ms) · seeds 42–46/, {
    timeout: 120_000,
  });
  const group = (await (await page.request.get(`/api/v1/projects/${projectId}/runs`)).json()) as { groupId: string | null; seed: number }[];
  expect(group.filter((r) => r.groupId).map((r) => r.seed).sort()).toEqual([42, 43, 44, 45, 46]);

  expect(csp, "CSP violations").toEqual([]);
});
