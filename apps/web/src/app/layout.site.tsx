/**
 * The public website's root layout (ADR-0078; only `npm run build:site` sees *.site.tsx files).
 * The app's look and theme script, Geist served from the site itself, a content security policy
 * that keeps every request on the site's origin, and the site's header and footer.
 */
import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Script from "next/script";
import { SiteFooter } from "@/components/site/SiteFooter";
import { SiteHeader } from "@/components/site/SiteHeader";
import { repository } from "@/lib/site/content";
import { SITE_DESCRIPTION, SITE_NAME, SITE_SUMMARY, SITE_URL, contentSecurityPolicy, demoUrl, pageMetadata } from "@/lib/site/site";
import { THEME_COLORS, THEME_SCRIPT } from "@/lib/theme-script";
import "./globals.css";
import "./site.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const mono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

const home = pageMetadata({
  title: "ArchSim: crash-test your architecture before your users do",
  description: SITE_DESCRIPTION,
  socialDescription: SITE_SUMMARY,
  path: "/",
});

export const metadata: Metadata = {
  ...home,
  metadataBase: new URL(SITE_URL),
  title: { default: String(home.title), template: `%s · ${SITE_NAME}` },
  applicationName: SITE_NAME,
  referrer: "no-referrer",
  formatDetection: { telephone: false },
};

// The site is dark unless light was chosen, whatever the operating system prefers: the bar starts
// dark, and the theme script and the theme picker give it the colour of the theme shown.
export const viewport: Viewport = { themeColor: THEME_COLORS.dark };

export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning className={`${geistSans.variable} ${mono.variable} antialiased`}>
      <head>
        <meta httpEquiv="Content-Security-Policy" content={contentSecurityPolicy(process.env.NODE_ENV === "development")} />
      </head>
      <body className="min-h-full">
        <Script id="archsim-theme" strategy="beforeInteractive">
          {THEME_SCRIPT}
        </Script>
        <a
          href="#main"
          className="sr-only z-50 rounded-lg bg-surface px-4 py-2 text-[14px] font-semibold text-ink shadow-float focus:not-sr-only focus:fixed focus:left-4 focus:top-3"
        >
          Skip to content
        </a>
        <div className="flex min-h-dvh flex-col">
          <SiteHeader demoUrl={demoUrl(process.env)} />
          <div className="flex-1">{children}</div>
          <SiteFooter copyright={repository().copyright()} />
        </div>
      </body>
    </html>
  );
}
