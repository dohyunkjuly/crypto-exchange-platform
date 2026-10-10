"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Menu, MenuDivider, MenuItem } from "@/components/ui/Menu";
import { NavIcon } from "@/components/ui/NavIcon";
import { useLogout } from "@/features/auth/session";
import type { User } from "@/features/auth/api";
import { GROUPS, TOP } from "@/features/my/shared/nav";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";

/**
 * The signed-in account: the same sections as the rail, with the same icons, so the two read as
 * one navigation. Groups open in place rather than listing eleven rows flat. No address
 * anywhere -- it is an identifier, not a greeting, and a shoulder-surfer should not read it off
 * a header.
 *
 * On a mouse the icon is the dashboard link and hovering opens the menu. On a
 * touch screen there is no hover, so it is a button that opens the menu: a tap that silently
 * navigated would put the menu out of reach entirely.
 */
export function UserMenu({ user }: { user: User }) {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const logout = useLogout();
  const touch = useCoarsePointer();
  const go = (path: string) => router.push(localePath(locale, path));

  return (
    <Menu label={t("header.account")} trigger={() => <NavIcon name="account" size={20} />}
          openOn={touch ? "click" : "hover"} href={touch ? undefined : "/my/dashboard"} wide>
      {(close) => (
        <>
          {!user.emailVerified && (
            <>
              <MenuItem icon={<NavIcon name="verify" />} onSelect={() => { close(); go("/verify-email"); }} tone="danger">
                {t("header.verifyEmail")}
              </MenuItem>
              <MenuDivider />
            </>
          )}
          {TOP.map((i) => (
            <MenuItem key={i.href} icon={<NavIcon name={i.icon} />} onSelect={() => { close(); go(i.href); }}>
              {t(i.key)}
            </MenuItem>
          ))}
          {/* Copy trading is its own section, not an account page; one way in from here. */}
          <MenuItem icon={<NavIcon name="copy" />} onSelect={() => { close(); go("/copy/mine"); }}>{t("menu.copy")}</MenuItem>
          {GROUPS.map((g) => (
            <Group key={g.id} id={g.id} label={t(g.key)} icon={<NavIcon name={g.icon} />}>
              {g.items.map((i) => (
                <MenuItem key={i.href} nested onSelect={() => { close(); go(i.href); }}>{t(i.key)}</MenuItem>
              ))}
            </Group>
          ))}
          <MenuDivider />
          <MenuItem icon={<NavIcon name="signOut" />} tone="muted"
                    onSelect={() => { close(); logout.mutateAsync().finally(() => router.replace(localePath(locale, "/login"))); }}>
            {t("header.signOut")}
          </MenuItem>
        </>
      )}
    </Menu>
  );
}

/** A section that opens where it stands, so the menu is a page of headings until asked. */
function Group({ id, label, icon, children }: { id: string; label: string; icon: React.ReactNode; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <MenuItem icon={icon} aria-expanded={open} aria-controls={`menu-${id}`} onSelect={() => setOpen((o) => !o)}
                trailing={
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                       strokeLinecap="round" strokeLinejoin="round" aria-hidden
                       className={`text-muted transition-transform ${open ? "rotate-180" : ""}`}>
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                }>
        {label}
      </MenuItem>
      {open && <div id={`menu-${id}`}>{children}</div>}
    </>
  );
}

/**
 * Whether the pointer is a finger. Answered after mount, not during render: the server has no
 * pointer, and guessing one would hydrate into the wrong trigger.
 */
function useCoarsePointer(): boolean {
  const [coarse, setCoarse] = useState(false);
  useEffect(() => {
    // Absent in jsdom, and in a browser old enough not to have it the mouse is the safe guess.
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(pointer: coarse)");
    const read = () => setCoarse(query.matches);
    read();
    query.addEventListener("change", read);
    return () => query.removeEventListener("change", read);
  }, []);
  return coarse;
}
