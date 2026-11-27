/**
 * Reviewing a design, end to end (no sign-in: the one local user does everything): comment on a
 * part, reply, simulate, approve the run with a note, save (the approval goes stale), read the
 * history, delete the part (its thread stays, detached) and resolve the thread.
 */
import { expect, type Page, test } from "@playwright/test";

function workspace(page: Page, key: "build" | "stress" | "decide" | "dashboard") {
  return page.getByTestId(`workspace-${key}`).filter({ visible: true });
}

test("review: comment, reply, approve, stale after save, history, detached, resolve", async ({ page }) => {
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
  const cache = page.getByTestId("node-cache");
  await expect(cache).toBeVisible();

  // Comment on the cache: the thread shows in the side panel and a badge on the part.
  await cache.click();
  const section = page.getByTestId("comments-section");
  await section.getByLabel("Start a discussion about this part").fill("Is a 5 minute TTL too long for prices?");
  await section.getByRole("button", { name: "Comment", exact: true }).click();
  const thread = section.locator('[data-testid^="thread-"]').first();
  await expect(thread).toContainText("Is a 5 minute TTL too long for prices?");
  await expect(page.getByTestId("comment-badge-cache")).toHaveAccessibleName("1 open comment thread");
  await thread.getByRole("button", { name: "Reply" }).click();
  await thread.getByLabel("Reply").fill("Prices change hourly, so it is fine.");
  await thread.getByRole("button", { name: "Reply" }).click();
  await expect(thread).toContainText("Prices change hourly, so it is fine.");
  await expect(page.getByTestId("activity-button")).toContainText("1");

  // Simulate, then approve the run on the Results page.
  await page.getByTestId("run-button").click();
  await expect(page.getByRole("button", { name: "Stop simulation" })).toBeHidden({ timeout: 45_000 });
  await expect(page.getByTestId("run-button")).toBeEnabled();
  await workspace(page, "decide").click();
  const review = page.getByTestId("signoff-panel");
  await expect(review.getByTestId("signoff-state")).toHaveText("Not reviewed");
  await review.getByLabel("Review note").fill("Holds up at the starter's traffic.");
  await review.getByTestId("signoff-approve").click();
  await expect(review.getByTestId("signoff-state")).toHaveText("Approved");
  await expect(review.getByTestId("signoff-entry")).toContainText("Holds up at the starter's traffic.");

  // The run list carries the review.
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: "Compare" }).click();
  await page.getByRole("tab", { name: "All runs" }).click();
  await expect(page.locator('[data-testid^="signoff-badge-"]').first()).toHaveAttribute("data-state", "approved");

  // Saving the design makes the approval stale.
  await page.getByRole("tablist", { name: "Results" }).getByRole("tab", { name: "Answer" }).click();
  await page.getByLabel("Project name").fill("Cache-aside, reviewed");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByTestId("save-status")).toHaveText("Saved");
  await expect(review.getByTestId("signoff-state")).toHaveText("Design changed since review");
  await expect(review.getByTestId("signoff-entry")).toHaveAttribute("data-stale", "true");

  // The history tells the story, newest first.
  await page.getByTestId("activity-button").click();
  const drawer = page.getByTestId("activity-drawer");
  await drawer.getByRole("tab", { name: "History" }).click();
  const events = drawer.getByTestId("activity-event");
  await expect(events.first()).toContainText("saved revision 2");
  await expect(drawer).toContainText("approved the design (revision 1)");
  await expect(drawer).toContainText("replied on");
  await expect(drawer).toContainText("commented on");
  await expect(drawer.locator('[data-action="project.create"]')).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();

  // Delete the cache: the thread stays, detached, in the overview and the drawer.
  await workspace(page, "build").click();
  await cache.click();
  await page.keyboard.press("Delete");
  await expect(cache).toBeHidden();
  await page.locator(".react-flow__pane").click({ position: { x: 20, y: 20 } });
  const overview = page.getByTestId("comments-section");
  await expect(overview).toContainText("On parts deleted since");
  const detached = overview.locator('[data-testid^="thread-"]').filter({ hasText: "5 minute TTL" });
  await expect(detached).toContainText("detached");

  // Resolve it: nothing is open any more.
  await detached.getByRole("button", { name: "Resolve" }).click();
  await expect(overview).not.toContainText("On parts deleted since");
  await expect(page.getByTestId("activity-button")).not.toContainText("1");
  await page.getByTestId("activity-button").click();
  await drawer.getByRole("tab", { name: "Comments" }).click();
  await drawer.getByRole("button", { name: /^All/ }).click();
  await expect(drawer.locator('[data-resolved="true"]')).toContainText("5 minute TTL");
});
