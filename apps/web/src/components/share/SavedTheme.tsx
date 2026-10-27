"use client";

import { useEffect } from "react";

/**
 * Applies the saved theme (dark unless "light", or the system's for "system"), as the root
 * layout's script does before paint. A share link that answers 404 is rendered by the browser
 * from Next's error document, where that script never runs.
 */
export function SavedTheme() {
  useEffect(() => {
    let dark = true;
    try {
      const t = localStorage.getItem("archsim-theme-studio");
      dark = t === "light" ? false : t === "system" ? window.matchMedia("(prefers-color-scheme: dark)").matches : true;
    } catch {
      /* storage blocked: the default, dark */
    }
    document.documentElement.classList.toggle("dark", dark);
  }, []);
  return null;
}
