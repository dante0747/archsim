/**
 * One current run. Results, the canvas and the Dashboard show the same run: the one picked, and
 * then the next run that finishes, wherever an older one was picked before. Viewing a run from
 * the list of runs moves them all too.
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";

/** A cache-aside project with two finished runs of its design, "First run" and "Second run". */
async function projectWithTwoRuns(request: APIRequestContext) {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "cache-aside-starter", name: "One current run" } });
  expect(created.status()).toBe(201);
  const projectId = (await created.json()).id as string;
  const ids: string[] = [];
  for (const label of ["First run", "Second run"]) {
    const run = await request.post(`/api/v1/projects/${projectId}/runs`, { data: { label } });
    expect(run.status()).toBe(202);
    const id = (await run.json()).id as string;
    await expect
      .poll(async () => (await (await request.get(`/api/v1/runs/${id}`)).json()).status, { timeout: 60_000, intervals: [500] })
      .toBe("succeeded");
    ids.push(id);
  }
  return { projectId, first: ids[0], second: ids[1] };
}

function workspace(page: Page, key: "build" | "decide" | "dashboard") {
  return page.getByTestId(`workspace-${key}`).filter({ visible: true });
}

/** The run picker in the header of the workspace that is open (Results or Dashboard). */
const runPicker = (page: Page) => page.getByRole("combobox", { name: "Run", exact: true });

async function openSummary(page: Page) {
  await workspace(page, "decide").click();
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: "Details" }).click();
  await page.getByRole("tab", { name: "Summary" }).click();
}

test("Results, the Dashboard and Summary follow the run that finishes, after an older run was picked", async ({ page }) => {
  const { projectId, first, second } = await projectWithTwoRuns(page.request);
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByTestId("transport-lcd")).not.toHaveAttribute("aria-label", "No run yet");

  // The newest run is the current one; pick the older one in the Results header.
  await openSummary(page);
  await expect(runPicker(page)).toHaveValue(second);
  await runPicker(page).selectOption(first);
  await expect(page.getByText(/^Verdict · First run/)).toBeVisible();

  // Simulate again: the run is followed, and becomes the current one when it finishes.
  await page.getByTestId("run-button").click();
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeHidden({ timeout: 45_000 });
  const runs = (await (await page.request.get(`/api/v1/projects/${projectId}/runs`)).json()) as { id: string }[];
  const third = runs.map((r) => r.id).find((id) => id !== first && id !== second)!;
  expect(third, "the run started from the page").toBeTruthy();

  // Summary shows it (no longer the run picked before), and so do the Results header and the Dashboard's.
  await expect(page.getByText(/^Verdict · /)).not.toContainText("First run");
  await expect(runPicker(page)).toHaveValue(third);
  await workspace(page, "dashboard").click();
  await expect(runPicker(page)).toHaveValue(third);

  // Viewing a run from the list of runs moves every view to it.
  await workspace(page, "decide").click();
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: "Compare" }).click();
  await page.getByRole("tab", { name: "All runs" }).click();
  await page.getByRole("button", { name: "View Second run" }).click();
  await expect(page.getByText(/^Verdict · Second run/)).toBeVisible();
  await expect(runPicker(page)).toHaveValue(second);
});
