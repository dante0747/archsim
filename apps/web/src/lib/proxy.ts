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
