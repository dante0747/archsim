import type { Metadata } from "next";
import { Geist, JetBrains_Mono } from "next/font/google";
import { Toaster } from "@/components/ui/toaster";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const mono = JetBrains_Mono({ variable: "--font-jetbrains-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "ArchSim — architecture simulator",
  description:
    "Design distributed systems, simulate workloads with a discrete-event engine, and understand bottlenecks.",
};

// Applies the saved (or system) theme before first paint to avoid a flash.
const themeScript = `(function(){try{var t=localStorage.getItem('archsim-theme');var d=t?t==='dark':window.matchMedia('(prefers-color-scheme: dark)').matches;if(d)document.documentElement.classList.add('dark');}catch(e){}})();`;

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${geistSans.variable} ${mono.variable} h-full antialiased`}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body className="h-full">
        {children}
        <Toaster />
      </body>
    </html>
  );
}
