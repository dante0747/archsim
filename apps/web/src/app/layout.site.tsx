/**
 * The public website's root layout (ADR-0078; only `npm run build:site` sees *.site.tsx files).
 * The app's look from a stylesheet of the site's own (site.css, ADR-0100) and the app's theme
 * script, Geist served from the site itself, a content security policy that keeps every request on
 * the site's origin, and the site's header and footer.
 */
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { SiteFooter } from "@/components/site/SiteFooter";
import { SiteHeader } from "@/components/site/SiteHeader";
import { repository } from "@/lib/site/content";
import { SITE_DESCRIPTION, SITE_NAME, SITE_SUMMARY, SITE_URL, contentSecurityPolicy, demoUrl, pageMetadata } from "@/lib/site/site";
import { THEME_COLOR_SCRIPT, THEME_SCRIPT } from "@/lib/theme-script";
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

// The site is dark unless light was chosen, whatever the operating system prefers. The browser bar
// takes the colour of the theme shown: the theme script adds its theme-color tag (THEME_COLOR_SCRIPT)
// and the theme picker keeps it in step.

export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    // Dark in the HTML itself, the site's default: a page that paints before any script runs paints
    // dark. The theme script, the body's first element, runs before anything in the body is drawn,
    // turns the page light for whoever chose light, and adds its theme-color tag after everything
    // Next.js writes in the head. As next/script it would wait until Next.js's own scripts load.
    <html lang="en" suppressHydrationWarning className={`dark ${geistSans.variable} ${mono.variable} antialiased`}>
      <head>
        <meta httpEquiv="Content-Security-Policy" content={contentSecurityPolicy(process.env.NODE_ENV === "development")} />
      </head>
      <body className="min-h-full">
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT + THEME_COLOR_SCRIPT }} />
        <a
          href="#main"
          className="sr-only z-50 rounded-lg bg-surface text-[14px] font-semibold text-ink shadow-float focus:not-sr-only focus:fixed focus:left-4 focus:top-2 focus:px-4 focus:py-2.5"
        >
          Skip to content
        </a>
        <div className="flex min-h-dvh flex-col">
          <SiteHeader demoUrl={demoUrl(process.env)} />
          <div className="flex-1">{children}</div>
          <SiteFooter copyright={repository().copyright()} commercialContact={repository().readme().commercialContact} />
        </div>
      </body>
    </html>
  );
}
