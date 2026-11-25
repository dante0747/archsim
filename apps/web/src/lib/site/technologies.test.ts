import { siApachekafka, siGo, siMysql, siPostgresql } from "simple-icons";
import { describe, expect, it } from "vitest";
import type { Catalog } from "@/lib/types";
import catalogJson from "@/test/fixtures/catalog.json";
import { FEATURED_TECHNOLOGIES, featuredTechnologies } from "./technologies";

const catalog = catalogJson as unknown as Catalog;

describe("featuredTechnologies", () => {
  it("picks the home page's technologies from the catalog, in order, with the names and logos the page gives them", () => {
    const featured = featuredTechnologies(catalog, [
      { id: "postgresql", label: "PostgreSQL", logo: siPostgresql },
      { id: "go-k8s-hpa", label: "Go", logo: siGo },
    ]);

    expect(featured).toEqual([
      { key: "database:postgresql", name: "PostgreSQL", logo: { path: siPostgresql.path, color: `#${siPostgresql.hex}` } },
      { key: "service:go-k8s-hpa", name: "Go", logo: { path: siGo.path, color: `#${siGo.hex}` } },
    ]);
  });

  it("draws a logo too dark for the dark theme in the text colour", () => {
    const [kafka] = featuredTechnologies(catalog, [{ id: "kafka", label: "Kafka", logo: siApachekafka }]);

    expect(kafka.logo.color).toBeNull();
  });

  it("refuses a technology the catalog no longer has, so the page cannot feature one it cannot simulate", () => {
    expect(() => featuredTechnologies(catalog, [{ id: "no-such-preset", label: "Nothing", logo: siGo }])).toThrow(/no technology profile "no-such-preset"/);
  });

  it("refuses an experimental technology, so the home page features core ones only", () => {
    expect(() => featuredTechnologies(catalog, [{ id: "mysql", label: "MySQL", logo: siMysql }])).toThrow(/"mysql" is experimental, not core/);
  });

  it("features technologies that are all in today's catalog, and core", () => {
    expect(featuredTechnologies(catalog, FEATURED_TECHNOLOGIES)).toHaveLength(FEATURED_TECHNOLOGIES.length);
  });
});
