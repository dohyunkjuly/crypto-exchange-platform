"use client";

import { useSyncExternalStore } from "react";

/** One list a key: its listeners and its cache, made on first use. */
type Store = { listeners: Set<() => void>; cache: string | null };
const stores = new Map<string, Store>();
const storeOf = (key: string): Store => {
  let s = stores.get(key);
  if (!s) { s = { listeners: new Set(), cache: null }; stores.set(key, s); }
  return s;
};
const read = (key: string) => { try { return localStorage.getItem(key) ?? ""; } catch { return ""; } };
const snapshot = (key: string) => { const s = storeOf(key); return (s.cache ??= read(key)); };
const subscribe = (key: string, cb: () => void) => { const s = storeOf(key); s.listeners.add(cb); return () => { s.listeners.delete(cb); }; };
const write = (key: string, next: Set<string>) => {
  const s = storeOf(key);
  s.cache = [...next].join(",");
  try { localStorage.setItem(key, s.cache); } catch { /* not persisted; still applied this session */ }
  s.listeners.forEach((cb) => cb());
};

/**
 * A set of ids starred in this browser only (docs/STYLE.md), kept under `key`: pairs on the
 * trading screen, leaders on the copy page. Nothing here is sent to the server.
 */
export function useFavoritesOf(key: string): [Set<string>, (id: string) => void] {
  const raw = useSyncExternalStore((cb) => subscribe(key, cb), () => snapshot(key), () => "");
  const set = new Set(raw ? raw.split(",") : []);
  const toggle = (id: string) => {
    const next = new Set(set); if (!next.delete(id)) next.add(id);
    write(key, next);
  };
  return [set, toggle];
}
