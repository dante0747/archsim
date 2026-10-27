import { afterEach, describe, expect, it, vi } from "vitest";
import { latestActive, shareApi, shareUrl } from "./share-api";
import type { Share } from "./share-types";

function share(over: Partial<Share> = {}): Share {
  return {
    id: "s1",
    slug: "4kTq9ZbW2xYc7Lm0PdRs1A",
    path: "/r/4kTq9ZbW2xYc7Lm0PdRs1A",
    url: null,
    title: "Checkout",
    projectId: "p1",
    runId: "r1",
    createdBy: "local",
    createdAt: "2026-10-20T12:00:00Z",
    expiresAt: null,
    revokedAt: null,
    viewCount: 0,
    resultDigest: null,
    reportVersion: "1.13",
    status: "active",
    ...over,
  };
}

describe("share links", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("use the API's public address when it has one, else this site's", () => {
    expect(shareUrl(share(), "http://localhost:3010")).toBe("http://localhost:3010/r/4kTq9ZbW2xYc7Lm0PdRs1A");
    expect(shareUrl(share({ url: "https://archsim.example.com/r/x" }), "http://localhost:3010")).toBe("https://archsim.example.com/r/x");
  });

  it("offer the newest link that still works", () => {
    expect(latestActive([share({ id: "a", status: "revoked" }), share({ id: "b" }), share({ id: "c" })])?.id).toBe("b");
    expect(latestActive([share({ status: "expired" })])).toBeNull();
  });

  it("are made, listed, revoked and copied through the API", async () => {
    const calls: [string, RequestInit | undefined][] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        calls.push([url, init]);
        return new Response(init?.method === "DELETE" ? null : JSON.stringify({}), { status: init?.method === "DELETE" ? 204 : 200 });
      }),
    );
    await shareApi.create("r1", { title: "T", expiresInDays: 7 });
    await shareApi.list("p1");
    await shareApi.revoke("s1");
    await shareApi.copy("slug1");
    expect(calls.map(([u, i]) => `${i?.method ?? "GET"} ${u}`)).toEqual([
      "POST /api/v1/runs/r1/shares",
      "GET /api/v1/projects/p1/shares",
      "DELETE /api/v1/shares/s1",
      "POST /api/v1/projects/from-share",
    ]);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ title: "T", expiresInDays: 7 });
    expect(JSON.parse(String(calls[3][1]?.body))).toEqual({ slug: "slug1" });
  });
});
