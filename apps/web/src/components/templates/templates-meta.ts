/** Presentation details for the built-in templates: category, level and what each one shows. */
import { peakRps } from "@/lib/load";
import { kindOf, type Kind } from "@/lib/theme";
import type { ArchitectureDocument, ExampleSummary } from "@/lib/types";

export type TemplateCategory = "Starter" | "Scale" | "Data" | "Async" | "Serverless" | "Resilience" | "Learn";

export const CATEGORY_COLOR: Record<TemplateCategory, string> = {
  Starter: "var(--fam-traffic)",
  Scale: "var(--fam-compute)",
  Data: "var(--fam-database)",
  Async: "var(--fam-messaging)",
  Serverless: "var(--fam-network)",
  Resilience: "var(--fam-cache)",
  Learn: "var(--series-3)",
};

export interface TemplateMeta {
  category: TemplateCategory;
  level: "Beginner" | "Intermediate" | "Advanced";
  /** One line: what you will watch happen. */
  insight: string;
}

const META: Record<string, TemplateMeta> = {
  "cache-aside-starter": {
    category: "Starter",
    level: "Beginner",
    insight: "Redis absorbs the reads; every miss falls through to PostgreSQL.",
  },
  "black-friday-kubernetes": {
    category: "Scale",
    level: "Advanced",
    insight: "The autoscaler races a 5× ramp while payments dominate the tail.",
  },
  "iot-cassandra-quorum": {
    category: "Data",
    level: "Intermediate",
    insight: "One slow replica decides how fast a quorum write can be.",
  },
  "kafka-order-pipeline": {
    category: "Async",
    level: "Intermediate",
    insight: "Clients get fast acks while consumer lag soaks up the burst.",
  },
  "mongodb-hot-shard": {
    category: "Data",
    level: "Intermediate",
    insight: "One hot shard saturates while the other three sit idle.",
  },
  "queueing-lab": {
    category: "Learn",
    level: "Beginner",
    insight: "Latency explodes near saturation, just as queueing theory predicts.",
  },
  "retry-storm-ttl-cache": {
    category: "Resilience",
    level: "Advanced",
    insight: "Aggressive retries turn a traffic burst into a storm.",
  },
  "serverless-burst": {
    category: "Serverless",
    level: "Intermediate",
    insight: "A 10× burst meets cold starts, a concurrency cap and a throttled table.",
  },
};

/** Templates shown first, in this order; the rest follow alphabetically. */
export const FEATURED = ["black-friday-kubernetes", "cache-aside-starter", "kafka-order-pipeline"];

export function templateMeta(ex: Pick<ExampleSummary, "id" | "tags">): TemplateMeta {
  return (
    META[ex.id] ?? {
      category: ex.tags.includes("education") ? "Learn" : "Starter",
      level: "Intermediate",
      insight: "Open it and press Simulate to see how it behaves.",
    }
  );
}

export interface TemplateFacts {
  parts: number;
  links: number;
  peakRps: number;
  /** Distinct technologies in the design, with their kind (for logos). */
  technologies: { technology: string; kind: Kind }[];
}

/** The engine's default technology for parts that leave it unset (DatabaseConfig, CacheConfig). */
const DEFAULT_TECHNOLOGY: Record<string, string> = { database: "postgresql", cache: "redis" };

/** A part's technology, falling back to the engine default; null for generic parts. */
export function partTechnology(type: string, config: Record<string, unknown> | undefined): string | null {
  const tech = typeof config?.technology === "string" ? config.technology : DEFAULT_TECHNOLOGY[type];
  return tech && tech !== "generic" ? tech : null;
}

export function templateFacts(doc: ArchitectureDocument): TemplateFacts {
  const seen = new Map<string, Kind>();
  for (const n of doc.architecture.nodes) {
    const tech = partTechnology(n.type, n.config);
    if (tech && !seen.has(tech)) seen.set(tech, kindOf(n.type));
  }
  return {
    parts: doc.architecture.nodes.length,
    links: doc.architecture.edges.length,
    peakRps: peakRps(doc.workload),
    technologies: [...seen].map(([technology, kind]) => ({ technology, kind })),
  };
}

export function orderTemplates<T extends { id: string; name: string }>(items: T[]): T[] {
  const rank = (id: string) => {
    const i = FEATURED.indexOf(id);
    return i === -1 ? FEATURED.length : i;
  };
  return [...items].sort((a, b) => rank(a.id) - rank(b.id) || a.name.localeCompare(b.name));
}
