/**
 * The run picker in the Results and Dashboard headers. At common desktop widths it shows the whole
 * label of the run it shows, "Current design · run 3f9a2c1d · seed 42 · 43s ago". A label too long
 * for it ends in an ellipsis and keeps its full text in a tooltip. In a header too narrow for
 * every control, the picker gives way: the others keep their whole width, inside the window.
 */
import { type APIRequestContext, expect, type Locator, type Page, test } from "@playwright/test";

const LONG_LABEL = "Black Friday at five times the traffic, with the payments provider slowed down and the catalog cache cold";
const HEADERS = ["decide", "dashboard"] as const;

interface FinishedRun {
  projectId: string;
  runId: string;
}

/** A Black Friday project with one finished run of its design, under `label` if one is given. */
async function projectWithRun(request: APIRequestContext, label?: string): Promise<FinishedRun> {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "black-friday-kubernetes" } });
  expect(created.status()).toBe(201);
  const projectId = (await created.json()).id as string;
  const run = await request.post(`/api/v1/projects/${projectId}/runs`, { data: label ? { label } : {} });
  expect(run.status()).toBe(202);
  const runId = (await run.json()).id as string;
  await expect
    .poll(async () => (await (await request.get(`/api/v1/runs/${runId}`)).json()).status, { timeout: 60_000, intervals: [500] })
    .toBe("succeeded");
  return { projectId, runId };
}

let designRun: Promise<FinishedRun> | null = null;
let longRun: Promise<FinishedRun> | null = null;

async function openProject(page: Page, projectId: string, width: number) {
  await page.setViewportSize({ width, height: 900 });
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByTestId("transport-lcd")).not.toHaveAttribute("aria-label", "No run yet");
}

/** The run picker in a workspace's header, once that workspace is open. */
async function runPicker(page: Page, key: (typeof HEADERS)[number]): Promise<Locator> {
  await page.getByTestId(`workspace-${key}`).filter({ visible: true }).click();
  return page.getByRole("combobox", { name: "Run", exact: true });
}

/** How wide a control is, how wide it would be if nothing squeezed it, and where it ends. */
function widths(control: Locator): Promise<{ shown: number; needed: number; right: number }> {
  return control.evaluate((el: HTMLElement) => {
    const { width: shown, right } = el.getBoundingClientRect();
    const style = el.style.cssText;
    el.style.flexShrink = "0";
    el.style.maxWidth = "none";
    const needed = el.getBoundingClientRect().width;
    el.style.cssText = style;
    return { shown, needed, right };
  });
}

/** Each control keeps its whole width and ends inside the window. */
async function expectWhole(controls: Locator[], width: number) {
  for (const control of controls) {
    const name = (await control.getAttribute("aria-label")) ?? (await control.innerText());
    const { shown, needed, right } = await widths(control);
    expect(shown, `${name} keeps its whole width`).toBeGreaterThanOrEqual(needed - 0.5);
    expect(right, `${name} ends inside the window`).toBeLessThanOrEqual(width);
  }
}

for (const width of [1280, 1440]) {
  test(`at ${width} px the run picker shows the whole label of its run`, async ({ page }) => {
    designRun ??= projectWithRun(page.request);
    const { projectId, runId } = await designRun;
    await openProject(page, projectId, width);

    for (const key of HEADERS) {
      const picker = await runPicker(page, key);

      await expect(picker).toHaveValue(runId);
      await expect(picker).toHaveAttribute("title", new RegExp(`^Current design · run ${runId.slice(0, 8)} · seed \\d+ · `));
      const { shown, needed } = await widths(picker);
      expect(shown, `${key}: the picker has room for its whole label`).toBeGreaterThanOrEqual(needed - 0.5);
    }
  });
}

test("a label too long for the run picker ends in an ellipsis and keeps its full text in a tooltip", async ({ page }) => {
  longRun ??= projectWithRun(page.request, LONG_LABEL);
  const { projectId, runId } = await longRun;
  await openProject(page, projectId, 1280);

  for (const key of HEADERS) {
    const picker = await runPicker(page, key);

    await expect(picker).toHaveValue(runId);
    await expect(picker).toHaveAttribute("title", new RegExp(`^${LONG_LABEL} · run ${runId.slice(0, 8)} · seed \\d+ · `));
    await expect(picker).toHaveCSS("text-overflow", "ellipsis");
    const { shown, needed } = await widths(picker);
    expect(shown, `${key}: the label is cut short`).toBeLessThan(needed);
  }
});

test("at 1024 px the run picker gives way, and every other control in its header stays whole", async ({ page }) => {
  const width = 1024;
  longRun ??= projectWithRun(page.request, LONG_LABEL);
  const { projectId } = await longRun;
  await openProject(page, projectId, width);

  await runPicker(page, "decide");
  const results = page.getByRole("tablist", { name: "Results" });
  await expectWhole(
    [
      ...(await results.getByRole("tab").all()),
      results.getByRole("button", { name: "Share", exact: true }),
      results.getByRole("button", { name: "Report", exact: true }),
    ],
    width,
  );

  // a replay adds its LIVE badge and clock to the Dashboard's header
  await runPicker(page, "dashboard");
  const dashboard = page.getByRole("heading", { name: "Dashboard", exact: true }).locator("xpath=..");
  await dashboard.getByRole("radio", { name: "1×", exact: true }).click();
  await dashboard.getByRole("button", { name: "Replay", exact: true }).click();
  await expect(dashboard.getByRole("status", { name: "Replaying live" })).toBeVisible();
  await expectWhole(
    [
      dashboard.getByRole("button", { name: "Time range" }),
      dashboard.getByRole("button", { name: "Zoom out", exact: true }),
      dashboard.getByRole("button", { name: "Reset to the whole run" }),
      dashboard.getByRole("button", { name: "Pause replay" }),
      dashboard.getByRole("button", { name: "Stop replay" }),
    ],
    width,
  );
});
