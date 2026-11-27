/**
 * Unsaved work, end to end. A design saves itself after a short pause, and the top bar says in
 * words whether it is saved. Leaving the editor keeps the work, whichever way out is taken: a
 * link, a command, the browser's Back button. When the work cannot be saved, leaving asks first.
 */
import { type APIRequestContext, expect, type Page, test } from "@playwright/test";

async function newProject(request: APIRequestContext, name: string): Promise<string> {
  const created = await request.post("/api/v1/projects", { data: { exampleId: "cache-aside-starter", name } });
  expect(created.status()).toBe(201);
  return (await created.json()).id as string;
}

async function storedName(request: APIRequestContext, id: string): Promise<string> {
  const res = await request.get(`/api/v1/projects/${id}`);
  expect(res.ok()).toBe(true);
  return (await res.json()).name as string;
}

async function skipTour(page: Page) {
  await page.addInitScript(() => {
    try {
      localStorage.setItem("archsim-tour-v1", "1");
    } catch {
      /* storage unavailable */
    }
  });
}

async function open(page: Page, id: string) {
  await skipTour(page);
  await page.goto(`/projects/${id}`);
  await expect(page.getByTestId("node-cache")).toBeVisible();
}

/** The words beside the project's name that say whether the design is saved. */
const saveStatus = (page: Page) => page.getByTestId("save-status");
const projectName = (page: Page) => page.getByLabel("Project name");
const logo = (page: Page) => page.getByRole("link", { name: "Back to projects" });
/** A project's row in the projects list, which opens it without loading a new page. */
const projectLink = (page: Page, id: string) => page.locator(`a[href="/projects/${id}"]`).first();

test("an edit is saved by itself after a short pause, and the top bar says so in words", async ({ page }) => {
  const id = await newProject(page.request, "Saves by itself");
  await open(page, id);
  await expect(saveStatus(page)).toHaveText("Saved");

  await projectName(page).fill("Saved without a click");

  await expect(saveStatus(page)).toHaveText("Unsaved changes");
  await expect(saveStatus(page)).toHaveText("Saved");
  expect(await storedName(page.request, id)).toBe("Saved without a click");
});

test("leaving by the logo right after an edit keeps the edit", async ({ page }) => {
  const id = await newProject(page.request, "Left by the logo");
  await open(page, id);

  await projectName(page).fill("Edited, then left at once");
  await logo(page).click();

  await page.waitForURL(/\/projects$/);
  expect(await storedName(page.request, id)).toBe("Edited, then left at once");
  await projectLink(page, id).click();
  await expect(projectName(page)).toHaveValue("Edited, then left at once");
});

test("leaving by the command palette right after an edit keeps the edit", async ({ page }) => {
  const id = await newProject(page.request, "Left by a command");
  await open(page, id);

  await projectName(page).fill("Edited, then sent away");
  await page.keyboard.press("Control+k");
  await page.getByRole("combobox", { name: "Search commands" }).fill("Back to projects");
  await page.keyboard.press("Enter");

  await page.waitForURL(/\/projects$/);
  expect(await storedName(page.request, id)).toBe("Edited, then sent away");
});

test("the browser's Back button right after an edit keeps the edit", async ({ page }) => {
  const id = await newProject(page.request, "Left by the Back button");
  await skipTour(page);
  await page.goto("/projects");
  await projectLink(page, id).click();
  await expect(page.getByTestId("node-cache")).toBeVisible();

  await projectName(page).fill("Edited, then Back");
  await page.goBack();

  await page.waitForURL(/\/projects$/);
  await expect.poll(() => storedName(page.request, id)).toBe("Edited, then Back");
  await page.goForward();
  await expect(projectName(page)).toHaveValue("Edited, then Back");
});

test("leaving with work that cannot be saved asks first", async ({ page }) => {
  const id = await newProject(page.request, "Cannot be saved");
  await open(page, id);
  await page.route(`**/api/v1/projects/${id}`, async (route) => {
    if (route.request().method() !== "PUT") return route.continue();
    await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ detail: "The database is not reachable." }) });
  });

  await projectName(page).fill("Edited while saving fails");
  await logo(page).click();

  const ask = page.getByRole("dialog", { name: "Your changes are not saved" });
  await expect(ask).toContainText("The database is not reachable.");
  await ask.getByRole("button", { name: "Stay" }).click();
  await expect(ask).toBeHidden();
  expect(page.url()).toContain(`/projects/${id}`);
  await expect(projectName(page)).toHaveValue("Edited while saving fails");
  await expect(saveStatus(page)).toHaveText("Not saved");

  await logo(page).click();
  await ask.getByRole("button", { name: "Leave without saving" }).click();

  await page.waitForURL(/\/projects$/);
  expect(await storedName(page.request, id)).toBe("Cannot be saved");
});

test("a save made by itself that meets a version saved elsewhere says so and offers the choices", async ({ page }) => {
  const id = await newProject(page.request, "Changed elsewhere");
  await open(page, id);
  expect((await page.request.put(`/api/v1/projects/${id}`, { data: { name: "Theirs" } })).ok()).toBe(true);

  await projectName(page).fill("Mine");

  await expect(saveStatus(page)).toHaveText("Changed elsewhere");
  await saveStatus(page).getByRole("button", { name: "Changed elsewhere" }).click();
  await expect(page.getByRole("dialog", { name: "This project was changed elsewhere" })).toBeVisible();
  expect(await storedName(page.request, id)).toBe("Theirs");
});

test("a design whose name is cleared still saves, under the name the API gives one without", async ({ page }) => {
  const id = await newProject(page.request, "Named, then not");
  await open(page, id);

  await projectName(page).fill("");

  await expect(saveStatus(page)).toHaveText("Saved");
  expect(await storedName(page.request, id)).toBe("Untitled architecture");
  await expect(projectName(page)).toHaveValue("");
  await expect(projectName(page)).toHaveAttribute("placeholder", "Untitled architecture");
});
