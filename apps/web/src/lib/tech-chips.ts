/** The technologies the catalog brings, as the logo chips the landing pages show. */
import { kindOf, type Kind } from "@/lib/theme";
import type { Catalog } from "@/lib/types";

export interface Chip {
  key: string;
  name: string;
  technology: string | null;
  kind: Kind;
}

/** Client technologies (they live on the client parts' technology field, not in presets). */
const CLIENT_TECH: [string, string][] = [
  ["ios", "iOS"],
  ["android", "Android"],
  ["react_native", "React Native"],
  ["flutter", "Flutter"],
  ["react", "React"],
  ["nextjs", "Next.js"],
  ["vue", "Vue"],
  ["angular", "Angular"],
  ["svelte", "Svelte"],
  ["graphql", "GraphQL"],
  ["rest", "REST / OpenAPI"],
];

/** How many technology profiles the catalog has, by tier, and how many kinds of clients. */
export function catalogStats(catalog: Catalog): { technologies: number; tiers: string; clientKinds: number } {
  const tiers = catalog.components.flatMap((c) => (c.presets ?? []).map((p) => p.tier ?? null));
  const count = (t: string | null) => tiers.filter((x) => x === t).length;
  const thirdParty = count(null);
  return {
    technologies: tiers.length,
    tiers: `${count("core")} core, ${count("experimental")} experimental${thirdParty ? `, ${thirdParty} third-party APIs` : ""}`,
    clientKinds: catalog.components.filter((c) => c.isTrafficSource).length,
  };
}

export function techChips(catalog: Catalog): Chip[] {
  const presets = catalog.components.flatMap((c) =>
    (c.presets ?? []).map((p) => ({ key: `${c.type}:${p.id}`, name: p.name, technology: p.technology, kind: kindOf(c.type, c.category) })),
  );
  const clients = CLIENT_TECH.map(([technology, name]) => ({ key: `client:${technology}`, name, technology, kind: "traffic" as Kind }));
  return [...presets, ...clients];
}
