/**
 * Leaving a page that may hold unsaved work, by the app's own links and commands (ADR-0113). The
 * page that holds the work registers a guard; whatever would leave it asks the guard first. A
 * guard that has nothing to keep lets the move go ahead at once; one that has holds it back and
 * calls `go` itself, later or never.
 *
 * The browser's Back and Forward buttons cannot be held back this way, and closing the tab only
 * by the browser's own prompt: the editor covers those by saving as it closes.
 */

/** Decides about leaving: true to go ahead now; false when it took `go` to call later, or never. */
export type LeaveGuard = (go: () => void) => boolean;

let guard: LeaveGuard | null = null;

/** Registers the open page's guard; null when the page closes. */
export function setLeaveGuard(next: LeaveGuard | null): void {
  guard = next;
}

/** Leaves by `go` (a route change, a sign-out): at once, or when the open page's guard lets it. */
export function leave(go: () => void): void {
  if (!guard || guard(go)) go();
}

/**
 * For a link's `onNavigate`: lets the link go ahead, or holds it back and hands `go`, which makes
 * the same move, to the open page's guard.
 */
export function guardLink(event: { preventDefault: () => void }, go: () => void): void {
  if (guard && !guard(go)) event.preventDefault();
}
