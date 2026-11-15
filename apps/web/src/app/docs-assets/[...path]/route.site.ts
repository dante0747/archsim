/** The images under docs/, which the documents and the home page show, served as they are. */
import { extname } from "node:path";
import { repository } from "@/lib/site/content";

export const dynamic = "force-static";
export const dynamicParams = false;

const TYPES: Record<string, string> = {
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".svg": "image/svg+xml",
  ".webp": "image/webp",
};

export function generateStaticParams(): { path: string[] }[] {
  return repository()
    .docAssets()
    .map((p) => ({ path: p.slice("docs/".length).split("/") }));
}

export async function GET(_request: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  const file = `docs/${path.join("/")}`;
  if (!repository().docAssets().includes(file)) return new Response("Not found", { status: 404 });
  return new Response(new Uint8Array(repository().bytes(file)), { headers: { "Content-Type": TYPES[extname(file).toLowerCase()] ?? "application/octet-stream" } });
}
