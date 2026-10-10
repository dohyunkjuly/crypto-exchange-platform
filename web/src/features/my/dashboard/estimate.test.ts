import { describe, expect, it } from "vitest";
import type { Ticker } from "@/features/markets/api";
import { estimateTotal, holdings } from "./estimate";

const bal = (asset: string, total: string) => ({ asset, available: total, locked: "0", total, version: 1 });
const tick = (symbol: string, lastPrice: string) => ({ symbol, lastPrice, openPrice: "0", highPrice: "0", lowPrice: "0", priceChange: "0", priceChangePercent: "0", volume: "0", quoteVolume: "0" });

describe("estimateTotal", () => {
  it("values holdings at last price and counts USDT at par", () => {
    const { total, skipped } = estimateTotal(
      [bal("BTC", "0.5"), bal("USDT", "100"), bal("ETH", "2")],
      [tick("BTCUSDT", "60000"), tick("ETHUSDT", "3000.5")],
    );
    // 0.5*60000 + 100 + 2*3000.5 -- exact, no float drift
    expect(total).toBe("36101.0");
    expect(skipped).toEqual([]);
  });

  it("names an asset it could not value rather than counting it as zero silently", () => {
    const { total, skipped } = estimateTotal([bal("DOGE", "1000"), bal("USDT", "5")], [tick("BTCUSDT", "1")]);
    expect(total).toBe("5");
    expect(skipped).toEqual(["DOGE"]);
  });
});

describe("holdings", () => {
  const assets = [
    { symbol: "BTC", name: "Bitcoin", displayDecimals: 8 },
    { symbol: "USDT", name: "Tether", displayDecimals: 6 },
    { symbol: "PEPE", name: "Pepe", displayDecimals: 8 },
  ];
  const tickers = [
    { symbol: "BTCUSDT", lastPrice: "100", priceChangePercent: "2.5" },
  ] as Ticker[];

  it("values each holding, keeps the quote asset at par, and puts the biggest first", () => {
    const rows = holdings([{ asset: "USDT", total: "50" }, { asset: "BTC", total: "2" }], assets, tickers);
    expect(rows.map((r) => r.asset)).toEqual(["BTC", "USDT"]);
    expect(rows[0]).toMatchObject({ value: "200", price: "100", changePercent: "2.5", symbol: "BTCUSDT", name: "Bitcoin" });
    expect(rows[1]).toMatchObject({ value: "50", price: "1", symbol: null });
  });

  it("drops what is not held, so the list is not a page of zeros", () => {
    expect(holdings([{ asset: "BTC", total: "0" }, { asset: "USDT", total: "1" }], assets, tickers)
      .map((r) => r.asset)).toEqual(["USDT"]);
  });

  it("still shows an asset no market can price, after the ones it can", () => {
    const rows = holdings([{ asset: "PEPE", total: "9" }, { asset: "USDT", total: "1" }], assets, tickers);
    expect(rows.map((r) => r.asset)).toEqual(["USDT", "PEPE"]);
    expect(rows[1]).toMatchObject({ value: null, price: null, symbol: null, total: "9" });
  });
});
