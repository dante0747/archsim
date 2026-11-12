/**
 * The API's protected resource metadata (RFC 9728), served at the public address the document
 * names: /.well-known/oauth-protected-resource/api/v1 for the resource <public URL>/api/v1, and
 * the same document at the root. It is passed on to the API as app/api/[...path]/route.ts passes
 * on /api; nothing else under /.well-known reaches the API. Without sign-in the API has no such
 * document, and its 404 comes back.
 */
import { API_URL, forwardTo } from "@/lib/proxy";

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const suffix = path.map((segment) => `/${encodeURIComponent(segment)}`).join("");
  return forwardTo(req, `${API_URL}/.well-known/oauth-protected-resource${suffix}`);
}
