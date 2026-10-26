import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Script from "next/script";
import { AppAgentTools } from "@/components/agent/AgentTools";
import { Toaster } from "@/components/ui/toaster";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const mono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "ArchSim — architecture simulator",
  description:
    "Design distributed systems, simulate workloads with a discrete-event engine, and understand bottlenecks.",
};

// Applies the saved theme (dark by default) before first paint to avoid a flash.
const themeScript = `(function(){var d=true;try{var t=localStorage.getItem('archsim-theme-studio');d=t==='light'?false:t==='system'?window.matchMedia('(prefers-color-scheme: dark)').matches:true;}catch(e){}if(d)document.documentElement.classList.add('dark');})();`;

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${geistSans.variable} ${mono.variable} h-full antialiased`}
    >
      <body className="h-full">
        <Script id="archsim-theme" strategy="beforeInteractive">
          {themeScript}
        </Script>
        {children}
        <Toaster />
        <AppAgentTools />
      </body>
    </html>
  );
}
