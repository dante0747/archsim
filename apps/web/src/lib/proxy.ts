/**
 * What the same-origin proxy (app/api/[...path]/route.ts) passes between the browser and the API.
 * Only these headers cross, in each direction; anything else a browser sends (a forged
 * X-ArchSim-Client-IP, say) stays behind.
 */

/** Browser -> API: the body's type, sign-in (session cookie or API token), the CSRF header and
 * the current workspace. */
export const FORWARD_REQUEST_HEADERS = [
  "accept",
  "authorization",
  "content-type",
  "cookie",
  "user-agent",
  "x-archsim-csrf",
  "x-archsim-workspace",
];

/** API -> browser: set-cookie is handled apart (one header per cookie). */
export const FORWARD_RESPONSE_HEADERS = [
  "cache-control",
  "content-disposition",
  "content-type",
  "location",
  "retry-after",
  "www-authenticate",
];

export const CLIENT_IP_HEADER = "x-archsim-client-ip";

/**
 * Browser -> API, without sign-in: the host the browser addressed and the page it came from, so
 * the API refuses a page that points its own name at this machine, or a change from another site,
 * as it would have refused the browser itself (ADR-0107). Always this request's own Host and
 * Origin, never what the browser sent in these headers.
 */
export const CLIENT_HOST_HEADER = "x-archsim-client-host";
export const CLIENT_ORIGIN_HEADER = "x-archsim-client-origin";

const IP_CHARS = /^[0-9A-Fa-f:.]{2,45}$/;

/**
 * The visitor's address from X-Forwarded-For, given how many proxies of yours stand in front of
 * this server (ARCHSIM_TRUSTED_HOPS: 1 behind Caddy). Each proxy appends the address it saw, so
 * the visitor is the `hops`-th entry from the right; anything left of it was written by the
 * client and is not believed. With 0 hops (nothing in front) the header is the client's own
 * word, so no address is sent and the API uses the web server's.
 */
export function clientIp(forwardedFor: string | null, hops: number): string | null {
  if (!forwardedFor || !Number.isInteger(hops) || hops < 1) return null;
  const entries = forwardedFor
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (entries.length < hops) return null;
  const ip = entries[entries.length - hops];
  return IP_CHARS.test(ip) ? ip : null;
}

/** The headers to send the API for a browser request. */
export function forwardRequestHeaders(incoming: Headers, hops: number): Headers {
  const out = new Headers();
  for (const h of FORWARD_REQUEST_HEADERS) {
    const v = incoming.get(h);
    if (v) out.set(h, v);
  }
  const ip = clientIp(incoming.get("x-forwarded-for"), hops);
  if (ip) out.set(CLIENT_IP_HEADER, ip);
  const host = incoming.get("host");
  if (host) out.set(CLIENT_HOST_HEADER, host);
  const origin = incoming.get("origin");
  if (origin) out.set(CLIENT_ORIGIN_HEADER, origin);
  return out;
}

/** The headers to send the browser for an API response, every Set-Cookie kept separate. */
export function forwardResponseHeaders(incoming: Headers): Headers {
  const out = new Headers();
  for (const h of FORWARD_RESPONSE_HEADERS) {
    const v = incoming.get(h);
    if (v) out.set(h, v);
  }
  for (const cookie of incoming.getSetCookie()) out.append("set-cookie", cookie);
  return out;
}

/** Where the API is: ARCHSIM_API_URL, read when the server starts, so one build works locally and in Docker. */
export const API_URL = (process.env.ARCHSIM_API_URL ?? "http://127.0.0.1:8000").replace(/\/$/, "");

/** How many proxies of yours stand in front of this server (see clientIp). */
const TRUSTED_HOPS = Number(process.env.ARCHSIM_TRUSTED_HOPS ?? "0");

/**
 * Sends a browser's request on to the API at `target`, with the headers that cross, and the API's
 * answer back; a 502 in the API's error shape when the API cannot be reached.
 */
export async function forwardTo(req: Request, target: string): Promise<Response> {
  const hasBody = !["GET", "HEAD"].includes(req.method);
  try {
    const res = await fetch(target, {
      method: req.method,
      headers: forwardRequestHeaders(req.headers, TRUSTED_HOPS),
      body: hasBody ? await req.arrayBuffer() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
    const body = res.status === 204 || res.status === 304 ? null : res.body;
    return new Response(body, { status: res.status, headers: forwardResponseHeaders(res.headers) });
  } catch {
    return Response.json(
      {
        detail: {
          message: `Cannot reach the ArchSim API at ${API_URL}. Start it as the README's quick start shows, or set ARCHSIM_API_URL to where it runs.`,
          issues: [],
        },
      },
      { status: 502 },
    );
  }
}
