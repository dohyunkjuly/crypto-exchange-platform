import type { Candle, CandleInterval } from "@/features/markets/api";

/** Every interval the backend materialises (CandleInterval.java), in display order. */
export const INTERVALS: CandleInterval[] = ["1m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "1M"];
/** The row above the chart; the rest sit behind the picker: pinned, then available. */
export const PINNED: CandleInterval[] = ["1m", "5m", "15m", "1h", "4h", "1d"];
export const AVAILABLE: CandleInterval[] = INTERVALS.filter((i) => !PINNED.includes(i));

const MINUTE = 60_000;
const FIXED_MS: Partial<Record<CandleInterval, number>> = {
  "1m": MINUTE, "5m": 5 * MINUTE, "15m": 15 * MINUTE, "30m": 30 * MINUTE, "1h": 60 * MINUTE, "2h": 120 * MINUTE,
  "4h": 240 * MINUTE, "6h": 360 * MINUTE, "8h": 480 * MINUTE, "12h": 720 * MINUTE, "1d": 1440 * MINUTE,
};

/** The open time of the bucket `ms` falls in. A month is a calendar month in UTC, as the backend's
 *  CandleInterval.bucketStart has it; everything else divides a day and floors against the epoch. */
export function bucketStart(ms: number, interval: CandleInterval): number {
  const fixed = FIXED_MS[interval];
  if (fixed) return Math.floor(ms / fixed) * fixed;
  const d = new Date(ms);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
}

/** The open time of the bucket after the one opening at `openMs`. */
export function nextOpen(openMs: number, interval: CandleInterval): number {
  const fixed = FIXED_MS[interval];
  if (fixed) return openMs + fixed;
  const d = new Date(openMs);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
}

/** Minutes lowercase, hours and days upper, so "1m" (a minute) and "1M" (a month) never read alike. */
export const intervalLabel = (i: CandleInterval) => (i.endsWith("m") ? i : i.toUpperCase());

/** A kline frame's fields this chart uses; see web-bff's KlineData. */
export type Kline = { symbol: string; interval: string; openTime: string; open: string; high: string; low: string; close: string; volume: string; quoteVolume: string; tradeCount: number };

const flat = (openTime: number, close: string): Candle =>
  ({ openTime: new Date(openTime).toISOString(), open: close, high: close, low: close, close, volume: "0", quoteVolume: "0", tradeCount: 0 });
const ms = (c: Pick<Candle, "openTime">) => Date.parse(c.openTime);

/**
 * The series after one kline frame: the bar with that open time replaced, or appended, with any
 * quiet buckets between filled flat at the previous close -- the same rule the REST read applies,
 * so a frame that arrives after a gap does not leave a hole. A frame older than the last bar is ignored.
 */
export function applyKline(series: Candle[], k: Kline, interval: CandleInterval): Candle[] {
  const bar: Candle = { openTime: k.openTime, open: k.open, high: k.high, low: k.low, close: k.close, volume: k.volume, quoteVolume: k.quoteVolume, tradeCount: k.tradeCount };
  if (series.length === 0) return [bar];
  const last = series[series.length - 1];
  const at = ms(bar); const lastAt = ms(last);
  if (at === lastAt) return [...series.slice(0, -1), bar];
  if (at < lastAt) return series;
  const filled = [...series];
  for (let t = nextOpen(lastAt, interval); t < at; t = nextOpen(t, interval)) filled.push(flat(t, last.close));
  filled.push(bar);
  return filled;
}

/** An older page in front of the series. Only bars before the current oldest are taken, so a page that
 *  overlaps (a bar that arrived live while the page was in flight) does not duplicate a time. */
export function prependPage(series: Candle[], page: Candle[]): Candle[] {
  if (series.length === 0) return page;
  const oldest = ms(series[0]);
  return [...page.filter((c) => ms(c) < oldest), ...series];
}

/** Extends the series with flat bars up to the bucket containing `nowMs`, so the current bar exists before its first trade. */
export function advance(series: Candle[], nowMs: number, interval: CandleInterval): Candle[] {
  if (series.length === 0) return series;
  const last = series[series.length - 1];
  const current = bucketStart(nowMs, interval);
  let lastAt = ms(last);
  if (current <= lastAt) return series;
  const out = [...series];
  for (let t = nextOpen(lastAt, interval); t <= current; t = nextOpen(t, interval)) { out.push(flat(t, last.close)); lastAt = t; }
  return out;
}
