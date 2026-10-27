/**
 * A share link: one run's results, frozen when the link was made, for anyone who has it. Rendered
 * on the server on every request (never cached), so a revoked or expired link answers 404 at once.
 * Not indexed by search engines; link previews get the verdict and an image (opengraph-image.tsx).
 */
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SharePage } from "@/components/share/SharePage";
import { getShare, publicBase } from "@/lib/server-api";
import { previewLine, viewReport } from "@/lib/share-view";

const NOT_INDEXED = { index: false, follow: false } as const;

export async function generateMetadata(props: PageProps<"/r/[slug]">): Promise<Metadata> {
  const { slug } = await props.params;
  const base = publicBase();
  const common: Metadata = { robots: NOT_INDEXED, ...(base ? { metadataBase: base } : {}) };
  const res = await getShare(slug);
  if (res.kind !== "ok") return { ...common, title: "Shared results · ArchSim" };
  const share = res.share;
  const title = `${share.title} · ArchSim`;
  const description = previewLine(share.objectives, share.report);
  return {
    ...common,
    title,
    description,
    openGraph: { title, description, type: "article", siteName: "ArchSim" },
    twitter: { card: "summary_large_image", title, description },
  };
}

function Message({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-3 bg-page px-6 py-16 text-center">
      <h1 className="font-display text-[26px] font-semibold tracking-[-0.03em] text-ink">{title}</h1>
      <p className="max-w-sm text-[15px] leading-relaxed text-ink-2">{children}</p>
    </main>
  );
}

export default async function SharedResults(props: PageProps<"/r/[slug]">) {
  const { slug } = await props.params;
  const res = await getShare(slug);
  if (res.kind === "not-found") notFound();
  if (res.kind === "limited") {
    return (
      <Message title="Too many pages opened">
        This address has opened many shared results in a short time. Try again {res.retryAfter ? `in ${res.retryAfter} s` : "in a minute"}.
      </Message>
    );
  }
  if (res.kind === "unavailable") {
    return <Message title="Shared results are unavailable">{res.message} Try again in a moment.</Message>;
  }
  const { report, ...rest } = res.share;
  return <SharePage share={{ ...rest, report: viewReport(report) }} />;
}
