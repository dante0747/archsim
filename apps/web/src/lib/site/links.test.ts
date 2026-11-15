import { describe, expect, it } from "vitest";
import { assetRoute, docRoute, docSlug, resolveHref } from "./links";

const folders = new Set(["config/checkstyle", "benchmarks/results", "docs/adr"]);
const from = (file: string) => ({ from: file, isFolder: (p: string) => folders.has(p) });

describe("docRoute", () => {
  it("gives each document under docs/ a page, its README the folder's own", () => {
    expect(docRoute("docs/getting-started.md")).toBe("/docs/getting-started/");
    expect(docRoute("docs/validation/java-parity.md")).toBe("/docs/validation/java-parity/");
    expect(docRoute("docs/README.md")).toBe("/docs/");
    expect(docRoute("docs/adr/README.md")).toBe("/docs/adr/");
  });

  it("gives the changelog its page, and nothing else outside docs/ one", () => {
    expect(docRoute("CHANGELOG.md")).toBe("/changelog/");
    expect(docRoute("CONTRIBUTING.md")).toBeNull();
    expect(docRoute("docs/images/build.jpg")).toBeNull();
  });
});

describe("docSlug", () => {
  it("is the page's path under /docs, empty for docs/README.md", () => {
    expect(docSlug("docs/adr/0001-record-decisions.md")).toEqual(["adr", "0001-record-decisions"]);
    expect(docSlug("docs/adr/README.md")).toEqual(["adr"]);
    expect(docSlug("docs/README.md")).toEqual([]);
    expect(docSlug("README.md")).toBeNull();
  });
});

describe("assetRoute", () => {
  it("serves the images under docs/, and nothing else", () => {
    expect(assetRoute("docs/images/build.jpg")).toBe("/docs-assets/images/build.jpg");
    expect(assetRoute("docs/validation/chain.svg")).toBe("/docs-assets/validation/chain.svg");
    expect(assetRoute("docs/validation/accuracy.json")).toBeNull();
    expect(assetRoute("apps/web/src/app/icon.svg")).toBeNull();
  });
});

describe("resolveHref", () => {
  it("turns a link between documents into a link between pages, keeping the anchor", () => {
    expect(resolveHref("getting-started.md#what-you-need", from("docs/README.md"))).toBe("/docs/getting-started/#what-you-need");
    expect(resolveHref("../validation.md", from("docs/validation/java-parity.md"))).toBe("/docs/validation/");
    expect(resolveHref("adr/README.md", from("docs/README.md"))).toBe("/docs/adr/");
    expect(resolveHref("docs/features.md", from("README.md"))).toBe("/docs/features/");
    expect(resolveHref("../CHANGELOG.md", from("docs/README.md"))).toBe("/changelog/");
  });

  it("links any other file of the repository to GitHub, a folder as a tree", () => {
    expect(resolveHref("../SECURITY.md", from("docs/README.md"))).toBe("https://github.com/dante0747/archsim/blob/main/SECURITY.md");
    expect(resolveHref("../../NOTICE", from("docs/adr/0067-license.md"))).toBe("https://github.com/dante0747/archsim/blob/main/NOTICE");
    expect(resolveHref("validation/accuracy.json", from("docs/validation.md"))).toBe("https://github.com/dante0747/archsim/blob/main/docs/validation/accuracy.json");
    expect(resolveHref("../config/checkstyle/", from("docs/java-conventions.md"))).toBe("https://github.com/dante0747/archsim/tree/main/config/checkstyle/");
    expect(resolveHref("../benchmarks/results", from("docs/validation.md"))).toBe("https://github.com/dante0747/archsim/tree/main/benchmarks/results");
    expect(resolveHref("CONTRIBUTING.md#working-with-ai-assistants", from("README.md"))).toBe(
      "https://github.com/dante0747/archsim/blob/main/CONTRIBUTING.md#working-with-ai-assistants",
    );
  });

  it("serves images under docs/ from the site", () => {
    expect(resolveHref("images/build.jpg", from("docs/features.md"))).toBe("/docs-assets/images/build.jpg");
    expect(resolveHref("docs/images/build.jpg", from("README.md"))).toBe("/docs-assets/images/build.jpg");
  });

  it("leaves anchors and links that leave the repository alone", () => {
    for (const href of ["#reproduce", "https://adr.github.io/madr/", "mailto:someone@example.com", "http://localhost:3000"]) {
      expect(resolveHref(href, from("docs/README.md"))).toBe(href);
    }
  });

  it("refuses a link that climbs out of the repository", () => {
    expect(() => resolveHref("../../outside.md", from("docs/README.md"))).toThrow(/outside the repository/);
  });
});
