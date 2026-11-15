import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Script from "next/script";
import { AppAgentTools } from "@/components/agent/AgentTools";
import { Toaster } from "@/components/ui/toaster";
import { THEME_SCRIPT } from "@/lib/theme-script";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const mono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "ArchSim — architecture simulator",
  description:
    "Design distributed systems, simulate workloads with a discrete-event engine, and understand bottlenecks.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${geistSans.variable} ${mono.variable} h-full antialiased`}
    >
      <body className="h-full">
        <Script id="archsim-theme" strategy="beforeInteractive">
          {THEME_SCRIPT}
        </Script>
        {children}
        <Toaster />
        <AppAgentTools />
      </body>
    </html>
  );
}
