/**
 * Sandboxes: a visitor tries ArchSim without signing in, as a guest whose sandbox (and every
 * design in it) the server deletes a day later (ADR-0079).
 */
import type { Me } from "./auth-types";

/** Whether the person is a sandbox's guest, who did not sign in. */
export function isGuest(me: Me | null | undefined): boolean {
  return me?.user.guest === true;
}

/** When a sandbox is deleted, as people read it: "today at 2:05 PM", "tomorrow at 9:00 AM". */
export function sandboxEndText(expiresAt: string, now: Date = new Date()): string {
  const end = new Date(expiresAt);
  if (end.getTime() <= now.getTime()) return "in a moment";
  const time = end.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  if (end < tomorrow) return `today at ${time}`;
  const dayAfter = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 2);
  if (end < dayAfter) return `tomorrow at ${time}`;
  return `on ${end.toLocaleDateString("en-US", { month: "short", day: "numeric" })} at ${time}`;
}
