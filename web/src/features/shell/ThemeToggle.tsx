"use client";

import { useSyncExternalStore } from "react";
import { useT } from "@/lib/i18n";

type Theme = "dark" | "light";
const KEY = "theme";
const EVENT = "themechange";

/** The <html> attribute is the source of truth -- the boot script in layout.tsx sets it before
 *  paint, and the tokens key on it -- so this reads it rather than keeping a copy. */
function read(): Theme {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}
function subscribe(cb: () => void) {
  window.addEventListener(EVENT, cb);
  window.addEventListener("storage", cb);
  return () => { window.removeEventListener(EVENT, cb); window.removeEventListener("storage", cb); };
}

export function setTheme(next: Theme) {
  if (next === "light") document.documentElement.dataset.theme = "light";
  else delete document.documentElement.dataset.theme;
  try { localStorage.setItem(KEY, next); } catch { /* private mode: the choice just does not persist */ }
  window.dispatchEvent(new Event(EVENT));
}

export function ThemeToggle() {
  const t = useT();
  // null on the server: it cannot know the viewer's choice, and guessing is a hydration mismatch.
  const theme = useSyncExternalStore(subscribe, read, () => null);
  const light = theme === "light";

  return (
    <button
      type="button"
      onClick={() => setTheme(light ? "dark" : "light")}
      aria-label={light ? t("header.toDark") : t("header.toLight")}
      aria-pressed={light}
      className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-surface-raised hover:text-text"
    >
      {light ? <Moon /> : <Sun />}
    </button>
  );
}

function Sun() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}
function Moon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  );
}
