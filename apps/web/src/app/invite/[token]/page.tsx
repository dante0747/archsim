import type { Metadata } from "next";
import { InvitePage } from "@/components/auth/InvitePage";

export const metadata: Metadata = { title: "Invitation · ArchSim", robots: { index: false } };

export default async function Invite(props: PageProps<"/invite/[token]">) {
  const { token } = await props.params;
  return <InvitePage token={token} />;
}
