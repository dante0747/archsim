/**
 * IndexNow's key file (scripts/indexnow.mjs): the key, at /<key>.txt, proves that the pings the
 * site's deploy sends about archsim.io come from the site's owner.
 */
import { INDEXNOW_KEY } from "../../../scripts/indexnow.mjs";

export const dynamic = "force-static";

export function GET() {
  return new Response(INDEXNOW_KEY, { headers: { "Content-Type": "text/plain; charset=utf-8" } });
}
