"use client";

import { useEffect, useId, useRef, useState } from "react";
import { CoinIcon } from "@/components/ui/CoinIcon";
import type { MarketSymbol } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { matchPairs } from "@/features/markets/pairs";
import { clampRange, dayOf, lastDays, type DayRange } from "./filterMath";

/** Inputs sit on surface-raised (docs/STYLE.md "Surfaces"). */
const control = "h-9 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong";
/** The trading panel's size: shorter, smaller type. */
const small = "h-7 rounded-md border border-border bg-surface-raised px-2 text-xs text-text outline-none focus:border-border-strong";

/** Today as a calendar day, fixed for the component's life so render stays pure. */
export function useToday(): string {
  const [today] = useState(() => dayOf(new Date()));
  return today;
}

/**
 * The filters: a row above the table on a desktop, a sheet over the page on a phone, behind
 * their own icon. Four controls across a narrow screen push the rows anybody came for below
 * the fold, and a sideways scroll hides them instead.
 *
 * One element either way, restyled at `sm`: two copies would mean two controls with the same
 * label in one document, which is a trap for a screen reader and for autofill. `compact` is
 * the trading screen's own bar, which has neither the width nor the need for a sheet.
 */
export function FilterBar({ children, onReset, compact = false }: { children: React.ReactNode; onReset: () => void; compact?: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const id = useId();

  if (compact) {
    return (
      <div className="flex flex-wrap items-end gap-2">
        {children}
        <button type="button" onClick={onReset} className="h-7 px-1.5 text-xs text-muted hover:text-text">{t("filters.reset")}</button>
      </div>
    );
  }
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} aria-expanded={open} aria-controls={id} aria-label={t("filters.title")}
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center self-start rounded-lg border border-border text-muted sm:hidden">
        <FunnelIcon />
      </button>
      {open && <div className="fixed inset-0 z-40 bg-black/60 sm:hidden" onClick={() => setOpen(false)} />}
      <div id={id}
           className={`${open ? "fixed inset-x-0 bottom-0 z-50 flex max-h-[85vh] flex-col gap-5 overflow-auto rounded-t-2xl border-t border-border bg-surface p-5" : "hidden"}
                       sm:static sm:z-auto sm:flex sm:max-h-none sm:flex-row sm:flex-wrap sm:items-end sm:gap-3 sm:overflow-visible sm:rounded-none sm:border-0 sm:bg-transparent sm:p-0
                       [&>*]:w-full sm:[&>*]:w-auto [&_select]:w-full sm:[&_select]:w-auto`}>
        <div className="flex items-center justify-between sm:hidden">
          <h2 className="text-lg font-semibold">{t("filters.title")}</h2>
          <button type="button" aria-label={t("common.close")} onClick={() => setOpen(false)} className="text-muted hover:text-text">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
        {children}
        <div className="flex gap-3 pt-1 sm:w-auto sm:gap-2 sm:pt-0">
          {/* The filters are live, so this only puts the sheet away -- but a sheet with no way
              out but the corner is a trap on a phone. */}
          <button type="button" onClick={onReset}
                  className="h-11 flex-1 rounded-lg border border-border text-base text-muted hover:text-text sm:h-9 sm:flex-none sm:rounded-none sm:border-0 sm:px-2">
            {t("filters.reset")}
          </button>
          <button type="button" onClick={() => setOpen(false)}
                  className="h-11 flex-1 rounded-lg bg-text text-base font-medium text-bg hover:opacity-85 sm:hidden">
            {t("filters.search")}
          </button>
        </div>
      </div>
    </>
  );
}

/** Shared with the account pages' own filter bars. */
export function FunnelIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 5h18l-7 8v6l-4 2v-8z" />
    </svg>
  );
}

function Labeled({ label, children, compact = false }: { label: string; children: React.ReactNode; compact?: boolean }) {
  // Compact drops the caption: the select's label attribute still names it for assistive tech.
  return <div className="flex flex-col gap-1">{!compact && <span className="text-sm text-muted">{label}</span>}{children}</div>;
}

export function SelectFilter<V extends string>({ label, value, options, onChange, compact = false, disabled = false }: {
  label: string; value: V; options: { value: V; label: string }[]; onChange: (v: V) => void; compact?: boolean; disabled?: boolean;
}) {
  return (
    <Labeled label={label} compact={compact}>
      <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value as V)} disabled={disabled}
              className={`${compact ? small : control} pr-8 disabled:cursor-not-allowed disabled:text-dim`}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </Labeled>
  );
}

/** Free text, for a filter nobody can offer a list for -- a label somebody typed, a key. */
export function TextFilter({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <Labeled label={label}>
      <input aria-label={label} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
             className={`${control} placeholder:text-dim`} />
    </Labeled>
  );
}

