import { describe, expect, it } from "vitest";
import { frameMatches } from "./MarketStream";

describe("frameMatches", () => {
  it("routes a frame by channel and symbol, and by interval for klines", () => {
    expect(frameMatches({ type: "depth", data: { symbol: "BTCUSDT" } }, { channel: "depth", symbol: "BTCUSDT" })).toBe(true);
    expect(frameMatches({ type: "depth", data: { symbol: "ETHUSDT" } }, { channel: "depth", symbol: "BTCUSDT" })).toBe(false);
    expect(frameMatches({ type: "trade", data: { symbol: "BTCUSDT" } }, { channel: "depth", symbol: "BTCUSDT" })).toBe(false);
    expect(frameMatches({ type: "kline", data: { symbol: "BTCUSDT", interval: "5m" } }, { channel: "kline", symbol: "BTCUSDT", interval: "1m" })).toBe(false);
    expect(frameMatches({ type: "kline", data: { symbol: "BTCUSDT", interval: "1m" } }, { channel: "kline", symbol: "BTCUSDT", interval: "1m" })).toBe(true);
  });
  it("ignores replies and malformed frames", () => {
    expect(frameMatches({ type: "subscribed", data: { channel: "ticker", symbol: "BTCUSDT" } }, { channel: "ticker", symbol: "BTCUSDT" })).toBe(false);
    expect(frameMatches({ type: "ticker", data: null }, { channel: "ticker", symbol: "BTCUSDT" })).toBe(false);
  });
});
