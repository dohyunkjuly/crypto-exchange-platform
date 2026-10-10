"use client";

import { usePathname } from "next/navigation";
import { useState } from "react";
import { NavIcon, type NavIconName } from "@/components/ui/NavIcon";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { GROUPS, TOP, isPrefixOfAnother, type NavGroup, type NavItem } from "./nav";

/**
 * The rail at lg and up: Dashboard, Assets and Orders flat, Account and Sub-accounts as
 * sections that open in place, each already open whenever the current page is inside it.
 *
 * Below lg it is one button naming the page you are on, which opens the same list as a sheet
 * over the page. A scrolling row was the first attempt and is worse: the entry you want is
 * usually the one off the right edge, and nothing on screen says the row scrolls.
 */
export function Sidebar() {
  const t = useT();
  const locale = useLocale();
  const pathname = usePathname();
  // A page inside a section (Security's change-password page) keeps that section lit. The
  // sub-accounts list is a prefix of its own sections, so it is lit only on an exact match.
  const active = (href: string, exact = false) =>
    pathname === localePath(locale, href) || (!exact && pathname.startsWith(`${localePath(locale, href)}/`));
  const isExact = isPrefixOfAnother;

  const link = (item: NavItem, nested = false, icon?: NavIconName) => {
    const lit = active(item.href, isExact(item));
    return (
      <LocaleLink
        key={item.href}
        href={item.href}
        aria-current={lit ? "page" : undefined}
        // Nested rows indent to where the labels above them start, so a section reads as one
        // column of words with its parent rather than a second, narrower list.
        className={`flex items-center gap-3 whitespace-nowrap rounded-lg py-2 pr-3 text-base ${nested ? "pl-12" : "pl-3"} ${lit ? "bg-surface-raised text-text" : "text-muted hover:text-text"}`}
      >
        {icon && <NavIcon name={icon} />}
        {t(item.key)}
      </LocaleLink>
    );
  };

  const here = [...TOP, ...GROUPS.flatMap((g) => g.items)].find((i) => active(i.href, isExact(i)));
  const list = (close?: () => void) => (
    <>
      {TOP.map((i) => link(i, false, i.icon))}
      {GROUPS.map((g) => (
        <Section key={g.id} group={g} inside={g.items.some((i) => active(i.href))} link={link} onChoose={close} />
      ))}
    </>
  );

  return (
    <nav aria-label={t("menu.account")} className="lg:w-56 lg:shrink-0">
      <MobileMenu here={here ? t(here.key) : t("menu.account")}>
        {(close) => <div className="flex flex-col [&_a]:py-3 [&_button]:py-3" onClick={close}>{list(close)}</div>}
      </MobileMenu>

      {/* lg and up: the rail */}
      <div className="hidden flex-col gap-1 lg:flex">{list()}</div>
    </nav>
  );
}

/**
 * The page you are on, and the whole navigation under it when tapped.
 *
 * It opens in place, pushing the page down rather than covering it: there is nothing on this
 * page worth dimming, and a panel over the top would hide the very heading that says where the
 * menu just took you. Full-bleed -- the negative margins undo the page's own padding -- so the
 * bar reads as part of the header above it and not as a control floating inside the content.
 */
function MobileMenu({ here, children }: { here: string; children: (close: () => void) => React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="-mx-3 border-b border-border sm:-mx-6 lg:hidden">
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-controls="account-menu"
              className="flex w-full items-center justify-between gap-3 px-3 py-4 text-base font-medium sm:px-6">
        {here}
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden
             className={`text-muted transition-transform ${open ? "rotate-180" : ""}`}>
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div id="account-menu" className="border-t border-border px-1 py-2 sm:px-4">
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  );
}

/** A group: its own row, level with Dashboard and the rest, opening in place. */
function Section({ group, inside, link, onChoose }: {
  group: NavGroup; inside: boolean; link: (item: NavItem, nested?: boolean, icon?: NavIconName) => React.ReactNode;
  onChoose?: () => void;
}) {
  const t = useT();
  // null = follow the route; a click pins it either way.
  const [pinned, setPinned] = useState<boolean | null>(null);
  const open = pinned ?? inside;
  const id = `nav-${group.id}`;
  return (
    <>
      <button
        type="button"
        // Inside the sheet a tap on a row closes it; opening a section must not.
        onClick={(e) => { e.stopPropagation(); setPinned(!open); }}
        aria-expanded={open}
        aria-controls={id}
        className={`flex items-center gap-3 rounded-lg py-2 pl-3 pr-3 text-base ${inside ? "text-text" : "text-muted hover:text-text"}`}
      >
        <NavIcon name={group.icon} />
        {t(group.key)}
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden
             className={`ml-auto transition-transform ${open ? "rotate-180" : ""}`}>
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div id={id} className="flex flex-col gap-1" onClick={onChoose}>
          {group.items.map((i) => link(i, true))}
        </div>
      )}
    </>
  );
}
