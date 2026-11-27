import { describe, expect, it } from "vitest";
import { clientIp, forwardRequestHeaders, forwardResponseHeaders } from "./proxy";

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

  it("tells the API the name the browser addressed and the page it came from, not what the page claims", () => {
    const out = forwardRequestHeaders(
      new Headers({
        host: "localhost:3000",
        origin: "http://localhost:3000",
        "x-archsim-client-host": "forged.example",
        "x-archsim-client-origin": "http://forged.example",
      }),
      0,
    );
    expect(out.get("x-archsim-client-host")).toBe("localhost:3000");
    expect(out.get("x-archsim-client-origin")).toBe("http://localhost:3000");
    expect(out.get("origin")).toBeNull();
  });

  it("sends no page for a request that came from none", () => {
    const out = forwardRequestHeaders(
      new Headers({ host: "localhost:3000", "x-archsim-client-origin": "http://forged.example" }),
      0,
    );
    expect(out.get("x-archsim-client-origin")).toBeNull();
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
