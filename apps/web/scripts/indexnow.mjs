/**
 * IndexNow (https://www.indexnow.org): tells the search engines that take part (Bing, Yandex,
 * Seznam, Naver and others, which share what one of them is told) that the website's pages
 * changed, so they crawl them again soon. The site's workflow runs it after every deploy from main
 * (.github/workflows/site.yml), with every URL of the live sitemap:
 *
 *   node apps/web/scripts/indexnow.mjs https://archsim.io/sitemap.xml
 *
 * IndexNow trusts a ping about archsim.io because the site serves the key below at
 * https://archsim.io/<key>.txt (src/app/<key>.txt/route.site.ts). The key is public by design.
 * A failed ping is a warning in the workflow's log, never a failed deploy.
 */
import { pathToFileURL } from "node:url";

export const INDEXNOW_KEY = "92a951fcad63c58a46b68f9e5a6a3098";

const HOST = "archsim.io";
const ENDPOINT = "https://api.indexnow.org/indexnow";
const TIMEOUT_MS = 30_000;

const decode = (s) =>
  s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

/** Every page's URL a sitemap lists. */
export function sitemapUrls(xml) {
  return [...xml.matchAll(/<loc>\s*([^<]*?)\s*<\/loc>/g)].map((m) => decode(m[1]));
}

/**
 * Posts every URL of the sitemap at `sitemap` to IndexNow.
 * @param {string} sitemap the sitemap's URL
 * @param {(url: string, init?: RequestInit) => Promise<Response>} [fetchImpl]
 * @returns {Promise<string>} what IndexNow answered; it rejects when the sitemap cannot be read or IndexNow refuses
 */
export async function notifyIndexNow(sitemap, fetchImpl = fetch) {
  const read = await fetchImpl(sitemap, { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!read.ok) throw new Error(`cannot read the sitemap ${sitemap}: ${read.status}`);
  const urlList = sitemapUrls(await read.text());
  if (urlList.length === 0) throw new Error(`the sitemap ${sitemap} lists no pages`);
  const answer = await fetchImpl(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ host: HOST, key: INDEXNOW_KEY, keyLocation: `https://${HOST}/${INDEXNOW_KEY}.txt`, urlList }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!answer.ok) throw new Error(`IndexNow answered ${answer.status}: ${(await answer.text()).trim()}`);
  return `IndexNow took ${urlList.length} URLs (${answer.status})`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const sitemap = process.argv[2] ?? `https://${HOST}/sitemap.xml`;
  notifyIndexNow(sitemap).then(
    (message) => console.log(message),
    // a GitHub Actions warning: the deploy stands whatever IndexNow says
    (e) => console.log(`::warning title=IndexNow::${e instanceof Error ? e.message : String(e)}`),
  );
}
