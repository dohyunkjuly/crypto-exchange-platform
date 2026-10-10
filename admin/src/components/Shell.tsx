"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { EXCHANGE_NAME } from "@/lib/env";
import { logout, useAdmin, type Admin } from "@/lib/session";
import { Icon, type IconName } from "./Icon";

type Section = { href: string; label: string; icon: IconName; least: Admin["role"] };

/**
 * What an admin may open, grouped the way the work is. A role is a floor, not a set of
 * permissions to compose (docs/reference/admin.md), so a section names the least role that may
 * see it. Hiding a link is a courtesy; admin-bff refuses the call either way. A section appears
 * here when its page exists -- a link to nothing is worse than no link.
 */
const GROUPS: { name: string; sections: Section[] }[] = [
  { name: "Overview", sections: [{ href: "/dashboard", label: "Dashboard", icon: "home", least: "VIEWER" }] },
  { name: "Money", sections: [
    { href: "/treasury", label: "Treasury", icon: "wallet", least: "VIEWER" },
    { href: "/ledger", label: "Ledger", icon: "ledger", least: "VIEWER" },
    { href: "/fees", label: "Fees", icon: "percent", least: "VIEWER" },
  ] },
  { name: "Trading", sections: [
    { href: "/markets", label: "Markets", icon: "markets", least: "VIEWER" },
    { href: "/orders", label: "Orders", icon: "orders", least: "VIEWER" },
    { href: "/trades", label: "Trades", icon: "trades", least: "VIEWER" },
    { href: "/copy/settings", label: "Copy settings", icon: "copy", least: "VIEWER" },
  ] },
  { name: "People", sections: [
    { href: "/users", label: "Customers", icon: "users", least: "VIEWER" },
    { href: "/kyc", label: "KYC review", icon: "id", least: "VIEWER" },
    { href: "/copy/leaders", label: "Copy leaders", icon: "copy", least: "VIEWER" },
  ] },
  { name: "System", sections: [
    { href: "/audit", label: "Audit log", icon: "clock", least: "VIEWER" },
    { href: "/admins", label: "Admins", icon: "shield", least: "SUPERADMIN" },
  ] },
];
const RANK = { VIEWER: 0, OPERATOR: 1, SUPERADMIN: 2 };

/**
 * Every operated screen sits in here: where you are, what you may open, a way to jump straight to
 * a customer, and a way out.
 *
 * <p>Renders nothing until admin-bff has said who this is. A staff screen that paints first and
 * redirects afterwards shows the shape of the exchange to whoever has the tab open.
 *
 * @param title the page's heading; omitted where the page's own content is the heading (a
 *              customer file leads with the customer)
 * @param actions the page's buttons, on the heading's row
 */
export function Shell({ title, actions, children }: {
  title?: string; actions?: React.ReactNode; children: React.ReactNode;
}) {
  const admin = useAdmin();
  const pathname = usePathname();
  const [menu, setMenu] = useState(false);

  useEffect(() => {
    if (!menu) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setMenu(false); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menu]);

  if (!admin) return null;

  const groups = GROUPS
    .map((g) => ({ ...g, sections: g.sections.filter((s) => RANK[admin.role] >= RANK[s.least]) }))
    .filter((g) => g.sections.length > 0);
  const current = groups.flatMap((g) => g.sections.map((s) => ({ group: g.name, ...s })))
    .find((s) => pathname === s.href || pathname.startsWith(`${s.href}/`));
  const sections = <Sections groups={groups} current={current?.href} admin={admin} onNavigate={() => setMenu(false)} />;

  return (
    <div className="flex-1 flex min-h-screen">
      <nav aria-label="Sections" className="hidden md:flex w-60 shrink-0 border-r border-border bg-bg px-3 py-3 flex-col">
        {sections}
      </nav>
      {/* On a phone the sections are a drawer: a sidebar would take half the screen from every page. */}
      {menu && (
        <div className="md:hidden fixed inset-0 z-40 flex">
          <div aria-hidden className="absolute inset-0 bg-[rgba(31,35,40,0.35)]" onClick={() => setMenu(false)} />
          <nav aria-label="Sections" className="relative w-64 max-w-[80vw] h-full overflow-y-auto border-r border-border bg-bg px-3 py-3 flex flex-col">
            {sections}
          </nav>
        </div>
      )}

      <div className="flex-1 min-w-0 flex flex-col">
        <header className="h-14 shrink-0 px-4 md:px-6 flex items-center gap-3 md:gap-4 border-b border-border bg-surface">
          <button type="button" className="btn px-2 md:hidden" aria-label="Open menu" aria-expanded={menu} onClick={() => setMenu(true)}>
            <Icon name="menu" />
          </button>
          <div className="hidden md:flex flex-1 min-w-0">
            <Crumbs group={current?.group} section={current} deeper={!!current && pathname !== current.href} />
          </div>
          <JumpBox />
        </header>
        <main className="flex-1 min-h-0 overflow-auto px-4 py-4 md:px-6 md:py-6 flex flex-col gap-4 md:gap-5">
          {(title || actions) && (
            <div className="flex flex-wrap items-start justify-between gap-3 md:gap-4">
              {title && <h1 className="m-0 text-xl md:text-2xl font-semibold tracking-tight">{title}</h1>}
              {actions && <div className="flex items-center gap-2">{actions}</div>}
            </div>
          )}
          {children}
        </main>
      </div>
    </div>
  );
}

