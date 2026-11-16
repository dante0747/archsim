import { describe, expect, it } from "vitest";
import { isGuest, sandboxEndText } from "./sandbox";
import type { Me } from "./auth-types";

const NOW = new Date(2026, 10, 16, 9, 30);

/** The visitor's time zone at `date`, as the browser abbreviates it: "EST", "GMT+1". */
const zone = (date: Date) => {
  const name = new Intl.DateTimeFormat("en-US", { timeZoneName: "short" }).formatToParts(date).find((p) => p.type === "timeZoneName")?.value ?? "";
  return name.replace(/[+]/g, "\\+");
};

describe("when a sandbox is deleted, as people read it", () => {
  it("names the time alone later today, in the visitor's time zone", () => {
    const end = new Date(2026, 10, 16, 14, 5);

    expect(sandboxEndText(end.toISOString(), NOW)).toMatch(new RegExp(`^today at 2:05\\sPM ${zone(end)}$`));
  });

  it("says tomorrow for the next day, in the visitor's time zone", () => {
    const end = new Date(2026, 10, 17, 9, 0);

    expect(sandboxEndText(end.toISOString(), NOW)).toMatch(new RegExp(`^tomorrow at 9:00\\sAM ${zone(end)}$`));
  });

  it("says soon once the time has passed", () => {
    expect(sandboxEndText(new Date(2026, 10, 16, 9, 0).toISOString(), NOW)).toBe("in a moment");
  });
});

describe("who is a sandbox's guest", () => {
  const me = (guest: boolean): Me => ({
    mode: "oauth",
    kind: "session",
    user: { id: "u", name: guest ? "Guest" : "Ada", email: null, avatarUrl: null, guest, expiresAt: guest ? NOW.toISOString() : null },
    workspaces: [],
    csrfToken: "t",
  });

  it("is someone the API says is one", () => {
    expect(isGuest(me(true))).toBe(true);
  });

  it("is not someone signed in, or nobody", () => {
    expect(isGuest(me(false))).toBe(false);
    expect(isGuest(null)).toBe(false);
  });
});
