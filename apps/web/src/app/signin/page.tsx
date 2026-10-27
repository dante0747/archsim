import type { Metadata } from "next";
import { SignInPage } from "@/components/auth/SignInPage";

export const metadata: Metadata = { title: "Sign in · ArchSim", robots: { index: false } };

export default async function SignIn(props: PageProps<"/signin">) {
  const params = await props.searchParams;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : null);
  return <SignInPage next={one(params.next)} error={one(params.error)} />;
}
