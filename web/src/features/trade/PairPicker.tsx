"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { Menu } from "@/components/ui/Menu";
import { fetchSymbols, fetchTickers, type MarketSymbol } from "@/features/markets/api";
import { rankPairs, type PairSort } from "@/features/markets/pairs";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { compact, percent, price } from "@/lib/format";
import { isNegative, isZero, scaleOf } from "@/lib/money";
import { useFavorites } from "./favorites";

/** The pair menu: search, Favorites | Spot, a quote-asset row, then sortable rows. */
export function PairPicker({ market }: { market: MarketSymbol }) {
  const t = useT();
  return (
    <Menu align="left" label={t("trade.choosePair")} trigger={(open) => (
      <span className="flex items-center gap-2 text-base font-semibold text-text">
        <CoinIcon symbol={market.baseAsset} size={22} />
        {market.baseAsset}/{market.quoteAsset}
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden className={`text-muted transition-transform ${open ? "rotate-180" : ""}`}><path d="m6 9 6 6 6-6" /></svg>
      </span>
    )}>
      {(close) => <PairList current={market.symbol} onPick={close} />}
    </Menu>
  );
}

function PairList({ current, onPick }: { current: string; onPick: () => void }) {
  const t = useT();
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<"favorites" | "spot">("spot");
  const [quote, setQuote] = useState<string | null>(null);
  const [sort, setSort] = useState<{ by: PairSort; desc: boolean }>({ by: "turnover", desc: true });
  const [favorites, toggleFavorite] = useFavorites();
  const symbols = useQuery({ queryKey: ["symbols"], queryFn: ({ signal }) => fetchSymbols(signal), staleTime: 5 * 60_000 });
  const tickers = useQuery({ queryKey: ["tickers"], queryFn: ({ signal }) => fetchTickers(signal), refetchInterval: 5_000 });
  const quotes = [...new Set((symbols.data ?? []).map((m) => m.quoteAsset))].sort();
  const rows = rankPairs(symbols.data ?? [], tickers.data ?? [], { query, quote, favorites, onlyFavorites: tab === "favorites", sort: sort.by, desc: sort.desc });
  const sortBy = (by: PairSort) => setSort((s) => ({ by, desc: s.by === by ? !s.desc : true }));
  const chip = (on: boolean) => `rounded-full px-2.5 py-1 text-xs ${on ? "bg-surface-raised text-text" : "text-muted hover:text-text"}`;
  const head = (label: string, by: PairSort, right = false) => (
    <button type="button" onClick={() => sortBy(by)} className={`flex items-center gap-1 hover:text-text ${right ? "justify-end" : ""} ${sort.by === by ? "text-text" : ""}`}>
      {label}<span aria-hidden className="text-[10px]">{sort.by === by ? (sort.desc ? "▼" : "▲") : "⇅"}</span>
    </button>
  );

  return (
    <div className="flex w-[430px] max-w-[calc(100vw-2rem)] flex-col gap-2 p-3">
      <div className="flex h-9 items-center gap-2 rounded-lg border border-border bg-surface-raised px-2 focus-within:border-border-strong">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden className="text-dim"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
        <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("trade.searchPair")} aria-label={t("trade.searchPair")}
               className="w-full bg-transparent text-sm text-text outline-none placeholder:text-dim" />
      </div>
      <div role="tablist" className="flex gap-4 border-b border-border text-sm">
        {(["favorites", "spot"] as const).map((x) => (
          <button key={x} role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                  className={`-mb-px border-b-2 pb-1.5 ${tab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>{t(x === "spot" ? "trade.spot" : "trade.favorites")}</button>
        ))}
      </div>
      <div className="flex flex-wrap gap-1">
        <button type="button" className={chip(quote === null)} onClick={() => setQuote(null)}>{t("filters.all")}</button>
        {quotes.map((q) => <button key={q} type="button" className={chip(quote === q)} onClick={() => setQuote(q)}>{q}</button>)}
      </div>
      <div className="grid grid-cols-[1fr_auto_auto] gap-x-4 px-1 text-xs text-muted">
        {head(t("trade.nameTurnover"), "turnover")}{head(t("markets.lastPrice"), "price", true)}{head(t("markets.change"), "change", true)}
      </div>
      <ul className="scroll-slim max-h-[60vh] overflow-y-auto">
        {rows.map(({ market: m, ticker: tk }) => {
          const traded = !!tk && !isZero(tk.lastPrice);
          const tone = !traded || isZero(tk!.priceChangePercent) ? "text-muted" : isNegative(tk!.priceChangePercent) ? "text-down" : "text-up";
          const starred = favorites.has(m.symbol);
          return (
            <li key={m.symbol} className={`grid grid-cols-[auto_1fr_auto_auto] items-center gap-x-3 rounded-md px-1 py-1.5 hover:bg-surface-raised ${m.symbol === current ? "bg-surface-raised" : ""}`}>
              <button type="button" aria-label={t(starred ? "trade.unfavorite" : "trade.favorite")} aria-pressed={starred} onClick={() => toggleFavorite(m.symbol)}
                      className={starred ? "text-text" : "text-dim hover:text-muted"}>
                <svg viewBox="0 0 24 24" width="14" height="14" fill={starred ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden><path d="m12 3 2.7 5.7 6.3.8-4.6 4.3 1.2 6.2L12 17l-5.6 3 1.2-6.2L3 9.5l6.3-.8Z" /></svg>
              </button>
              <LocaleLink href={`/trade/${m.symbol}`} onClick={onPick} className="flex min-w-0 items-center gap-2">
                <CoinIcon symbol={m.baseAsset} size={22} />
                <span className="flex min-w-0 flex-col">
                  <span className="text-sm font-medium">{m.baseAsset}/{m.quoteAsset}</span>
                  <span className="truncate text-xs text-muted">{traded ? `${compact(tk!.quoteVolume)} ${m.quoteAsset}` : "—"}</span>
                </span>
              </LocaleLink>
              <span className={`num text-right text-sm ${tone}`}>{traded ? price(tk!.lastPrice, scaleOf(m.priceTick)) : "—"}</span>
              <span className={`num w-16 text-right text-sm ${tone}`}>{traded ? percent(tk!.priceChangePercent) : "—"}</span>
            </li>
          );
        })}
        {rows.length === 0 && <li className="py-6 text-center text-sm text-dim">{t("filters.noMatch")}</li>}
      </ul>
    </div>
  );
}
