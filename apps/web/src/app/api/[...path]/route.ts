/**
 * Same-origin proxy to the ArchSim API (archsim-app). The target is read from ARCHSIM_API_URL
 * when the server starts, so the same build works locally and in Docker.
 *
 * Sign-in rides through here: the session cookie, API tokens and the CSRF header go to the API,
 * and its Set-Cookie, redirects (sign-in) and Retry-After (rate limits) come back. The visitor's
 * address is sent as X-ArchSim-Client-IP, worked out from X-Forwarded-For and
 * ARCHSIM_TRUSTED_HOPS (see lib/proxy.ts); the API believes it only from this server.
 */
import type { NextRequest } from "next/server";
import { forwardRequestHeaders, forwardResponseHeaders } from "@/lib/proxy";

const API_URL = (process.env.ARCHSIM_API_URL ?? "http://127.0.0.1:8000").replace(/\/$/, "");
const TRUSTED_HOPS = Number(process.env.ARCHSIM_TRUSTED_HOPS ?? "0");

async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const search = new URL(req.url).search;
  const target = `${API_URL}/api/${path.map(encodeURIComponent).join("/")}${search}`;
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

export { proxy as DELETE, proxy as GET, proxy as PATCH, proxy as POST, proxy as PUT };
