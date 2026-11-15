import type { Metadata } from "next";
import { DocsShell } from "@/components/site/DocsShell";
import { repository } from "@/lib/site/content";
import { pageMetadata } from "@/lib/site/site";

export function generateMetadata(): Metadata {
  const doc = repository().doc([]);
  return pageMetadata({ title: "Documentation", description: doc.description, path: "/docs/" });
}

/** docs/README.md: the documentation's index. */
export default function DocsIndex() {
  const repo = repository();
  return <DocsShell nav={repo.nav()} doc={repo.doc([])} route="/docs/" />;
}
