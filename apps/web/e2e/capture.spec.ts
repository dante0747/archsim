/**
 * README images and the demo GIF, on demand: `npm run build`, then `npm run e2e:capture`
 * (playwright.capture.config.ts; the default `npm run e2e` skips this file).
 *
 * The templates run at full length here. The shots come from the Black Friday template with every
 * scenario simulated. Its month bills 24 peak hours, so the trade-offs map sets the autoscaler, at
 * the pods it scales to for those hours, against 3 and 14 fixed pods. Each shot is its own test,
 * so one broken shot doesn't stop the rest. The video records the starter's cache-outage demo
 * (about 60 s) and converts it to a GIF with ffmpeg.
 *
 * Writes docs/images/<name>.jpg (taken at 1440 × 900, two device pixels per CSS pixel, and scaled
 * to 800 × 500, the names the README uses), the website's renditions of the screens its home page
 * shows under docs/images/site/ (src/lib/site/product-views.ts), and docs/images/cache-outage.gif.
 * Set CAPTURE_OUT_DIR to write somewhere else. Without ffmpeg on PATH the shots stay full size, the
 * website's renditions are not written, and the video is saved as .webm, with the command to
 * convert it printed.
 */
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, statSync } from "node:fs";
import path from "node:path";
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";
import { PRODUCT_VIEWS, renditions } from "../src/lib/site/product-views";

const OUT = path.resolve(process.env.CAPTURE_OUT_DIR ?? path.join(__dirname, "..", "..", "..", "docs", "images"));
const SHOWCASE = "black-friday-kubernetes";
const HAS_FFMPEG = spawnSync("ffmpeg", ["-version"], { stdio: "ignore" }).status === 0;

type Theme = "dark" | "light";

function ffmpeg(args: string[]) {
  const r = spawnSync("ffmpeg", ["-y", "-loglevel", "error", ...args], { encoding: "utf8" });
  if (r.status !== 0) throw new Error(`ffmpeg ${args.join(" ")} failed: ${r.stderr}`);
}

