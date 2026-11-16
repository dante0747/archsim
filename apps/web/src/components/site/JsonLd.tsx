import { type JsonLd as Data, jsonLdText } from "@/lib/site/structured-data";

/** A page's structured data for search engines (lib/site/structured-data.ts). It runs nothing. */
export function JsonLd({ data }: { data: Data }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: jsonLdText(data) }} />;
}
