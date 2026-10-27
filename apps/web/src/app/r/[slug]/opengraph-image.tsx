/**
 * The link preview of a share (chat apps, social sites): the verdict, latency p95, throughput,
 * success and confidence, drawn with next/og in the app's dark colours. Made on request, never
 * cached, and it does not count as a view. A revoked or expired link has no image (404).
 */
import { ImageResponse } from "next/og";
import { getSharePreview } from "@/lib/server-api";
import { shareObjectives, shareVerdict } from "@/lib/share-view";
import { CONFIDENCE_LABEL } from "@/lib/confidence";
import { fmtPct, fmtRps } from "@/lib/format";
import { formatSloValue } from "@/lib/slo";

export const alt = "ArchSim shared results: the verdict and key numbers";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

const C = {
  page: "#1c1c1e",
  surface: "#232326",
  line: "#38383c",
  ink: "#f2f2f7",
  ink2: "#aeaeb6",
  ink3: "#96969e",
  good: "#30d158",
  bad: "#ff453a",
  tiles: ["#0a84ff", "#ff9f0a", "#ff375f", "#30d158"],
};

function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", padding: "20px 28px", background: C.surface, borderRadius: 18, border: `2px solid ${C.line}`, minWidth: 230 }}>
      <div style={{ fontSize: 24, color: C.ink3 }}>{label}</div>
      <div style={{ fontSize: 46, fontWeight: 700, color: C.ink, marginTop: 6 }}>{value}</div>
    </div>
  );
}

export default async function Image({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const res = await getSharePreview(slug);
  if (res.kind !== "ok") return new Response("Not found", { status: 404, headers: { "Cache-Control": "no-store" } });
  const share = res.share;
  const report = share.report;
  const v = shareVerdict(shareObjectives(share.objectives), report);
  const tone = v.tone === "good" ? C.good : v.tone === "bad" ? C.bad : C.ink2;
  const s = report.summary;
  return new ImageResponse(
    (
      <div style={{ width: "100%", height: "100%", display: "flex", flexDirection: "column", background: C.page, padding: "56px 64px", color: C.ink }}>
        <div style={{ display: "flex", alignItems: "center" }}>
          <div style={{ display: "flex", flexWrap: "wrap", width: 52, height: 52 }}>
            {C.tiles.map((c) => (
              <div key={c} style={{ width: 22, height: 22, margin: 2, borderRadius: 6, background: c }} />
            ))}
          </div>
          <div style={{ marginLeft: 18, fontSize: 32, fontWeight: 700 }}>archsim</div>
          <div style={{ marginLeft: 18, fontSize: 26, color: C.ink3 }}>shared results</div>
        </div>
        <div style={{ marginTop: 44, fontSize: 32, color: C.ink2 }}>{clip(share.title, 60)}</div>
        <div style={{ display: "flex", alignItems: "center", marginTop: 14 }}>
          <div style={{ width: 22, height: 22, borderRadius: 11, background: tone, marginRight: 22 }} />
          <div style={{ fontSize: 76, fontWeight: 700, letterSpacing: -2 }}>{v.headline}</div>
        </div>
        <div style={{ display: "flex", marginTop: "auto", gap: 20 }}>
          <Figure label="Latency p95" value={formatSloValue("p95", s.latencyMs.p95)} />
          <Figure label="Handled" value={fmtRps(s.throughputRps)} />
          <Figure label="Success" value={fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)} />
          {report.confidence && <Figure label="Confidence" value={CONFIDENCE_LABEL[report.confidence.level].replace(" confidence", "")} />}
        </div>
      </div>
    ),
    { ...size, headers: { "Cache-Control": "no-store" } },
  );
}
