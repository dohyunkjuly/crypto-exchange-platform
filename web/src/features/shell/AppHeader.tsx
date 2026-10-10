"use client";

import { usePathname, useRouter } from "next/navigation";
import { Menu, MenuDivider, MenuItem } from "@/components/ui/Menu";
import { Wordmark } from "@/components/ui/Brand";
import { Button } from "@/components/ui/Button";
import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/features/auth/session";
import { fetchTickers } from "@/features/markets/api";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { EXCHANGE_NAME } from "@/lib/env";
import { ThemeToggle } from "./ThemeToggle";
import { LocaleSwitcher } from "./LocaleSwitcher";
import { UserMenu } from "./UserMenu";

/**
 * One header for every page. Signed-out it offers sign in; signed-in it offers the account.
 *
 * <p>Edge to edge, whatever the page below does with its own width: a nav bar that moves
 * with the content column reads as a different bar on each route.
 *
 * <p>On a phone the product links fold into one menu on the left and "Log in" into it too:
 * the mark, the account and the two viewer controls are what fit beside them.
 */
export function AppHeader() {
  const t = useT();
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  const session = useSession();
  const signedIn = session.status === "signedIn";

  // Product navigation only. Account pages live under the user icon, as on every exchange.
  const { data: tickers } = useQuery({ queryKey: ["tickers"], queryFn: ({ signal }) => fetchTickers(signal), staleTime: 60_000 });
  const nav = [
    { href: "/", label: t("nav.markets") },
    // Busiest market, the way a "Trade" link opens a default pair; the landing's markets
    // list until tickers have loaded.
    { href: tickers?.[0] ? `/trade/${tickers[0].symbol}` : "/#markets", label: t("nav.trade") },
    { href: "/copy", label: t("nav.copy") },
  ];

  return (
    <header className="sticky top-0 z-30 border-b border-border bg-bg/90 backdrop-blur">
      <div className="flex h-12 items-center gap-3 whitespace-nowrap px-3 sm:gap-8 sm:px-6">
        <span className="-ml-1 flex sm:hidden">
          <Menu label={t("header.menu")} align="left"
                trigger={() => (
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden><path d="M4 7h16M4 12h16M4 17h16" /></svg>
                )}>
            {(close) => (
              <>
                {nav.map((n) => (
                  <MenuItem key={n.href} onSelect={() => { close(); router.push(localePath(locale, n.href)); }}>{n.label}</MenuItem>
                ))}
                {session.status === "signedOut" && (
                  <>
                    <MenuDivider />
                    <MenuItem onSelect={() => { close(); router.push(localePath(locale, "/login")); }}>{t("header.signIn")}</MenuItem>
                  </>
                )}
              </>
            )}
          </Menu>
        </span>
        <LocaleLink href="/" aria-label={t("brand.home", { name: EXCHANGE_NAME })} className="flex items-center">
          <Wordmark height={22} />
        </LocaleLink>
        <nav className="hidden gap-6 text-base sm:flex">
          {nav.map((n) => (
            <LocaleLink
              key={n.href}
              href={n.href}
              className={pathname === localePath(locale, n.href) ? "text-text" : "text-muted hover:text-text"}
            >
              {n.label}
            </LocaleLink>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-2 text-base sm:gap-3">
          {session.status === "loading" ? null : signedIn ? (
            <UserMenu user={session.user} />
          ) : (
            <>
              <LocaleLink href="/login" className="hidden text-muted hover:text-text sm:inline">{t("header.signIn")}</LocaleLink>
              <LocaleLink href="/register"><Button size="sm" className="px-3 sm:px-4">{t("header.createAccount")}</Button></LocaleLink>
            </>
          )}
          {/* Rightmost, and outside the session branches: theme and language are the viewer's,
              not the account's, and must not appear late alongside the sign-in links. */}
          <span className="flex items-center gap-0.5 border-l border-border pl-2 sm:ml-1 sm:gap-1 sm:pl-3">
            <LocaleSwitcher />
            <ThemeToggle />
          </span>
        </div>
      </div>
    </header>
  );
}
