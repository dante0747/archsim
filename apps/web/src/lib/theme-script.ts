/**
 * Applies the saved theme (dark by default) before first paint to avoid a flash; it sets the dark
 * class or takes it away, so a page whose HTML starts dark, as every page's does, turns light at
 * once. The app's layout and the public website's (ADR-0078) both run it inline as the body's
 * first element, never as next/script, which would hold it back until Next.js's own scripts load.
 * The choice is the theme picker's (components/ui/theme-toggle.tsx), stored in this browser only.
 * Where the page has a theme-color meta tag (the website's), the browser bar takes the colour of
 * the theme shown, not the operating system's preference: the site is dark unless light was chosen.
 */

/** The browser bar's colour in each theme: the page's background (globals.css, --page). */
export const THEME_COLORS = { dark: "#1c1c1e", light: "#f2f2f4" } as const;

/** Gives the page's theme-color meta tag, if it has one, the colour of the theme shown. */
export function syncThemeColor(dark: boolean) {
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? THEME_COLORS.dark : THEME_COLORS.light);
}

// The meta tag may come after the script in the head: then it is set once the document is parsed.
export const THEME_SCRIPT = `(function(){var d=true;try{var t=localStorage.getItem('archsim-theme-studio');d=t==='light'?false:t==='system'?window.matchMedia('(prefers-color-scheme: dark)').matches:true;}catch(e){}document.documentElement.classList.toggle('dark',d);var c=d?'${THEME_COLORS.dark}':'${THEME_COLORS.light}';function s(){var m=document.querySelector('meta[name="theme-color"]');if(m)m.setAttribute('content',c);}s();if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',s);})();`;

/**
 * The website's theme-color tag (ADR-0078), run after THEME_SCRIPT: the script adds the tag itself,
 * in the colour of the theme shown, rather than editing one React rendered. An edited tag no longer
 * matches what React hydrates, so React adds a second one in the dark theme's colour.
 */
export const THEME_COLOR_SCRIPT = `(function(){if(document.querySelector('meta[name="theme-color"]'))return;var m=document.createElement('meta');m.name='theme-color';m.content=document.documentElement.classList.contains('dark')?'${THEME_COLORS.dark}':'${THEME_COLORS.light}';document.head.appendChild(m);})();`;
