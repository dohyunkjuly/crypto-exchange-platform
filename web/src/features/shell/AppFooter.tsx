"use client";

import { Wordmark } from "@/components/ui/Brand";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { EXCHANGE_NAME } from "@/lib/env";

/**
 * One footer for every page but the trading screen, which fills the window by design.
 *
 * <p>The same for everyone, signed in or not. The links behind the session gate are listed
 * unguarded on purpose -- a signed-out visitor following one lands on the login page and
 * arrives where they were going. Only pages that exist are listed; a column of dead links
 * reads as an unfinished site.
 */
export function AppFooter() {
  const t = useT();
  const columns: { heading: string; links: { href: string; label: string }[] }[] = [
    {
      heading: t("footer.product"),
      links: [
        { href: "/#markets", label: t("nav.markets") },
        { href: "/copy", label: t("nav.copy") },
        { href: "/copy/mine", label: t("copy.myCopies") },
        { href: "/copy/lead", label: t("copy.beLeader") },
      ],
    },
    {
      heading: t("menu.account"),
      links: [
        { href: "/my/dashboard", label: t("menu.dashboard") },
        { href: "/my/assets", label: t("menu.assets") },
        { href: "/my/orders", label: t("menu.orders") },
        { href: "/my/sub-accounts", label: t("menu.subAccounts") },
      ],
    },
    {
      heading: t("footer.security"),
      links: [
        { href: "/my/account/identification", label: t("menu.identification") },
        { href: "/my/account/security", label: t("menu.security") },
        { href: "/my/account/api", label: t("menu.api") },
      ],
    },
    {
      heading: t("footer.resources"),
      links: [
        { href: "/#faq", label: t("footer.faq") },
        { href: "/copy#faq", label: t("footer.copyFaq") },
      ],
    },
  ];

  return (
    <footer className="border-t border-border">
      <div className="mx-auto w-full max-w-7xl px-5 py-12">
        <div className="flex flex-col gap-10 sm:flex-row sm:justify-between">
          <div className="flex max-w-xs flex-col gap-3">
            <Wordmark height={22} />
            <p className="text-sm text-muted">{t("footer.blurb")}</p>
          </div>
          <nav className="grid grid-cols-2 gap-x-12 gap-y-8 sm:grid-cols-4">
            {columns.map((c) => (
              <div key={c.heading} className="flex flex-col gap-3">
                <h2 className="text-sm font-semibold">{c.heading}</h2>
                <ul className="flex flex-col gap-2.5 text-sm text-muted">
                  {c.links.map((l) => (
                    <li key={l.href}>
                      <LocaleLink href={l.href} className="hover:text-text">{l.label}</LocaleLink>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>
        </div>
        <div className="mt-12 flex flex-col gap-2 border-t border-border pt-6 text-xs text-dim sm:flex-row sm:items-center sm:justify-between">
          <span>{t("brand.footer", { name: EXCHANGE_NAME })}</span>
          <span>{t("footer.risk")}</span>
        </div>
      </div>
    </footer>
  );
}
