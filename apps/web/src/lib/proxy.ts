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
 * The largest body passed on to the API: the API's own limit, ARCHSIM_MAX_BODY_BYTES (5,000,000
 * bytes unless set; give both servers the same value). A larger one is refused before it is read
 * into memory, as the API would refuse it.
 */
export const MAX_BODY_BYTES = Number(process.env.ARCHSIM_MAX_BODY_BYTES ?? "5000000");

/**
 * How long the API has to answer in full: longer than any request it answers at once, of which a
 * calibration, its Prometheus queries and its replay, is the longest. Runs are queued, not waited
 * for.
 */
export const API_TIMEOUT_MS = 300_000;

/** An answer in the API's error shape. */
function apiError(status: number, message: string): Response {
  return Response.json({ detail: { message, issues: [] } }, { status });
}

/**
 * A request's body, read only while it stays within MAX_BODY_BYTES: a stated length over it is
 * refused unread, and a body of no stated length is read chunk by chunk and dropped once it passes
 * it. Null when it is too large.
 */
async function boundedBody(req: Request): Promise<Uint8Array<ArrayBuffer> | null> {
  if (Number(req.headers.get("content-length") ?? "0") > MAX_BODY_BYTES) return null;
  if (!req.body) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let size = 0;
  const reader = req.body.getReader();
  for (let read = await reader.read(); !read.done; read = await reader.read()) {
    size += read.value.byteLength;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(read.value);
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

/**
 * Sends a browser's request on to the API at `target`, with the headers that cross, and the API's
 * answer back: a 413 for a body larger than the API takes, a 504 when the API does not answer
 * within `timeoutMs`, and a 502 when it cannot be reached, each in the API's error shape.
 */
export async function forwardTo(req: Request, target: string, timeoutMs = API_TIMEOUT_MS): Promise<Response> {
  const body = ["GET", "HEAD"].includes(req.method) ? undefined : await boundedBody(req);
  if (body === null) return apiError(413, "Request body too large");
  try {
    const res = await fetch(target, {
      method: req.method,
      headers: forwardRequestHeaders(req.headers, TRUSTED_HOPS),
      body,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutMs),
    });
    const answer = res.status === 204 || res.status === 304 ? null : res.body;
    return new Response(answer, { status: res.status, headers: forwardResponseHeaders(res.headers) });
  } catch (e) {
    if (e instanceof Error && e.name === "TimeoutError") {
      return apiError(504, "The ArchSim API did not answer in time. Try again in a moment.");
    }
    return apiError(
      502,
      `Cannot reach the ArchSim API at ${API_URL}. Start it as the README's quick start shows, or set ARCHSIM_API_URL to where it runs.`,
    );
  }
}
