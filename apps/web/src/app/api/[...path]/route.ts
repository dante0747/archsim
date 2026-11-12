/**
 * Same-origin proxy to the ArchSim API (archsim-app), at ARCHSIM_API_URL (lib/proxy.ts).
 *
 * Sign-in rides through here: the session cookie, API tokens and the CSRF header go to the API,
 * and its Set-Cookie, redirects (sign-in) and Retry-After (rate limits) come back. The visitor's
 * address is sent as X-ArchSim-Client-IP, worked out from X-Forwarded-For and
 * ARCHSIM_TRUSTED_HOPS (see lib/proxy.ts); the API believes it only from this server.
 */
import type { NextRequest } from "next/server";
import { API_URL, forwardTo } from "@/lib/proxy";

async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const search = new URL(req.url).search;
  return forwardTo(req, `${API_URL}/api/${path.map(encodeURIComponent).join("/")}${search}`);
}

export { proxy as DELETE, proxy as GET, proxy as PATCH, proxy as POST, proxy as PUT };
