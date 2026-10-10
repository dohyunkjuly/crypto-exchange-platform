"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { useT } from "@/lib/i18n";
import { FunnelIcon } from "./filters";

/**
 * Filters applied on Search rather than as they change, for the sub-account lists. A filter
 * that applies itself makes Search a lie, and these lists are read with a key in hand.
 *
 * `children` are the controls; `actions` is the row's right-hand end -- the count, and whatever
 * that screen acts with. A sheet over the page on a phone, the row above the table on a
 * desktop: one element either way, so the controls are never two inputs with the same label in
 * one document.
 */
export function SearchFilters({ onSearch, onReset, children, actions }: {
  onSearch: () => void; onReset: () => void; children: React.ReactNode; actions?: React.ReactNode;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);

  return (
    <div className="flex flex-wrap items-end gap-3">
      <button type="button" onClick={() => setOpen(true)} aria-expanded={open}
              aria-controls="sub-account-filters" aria-label={t("subAccounts.filters")}
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border text-muted sm:hidden">
        <FunnelIcon />
      </button>

      {open && <div className="fixed inset-0 z-40 bg-black/60 sm:hidden" onClick={() => setOpen(false)} />}
      <div id="sub-account-filters"
           className={`${open ? "fixed inset-x-0 bottom-0 z-50 flex max-h-[85vh] flex-col gap-5 overflow-auto rounded-t-2xl border-t border-border bg-surface p-5" : "hidden"}
                       sm:static sm:z-auto sm:flex sm:max-h-none sm:flex-row sm:flex-wrap sm:items-end sm:gap-3 sm:overflow-visible sm:rounded-none sm:border-0 sm:bg-transparent sm:p-0
                       [&_input]:w-full [&_select]:w-full sm:[&_input]:w-auto sm:[&_select]:w-auto`}>
        <div className="flex items-center justify-between sm:hidden">
          <h2 className="text-lg font-semibold">{t("subAccounts.filters")}</h2>
          <button type="button" aria-label={t("common.close")} onClick={() => setOpen(false)} className="text-muted hover:text-text">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>

        {children}

        <div className="flex gap-3 pt-1 sm:gap-2 sm:pt-0">
          <Button variant="secondary" className="h-11 flex-1 sm:h-9 sm:flex-none" onClick={onReset}>
            {t("filters.reset")}
          </Button>
          <Button className="h-11 flex-1 sm:h-9 sm:flex-none" onClick={() => { onSearch(); setOpen(false); }}>
            {t("subAccounts.search")}
          </Button>
        </div>
      </div>

      {actions && <div className="ml-auto flex items-center gap-4">{actions}</div>}
    </div>
  );
}
