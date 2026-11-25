import { JsonLd } from "@/components/site/JsonLd";
import { SiteHome } from "@/components/site/SiteHome";
import { repository } from "@/lib/site/content";
import { productViews } from "@/lib/site/product-views";
import { releaseNotes } from "@/lib/site/release";
import { demoUrl } from "@/lib/site/site";
import { homeStructuredData } from "@/lib/site/structured-data";
import { FEATURED_TECHNOLOGIES, featuredTechnologies } from "@/lib/site/technologies";
import { catalogStats } from "@/lib/tech-chips";
import type { Catalog } from "@/lib/types";
// The engine's component catalog, as `./gradlew :archsim-engine:generate` writes it and
// archsim-engine's GeneratedFileTests keeps it: what GET /api/v1/components serves.
import catalogJson from "@/test/fixtures/catalog.json";
import pkg from "../../package.json";

const catalog = catalogJson as unknown as Catalog;

export default function Home() {
  const repo = repository();
  const readme = repo.readme();
  const stats = catalogStats(catalog);
  return (
    <>
      <JsonLd data={homeStructuredData(pkg.version)} />
      <SiteHome
        views={productViews(readme.features, repo.docAssets())}
        quickStart={readme.quickStart}
        accuracy={readme.accuracy}
        technologies={{ featured: featuredTechnologies(catalog, FEATURED_TECHNOLOGIES), count: stats.technologies, tiers: stats.tiers }}
        version={pkg.version}
        releaseNotes={releaseNotes(repo.render("CHANGELOG.md").headings, pkg.version)}
        demoUrl={demoUrl(process.env)}
      />
    </>
  );
}
