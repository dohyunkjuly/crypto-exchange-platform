/**
 * Configuration, validated once at module load.
 *
 * No `??` fallbacks. A default here means a typo in production points the exchange at
 * nothing and the page merely looks broken -- the same reason AGENTS.md forbids a
 * secret having a default in application.yml. Missing configuration must stop the app,
 * not degrade it.
 *
 * Note `NEXT_PUBLIC_` is not a security boundary: these values are inlined into the
 * client bundle at build time and are readable by anyone. Nothing secret goes here, and
 * nothing secret needs to -- the session lives in an httpOnly cookie the browser never
 * shows us.
 */

function required(name: string, value: string | undefined): string {
  if (value === undefined || value.trim() === "") {
    throw new Error(
      `${name} is not set. Copy .env.example to .env.local -- the app cannot guess where web-bff is.`,
    );
  }
  return value.trim();
}

/** Absolute http(s) origin, or "" for `/`: the page's own origin, which is what it is behind
 *  the edge proxy (deploy/nginx) that serves the page and /bff/ from one host. */
function httpOrigin(name: string, value: string | undefined): string {
  const raw = required(name, value);
  if (raw === "/") return "";
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`${name} must be an absolute URL, got "${raw}"`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`${name} must be http or https, got "${url.protocol}"`);
  }
  // Trailing slashes are where "/bff//auth" comes from. Normalise once, here, rather
  // than making every call site remember.
  return url.origin;
}

/**
 * Whose exchange this is: the tab title, the footer, the sign-in copy, the logos' alt text.
 * Nothing in the source names one -- the product is white-label (docs/reference/white-label.md),
 * so the operator's name is configuration, and a blank one is a broken deployment that looks
 * like a working one.
 */
export const EXCHANGE_NAME = required("NEXT_PUBLIC_EXCHANGE_NAME", process.env.NEXT_PUBLIC_EXCHANGE_NAME);

/** Where web-bff is. The browser talks to this and nothing else. "" is the page's own origin. */
export const BFF_ORIGIN = httpOrigin(
  "NEXT_PUBLIC_BFF_URL",
  process.env.NEXT_PUBLIC_BFF_URL,
);

/** The same host over ws/wss, derived rather than configured twice -- two variables that
 *  must agree is one variable and a bug waiting to happen. A function, because the page's own
 *  origin is only known in the browser. */
export function bffWsOrigin(): string {
  const origin = BFF_ORIGIN || window.location.origin;
  return origin.replace(/^http/, "ws");
}

/** Optional: Google sign-in is a feature an operator can simply not enable, and web-bff
 *  already answers GOOGLE_SIGN_IN_UNAVAILABLE when its own client id is missing. Absent
 *  here means "hide the button", which is a configuration, not an error. */
export const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID?.trim() || null;
