"use client";

import { useEffect, useRef, useState } from "react";
import { LocaleLink } from "@/lib/i18n/LocaleLink";

/**
 * A trigger and a panel beneath it, with `role="menu"` applied to the panel.
 *
 * `openOn="click"` toggles, and closes on outside click or Escape. `openOn="hover"` opens
 * while the pointer or keyboard focus is inside, so the trigger can also be a link that
 * navigates on click -- the account icon: hover for the menu, click for the dashboard.
 * The panel is positioned flush against the trigger (its gap is inside it) so the pointer
 * never leaves the wrapper on the way down.
 */
export function Menu({
  label, trigger, children, align = "right", openOn = "click", href, wide = false,
}: {
  label: string;
  trigger: (open: boolean) => React.ReactNode;
  children: (close: () => void) => React.ReactNode;
  align?: "left" | "right";
  openOn?: "click" | "hover";
  /** With a hover menu: where the trigger itself navigates to. */
  href?: string;
  /** A navigation panel rather than a short list: most of a phone's width, roomy on a desktop. */
  wide?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const close = () => setOpen(false);

  // Hover menus close on a short grace period, cancelled by re-entry. A pointer crossing the
  // panel's edge -- or a synthetic leave fired on the way to a row -- must not slam it shut.
  const cancelClose = () => { if (closeTimer.current) { clearTimeout(closeTimer.current); closeTimer.current = null; } };
  const scheduleClose = () => { cancelClose(); closeTimer.current = setTimeout(() => setOpen(false), 120); };
  useEffect(() => cancelClose, []);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("mousedown", onDown); document.removeEventListener("keydown", onKey); };
  }, [open]);

  const hover = openOn === "hover";
  const triggerClass = "inline-flex h-9 min-w-9 items-center justify-center rounded-lg px-1.5 text-muted hover:bg-surface-raised hover:text-text aria-expanded:text-text";
  const a11y = { "aria-label": label, "aria-haspopup": "menu" as const, "aria-expanded": open };

  return (
    <div
      ref={root}
      className="relative"
      onMouseEnter={hover ? () => { cancelClose(); setOpen(true); } : undefined}
      onMouseLeave={hover ? scheduleClose : undefined}
      onFocus={hover ? () => { cancelClose(); setOpen(true); } : undefined}
      // After focus has settled, not from relatedTarget: browsers set it, jsdom does not.
      onBlur={hover ? () => setTimeout(() => { if (!root.current?.contains(document.activeElement)) scheduleClose(); }, 0) : undefined}
    >
      {hover && href ? (
        <LocaleLink href={href} {...a11y} className={triggerClass}>{trigger(open)}</LocaleLink>
      ) : (
        <button type="button" onClick={() => setOpen((o) => !o)} {...a11y} className={triggerClass}>{trigger(open)}</button>
      )}
      {open && (
        <div className={`absolute top-full z-20 pt-1 ${align === "right" ? "right-0" : "left-0"}`}>
          <div role="menu" className={`overflow-hidden rounded-lg border border-border bg-surface py-1.5 shadow-lg ${wide ? "w-[min(20rem,calc(100vw-1.5rem))]" : "min-w-52"}`}>
            {children(close)}
          </div>
        </div>
      )}
    </div>
  );
}

/** One row. `role` defaults to menuitem; a radio-style row passes menuitemradio + checked. */
export function MenuItem({
  onSelect, children, role = "menuitem", checked, tone = "text", icon, trailing, nested = false, ...rest
}: {
  onSelect: () => void; children: React.ReactNode;
  role?: "menuitem" | "menuitemradio"; checked?: boolean; tone?: "text" | "muted" | "danger";
  /** Leading glyph. With one the row is a navigation entry and reads at the page's size. */
  icon?: React.ReactNode;
  /** At the far end: a chevron on a section that opens in place. */
  trailing?: React.ReactNode;
  /** Inside such a section: indented to the width of the icons above it, and quieter. */
  nested?: boolean;
} & React.AriaAttributes) {
  const color = tone === "danger" ? "text-danger" : tone === "muted" ? "text-muted" : "text-text";
  const big = icon || trailing || nested;
  return (
    <button type="button" role={role} aria-checked={role === "menuitemradio" ? checked : undefined} onClick={onSelect}
            {...rest}
            className={`flex w-full items-center gap-3 text-left hover:bg-surface-raised
              ${big ? "px-3 py-2.5 text-base" : "justify-between gap-6 px-3 py-2 text-sm"}
              ${nested ? "pl-12 text-muted hover:text-text" : ""} ${color}`}>
      {icon}
      <span className="flex-1">{children}</span>
      {trailing}
    </button>
  );
}

export function MenuDivider() {
  return <div role="separator" className="my-1 border-t border-border" />;
}
