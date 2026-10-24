/**
 * Same-origin proxy to the FastAPI backend. The target is read at request time from
 * ARCHSIM_API_URL, so the same build works locally and in Docker.
 */
import type { NextRequest } from "next/server";

const API_URL = (process.env.ARCHSIM_API_URL ?? "http://127.0.0.1:8000").replace(/\/$/, "");
const FORWARD_REQUEST_HEADERS = ["content-type", "accept"];
const FORWARD_RESPONSE_HEADERS = ["content-type", "content-disposition"];

async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const search = new URL(req.url).search;
  const target = `${API_URL}/api/${path.map(encodeURIComponent).join("/")}${search}`;
  const headers = new Headers();
  for (const h of FORWARD_REQUEST_HEADERS) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  const hasBody = !["GET", "HEAD"].includes(req.method);
  try {
    const res = await fetch(target, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
    const out = new Headers();
    for (const h of FORWARD_RESPONSE_HEADERS) {
      const v = res.headers.get(h);
      if (v) out.set(h, v);
    }
    return new Response(res.status === 204 ? null : res.body, { status: res.status, headers: out });
  } catch {
    return Response.json(
      {
        detail: {
          message: `Cannot reach the ArchSim API at ${API_URL}. Start it with "uv run archsim-api serve".`,
          issues: [],
        },
      },
      { status: 502 },
    );
  }
}

export { proxy as DELETE, proxy as GET, proxy as PATCH, proxy as POST, proxy as PUT };
