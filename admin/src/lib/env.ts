/** Validated once at module load, with no fallback: a missing URL stops the app rather than
 *  pointing it at nothing. See web/src/lib/env.ts. `/` is the page's own origin, which it is
 *  behind the edge proxy (deploy/nginx) that serves the page and /admin/ from one host. */
function httpOrigin(name: string, value: string | undefined): string {
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is not set. Copy .env.example to .env.local.`);
  }
  if (value.trim() === "/") return "";
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error(`${name} must be an absolute URL, got "${value}"`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`${name} must be http or https, got "${url.protocol}"`);
  }
  return url.origin;
}

/** Validated the same way, and for the same reason: a blank title in the tab and the header
 *  is a broken deployment that looks like a working one. */
function text(name: string, value: string | undefined): string {
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is not set. Copy .env.example to .env.local.`);
  }
  return value.trim();
}

/**
 * Whose exchange this is. Nothing here names one: the product is white-label
 * (docs/reference/white-label.md), so the operator's name is configuration, not a string in
 * the source.
 */
export const EXCHANGE_NAME = text("NEXT_PUBLIC_EXCHANGE_NAME", process.env.NEXT_PUBLIC_EXCHANGE_NAME);

/** Where admin-bff is. The browser talks to this and nothing else. */
export const ADMIN_BFF_ORIGIN = httpOrigin(
  "NEXT_PUBLIC_ADMIN_BFF_URL",
  process.env.NEXT_PUBLIC_ADMIN_BFF_URL,
);
