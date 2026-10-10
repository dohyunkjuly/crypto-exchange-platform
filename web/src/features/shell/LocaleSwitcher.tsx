"use client";

import { usePathname, useRouter } from "next/navigation";
import { Menu, MenuItem } from "@/components/ui/Menu";
import { useLocale, useT } from "@/lib/i18n";
import { LOCALES, LOCALE_LABELS, swapLocale, type Locale } from "@/lib/i18n/locales";
import { rememberLocale } from "@/lib/i18n/cookie";

export function LocaleSwitcher() {
  const t = useT();
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();

  const choose = (next: Locale) => {
    if (next === locale) return;
    rememberLocale(next);
    router.replace(swapLocale(pathname, next));
  };

  return (
    <Menu label={t("header.language")} trigger={() => <Globe />}>
      {(close) => LOCALES.map((l) => (
        <MenuItem key={l} role="menuitemradio" checked={l === locale} tone={l === locale ? "text" : "muted"}
                  onSelect={() => { close(); choose(l); }}>
          {LOCALE_LABELS[l]}
          {l === locale && <Check />}
        </MenuItem>
      ))}
    </Menu>
  );
}

function Globe() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
    </svg>
  );
}
export function Check() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 12l5 5L20 7" />
    </svg>
  );
}
