/** GitHub Pages' custom domain: the CNAME file at the site's root names archsim.io. */
export const dynamic = "force-static";

export function GET() {
  return new Response("archsim.io\n", { headers: { "Content-Type": "text/plain; charset=utf-8" } });
}
