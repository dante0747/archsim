import { afterEach, describe, expect, it } from "vitest";
import { pickWorkspace } from "@/store/auth";
import type { WorkspaceInfo } from "./auth-types";
import { csrfToken, requestHeaders, setActiveWorkspace, setCsrfToken, signInPage } from "./session";

const ws = (id: string, kind: WorkspaceInfo["kind"]): WorkspaceInfo => ({
  id,
  name: id,
  kind,
  role: "owner",
  maxConcurrentRuns: null,
  maxRunsPerHour: null,
  createdAt: "2026-10-27T00:00:00Z",
});

afterEach(() => {
  setActiveWorkspace(null);
  setCsrfToken(null);
});

describe("csrfToken", () => {
  it("reads the token from its cookie, else from /auth/me", () => {
    expect(csrfToken("a=1; __Host-archsim_csrf=tok-123; b=2")).toBe("tok-123");
    expect(csrfToken("__Host-archsim_csrf=tok")).toBe("tok");
    expect(csrfToken("x__Host-archsim_csrf=nope")).toBeNull();
    setCsrfToken("from-me");
    expect(csrfToken("")).toBe("from-me");
  });
});

describe("requestHeaders", () => {
  it("sends the workspace always and the CSRF token only with changes", () => {
    setActiveWorkspace("team-1");
    setCsrfToken("tok");
    expect(requestHeaders("GET")).toEqual({ "X-ArchSim-Workspace": "team-1" });
    expect(requestHeaders("POST")).toEqual({ "X-ArchSim-Workspace": "team-1", "X-ArchSim-CSRF": "tok" });
    expect(requestHeaders("delete")["X-ArchSim-CSRF"]).toBe("tok");
  });

  it("sends nothing without sign-in", () => {
    expect(requestHeaders("POST")).toEqual({});
  });
});

describe("pickWorkspace", () => {
  const list = [ws("default", "team"), ws("me", "personal"), ws("team", "team")];
  it("keeps the stored choice while you belong to it", () => {
    expect(pickWorkspace(list, "team")).toBe("team");
  });
  it("falls back to your personal workspace, then default", () => {
    expect(pickWorkspace(list, "gone")).toBe("me");
    expect(pickWorkspace([ws("default", "team"), ws("t", "team")], null)).toBe("default");
    expect(pickWorkspace([], null)).toBeNull();
  });
});

describe("signInPage", () => {
  it("comes back to the page you were on", () => {
    expect(signInPage("/projects/abc?tab=2")).toBe("/signin?next=%2Fprojects%2Fabc%3Ftab%3D2");
  });
});
