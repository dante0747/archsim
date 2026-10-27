import type { Metadata } from "next";
import { SettingsPage } from "@/components/auth/SettingsPage";

export const metadata: Metadata = { title: "Settings · ArchSim", robots: { index: false } };

export default function Settings() {
  return <SettingsPage />;
}
