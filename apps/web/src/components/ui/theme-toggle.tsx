"use client";

/**
 * Themes: one light theme and five dark ones (Midnight, Tokyo Night, Dracula, Nord, GitHub Dark).
 * A dark theme sets the `dark` class (Tailwind's dark variant and the base dark tokens) plus
 * `data-theme` for its palette. The choice is stored in this browser only.
 */
import { Check, Palette } from "lucide-react";
import { useSyncExternalStore } from "react";
import { Button } from "./button";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "./overlays";
import { Tip, TooltipProvider } from "./misc";

export type ThemeId = "midnight" | "tokyo" | "dracula" | "nord" | "github" | "light";

export interface ThemeOption {
  id: ThemeId;
  label: string;
  dark: boolean;
  /** Background, card and two accents, for the swatch. */
  swatch: [string, string, string, string];
}

export const THEMES: ThemeOption[] = [
  { id: "midnight", label: "Midnight", dark: true, swatch: ["#121522", "#242a40", "#7c6cff", "#ff5f8f"] },
  { id: "tokyo", label: "Tokyo Night", dark: true, swatch: ["#1a1b26", "#24283b", "#7aa2f7", "#bb9af7"] },
  { id: "dracula", label: "Dracula", dark: true, swatch: ["#21222c", "#44475a", "#bd93f9", "#ff79c6"] },
  { id: "nord", label: "Nord", dark: true, swatch: ["#2e3440", "#434c5e", "#88c0d0", "#a3be8c"] },
  { id: "github", label: "GitHub Dark", dark: true, swatch: ["#0d1117", "#1c2128", "#2f81f7", "#3fb950"] },
  { id: "light", label: "Light", dark: false, swatch: ["#f7f6fc", "#ffffff", "#6a45ff", "#0aa5c8"] },
];

const STORE_KEY = "archsim-theme";
const LAST_DARK_KEY = "archsim-dark-theme";
const DEFAULT_DARK: ThemeId = "midnight";

function subscribe(cb: () => void) {
  const obs = new MutationObserver(cb);
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class", "data-theme"] });
  return () => obs.disconnect();
}

function readTheme(): ThemeId {
  const el = document.documentElement;
  if (!el.classList.contains("dark")) return "light";
  const id = el.dataset.theme as ThemeId | undefined;
  return THEMES.some((t) => t.id === id && t.dark) ? (id as ThemeId) : DEFAULT_DARK;
}

export function useIsDark(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => document.documentElement.classList.contains("dark"),
    () => false,
  );
}

export function useTheme(): ThemeId {
  return useSyncExternalStore(subscribe, readTheme, () => DEFAULT_DARK);
}

/** Switch theme and remember it. */
export function applyTheme(id: ThemeId) {
  const theme = THEMES.find((t) => t.id === id) ?? THEMES[0];
  const el = document.documentElement;
  el.classList.toggle("dark", theme.dark);
  if (theme.dark) el.dataset.theme = theme.id;
  else delete el.dataset.theme;
  try {
    localStorage.setItem(STORE_KEY, theme.id);
    if (theme.dark) localStorage.setItem(LAST_DARK_KEY, theme.id);
  } catch {
    /* storage unavailable */
  }
}

/** Flip between light and the last dark theme used. */
export function toggleTheme() {
  if (document.documentElement.classList.contains("dark")) {
    applyTheme("light");
    return;
  }
  let last: ThemeId = DEFAULT_DARK;
  try {
    const stored = localStorage.getItem(LAST_DARK_KEY) as ThemeId | null;
    if (stored && THEMES.some((t) => t.id === stored && t.dark)) last = stored;
  } catch {
    /* storage unavailable */
  }
  applyTheme(last);
}

function Swatch({ colors }: { colors: ThemeOption["swatch"] }) {
  const [bg, card, a, b] = colors;
  return (
    <span className="relative inline-flex h-5 w-8 shrink-0 overflow-hidden rounded-md border border-line-strong" style={{ background: bg }} aria-hidden>
      <span className="absolute inset-y-1 left-1 w-3.5 rounded-sm" style={{ background: card }} />
      <span className="absolute right-1 top-1 size-1.5 rounded-full" style={{ background: a }} />
      <span className="absolute bottom-1 right-1 size-1.5 rounded-full" style={{ background: b }} />
    </span>
  );
}

/**
 * Theme picker: a palette button that lists the themes with small previews. It brings its own
 * tooltip provider so it also works on pages without one (the projects home).
 */
export function ThemeMenu({ align = "end" }: { align?: "start" | "end" }) {
  const current = useTheme();
  const item = (t: ThemeOption) => (
    <MenuItem key={t.id} onSelect={() => applyTheme(t.id)}>
      <Swatch colors={t.swatch} />
      <span className="flex-1">{t.label}</span>
      {current === t.id && <Check className="text-accent" />}
    </MenuItem>
  );
  return (
    <TooltipProvider>
      <Menu>
        <Tip content="Theme">
          <MenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="Choose a theme">
              <Palette />
            </Button>
          </MenuTrigger>
        </Tip>
        <MenuContent align={align} className="w-56">
          <MenuLabel>Dark</MenuLabel>
          {THEMES.filter((t) => t.dark).map(item)}
          <MenuSeparator />
          {THEMES.filter((t) => !t.dark).map(item)}
        </MenuContent>
      </Menu>
    </TooltipProvider>
  );
}

/** Kept for places that only need the picker button. */
export const ThemeToggle = ThemeMenu;
