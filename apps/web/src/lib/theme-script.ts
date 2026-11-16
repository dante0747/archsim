/**
 * Applies the saved theme (dark by default) before first paint to avoid a flash. The app's layout
 * and the public website's (ADR-0078) both run it; the choice is the theme picker's
 * (components/ui/theme-toggle.tsx), stored in this browser only. Where the page has a theme-color
 * meta tag (the website's), the browser bar takes the colour of the theme shown, not the operating
 * system's preference: the site is dark unless light was chosen.
 */

/** The browser bar's colour in each theme: the page's background (globals.css, --page). */
export const THEME_COLORS = { dark: "#1c1c1e", light: "#f2f2f4" } as const;

/** Gives the page's theme-color meta tag, if it has one, the colour of the theme shown. */
export function syncThemeColor(dark: boolean) {
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? THEME_COLORS.dark : THEME_COLORS.light);
}

// The meta tag may come after the script in the head: then it is set once the document is parsed.
export const THEME_SCRIPT = `(function(){var d=true;try{var t=localStorage.getItem('archsim-theme-studio');d=t==='light'?false:t==='system'?window.matchMedia('(prefers-color-scheme: dark)').matches:true;}catch(e){}if(d)document.documentElement.classList.add('dark');var c=d?'${THEME_COLORS.dark}':'${THEME_COLORS.light}';function s(){var m=document.querySelector('meta[name="theme-color"]');if(m)m.setAttribute('content',c);}s();if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',s);})();`;