async function prepare(page: Page, theme: Theme) {
  await page.addInitScript((t) => {
    try {
      localStorage.setItem("archsim-theme-studio", t);
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  }, theme);
}

/**
 * The website's renditions of a screen its home page shows (src/lib/site/product-views.ts), as
 * WebP: the whole screen at each width the page serves, and the phone's crop at one and two
 * pixels per CSS pixel. `scale` is the screenshot's device pixels per CSS pixel.
 */
function siteRenditions(png: string, name: string, scale: number) {
  const crop = PRODUCT_VIEWS[name].crop;
  mkdirSync(path.join(OUT, "site"), { recursive: true });
  for (const r of renditions(name)) {
    const area = r.crop && crop ? `crop=${crop.width * scale}:${crop.height * scale}:${crop.x * scale}:${crop.y * scale},` : "";
    ffmpeg(["-i", png, "-vf", `${area}scale=${r.width}:-2:flags=lanczos`, "-c:v", "libwebp", "-quality", "82", "-compression_level", "6", path.join(OUT, "site", path.basename(r.path))]);
  }
}

/** Screenshot the viewport into docs/images/<name>.jpg, and the website's renditions of it. */
async function shoot(page: Page, name: string) {
  await page.mouse.move(0, 0);
  await page.waitForTimeout(800); // entry animations
  mkdirSync(OUT, { recursive: true });
  const out = path.join(OUT, `${name}.jpg`);
  if (HAS_FFMPEG) {
    const png = test.info().outputPath(`${name}.png`);
    await page.screenshot({ path: png });
    ffmpeg(["-i", png, "-vf", "scale=800:500:flags=lanczos", "-q:v", "3", out]);
    if (name in PRODUCT_VIEWS) siteRenditions(png, name, await page.evaluate(() => window.devicePixelRatio));
  } else {
    await page.screenshot({ path: out, type: "jpeg", quality: 85 });
  }
}

async function waitForRuns(api: APIRequestContext, ids: string[]) {
  await expect
    .poll(
      async () => {
        const runs = await Promise.all(ids.map(async (id) => (await (await api.get(`/api/v1/runs/${id}`)).json()) as { status: string }));
        const bad = runs.find((r) => r.status === "failed" || r.status === "cancelled");
        if (bad) throw new Error(`a run ended ${bad.status}`);
        return runs.every((r) => r.status === "succeeded");
      },
      { timeout: 8 * 60_000, intervals: [2_000] },
    )
    .toBe(true);
}

/**
 * A project from a template with every scenario simulated, then, with `design`, the design itself
 * (the run shown first). Made by the first test that needs it; the database lives as long as the
 * API server, so a restarted worker finds the finished project and reuses it.
 */
async function makeProject(api: APIRequestContext, exampleId: string, design: boolean): Promise<string> {
  const example = (await (await api.get(`/api/v1/examples/${exampleId}`)).json()) as { metadata: { name: string }; scenarios: { id: string }[] };
  const projects = (await (await api.get("/api/v1/projects")).json()) as { id: string; name: string }[];
  for (const p of projects.filter((x) => x.name === example.metadata.name)) {
    const runs = (await (await api.get(`/api/v1/projects/${p.id}/runs`)).json()) as { status: string; scenarioId: string | null }[];
    const done = runs.filter((r) => r.status === "succeeded");
    if (design ? done.length > example.scenarios.length && done[0].scenarioId === null : done.length >= example.scenarios.length) return p.id;
  }
  const project = (await (await api.post("/api/v1/projects", { data: { exampleId } })).json()) as { id: string };
  const batch = (await (
    await api.post(`/api/v1/projects/${project.id}/runs/batch`, { data: { scenarioIds: example.scenarios.map((s) => s.id) } })
  ).json()) as { id: string }[];
  await waitForRuns(api, batch.map((r) => r.id));
  if (design) {
    const base = (await (await api.post(`/api/v1/projects/${project.id}/runs`, { data: {} })).json()) as { id: string };
    await waitForRuns(api, [base.id]);
  }
  return project.id;
}

const made = new Map<string, Promise<string>>();

function workspace(page: Page, key: "build" | "stress" | "decide" | "dashboard") {
  return page.getByTestId(`workspace-${key}`).filter({ visible: true });
}

/** Open a template's project (made on first use), once the part `nodeId` is on the canvas. */
async function openProject(page: Page, exampleId: string, design: boolean, nodeId: string, theme: Theme) {
  test.setTimeout(12 * 60_000); // the first caller simulates the project
  let project = made.get(exampleId);
  if (!project) {
    project = makeProject(page.request, exampleId, design).catch((e) => {
      made.delete(exampleId);
      throw e;
    });
    made.set(exampleId, project);
  }
  const id = await project;
  await prepare(page, theme);
  await page.goto(`/projects/${id}`);
  await expect(page.getByTestId(`node-${nodeId}`)).toBeVisible();
  // the latest run is on the canvas once the transport shows it
  await expect(page.getByTestId("transport-lcd")).not.toHaveAttribute("aria-label", "No run yet");
}

async function openShowcase(page: Page, theme: Theme) {
  await openProject(page, SHOWCASE, true, "orders", theme);
}

async function openResults(page: Page, tab: "Answer" | "Compare" | "Details") {
  await workspace(page, "decide").click();
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: tab }).click();
}

test("home", async ({ page }) => {
  await prepare(page, "dark");
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await shoot(page, "home");
});

test("templates", async ({ page }) => {
  await prepare(page, "dark");
  await page.goto("/templates");
  await expect(page.getByRole("button", { name: /^Open the .* template$/ }).first()).toBeVisible();
  // The counts, the filters with theirs and the featured template, as in the README: the counts
  // just under the sticky header, the featured template running on past the bottom edge.
  const counts = page.locator("dl", { hasText: "scenarios to compare" });
  await expect(counts).not.toContainText("…"); // every template's design has loaded
  const header = await page.locator("header").first().boundingBox();
  const top = await counts.boundingBox();
  if (!header || !top) throw new Error("the gallery's header or counts are not on screen");
  await page.evaluate((y) => window.scrollBy(0, y), top.y - header.height - 20);
  await page.waitForTimeout(1_500); // the previews draw their diagrams
  await shoot(page, "templates");
});

