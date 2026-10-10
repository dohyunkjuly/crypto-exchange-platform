import { describe, expect, it } from "vitest";
import type { MarketSymbol, Ticker } from "./api";
import { matchPairs, rankPairs } from "./pairs";

const m = (symbol: string, quoteAsset = "USDT") => ({ symbol, baseAsset: symbol.replace(quoteAsset, ""), quoteAsset }) as MarketSymbol;
const t = (symbol: string, quoteVolume: string, lastPrice: string, priceChangePercent: string) => ({ symbol, quoteVolume, lastPrice, priceChangePercent }) as Ticker;
const markets = [m("BTCUSDT"), m("ETHUSDT"), m("ETHBTC", "BTC")];
const tickers = [t("BTCUSDT", "900", "60000", "-1.5"), t("ETHUSDT", "1200", "3000", "2.0")];

describe("matchPairs", () => {
  it("matches however the pair is typed", () => {
    expect(matchPairs(markets, "btc").map((x) => x.symbol)).toEqual(["BTCUSDT", "ETHBTC"]);
    expect(matchPairs(markets, "eth/usdt").map((x) => x.symbol)).toEqual(["ETHUSDT"]);
    expect(matchPairs(markets, "  ")).toHaveLength(3);
  });
});

describe("rankPairs", () => {
  const base = { query: "", quote: null, favorites: new Set<string>(), onlyFavorites: false, desc: true } as const;
  it("sorts by turnover, busiest first, with never-traded markets last", () => {
    expect(rankPairs(markets, tickers, { ...base, sort: "turnover" }).map((r) => r.market.symbol)).toEqual(["ETHUSDT", "BTCUSDT", "ETHBTC"]);
  });
  it("narrows by quote asset and by favourites", () => {
    expect(rankPairs(markets, tickers, { ...base, sort: "turnover", quote: "BTC" }).map((r) => r.market.symbol)).toEqual(["ETHBTC"]);
    expect(rankPairs(markets, tickers, { ...base, sort: "turnover", favorites: new Set(["BTCUSDT"]), onlyFavorites: true }).map((r) => r.market.symbol)).toEqual(["BTCUSDT"]);
  });
  it("sorts by change either way", () => {
    expect(rankPairs(markets, tickers, { ...base, sort: "change", desc: false }).map((r) => r.market.symbol)).toEqual(["BTCUSDT", "ETHBTC", "ETHUSDT"]);
  });
});
