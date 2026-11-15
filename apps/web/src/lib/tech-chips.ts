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

export function techChips(catalog: Catalog): Chip[] {
  const presets = catalog.components.flatMap((c) =>
    (c.presets ?? []).map((p) => ({ key: `${c.type}:${p.id}`, name: p.name, technology: p.technology, kind: kindOf(c.type, c.category) })),
  );
  const clients = CLIENT_TECH.map(([technology, name]) => ({ key: `client:${technology}`, name, technology, kind: "traffic" as Kind }));
  return [...presets, ...clients];
}
