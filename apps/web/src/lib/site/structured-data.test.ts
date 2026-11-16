import { describe, expect, it } from "vitest";
import { SITE_DESCRIPTION } from "./site";
import { docStructuredData, homeStructuredData, jsonLdText } from "./structured-data";

type Node = Record<string, unknown>;
const graph = (data: Node) => data["@graph"] as Node[];
const ofType = (data: Node, type: string) => graph(data).find((n) => n["@type"] === type);

describe("homeStructuredData", () => {
  it("describes ArchSim as free, open-source software at the version the site was built from", () => {
    const data = homeStructuredData("1.2.3");

    expect(data["@context"]).toBe("https://schema.org");
    expect(ofType(data, "SoftwareApplication")).toMatchObject({
      name: "ArchSim",
      applicationCategory: "DeveloperApplication",
      operatingSystem: "Linux, macOS, Windows (Docker)",
      softwareVersion: "1.2.3",
      description: SITE_DESCRIPTION,
      url: "https://archsim.io/",
      image: "https://archsim.io/docs-assets/images/social-preview.png",
      license: "https://www.gnu.org/licenses/agpl-3.0.html",
      sameAs: "https://github.com/dante0747/archsim",
      offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
    });
  });

  it("names the website itself", () => {
    expect(ofType(homeStructuredData("1.2.3"), "WebSite")).toMatchObject({ name: "ArchSim", url: "https://archsim.io/" });
  });
});

describe("docStructuredData", () => {
  it("marks a document up as an article with the breadcrumbs that lead to it", () => {
    const data = docStructuredData({
      title: "0001. Record decisions",
      description: "Why.",
      path: "/docs/adr/0001-record/",
      ancestors: [{ title: "Decisions", path: "/docs/adr/" }],
    });

    expect(ofType(data, "TechArticle")).toMatchObject({ headline: "0001. Record decisions", description: "Why.", url: "https://archsim.io/docs/adr/0001-record/" });
    expect(ofType(data, "BreadcrumbList")?.itemListElement).toEqual([
      { "@type": "ListItem", position: 1, name: "Documentation", item: "https://archsim.io/docs/" },
      { "@type": "ListItem", position: 2, name: "Decisions", item: "https://archsim.io/docs/adr/" },
      { "@type": "ListItem", position: 3, name: "0001. Record decisions", item: "https://archsim.io/docs/adr/0001-record/" },
    ]);
  });
});

describe("jsonLdText", () => {
  it("cannot close the script element it is written into", () => {
    const data = { name: "</script><script>alert(1)</script><!--" };

    const text = jsonLdText(data);

    expect(text).not.toContain("<");
    expect(JSON.parse(text)).toEqual(data);
  });
});
