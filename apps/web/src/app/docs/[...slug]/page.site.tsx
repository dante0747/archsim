import type { Metadata } from "next";
import { DocsShell } from "@/components/site/DocsShell";
import { JsonLd } from "@/components/site/JsonLd";
import { repository } from "@/lib/site/content";
import { docRoute, docSlug } from "@/lib/site/links";
import { pageMetadata } from "@/lib/site/site";
import { docStructuredData } from "@/lib/site/structured-data";

interface Props {
  params: Promise<{ slug: string[] }>;
}

// Only the documents under docs/ have pages; anything else is the site's 404.
export const dynamicParams = false;

export function generateStaticParams(): { slug: string[] }[] {
  return repository()
    .docFiles()
    .map((p) => docSlug(p))
    .filter((slug): slug is string[] => slug !== null && slug.length > 0)
    .map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params;
  const doc = repository().doc(slug);
  return pageMetadata({ title: doc.title, description: doc.description, path: docRoute(doc.path) ?? "/docs/" });
}

/** One document of docs/, rendered from its Markdown at build time. */
export default async function DocPage({ params }: Props) {
  const { slug } = await params;
  const repo = repository();
  const doc = repo.doc(slug);
  const route = docRoute(doc.path) ?? "/docs/";
  return (
    <>
      <JsonLd data={docStructuredData({ title: doc.title, description: doc.description, path: route, ancestors: repo.ancestors(slug) })} />
      <DocsShell nav={repo.nav()} doc={doc} route={route} />
    </>
  );
}