test("build", async ({ page }) => {
  await openShowcase(page, "light");
  await workspace(page, "build").click();
  await page.getByTestId("node-orders").click();
  await expect(page.getByText(/How PostgreSQL behaves/)).toBeVisible();
  await shoot(page, "build");
});

test("editor-results", async ({ page }) => {
  await openShowcase(page, "dark");
  await workspace(page, "stress").click();
  // Scrub to about 100 s of the 120 s ramp: click the timeline ruler's plot at that fraction.
  const timeline = page.getByLabel("Replay timeline");
  const ruler = timeline.locator(".cursor-ew-resize").first();
  const plot = ruler.locator(":scope > div").nth(1);
  const box = await plot.boundingBox();
  if (!box) throw new Error("the timeline ruler is not on screen");
  await page.mouse.click(box.x + box.width * (101.6 / 120), box.y + box.height / 2);
  await expect(page.getByTestId("transport-lcd")).toHaveAttribute("aria-label", /^Replay at/);
  await shoot(page, "editor-results");
});

test("scorecard", async ({ page }) => {
  await openShowcase(page, "light");
  await openResults(page, "Answer");
  await expect(page.getByRole("region", { name: "Answer" }).getByRole("heading", { level: 2 })).toBeVisible();
  await shoot(page, "scorecard");
});

test("tradeoffs", async ({ page }) => {
  // the autoscaler against 3 and 14 fixed pods, each at its own price
  await openShowcase(page, "dark");
  await openResults(page, "Compare");
  await page.getByRole("tab", { name: "Cost vs. speed" }).click();
  await expect(page.getByRole("img", { name: "Scatter of monthly cost against p95 latency per run" })).toBeVisible();
  await expect(page.getByText("loading reports…")).toBeHidden();
  await page.waitForTimeout(1_000); // the chart lays out
  await shoot(page, "tradeoffs");
});

test("traces", async ({ page }) => {
  await openShowcase(page, "dark");
  await openResults(page, "Details");
  await page.getByRole("tab", { name: "Request traces" }).click();
  const write = page.getByRole("button", { name: /^Succeeded Write at / }).first();
  await write.click();
  await expect(page.getByRole("heading", { name: /^Write request / })).toBeVisible();
  await shoot(page, "traces");
});

test("calculations", async ({ page }) => {
  await openShowcase(page, "dark");
  await openResults(page, "Details");
  await page.getByRole("tab", { name: "Calculations" }).click();
  await page.getByRole("navigation", { name: "Components" }).getByRole("button", { name: /Orders DB/ }).click();
  await page.getByRole("navigation", { name: "Components" }).scrollIntoViewIfNeeded();
  await shoot(page, "calculations");
});

test("dashboard", async ({ page }) => {
  await openShowcase(page, "dark");
  await workspace(page, "dashboard").click();
  await page.waitForTimeout(1_000); // the panels draw
  // Hover the first chart at 40% of its width: every panel shows the same moment.
  const chart = page.getByRole("img", { name: /drag to zoom/ }).first();
  await expect(chart).toBeVisible();
  const box = await chart.boundingBox();
  if (box) await page.mouse.move(box.x + box.width * 0.4, box.y + box.height / 2);
  await page.waitForTimeout(400);
  const png = test.info().outputPath("dashboard-hover.png");
  await page.screenshot({ path: png });
  mkdirSync(OUT, { recursive: true });
  const out = path.join(OUT, "dashboard.jpg");
  if (HAS_FFMPEG) ffmpeg(["-i", png, "-vf", "scale=800:500:flags=lanczos", "-q:v", "3", out]);
  else copyFileSync(png, out.replace(/\.jpg$/, ".png"));
});