/** The sidebar's contents, the same in the sidebar and in the phone's drawer. */
function Sections({ groups, current, admin, onNavigate }: {
  groups: { name: string; sections: Section[] }[]; current?: string; admin: Admin; onNavigate: () => void;
}) {
  return (
    <>
      <Link href="/" onClick={onNavigate} className="px-2.5 pt-1.5 pb-4 text-[15px] font-semibold text-text no-underline">{EXCHANGE_NAME}</Link>
      {groups.map((g) => (
        <div key={g.name} className="mb-2">
          <div className="px-2.5 pt-2 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted">{g.name}</div>
          <ul className="flex flex-col gap-0.5">
            {g.sections.map((s) => {
              const active = current === s.href;
              return (
                <li key={s.href}>
                  <Link href={s.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
                        className={`flex items-center gap-2.5 h-8 px-2.5 rounded-md text-[14px] no-underline ${
                          active ? "bg-surface border border-border font-semibold text-text" : "text-text hover:bg-[#eaeef2]"}`}>
                    <Icon name={s.icon} className={active ? "text-text" : "text-muted"} />
                    {s.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
      <div className="flex-1" />
      <Account admin={admin} />
    </>
  );
}

function Crumbs({ group, section, deeper }: { group?: string; section?: Section; deeper: boolean }) {
  if (!section) return null;
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 text-[14px]">
      <span className="text-muted">{group}</span>
      <span className="text-dim">/</span>
      {deeper
        ? <Link href={section.href} className="text-muted hover:text-accent">{section.label}</Link>
        : <span className="font-semibold">{section.label}</span>}
    </nav>
  );
}

/**
 * Straight to a customer from anywhere: an id opens their file, anything else searches. The
 * console's most frequent question is "this one person", and it should not start with a menu.
 * "/" focuses it, as it does in most tools staff already use.
 */
function JumpBox() {
  const router = useRouter();
  const [value, setValue] = useState("");
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT" || target.isContentEditable);
      if (e.key === "/" && !typing) {
        e.preventDefault();
        input.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <form role="search" className="flex-1 min-w-0 md:flex-none md:w-[400px]"
          onSubmit={(e) => {
            e.preventDefault();
            const q = value.trim();
            if (!q) return;
            router.push(/^\d{1,18}$/.test(q) ? `/users/${q}` : `/users?q=${encodeURIComponent(q)}`);
            setValue("");
            input.current?.blur();
          }}>
      <label className="flex items-center gap-2 h-8 px-2.5 rounded-md border border-border bg-bg text-muted focus-within:border-accent focus-within:bg-surface">
        <Icon name="search" />
        <input ref={input} aria-label="Find a customer" placeholder="Find a customer by email or ID"
               value={value} onChange={(e) => setValue(e.target.value)}
               className="flex-1 min-w-0 bg-transparent border-0 outline-none text-[14px] text-text placeholder:text-dim" />
        <kbd className="hidden md:inline font-mono text-[11px] leading-none px-1.5 py-1 rounded border border-border border-b-2 bg-surface text-muted">/</kbd>
      </label>
    </form>
  );
}

function Account({ admin }: { admin: Admin }) {
  const router = useRouter();
  const initials = admin.email.slice(0, 2).toUpperCase();
  return (
    <div className="border-t border-border pt-3 px-2.5 flex items-center gap-2.5">
      <span aria-hidden className="w-7 h-7 shrink-0 rounded-full bg-[#ddf4ff] text-accent flex items-center justify-center text-[11px] font-semibold">{initials}</span>
      <span className="flex-1 min-w-0 flex flex-col leading-tight">
        <span className="truncate text-[13px] font-medium" title={admin.email}>{admin.email}</span>
        <span className="text-[11px] text-muted">{admin.role}</span>
      </span>
      <button className="btn-link text-[12px] text-muted hover:text-text" onClick={() => logout().then(() => router.replace("/login"))}>
        Log out
      </button>
    </div>
  );
}
