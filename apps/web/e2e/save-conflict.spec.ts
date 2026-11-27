/**
 * Saving, where it could lose work. An edit made while a save is on its way stays unsaved. A save
 * over a version saved elsewhere is not a dead end: it offers to reload that version, to overwrite
 * it or to save a copy, and each choice leaves an editor that saves again.
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";

interface Stored {
  id: string;
  name: string;
  revision: number;
  document: { architecture: { nodes: { id: string; config: { replicas?: number } }[] } };
}

const THEIR_REPLICAS = 7;

async function newProject(request: APIRequestContext, name: string): Promise<string> {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "cache-aside-starter", name } });
  expect(created.status()).toBe(201);
  return (await created.json()).id as string;
}

async function stored(request: APIRequestContext, id: string): Promise<Stored> {
  const res = await request.get(`/api/v1/projects/${id}`);
  expect(res.ok()).toBe(true);
  return (await res.json()) as Stored;
}

const apiReplicas = (project: Stored) => project.document.architecture.nodes.find((n) => n.id === "api")!.config.replicas;

async function open(page: Page, id: string) {
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
  await page.goto(`/projects/${id}`);
  await expect(page.getByTestId("node-cache")).toBeVisible();
}

/** Someone else saves the project, renamed and with more API replicas: the stored revision moves on. */
async function saveElsewhere(request: APIRequestContext, id: string, name: string) {
  const theirs = await stored(request, id);
  theirs.document.architecture.nodes.find((n) => n.id === "api")!.config.replicas = THEIR_REPLICAS;
  const res = await request.put(`/api/v1/projects/${id}`, { data: { name, document: theirs.document } });
  expect(res.ok()).toBe(true);
}

/** Rename the open project and save it: the save meets the version saved elsewhere. */
async function saveOverTheirs(page: Page, name: string) {
  await page.getByLabel("Project name").fill(name);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  const choices = page.getByRole("dialog", { name: "This project was changed elsewhere" });
  await expect(choices).toBeVisible();
  return choices;
}

test("an edit made while a save is on its way stays unsaved", async ({ page }) => {
  const id = await newProject(page.request, "Save in flight");
  await open(page, id);
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route(`**/api/v1/projects/${id}`, async (route) => {
    if (route.request().method() === "PUT") await held;
    await route.continue();
  });
  const name = page.getByLabel("Project name");

  await name.fill("First");
  const answered = page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/projects/${id}`));
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByLabel("saving", { exact: true })).toBeVisible();
  await name.fill("Second");
  release();
  await answered;

  await expect(name).toHaveValue("Second");
  await expect(page.getByLabel("unsaved", { exact: true })).toBeVisible();
  expect((await stored(page.request, id)).name).toBe("First");
});

test("a save over a version saved elsewhere offers to reload theirs", async ({ page }) => {
  const id = await newProject(page.request, "Conflict: reload");
  await open(page, id);
  await saveElsewhere(page.request, id, "Theirs");
  const choices = await saveOverTheirs(page, "Mine");

  await choices.getByRole("button", { name: "Reload theirs" }).click();

  await expect(choices).toBeHidden();
  await expect(page.getByLabel("Project name")).toHaveValue("Theirs");
  await expect(page.getByLabel("saved", { exact: true })).toBeVisible();
  // not a dead end: the next save goes through, on top of their version
  await page.getByLabel("Project name").fill("Theirs, then mine");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(async () => (await stored(page.request, id)).name).toBe("Theirs, then mine");
  expect(apiReplicas(await stored(page.request, id))).toBe(THEIR_REPLICAS);
});

test("a save over a version saved elsewhere offers to overwrite it", async ({ page }) => {
  const id = await newProject(page.request, "Conflict: overwrite");
  const mine = apiReplicas(await stored(page.request, id));
  await open(page, id);
  await saveElsewhere(page.request, id, "Theirs");
  const choices = await saveOverTheirs(page, "Mine");

  await choices.getByRole("button", { name: "Overwrite" }).click();

  await expect(choices).toBeHidden();
  await expect(page.getByLabel("saved", { exact: true })).toBeVisible();
  const after = await stored(page.request, id);
  expect(after.name).toBe("Mine");
  expect(apiReplicas(after)).toBe(mine);
  // not a dead end: the next save goes through
  await page.getByLabel("Project name").fill("Mine, saved again");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(async () => (await stored(page.request, id)).name).toBe("Mine, saved again");
});

test("a save over a version saved elsewhere offers to save a copy", async ({ page }) => {
  const id = await newProject(page.request, "Conflict: copy");
  const mine = apiReplicas(await stored(page.request, id));
  await open(page, id);
  await saveElsewhere(page.request, id, "Theirs");
  const choices = await saveOverTheirs(page, "Mine");

  await choices.getByRole("button", { name: "Save a copy" }).click();

  // the editor moves to the copy, which holds this editor's version; the project keeps theirs
  await page.waitForURL((url) => /\/projects\/[^/]+$/.test(url.pathname) && !url.pathname.endsWith(id));
  const copyId = page.url().split("/").pop()!;
  await expect(page.getByLabel("Project name")).toHaveValue("Mine (copy)");
  await expect(page.getByLabel("saved", { exact: true })).toBeVisible();
  const copy = await stored(page.request, copyId);
  expect(copy.name).toBe("Mine (copy)");
  expect(apiReplicas(copy)).toBe(mine);
  const original = await stored(page.request, id);
  expect(original.name).toBe("Theirs");
  expect(apiReplicas(original)).toBe(THEIR_REPLICAS);
});
