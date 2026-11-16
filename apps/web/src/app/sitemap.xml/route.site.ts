/**
 * Every page of the site, the docs included, for search engines, each dated by the last commit to
 * the file it is made from (none in a shallow clone, which has no history to date them by).
 */
import { repository } from "@/lib/site/content";
import { lastCommitDates } from "@/lib/site/history";
import { sitemapXml } from "@/lib/site/site";

export const dynamic = "force-static";

export function GET() {
  const repo = repository();
  const pages = repo.pages();
  const dates = lastCommitDates(repo.root, pages.map((p) => p.source));
  return new Response(sitemapXml(pages.map((p) => ({ path: p.path, lastmod: dates.get(p.source) }))), { headers: { "Content-Type": "application/xml" } });
}
