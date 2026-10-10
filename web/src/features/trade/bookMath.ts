import type { DepthLevel, PublicTrade } from "@/features/markets/api";
import { add, compare } from "@/lib/money";

export type BookRow = { price: string; quantity: string; total: string; /** 0..1 of the side's total, for the depth bar */ share: number };

/**
 * Cumulative totals from the top of one side, plus each row's share of the side's total for its
 * depth bar. Exact string arithmetic for the totals; the share is a render-only ratio.
 */
export function cumulative(levels: DepthLevel[]): BookRow[] {
  let running = "0";
  const rows = levels.map((l) => { running = add(running, l.quantity); return { price: l.price, quantity: l.quantity, total: running }; });
  const grand = parseFloat(running) || 1;
  return rows.map((r) => ({ ...r, share: parseFloat(r.total) / grand }));
}

/** The buy/sell ratio bar: bids' share of the top-of-book quantity on both sides, 0..1. Render only. */
export function bidShare(bids: DepthLevel[], asks: DepthLevel[]): number {
  const sum = (ls: DepthLevel[]) => ls.reduce((acc, l) => add(acc, l.quantity), "0");
  const b = parseFloat(sum(bids)); const a = parseFloat(sum(asks));
  return b + a === 0 ? 0.5 : b / (b + a);
}

/** Which way the last price moved against the trade before it: for the arrow beside it. */
export function lastDirection(trades: PublicTrade[]): "up" | "down" | "flat" {
  if (trades.length < 2) return "flat";
  const c = compare(trades[0].price, trades[1].price);
  return c > 0 ? "up" : c < 0 ? "down" : "flat";
}

/** Newest first, capped, and never the same trade twice: a frame can repeat what the REST snapshot had. */
export function prependTrade(trades: PublicTrade[], trade: PublicTrade, cap: number): PublicTrade[] {
  if (trades.some((t) => t.sequence === trade.sequence)) return trades;
  return [trade, ...trades].slice(0, cap);
}
