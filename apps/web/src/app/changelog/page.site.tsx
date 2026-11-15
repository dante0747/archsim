import type { Metadata } from "next";
import { DocsShell } from "@/components/site/DocsShell";
import { repository } from "@/lib/site/content";
import { pageMetadata } from "@/lib/site/site";

export function generateMetadata(): Metadata {
  return pageMetadata({ title: "Changelog", description: repository().changelog().description, path: "/changelog/" });
}

/** CHANGELOG.md, rendered at build time, beside the docs it is indexed with. */
export default function Changelog() {
  const repo = repository();
  return <DocsShell nav={repo.nav()} doc={{ ...repo.changelog(), path: "CHANGELOG.md" }} route="/changelog/" />;
}
