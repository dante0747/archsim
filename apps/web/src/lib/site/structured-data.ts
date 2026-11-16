/**
 * The website's structured data for search engines (ADR-0078): schema.org JSON-LD, which a page
 * writes into a `<script type="application/ld+json">` element (components/site/JsonLd.tsx). The
 * home page describes ArchSim as software and names the site; a document of docs/ is an article
 * with the breadcrumbs that lead to it.
 */
import { REPOSITORY_URL } from "@/lib/repository";
import { SITE_DESCRIPTION, SITE_NAME, SOCIAL_IMAGE, siteUrl } from "./site";

export type JsonLd = Record<string, unknown>;

const LICENSE = "https://www.gnu.org/licenses/agpl-3.0.html";

const website = () => ({ "@type": "WebSite", name: SITE_NAME, url: siteUrl("/") });

/** The home page's: ArchSim, at `version`, and the website. */
export function homeStructuredData(version: string): JsonLd {
  return {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "SoftwareApplication",
        name: SITE_NAME,
        applicationCategory: "DeveloperApplication",
        operatingSystem: "Linux, macOS, Windows (Docker)",
        softwareVersion: version,
        description: SITE_DESCRIPTION,
        url: siteUrl("/"),
        image: SOCIAL_IMAGE.url,
        license: LICENSE,
        sameAs: REPOSITORY_URL,
        offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
      },
      website(),
    ],
  };
}

/** A document's: an article, and the trail from the docs index through `ancestors` to it. */
export function docStructuredData({ title, description, path, ancestors }: { title: string; description: string; path: string; ancestors: { title: string; path: string }[] }): JsonLd {
  const trail = [{ title: "Documentation", path: "/docs/" }, ...ancestors, { title, path }];
  return {
    "@context": "https://schema.org",
    "@graph": [
      { "@type": "TechArticle", headline: title, description, url: siteUrl(path), image: SOCIAL_IMAGE.url, inLanguage: "en", isPartOf: website() },
      {
        "@type": "BreadcrumbList",
        itemListElement: trail.map((crumb, i) => ({ "@type": "ListItem", position: i + 1, name: crumb.title, item: siteUrl(crumb.path) })),
      },
    ],
  };
}

/** JSON-LD as a script element's text, every `<` escaped so no value can end the element. */
export function jsonLdText(data: JsonLd): string {
  return JSON.stringify(data).replace(/</g, "\\u003c");
}
