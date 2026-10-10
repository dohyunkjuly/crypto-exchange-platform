import { LOCALE_COOKIE, type Locale } from "./locales";

/** Persists a chosen locale for the proxy. The proxy also syncs this from a visited /ko URL;
 *  writing it here makes the choice stick before the navigation lands. */
export function rememberLocale(locale: Locale): void {
  document.cookie = `${LOCALE_COOKIE}=${locale}; path=/; max-age=31536000; samesite=lax`;
}
