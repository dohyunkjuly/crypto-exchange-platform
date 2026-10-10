import { NextResponse, type NextRequest } from "next/server";
import { LOCALE_COOKIE, hasLocale, localeFromPath, matchLocale } from "@/lib/i18n/locales";

const ONE_YEAR = 60 * 60 * 24 * 365;

/**
 * Puts a locale on every page URL, the way every exchange does (/ko/trade/…).
 *
 * This is the only proxy in the app and it makes no auth decision (AGENTS.md): it reads a
 * cookie and Accept-Language, which is the header-only use Next's own guide shows. It never
 * touches the session, which it could not validate anyway.
 */
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const inPath = localeFromPath(pathname);

  if (inPath) {
    // A shared /ko link should stick: keep the cookie in step with the URL.
    const response = NextResponse.next();
    if (request.cookies.get(LOCALE_COOKIE)?.value !== inPath) {
      response.cookies.set(LOCALE_COOKIE, inPath, { path: "/", maxAge: ONE_YEAR, sameSite: "lax" });
    }
    return response;
  }

  const saved = request.cookies.get(LOCALE_COOKIE)?.value;
  const locale = hasLocale(saved) ? saved : matchLocale(request.headers.get("accept-language"));
  const url = request.nextUrl.clone();
  url.pathname = `/${locale}${pathname === "/" ? "" : pathname}`;
  return NextResponse.redirect(url);
}

export const config = {
  // Pages only. Anything with a file extension (icons, logos, chunks) and Next's internals
  // must not be redirected to /en/… -- that would 404 every asset.
  matcher: ["/((?!_next|.*\\..*).*)"],
};
