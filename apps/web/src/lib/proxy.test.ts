import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MAX_BODY_BYTES, clientIp, forwardRequestHeaders, forwardResponseHeaders, forwardTo } from "./proxy";

const TARGET = "http://api.test/api/v1/import";

const repo = (path: string) => readFileSync(resolve(process.cwd(), "../..", path), "utf8");

/** A browser's POST whose body is a stream, sent without its length, as a chunked upload is. */
function streamed(bytes: number): Request {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (let sent = 0; sent < bytes; sent += 65536) controller.enqueue(new Uint8Array(Math.min(65536, bytes - sent)));
      controller.close();
    },
  });
  return new Request(TARGET, { method: "POST", body, duplex: "half" } as RequestInit);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("forwardTo", () => {
  it("refuses a body that says it is larger than the API takes, without reading it or calling the API", async () => {
    const api = vi.fn();
    vi.stubGlobal("fetch", api);
    const req = new Request(TARGET, {
      method: "POST",
      headers: { "content-length": String(MAX_BODY_BYTES + 1), "content-type": "application/json" },
      body: "{}",
    });

    const res = await forwardTo(req, TARGET);

    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ detail: { message: "Request body too large", issues: [] } });
    expect(api).not.toHaveBeenCalled();
  });

  it("refuses a body of no stated length once it passes what the API takes", async () => {
    const api = vi.fn();
    vi.stubGlobal("fetch", api);

    const res = await forwardTo(streamed(MAX_BODY_BYTES + 1), TARGET);

    expect(res.status).toBe(413);
    expect(api).not.toHaveBeenCalled();
  });

  it("passes on a body within what the API takes", async () => {
    const api = vi.fn(async (_url: string, init: RequestInit) => new Response(String((init.body as Uint8Array).byteLength)));
    vi.stubGlobal("fetch", api);

    const res = await forwardTo(streamed(1000), TARGET);

    expect(await res.text()).toBe("1000");
  });

  it("answers 504 when the API does not answer in time", async () => {
    vi.stubGlobal(
      "fetch",
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => init.signal?.addEventListener("abort", () => reject(init.signal?.reason))),
    );

    const res = await forwardTo(new Request(TARGET), TARGET, 20);

    expect(res.status).toBe(504);
    expect((await res.json()).detail.message).toMatch(/did not answer/);
  });
});

describe("the body limit", () => {
  it("is the API's own by default, and the hosting kit's Caddy caps bodies at it too", () => {
    const apiDefault = Number(/@DefaultValue\("(\d+)"\)[^,]*long maxBodyBytes/.exec(repo("archsim-app/src/main/java/io/archsim/app/config/ArchsimProperties.java"))?.[1]);
    const caddy = /request_body \{\s*max_size (\d+)MB\s*\}/.exec(repo("deploy/Caddyfile"))?.[1];

    expect(MAX_BODY_BYTES).toBe(apiDefault);
    expect(Number(caddy) * 1_000_000).toBe(apiDefault);
  });
});

describe("clientIp", () => {
  it("believes nothing with no proxy in front", () => {
    expect(clientIp("203.0.113.9", 0)).toBeNull();
  });

  it("takes the address the last trusted proxy saw", () => {
    expect(clientIp("203.0.113.9", 1)).toBe("203.0.113.9");
    // a client that sends its own X-Forwarded-For only adds entries to the left
    expect(clientIp("1.2.3.4, 203.0.113.9", 1)).toBe("203.0.113.9");
    expect(clientIp("1.2.3.4, 203.0.113.9, 10.0.0.2", 2)).toBe("203.0.113.9");
    expect(clientIp("2001:db8::1", 1)).toBe("2001:db8::1");
  });

  it("sends nothing when the header is shorter than the proxies or not an address", () => {
    expect(clientIp("203.0.113.9", 2)).toBeNull();
    expect(clientIp(null, 1)).toBeNull();
    expect(clientIp("evil<script>", 1)).toBeNull();
  });
});

describe("forwarded headers", () => {
  it("passes sign-in headers to the API and drops the rest", () => {
    const out = forwardRequestHeaders(
      new Headers({
        cookie: "__Host-archsim_session=abc",
        authorization: "Bearer asim_pat_x",
        "x-archsim-csrf": "t",
        "x-archsim-workspace": "w1",
        "x-archsim-client-ip": "6.6.6.6",
        "x-forwarded-for": "6.6.6.6, 203.0.113.9",
        host: "evil.example",
      }),
      1,
    );
    expect(out.get("cookie")).toBe("__Host-archsim_session=abc");
    expect(out.get("authorization")).toBe("Bearer asim_pat_x");
    expect(out.get("x-archsim-csrf")).toBe("t");
    expect(out.get("x-archsim-workspace")).toBe("w1");
    expect(out.get("x-archsim-client-ip")).toBe("203.0.113.9");
    expect(out.get("host")).toBeNull();
    expect(out.get("x-forwarded-for")).toBeNull();
  });

  it("keeps every Set-Cookie and the redirect and retry headers", () => {
    const incoming = new Headers();
    incoming.append("set-cookie", "__Host-archsim_session=s; Path=/; Secure; HttpOnly");
    incoming.append("set-cookie", '__Host-archsim_oauth=""; Max-Age=0; Path=/; Secure');
    incoming.set("location", "/projects");
    incoming.set("retry-after", "12");
    incoming.set("server", "uvicorn");
    const out = forwardResponseHeaders(incoming);
    expect(out.getSetCookie()).toHaveLength(2);
    expect(out.get("location")).toBe("/projects");
    expect(out.get("retry-after")).toBe("12");
    expect(out.get("server")).toBeNull();
  });
});
