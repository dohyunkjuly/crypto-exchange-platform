import { describe, expect, it } from "vitest";
import type { Candle } from "@/features/markets/api";
import { advance, applyKline, bucketStart, nextOpen, prependPage, type Kline } from "./candles";

const M = 60_000;
const bar = (t: number, close: string): Candle => ({ openTime: new Date(t).toISOString(), open: close, high: close, low: close, close, volume: "1", quoteVolume: "1", tradeCount: 1 });
const kline = (t: number, close: string): Kline => ({ symbol: "BTCUSDT", interval: "1m", openTime: new Date(t).toISOString(), open: close, high: close, low: close, close, volume: "2", quoteVolume: "2", tradeCount: 2 });

describe("applyKline", () => {
  it("replaces the bar it belongs to", () => {
    const out = applyKline([bar(0, "100"), bar(M, "101")], kline(M, "102"), "1m");
    expect(out.map((c) => c.close)).toEqual(["100", "102"]);
  });
  it("fills the quiet buckets before a frame that arrives after a gap", () => {
    const out = applyKline([bar(0, "100")], kline(3 * M, "103"), "1m");
    expect(out.map((c) => c.close)).toEqual(["100", "100", "100", "103"]);
    expect(out[1].volume).toBe("0");
  });
  it("ignores a frame older than the last bar", () => {
    const series = [bar(0, "100"), bar(M, "101")];
    expect(applyKline(series, kline(0, "99"), "1m")).toBe(series);
  });
});

describe("advance", () => {
  it("adds flat bars up to the current bucket, and nothing when already there", () => {
    const out = advance([bar(0, "100")], 2 * M + 5, "1m");
    expect(out.map((c) => c.openTime)).toEqual([0, M, 2 * M].map((t) => new Date(t).toISOString()));
    expect(advance(out, 2 * M + 30_000, "1m")).toBe(out);
  });
  it("does nothing before the first trade, since there is no close to carry", () => {
    expect(advance([], 5 * M, "1m")).toEqual([]);
  });
});

describe("prependPage", () => {
  const bar = (min: number): Candle => ({ openTime: new Date(min * 60_000).toISOString(), open: "1", high: "1", low: "1", close: "1", volume: "0", quoteVolume: "0", tradeCount: 0 });
  it("puts the page before the series and drops bars the series already has", () => {
    const out = prependPage([bar(5), bar(6)], [bar(3), bar(4), bar(5)]);
    expect(out.map((c) => Date.parse(c.openTime) / 60_000)).toEqual([3, 4, 5, 6]);
  });
  it("is the page itself when the series is empty", () => {
    expect(prependPage([], [bar(1)])).toHaveLength(1);
  });
});

describe("month buckets", () => {
  it("floor to the first of the month in UTC and step a calendar month, not 30 days", () => {
    const feb17 = Date.UTC(2026, 1, 17, 13, 47);
    expect(bucketStart(feb17, "1M")).toBe(Date.UTC(2026, 1, 1));
    expect(nextOpen(Date.UTC(2026, 1, 1), "1M")).toBe(Date.UTC(2026, 2, 1));
    expect(nextOpen(Date.UTC(2026, 11, 1), "1M")).toBe(Date.UTC(2027, 0, 1));
    // advance across a month boundary adds exactly one bar
    const out = advance([bar(Date.UTC(2026, 0, 1), "100")], Date.UTC(2026, 1, 10), "1M");
    expect(out.map((c) => c.openTime)).toEqual([Date.UTC(2026, 0, 1), Date.UTC(2026, 1, 1)].map((t) => new Date(t).toISOString()));
  });
  it("fixed intervals floor against the epoch", () => {
    expect(bucketStart(Date.UTC(2026, 1, 17, 13, 47), "8h")).toBe(Date.UTC(2026, 1, 17, 8));
  });
});
