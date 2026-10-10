export const LOCALES = ["en", "ko"] as const;
export type Locale = (typeof LOCALES)[number];
export const DEFAULT_LOCALE: Locale = "en";
/** The cookie the proxy reads. Named as Next's own examples name it, for anyone who searches. */
export const LOCALE_COOKIE = "NEXT_LOCALE";

export const LOCALE_LABELS: Record<Locale, string> = { en: "English", ko: "한국어" };

export function hasLocale(value: string | undefined | null): value is Locale {
  return (LOCALES as readonly string[]).includes(value ?? "");
}

/** The locale a path starts with, if any: "/ko/login" -> "ko". */
export function localeFromPath(pathname: string): Locale | null {
  const seg = pathname.split("/")[1];
  return hasLocale(seg) ? seg : null;
}

/** Prefixes a locale unless the path already carries one, so callers can pass either. */
export function localePath(locale: Locale, path: string): string {
  if (localeFromPath(path)) return path;
  return path === "/" ? `/${locale}` : `/${locale}${path.startsWith("/") ? "" : "/"}${path}`;
}

/** Swaps the locale on a path that has one: ("/ko/login", "en") -> "/en/login". */
export function swapLocale(pathname: string, locale: Locale): string {
  const current = localeFromPath(pathname);
  if (!current) return localePath(locale, pathname);
  const rest = pathname.slice(current.length + 1);
  return `/${locale}${rest}`;
}

/**
 * Picks a locale from an Accept-Language header, honouring the q-weights and treating
 * "ko-KR" as "ko". Falls back to the default rather than guessing.
 */
export function matchLocale(acceptLanguage: string | null | undefined): Locale {
  if (!acceptLanguage) return DEFAULT_LOCALE;
  const ranked = acceptLanguage
    .split(",")
    .map((part, i) => {
      const [tag, ...params] = part.trim().split(";");
      const q = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
      return { tag: tag.toLowerCase(), q: q ? parseFloat(q.slice(2)) : 1, i };
    })
    .filter((r) => r.q > 0)
    .sort((a, b) => b.q - a.q || a.i - b.i);
  for (const { tag } of ranked) {
    const base = tag.split("-")[0];
    if (hasLocale(base)) return base;
  }
  return DEFAULT_LOCALE;
}
