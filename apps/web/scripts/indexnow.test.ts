import { describe, expect, it } from "vitest";
import { INDEXNOW_KEY, notifyIndexNow, sitemapUrls } from "./indexnow.mjs";

const SITEMAP = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
<url><loc>https://archsim.io/</loc><lastmod>2026-11-15T22:01:03+01:00</lastmod></url>
<url><loc>https://archsim.io/docs/a&amp;b/</loc></url>
</urlset>
`;

type Call = { url: string; init?: RequestInit };

function fakeFetch(answers: Record<string, Response>) {
  const calls: Call[] = [];
  const fetch = async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    return answers[url] ?? new Response("not found", { status: 404 });
  };
  return { fetch, calls };
}

describe("sitemapUrls", () => {
  it("reads every page's URL from a sitemap", () => {
    expect(sitemapUrls(SITEMAP)).toEqual(["https://archsim.io/", "https://archsim.io/docs/a&b/"]);
  });
});

describe("notifyIndexNow", () => {
  it("posts every page of the sitemap with the site's key and where it is served", async () => {
    const { fetch, calls } = fakeFetch({
      "https://archsim.io/sitemap.xml": new Response(SITEMAP),
      "https://api.indexnow.org/indexnow": new Response(null, { status: 202 }),
    });

    const message = await notifyIndexNow("https://archsim.io/sitemap.xml", fetch);

    const post = calls[1];
    expect(post.url).toBe("https://api.indexnow.org/indexnow");
    expect(post.init?.method).toBe("POST");
    expect(new Headers(post.init?.headers).get("content-type")).toBe("application/json; charset=utf-8");
    expect(JSON.parse(String(post.init?.body))).toEqual({
      host: "archsim.io",
      key: INDEXNOW_KEY,
      keyLocation: `https://archsim.io/${INDEXNOW_KEY}.txt`,
      urlList: ["https://archsim.io/", "https://archsim.io/docs/a&b/"],
    });
    expect(message).toMatch(/2 URLs.*202/);
  });

  it("fails with IndexNow's answer when it refuses the pages", async () => {
    const { fetch } = fakeFetch({
      "https://archsim.io/sitemap.xml": new Response(SITEMAP),
      "https://api.indexnow.org/indexnow": new Response("Key not valid", { status: 403 }),
    });

    await expect(notifyIndexNow("https://archsim.io/sitemap.xml", fetch)).rejects.toThrow(/403.*Key not valid/);
  });

  it("fails without posting when the sitemap cannot be read", async () => {
    const { fetch, calls } = fakeFetch({});

    await expect(notifyIndexNow("https://archsim.io/sitemap.xml", fetch)).rejects.toThrow(/sitemap.*404/);
    expect(calls).toHaveLength(1);
  });

  it("uses a key IndexNow accepts", () => {
    expect(INDEXNOW_KEY).toMatch(/^[0-9a-f]{32}$/);
  });
});
