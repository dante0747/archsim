/**
 * The editor's top bar at common desktop widths: the project name, the workspace tabs, the
 * transport and the run controls sit side by side, never on top of one another, in every
 * workspace and with a scenario chosen to simulate. A long project name ends in an ellipsis and
 * keeps its full text in a tooltip.
 */
import { type APIRequestContext, expect, type Locator, type Page, test } from "@playwright/test";

const LONG_NAME = "Black Friday on Kubernetes: checkout, catalog cache and payments under a 5× ramp";
const LONG_SCENARIO = "Payments provider 5× slower (60–90 s)";
const WORKSPACES = ["decide", "build", "stress", "dashboard", "decide"] as const;

/** A Black Friday project under a long name, with one finished run: every top-bar control shows. */
async function projectWithRun(request: APIRequestContext): Promise<string> {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "black-friday-kubernetes", name: LONG_NAME } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  const run = await request.post(`/api/v1/projects/${id}/runs`, { data: {} });
  expect(run.status()).toBe(202);
  const runId = (await run.json()).id as string;
  await expect
    .poll(async () => (await (await request.get(`/api/v1/runs/${runId}`)).json()).status, { timeout: 60_000, intervals: [500] })
    .toBe("succeeded");
  return id;
}

let project: Promise<string> | null = null;

async function openProject(page: Page) {
  project ??= projectWithRun(page.request);
  const id = await project;
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto(`/projects/${id}`);
  await expect(page.getByTestId("transport-lcd")).not.toHaveAttribute("aria-label", "No run yet");
}

async function box(locator: Locator, what: string) {
  const b = await locator.boundingBox();
  expect(b, `${what} is on screen`).not.toBeNull();
  return { left: b!.x, right: b!.x + b!.width, what };
}

/** The top bar's groups, left to right; each must end before the next begins. */
async function topBar(page: Page) {
  const activity = page.getByTestId("activity-button");
  const controls = (await activity.isVisible()) ? activity : page.getByTestId("run-button");
  return [
    await box(page.getByLabel("Project name"), "the project name"),
    await box(page.getByRole("tablist", { name: "Workspaces" }).filter({ visible: true }), "the workspace tabs"),
    await box(page.getByRole("group", { name: "Replay" }).filter({ visible: true }), "the replay buttons"),
    await box(page.getByTestId("transport-lcd"), "the transport display"),
    await box(controls, "the run controls"),
    await box(page.getByTestId("user-menu"), "the account menu"),
  ];
}

for (const width of [1280, 1366, 1440, 1536]) {
  test(`at ${width} px the top bar's controls never overlap`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await openProject(page);
    // the widest run control: a scenario with a long name chosen to simulate
    await page.getByRole("button", { name: "Choose what to simulate" }).click();
    await page.getByRole("menuitem", { name: new RegExp(LONG_SCENARIO.replace(/[()]/g, "\\$&")) }).click();
    await expect(page.getByRole("button", { name: "Choose what to simulate" })).toContainText("Payments provider");

    for (const key of WORKSPACES) {
      await page.getByTestId(`workspace-${key}`).filter({ visible: true }).click();
      await expect(page.getByTestId(`workspace-${key}`).filter({ visible: true })).toHaveAttribute("aria-selected", "true");
      const groups = await topBar(page);
      for (let i = 1; i < groups.length; i++) {
        expect(groups[i - 1].right, `${key}: ${groups[i - 1].what} ends before ${groups[i].what}`).toBeLessThanOrEqual(groups[i].left);
      }
      expect(groups[groups.length - 1].right, `${key}: the top bar fits the window`).toBeLessThanOrEqual(width);
    }
  });
}

test("a long project name ends in an ellipsis and keeps its full text in a tooltip", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openProject(page);

  const name = page.getByLabel("Project name");

  await expect(name).toHaveValue(LONG_NAME);
  await expect(name).toHaveAttribute("title", LONG_NAME);
  await expect(name).toHaveCSS("text-overflow", "ellipsis");
  expect(await name.evaluate((el: HTMLInputElement) => el.scrollWidth > el.clientWidth), "the name is cut short").toBe(true);
});
