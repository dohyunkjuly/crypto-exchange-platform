"use client";

import type { MarketSymbol } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { compact, percent, price } from "@/lib/format";
import { isNegative, isZero, scaleOf } from "@/lib/money";
import { useFavorites } from "./favorites";
import { PairPicker } from "./PairPicker";
import { useTicker } from "./useTicker";

/** The pair, its last price and 24h figures -- live from the ticker channel. */
export function MarketStrip({ market }: { market: MarketSymbol }) {
  const t = useT();
  const ticker = useTicker(market.symbol);
  const [favorites, toggleFavorite] = useFavorites();
  const starred = favorites.has(market.symbol);
  const traded = !!ticker && !isZero(ticker.lastPrice);
  const tone = !traded || isZero(ticker!.priceChangePercent) ? "text-muted" : isNegative(ticker!.priceChangePercent) ? "text-down" : "text-up";
  const d = scaleOf(market.priceTick);
  const px = (v: string) => (traded ? price(v, d) : "—");
  const stat = (label: string, value: string) => (
    <div className="flex flex-col"><span className="text-xs text-muted">{label}</span><span className="num text-sm">{value}</span></div>
  );
  return (
    // Below xl (the layout's own breakpoint) the pair sits on its own row, the price grows and the
    // 24h figures form a 2x2 grid beside it, as a phone header must.
    <div className="relative z-20 shrink-0 border-b border-border px-4 whitespace-nowrap xl:flex xl:h-14 xl:items-center xl:gap-6">
      <div className="flex items-center gap-2 py-2 xl:py-0">
        <PairPicker market={market} />
        <button type="button" aria-label={t(starred ? "trade.unfavorite" : "trade.favorite")} aria-pressed={starred} onClick={() => toggleFavorite(market.symbol)}
                className={`rounded-md p-1 ${starred ? "text-text" : "text-dim hover:text-muted"}`}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill={starred ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden><path d="m12 3 2.7 5.7 6.3.8-4.6 4.3 1.2 6.2L12 17l-5.6 3 1.2-6.2L3 9.5l6.3-.8Z" /></svg>
        </button>
      </div>
      <div className="flex items-end justify-between gap-4 pb-3 xl:contents">
        <div className={`flex flex-col ${tone}`}>
          <span className="num text-2xl font-semibold leading-tight xl:text-base">{px(ticker?.lastPrice ?? "0")}</span>
          <span className="num text-xs">{traded ? `${isNegative(ticker!.priceChange) ? "" : "+"}${price(ticker!.priceChange, d)} (${percent(ticker!.priceChangePercent)})` : t("trade.noTrades")}</span>
        </div>
        <div className="grid grid-cols-2 gap-x-6 gap-y-1 xl:contents">
          {stat(t("markets.low"), px(ticker?.lowPrice ?? "0"))}
          {stat(t("markets.high"), px(ticker?.highPrice ?? "0"))}
          {stat(t("trade.volume", { asset: market.baseAsset }), traded ? compact(ticker!.volume) : "—")}
          {stat(t("trade.turnover", { asset: market.quoteAsset }), traded ? compact(ticker!.quoteVolume) : "—")}
        </div>
      </div>
    </div>
  );
}
