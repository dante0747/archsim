import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
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
    // Dark in the HTML itself, the default: a page that paints before any script runs paints dark.
    // The theme script, the body's first element, runs before anything in the body is drawn and
    // turns the page light for whoever chose light. As next/script it would wait until Next.js's
    // own scripts load, so the page would show light first and then animate to dark.
    <html lang="en" suppressHydrationWarning className={`dark ${geistSans.variable} ${mono.variable} h-full antialiased`}>
      <body className="h-full">
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
        {children}
        <Toaster />
        <AppAgentTools />
      </body>
    </html>
  );
}
