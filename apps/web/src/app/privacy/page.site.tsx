import type { Metadata } from "next";
import { PrivacyPage } from "@/components/site/PrivacyPage";
import { pageMetadata } from "@/lib/site/site";

export const metadata: Metadata = pageMetadata({
  title: "Privacy",
  description: "What the hosted demo at app.archsim.io stores about you, where it runs, its cookies and logs, and how to have your data deleted.",
  path: "/privacy/",
});

/** The hosted demo's privacy notice, which the demo's ARCHSIM_PRIVACY_URL links to. */
export default function Privacy() {
  return <PrivacyPage />;
}
