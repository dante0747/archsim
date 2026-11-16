import type { Metadata } from "next";
import { SignInPage } from "@/components/auth/SignInPage";
import { privacyUrl } from "@/lib/privacy";

export const metadata: Metadata = { title: "Sign in · ArchSim", robots: { index: false } };

export default async function SignIn(props: PageProps<"/signin">) {
  const params = await props.searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : null);
  // rendered for each request (it reads the search params), so the setting is read at runtime
  return <SignInPage next={one(params.next)} error={one(params.error)} privacyUrl={privacyUrl(process.env)} />;
}
