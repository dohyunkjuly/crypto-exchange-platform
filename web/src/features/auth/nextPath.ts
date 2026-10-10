/** Where sign-in lands when nothing else was asked for. */
/** The dashboard for now; the trading screen once it exists. */
export const DEFAULT_NEXT = "/my/dashboard";

/**
 * Validates a requested `?next=` destination, falling back when it is not a path on this site.
 *
 * **The check is the point.** An unvalidated `next` is an open redirect: a link to
 * `/login?next=https://evil.example/` would send someone who has just authenticated to an
 * attacker's page, with our domain as the referrer. Protocol-relative `//evil.example` is the
 * form that slips past a naive `startsWith("/")` -- the browser reads it as absolute.
 */
export function safeNextPath(search: string): string {
  const requested = new URLSearchParams(search).get("next");
  return requested && requested.startsWith("/") && !requested.startsWith("//")
    ? requested
    : DEFAULT_NEXT;
}

/**
 * The destination from the live URL.
 *
 * Read here, from an event handler, rather than through `useSearchParams` during render --
 * which would be the obvious choice and is wrong for this page. Any component calling that hook
 * is excluded from prerendering, so the sign-in form would ship as an empty card until
 * JavaScript loaded, while every other page rendered immediately. The value is only needed once
 * a login has succeeded, which is necessarily after hydration, so nothing is lost by waiting.
 */
export function currentNextPath(): string {
  return safeNextPath(typeof window === "undefined" ? "" : window.location.search);
}
