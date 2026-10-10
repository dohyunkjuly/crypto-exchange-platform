import type { Order } from "./api";

/** The server's cap (TimeRange.MAX), in calendar days. */
export const MAX_RANGE_DAYS = 90;

/** Local calendar days, YYYY-MM-DD, both ends inclusive -- what a date input holds. */
export type DayRange = { from: string; to: string };

const pad = (n: number) => String(n).padStart(2, "0");
export const dayOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parseDay = (s: string) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
const addDays = (s: string, n: number) => { const d = parseDay(s); d.setDate(d.getDate() + n); return dayOf(d); };

/** The last `days` calendar days, today included. */
export const lastDays = (days: number, today: string): DayRange => ({ from: addDays(today, -(days - 1)), to: today });

/** Keeps a picked range forwards, out of the future and within 90 days by moving the end the user did not touch. */
export function clampRange({ from, to }: DayRange, moved: "from" | "to", today: string): DayRange {
  if (to > today) to = today;
  if (from > today) from = today;
  if (from > to) { if (moved === "from") to = from; else from = to; }
  if (from < addDays(to, -(MAX_RANGE_DAYS - 1))) {
    if (moved === "from") { to = addDays(from, MAX_RANGE_DAYS - 1); if (to > today) to = today; }
    else from = addDays(to, -(MAX_RANGE_DAYS - 1));
  }
  return { from, to };
}

/**
 * Local midnight to the midnight after `to`, as instants. Capped at exactly 90 days: a range
 * spanning a DST change is an hour longer, and the server would refuse it.
 */
export function toInstants({ from, to }: DayRange): { from: string; to: string } {
  const start = parseDay(from).getTime();
  const end = Math.min(parseDay(addDays(to, 1)).getTime(), start + MAX_RANGE_DAYS * 86_400_000);
  return { from: new Date(start).toISOString(), to: new Date(end).toISOString() };
}


/** "" means any. Open orders are one complete list, so they filter here rather than on the server. */
export type OpenFilter = { symbol: string; side: "" | "BUY" | "SELL"; type: string };
export const filterOpen = (orders: Order[], f: OpenFilter) =>
  orders.filter((o) => (!f.symbol || o.symbol === f.symbol) && (!f.side || o.side === f.side) && (!f.type || o.orderType === f.type));

/**
 * Open orders by where they are: resting on the book, or parked waiting for a trigger. A stop
 * that has fired left the trigger book, so it is "book" -- which tab it is in says whether it
 * has triggered.
 */
export type OpenKind = "book" | "triggers";
export const inKind = (o: Pick<Order, "status">, kind: OpenKind) => (o.status === "PENDING_TRIGGER") === (kind === "triggers");
