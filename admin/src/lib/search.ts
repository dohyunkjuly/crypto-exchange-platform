"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AdminApiError } from "./api";

/**
 * A search screen's filters and page, kept in the URL: a refresh keeps them, and a link means the
 * search you sent. Changing a filter goes back to the first page.
 */
export function useSearch<K extends string>(keys: readonly K[]) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const query = Object.fromEntries(keys.map((k) => [k, params.get(k) ?? ""])) as Record<K, string>;
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);

  const go = (next: Partial<Record<K, string>> & { page?: number }) => {
    const p = new URLSearchParams();
    for (const k of keys) {
      const v = next[k] ?? query[k];
      if (v) p.set(k, v);
    }
    if (next.page) p.set("page", String(next.page));
    const s = p.toString();
    router.replace(s ? `${pathname}?${s}` : pathname);
  };

  return {
    query, page, go,
    /** Changes whenever the search does; what a load keys on. */
    key: params.toString(),
    filtered: keys.some((k) => query[k]),
    clear: () => router.replace(pathname),
  };
}

/**
 * What `load` returns for `key`, fetched again whenever the key changes. `load` is a module-level
 * function of the key alone, so nothing a render recreates can refetch by accident. A 401 is left
 * to the session hook, which is already sending the admin to the login.
 */
export function useLoad<T>(load: (key: string) => Promise<T>, key: string) {
  const [state, setState] = useState<{ data: T | null; error: string | null }>({ data: null, error: null });
  useEffect(() => {
    let cancelled = false;
    load(key)
      .then((data) => { if (!cancelled) setState({ data, error: null }); })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setState({ data: null, error: e.message });
      });
    return () => { cancelled = true; };
  }, [load, key]);
  return state;
}
