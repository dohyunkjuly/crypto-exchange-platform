import type { MarketSymbol, Ticker } from "./api";

/** "btc", "BTC/USDT" and "btcusdt" all find BTCUSDT. */
export function matchPairs(markets: MarketSymbol[], query: string): MarketSymbol[] {
  const q = query.toUpperCase().replace(/[\s/-]/g, "");
  return q ? markets.filter((m) => m.symbol.includes(q)) : markets;
}

export type PairSort = "turnover" | "price" | "change";

/**
 * The pair picker's rows: markets with their tickers, narrowed by search, quote asset and
 * favourites, then sorted. Comparison is on the display strings through parseFloat, which is
 * allowed for rendering order only (AGENTS.md): nothing here is summed, compared for money or sent.
 */
export function rankPairs(markets: MarketSymbol[], tickers: Ticker[], opts: {
  query: string; quote: string | null; favorites: Set<string>; onlyFavorites: boolean; sort: PairSort; desc: boolean;
}): { market: MarketSymbol; ticker: Ticker | undefined }[] {
  const byId = new Map(tickers.map((t) => [t.symbol, t]));
  const key = (t: Ticker | undefined) => parseFloat(
    !t ? "0" : opts.sort === "turnover" ? t.quoteVolume : opts.sort === "price" ? t.lastPrice : t.priceChangePercent);
  return matchPairs(markets, opts.query)
    .filter((m) => (!opts.quote || m.quoteAsset === opts.quote) && (!opts.onlyFavorites || opts.favorites.has(m.symbol)))
    .map((market) => ({ market, ticker: byId.get(market.symbol) }))
    .sort((a, b) => (opts.desc ? key(b.ticker) - key(a.ticker) : key(a.ticker) - key(b.ticker)) || a.market.symbol.localeCompare(b.market.symbol));
}
