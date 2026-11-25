"use client";

/**
 * The Studio look in two themes, dark (charcoal, the default) and light, plus "Match system", which
 * follows the operating system. Dark sets the `dark` class on <html> (Tailwind's dark variant and the
 * dark tokens). The choice is stored in this browser only.
 */
import { Check, Moon, Sun } from "lucide-react";
import { useEffect, useSyncExternalStore } from "react";
import { syncThemeColor } from "@/lib/theme-script";
import { Button } from "./button";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "./overlays";
import { Tip, TooltipProvider } from "./misc";

export type ThemeId = "dark" | "light" | "system";

export interface ThemeOption {
  id: ThemeId;
  label: string;
  hint: string;
}

export const THEMES: ThemeOption[] = [
  { id: "dark", label: "Dark", hint: "Studio charcoal" },
  { id: "light", label: "Light", hint: "Studio light" },
  { id: "system", label: "Match system", hint: "Follows your device" },
];

// A new key: the Studio redesign starts everyone on its dark theme once.
const STORE_KEY = "archsim-theme-studio";
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((cb) => cb());

function systemDark(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/** The stored choice. Anything else (nothing stored, or a value from an older version) maps here. */
function readPreference(): ThemeId {
  try {
    const stored = localStorage.getItem(STORE_KEY);
    if (stored === "light" || stored === "system") return stored;
  } catch {
    /* storage unavailable */
  }
  return "dark";
}

function setDarkClass(dark: boolean) {
  const el = document.documentElement;
  el.classList.toggle("dark", dark);
  delete el.dataset.theme; // left over from the retired palettes
  syncThemeColor(dark);
}

function subscribeClass(cb: () => void) {
  const obs = new MutationObserver(cb);
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
  return () => obs.disconnect();
}

function subscribePreference(cb: () => void) {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function useIsDark(): boolean {
  return useSyncExternalStore(
    subscribeClass,
    () => document.documentElement.classList.contains("dark"),
    () => false,
  );
}

export function useTheme(): ThemeId {
  return useSyncExternalStore(subscribePreference, readPreference, () => "system");
}

/** Switch theme and remember the choice. */
export function applyTheme(id: ThemeId) {
  setDarkClass(id === "system" ? systemDark() : id === "dark");
  try {
    localStorage.setItem(STORE_KEY, id);
  } catch {
    /* storage unavailable */
  }
  emit();
}

/** Flip between dark and light (an explicit choice, no longer following the system). */
export function toggleTheme() {
  applyTheme(document.documentElement.classList.contains("dark") ? "light" : "dark");
}

/** While the choice is "Match system", follow the operating system when it changes. */
function useFollowSystem() {
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      if (readPreference() === "system") setDarkClass(mq.matches);
    };
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
}

/** A tiny preview of each theme: page, card, accent. */
function Swatch({ id }: { id: ThemeId }) {
  if (id === "system") {
    return (
      <span className="relative inline-flex h-5 w-8 shrink-0 overflow-hidden rounded-md border border-line-strong" aria-hidden>
        <span className="h-full w-1/2 bg-[#f6f8fa]" />
        <span className="h-full w-1/2 bg-[#0b0c10]" />
      </span>
    );
  }
  const [bg, line, card, accent] = id === "dark" ? ["#0b0c10", "rgba(204,214,235,0.25)", "#1c1f25", "#ff7a1a"] : ["#f6f8fa", "rgba(31,35,40,0.2)", "#ffffff", "#0969da"];
  return (
    <span className="relative inline-flex h-5 w-8 shrink-0 overflow-hidden rounded-md border border-line-strong" style={{ background: bg }} aria-hidden>
      <span className="absolute inset-y-1 left-1 w-3.5 rounded-[3px] border" style={{ background: card, borderColor: line }} />
      <span className="absolute right-1 top-1/2 size-1.5 -translate-y-1/2 rounded-full" style={{ background: accent }} />
    </span>
  );
}

/**
 * Theme picker: a sun or moon button with the two themes and "Match system". It brings its own
 * tooltip provider so it also works on pages without one (the projects home). `className` styles
 * the button (the website's header makes it a 44-pixel touch target).
 */
export function ThemeMenu({ align = "end", className }: { align?: "start" | "end"; className?: string }) {
  const current = useTheme();
  const dark = useIsDark();
  useFollowSystem();
  return (
    <TooltipProvider>
      <Menu>
        <Tip content="Theme">
          <MenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="Choose a theme" className={className}>
              {dark ? <Moon /> : <Sun />}
            </Button>
          </MenuTrigger>
        </Tip>
        <MenuContent align={align} className="w-60">
          {THEMES.map((t) => (
            <MenuItem key={t.id} onSelect={() => applyTheme(t.id)}>
              <Swatch id={t.id} />
              <span className="flex min-w-0 flex-1 flex-col leading-tight">
                <span>{t.label}</span>
                <span className="text-xs text-ink-3">{t.hint}</span>
              </span>
              {current === t.id && <Check className="text-accent" />}
            </MenuItem>
          ))}
        </MenuContent>
      </Menu>
    </TooltipProvider>
  );
}

/** Kept for places that only need the picker button. */
export const ThemeToggle = ThemeMenu;
