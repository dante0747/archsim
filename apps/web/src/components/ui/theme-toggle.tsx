"use client";

import { Moon, Sun } from "lucide-react";
import { useSyncExternalStore } from "react";
import { Button } from "./button";

function subscribe(cb: () => void) {
  const obs = new MutationObserver(cb);
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
  return () => obs.disconnect();
}

export function useIsDark(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => document.documentElement.classList.contains("dark"),
    () => false,
  );
}

/** Flip between the light and dark theme and remember the choice. */
export function toggleTheme() {
  const next = !document.documentElement.classList.contains("dark");
  document.documentElement.classList.toggle("dark", next);
  try {
    localStorage.setItem("archsim-theme", next ? "dark" : "light");
  } catch {
    /* storage unavailable */
  }
}

export function ThemeToggle() {
  const dark = useIsDark();
  const toggle = toggleTheme;
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggle}
      aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
      title={dark ? "Light theme" : "Dark theme"}
    >
      {dark ? <Sun /> : <Moon />}
    </Button>
  );
}
