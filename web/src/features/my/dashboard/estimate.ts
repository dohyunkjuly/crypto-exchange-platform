import { add, compare, multiply } from "@/lib/money";
import type { Balance } from "../shared/api";
import type { Ticker } from "@/features/markets/api";

export const QUOTE = "USDT";

/** Last price by market, for the valuations below; built once per render, not per row. */
export const lastPrices = (tickers: Ticker[]) => new Map(tickers.map((t) => [t.symbol, t.lastPrice]));

/** What an amount is worth in USDT, or null when no market prices it. */
export function valueOf(asset: string, amount: string, last: Map<string, string>): string | null {
  if (asset === QUOTE) {
    return amount;
  }
  const price = last.get(`${asset}${QUOTE}`);
  return price ? multiply(amount, price) : null;
}

/**
 * Sum of holdings valued in USDT at each market's last price. Exact string arithmetic, no
 * float (AGENTS.md). An asset with no USDT market is skipped and reported, not silently
 * valued at zero -- a number that is quietly wrong is worse than one that says what it missed.
 */
export function estimateTotal(balances: Balance[], tickers: Ticker[]): { total: string; skipped: string[] } {
  const last = lastPrices(tickers);
  let total = "0";
  const skipped: string[] = [];
  for (const b of balances) {
    const value = valueOf(b.asset, b.total, last);
    if (value === null) { skipped.push(b.asset); continue; }
    total = add(total, value);
  }
  return { total, skipped };
}

export type Holding = {
  asset: string;
  name: string;
  /** Everything held, spendable or locked behind an order. */
  total: string;
  decimals: number;
  /** Null for an asset with no USDT market: it is real, we just cannot price it. */
  price: string | null;
  value: string | null;
  changePercent: string | null;
  /** The market to trade it on, for the row's link. Null for the quote asset itself. */
  symbol: string | null;
};

/**
 * What the account actually holds, most valuable first, for the dashboard.
 *
 * <p>Zero balances are dropped: every account carries a row per listed asset once it has
 * touched one, and a page of zeros says nothing. An asset we cannot price still appears --
 * hiding someone's holding because no market exists for it would be worse than showing it
 * without a value.
 */
export function holdings(
  balances: { asset: string; total: string }[],
  assets: { symbol: string; name: string; displayDecimals: number }[],
  tickers: Ticker[],
): Holding[] {
  const meta = new Map(assets.map((a) => [a.symbol, a]));
  const byMarket = new Map(tickers.map((tk) => [tk.symbol, tk]));
  const rows = balances
    .filter((b) => compare(b.total, "0") > 0)
    .map((b): Holding => {
      const market = b.asset === QUOTE ? null : `${b.asset}${QUOTE}`;
      const ticker = market ? byMarket.get(market) : undefined;
      const price = b.asset === QUOTE ? "1" : ticker?.lastPrice ?? null;
      return {
        asset: b.asset,
        name: meta.get(b.asset)?.name ?? b.asset,
        total: b.total,
        decimals: meta.get(b.asset)?.displayDecimals ?? 8,
        price,
        value: price ? multiply(b.total, price) : null,
        changePercent: ticker?.priceChangePercent ?? null,
        symbol: ticker ? market : null,
      };
    });
  // Priced first, biggest holding at the top; the rest keep a stable alphabetical order so
  // the list does not reshuffle on every ticker poll.
  return rows.sort((a, b) =>
    a.value && b.value ? compare(b.value, a.value) : a.value ? -1 : b.value ? 1 : a.asset.localeCompare(b.asset));
}
