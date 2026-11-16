"use client";

/**
 * The browser tab's title for a page whose subject only the browser knows (the open project,
 * loaded after the page): set while the page shows it, and the page's own title put back after.
 */
import { useEffect } from "react";

/** "Checkout · ArchSim": the project first, so tabs of several projects tell apart. */
export function projectTitle(name: string): string {
  return `${name.trim() || "Untitled architecture"} · ArchSim`;
}

/** Shows `title` in the tab; null leaves the page's own until there is one. */
export function useDocumentTitle(title: string | null) {
  useEffect(() => {
    const before = document.title;
    return () => {
      document.title = before;
    };
  }, []);
  useEffect(() => {
    if (title !== null) document.title = title;
  }, [title]);
}