/** A pair dropdown with a search box; "" is every pair. */
export function PairPicker({ value, onChange, markets }: { value: string; onChange: (symbol: string) => void; markets: MarketSymbol[] }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const matches = matchPairs(markets, query);
  const current = markets.find((m) => m.symbol === value);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("mousedown", onDown); document.removeEventListener("keydown", onKey); };
  }, [open]);

  const pick = (symbol: string) => { onChange(symbol); setOpen(false); setQuery(""); };

  return (
    <Labeled label={t("filters.pair")}>
      <div ref={root} className="relative">
        <button type="button" aria-label={t("filters.pair")} aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen((o) => !o)}
                className={`${control} flex min-w-36 items-center justify-between gap-2`}>
          <span className="flex items-center gap-2">
            {current && <CoinIcon symbol={current.baseAsset} size={16} />}
            {current ? `${current.baseAsset}/${current.quoteAsset}` : value || t("filters.all")}
          </span>
          <Chevron />
        </button>
        {open && (
          <div className="absolute left-0 top-full z-20 mt-1 w-64 rounded-lg border border-border bg-surface p-2 shadow-lg">
            <div className="flex h-9 items-center gap-2 rounded-lg border border-border bg-surface-raised px-2 focus-within:border-border-strong">
              <SearchIcon />
              <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("filters.searchPair")} aria-label={t("filters.searchPair")}
                     onKeyDown={(e) => { if (e.key === "Enter" && matches[0]) pick(matches[0].symbol); }}
                     className="w-full bg-transparent text-base text-text outline-none placeholder:text-dim" />
            </div>
            <ul role="listbox" aria-label={t("filters.pair")} className="scroll-slim mt-2 max-h-64 overflow-y-auto">
              {!query && <PairOption selected={!value} onClick={() => pick("")}>{t("filters.all")}</PairOption>}
              {matches.map((m) => (
                <PairOption key={m.symbol} selected={value === m.symbol} onClick={() => pick(m.symbol)}>
                  <CoinIcon symbol={m.baseAsset} size={16} />
                  <span className="font-medium">{m.baseAsset}</span>
                  <span className="text-dim">/ {m.quoteAsset}</span>
                </PairOption>
              ))}
              {matches.length === 0 && <li className="px-2 py-3 text-center text-base text-dim">{t("filters.noMatch")}</li>}
            </ul>
          </div>
        )}
      </div>
    </Labeled>
  );
}

function PairOption({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <li role="option" aria-selected={selected}>
      <button type="button" onClick={onClick}
              className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-base hover:bg-surface-raised ${selected ? "text-text" : "text-muted"}`}>
        {children}
      </button>
    </li>
  );
}

const PRESETS = [{ days: 1, key: "filters.d1" }, { days: 7, key: "filters.w1" }, { days: 30, key: "filters.m1" }, { days: 90, key: "filters.m3" }] as const;

/** Presets plus two date inputs; the range can never exceed 90 days. */
export function DateRangeFilter({ value, onChange, today, compact = false }: { value: DayRange; onChange: (r: DayRange) => void; today: string; compact?: boolean }) {
  const t = useT();
  const set = (moved: "from" | "to", day: string) => { if (day) onChange(clampRange({ ...value, [moved]: day }, moved, today)); };
  const box = compact ? small : control;
  return (
    <Labeled label={`${t("filters.date")} · ${t("filters.rangeHint")}`} compact={compact}>
      <div className={`flex flex-wrap items-center ${compact ? "gap-1.5" : "gap-2"}`}>
        <div role="group" aria-label={t("filters.date")} className="flex items-center gap-1">
          {PRESETS.map((p) => {
            const r = lastDays(p.days, today);
            const on = r.from === value.from && r.to === value.to;
            return (
              <button key={p.days} type="button" aria-pressed={on} onClick={() => onChange(r)}
                      className={`${compact ? "h-7 rounded-md px-2 text-xs" : "h-9 rounded-lg px-2.5 text-base"} ${on ? "bg-surface-raised text-text" : "text-muted hover:text-text"}`}>{t(p.key)}</button>
            );
          })}
        </div>
        {compact && <span className="text-xs text-muted">{t("filters.time")}</span>}
        <input type="date" aria-label={t("filters.from")} value={value.from} max={today} onChange={(e) => set("from", e.target.value)} className={box} />
        <span className="text-dim">→</span>
        <input type="date" aria-label={t("filters.to")} value={value.to} max={today} onChange={(e) => set("to", e.target.value)} className={box} />
      </div>
    </Labeled>
  );
}

function SearchIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden className="shrink-0 text-dim">
      <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
    </svg>
  );
}

function Chevron() {
  return (
    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden className="text-dim">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