test("cache-outage demo video", async ({ browser, baseURL }) => {
  test.setTimeout(5 * 60_000);
  const size = { width: 1440, height: 900 };
  const context = await browser.newContext({ baseURL, viewport: size, recordVideo: { dir: test.info().outputPath("video"), size } });
  const page = await context.newPage();
  const t0 = Date.now();
  const at = () => (Date.now() - t0) / 1000; // seconds into the video
  await prepare(page, "dark");

  // Open the starter from the gallery, look at it, then lose the cache (the pad simulates).
  await page.goto("/templates");
  // The GIF starts on the drawn gallery, not on the blank and half-loaded frames before it.
  await expect(page.getByRole("button", { name: /^Open the .* template$/ }).first()).toBeVisible();
  const shownFrom = at();
  await page.waitForTimeout(1_500);
  await page.getByRole("button", { name: /Open the Cache-aside .* template/ }).first().click();
  await page.waitForURL(/\/projects\/[^/]+$/);
  await expect(page.getByTestId("node-cache")).toBeVisible();
  await page.waitForTimeout(2_500);
  const show = page.getByRole("button", { name: "Show chaos pads" });
  if (await show.isVisible()) await show.click();
  await page.getByRole("region", { name: "Chaos pads" }).getByRole("button", { name: /^Lose Redis Cache/ }).click();
  // The GIF plays the wait for the simulation 6× faster, keeping its first moments as they were.
  const waitFrom = at() + 1.5;
  const stop = page.getByRole("button", { name: "Stop simulation" });
  await expect(stop).toBeVisible();
  await expect(stop).toBeHidden({ timeout: 120_000 });
  const waitTo = Math.max(waitFrom, at() - 0.5);
  await page.waitForTimeout(1_500);

  // Replay the run at 2×: the outage, the reads falling through to PostgreSQL, the recovery.
  await workspace(page, "stress").click();
  const speed = page.getByRole("button", { name: /^Playback speed / });
  for (let i = 0; i < 5 && !/ 2×/.test((await speed.getAttribute("aria-label")) ?? ""); i++) await speed.click();
  await page.getByRole("button", { name: "Back to the start" }).filter({ visible: true }).first().click();
  await page.getByTestId("transport-play").filter({ visible: true }).first().click();
  await expect(page.getByRole("button", { name: "Pause the replay" }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Pause the replay" })).toHaveCount(0, { timeout: 90_000 });
  await page.waitForTimeout(1_000);

  // And the answer.
  await openResults(page, "Answer");
  await page.waitForTimeout(4_000);

  const video = page.video();
  await context.close();
  if (!video) throw new Error("no video was recorded");
  const webm = await video.path();
  mkdirSync(OUT, { recursive: true });
  const gif = path.join(OUT, "cache-outage.gif");
  // 8 frames a second, 800 px wide and 64 colours keep a minute of the studio under ~8 MB.
  const filter = "fps=8,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=64:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle";
  const [s, a, b] = [shownFrom.toFixed(2), waitFrom.toFixed(2), waitTo.toFixed(2)];
  const graph =
    `[0:v]split=3[s0][s1][s2];[s0]trim=${s}:${a},setpts=PTS-STARTPTS[v0];` +
    `[s1]trim=${a}:${b},setpts=(PTS-STARTPTS)/6[v1];[s2]trim=start=${b},setpts=PTS-STARTPTS[v2];` +
    `[v0][v1][v2]concat=n=3:v=1:a=0,${filter}`;
  if (HAS_FFMPEG) {
    ffmpeg(["-i", webm, "-filter_complex", graph, "-loop", "0", gif]);
    console.log(`wrote ${gif} (${(statSync(gif).size / 1e6).toFixed(1)} MB)`);
  } else {
    const kept = path.join(OUT, "cache-outage.webm");
    copyFileSync(webm, kept);
    console.log(`ffmpeg is not on PATH; saved ${kept}. Convert it with:\n  ffmpeg -i "${kept}" -vf "${filter}" -loop 0 "${gif}"`);
  }
});
