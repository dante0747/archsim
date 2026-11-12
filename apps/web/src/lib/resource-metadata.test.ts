import { afterEach, describe, expect, it, vi } from "vitest";
import { GET } from "@/app/.well-known/oauth-protected-resource/[[...path]]/route";

/** The document the API answers with sign-in on (docs/api.md). */
const DOCUMENT = {
  resource: "https://archsim.example/api/v1",
  bearer_methods_supported: ["header"],
  tls_client_certificate_bound_access_tokens: false,
};

function answer(body: unknown, status = 200): Response {
  return Response.json(body, { status });
}

function call(path?: string[]) {
  const url = `https://archsim.example/.well-known/oauth-protected-resource${path ? `/${path.join("/")}` : ""}`;
  return GET(new Request(url), { params: Promise.resolve({ path }) });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the protected resource metadata route", () => {
  it("passes RFC 9728's address for the API on to the API and its document back", async () => {
    const fetch = vi.fn(async () => answer(DOCUMENT));
    vi.stubGlobal("fetch", fetch);

    const response = await call(["api", "v1"]);

    expect(fetch).toHaveBeenCalledWith(
      "http://127.0.0.1:8000/.well-known/oauth-protected-resource/api/v1",
      expect.objectContaining({ method: "GET" }),
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(DOCUMENT);
  });

  it("passes the root address on as it is", async () => {
    const fetch = vi.fn(async () => answer(DOCUMENT));
    vi.stubGlobal("fetch", fetch);

    await call();

    expect(fetch).toHaveBeenCalledWith(
      "http://127.0.0.1:8000/.well-known/oauth-protected-resource",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("answers the API's refusal as it is, without sign-in on", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => answer({ detail: "Not Found" }, 404)));

    const response = await call(["api", "v1"]);

    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ detail: "Not Found" });
  });

  it("says so when the API cannot be reached", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("fetch failed"))));

    const response = await call(["api", "v1"]);

    expect(response.status).toBe(502);
  });
});
