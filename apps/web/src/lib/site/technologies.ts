/**
 * The technologies the home page features (ADR-0097): a few core profiles of the engine's catalog,
 * each by its preset id, with the short name and the logo the page gives it. A preset the catalog
 * no longer has, or one that is not core, fails the build, so the page never features a
 * technology ArchSim does not simulate as a core one. The logos are drawn at build time, so the
 * page sends no icon library to the browser.
 */
import { type SimpleIcon, siApachekafka, siGo, siKubernetes, siNginx, siNodedotjs, siOpenjdk, siPostgresql, siRedis } from "simple-icons";
import type { Catalog } from "@/lib/types";

export interface Featured {
  /** The preset's id in the catalog. */
  id: string;
  /** The technology's name on the page. */
  label: string;
  logo: SimpleIcon;
}

export interface FeaturedTechnology {
  key: string;
  name: string;
  /** The logo's path on a 24-unit square, and its brand colour, or null to draw it in the text colour. */
  logo: { path: string; color: string | null };
}

export const FEATURED_TECHNOLOGIES: Featured[] = [
  { id: "postgresql", label: "PostgreSQL", logo: siPostgresql },
  { id: "redis", label: "Redis", logo: siRedis },
  { id: "kafka", label: "Kafka", logo: siApachekafka },
  { id: "kubernetes-cluster", label: "Kubernetes", logo: siKubernetes },
  { id: "java-spring-docker", label: "Java", logo: siOpenjdk },
  { id: "go-k8s-hpa", label: "Go", logo: siGo },
  { id: "nodejs-docker", label: "Node.js", logo: siNodedotjs },
  { id: "nginx", label: "Nginx", logo: siNginx },
];

/** A brand colour too dark to see on the dark theme (Kafka's near-black) is drawn in the text colour instead, as the app does. */
function visibleColor(hex: string): string | null {
  const n = Number.parseInt(hex, 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.09 ? null : `#${hex}`;
}

export function featuredTechnologies(catalog: Catalog, featured: readonly Featured[]): FeaturedTechnology[] {
  return featured.map(({ id, label, logo }) => {
    for (const component of catalog.components) {
      const preset = component.presets?.find((p) => p.id === id);
      if (!preset) continue;
      if (preset.tier !== "core") throw new Error(`the catalog's "${id}" is ${preset.tier ?? "a third-party API"}, not core: the home page features core technologies only`);
      return { key: `${component.type}:${id}`, name: label, logo: { path: logo.path, color: visibleColor(logo.hex) } };
    }
    throw new Error(`the catalog has no technology profile "${id}", which the home page features (src/lib/site/technologies.ts)`);
  });
}
