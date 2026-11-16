import { JsonLd } from "@/components/site/JsonLd";
import { SiteHome } from "@/components/site/SiteHome";
import { accuracy } from "@/lib/accuracy";
import { accuracyHighlights } from "@/lib/site/accuracy-highlights";
import { repository } from "@/lib/site/content";
import { demoUrl } from "@/lib/site/site";
import { homeStructuredData } from "@/lib/site/structured-data";
import { catalogStats, techChips } from "@/lib/tech-chips";
import type { Catalog } from "@/lib/types";
// The engine's component catalog, as `./gradlew :archsim-engine:generate` writes it and
// archsim-engine's GeneratedFileTests keeps it: what GET /api/v1/components serves.
import catalogJson from "@/test/fixtures/catalog.json";
import pkg from "../../package.json";

const catalog = catalogJson as unknown as Catalog;

export default function Home() {
  const repo = repository();
  const templates = repo.templates();
  return (
    <>
      <JsonLd data={homeStructuredData(pkg.version)} />
      <SiteHome
        readme={repo.readme()}
        accuracy={accuracyHighlights(accuracy)}
        chips={techChips(catalog)}
        stats={{ ...catalogStats(catalog), templates: templates.count, scenarios: templates.scenarios }}
        version={pkg.version}
        demoUrl={demoUrl(process.env)}
      />
    </>
  );
}
