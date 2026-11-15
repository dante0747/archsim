/** Every page of the site, the docs included, for search engines. */
import { repository } from "@/lib/site/content";
import { sitemapXml } from "@/lib/site/site";

export const dynamic = "force-static";

export function GET() {
  return new Response(sitemapXml(repository().pages()), { headers: { "Content-Type": "application/xml" } });
}
