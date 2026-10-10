import { describe, expect, it } from "vitest";
import type { MarketSymbol } from "@/features/markets/api";
import { baseForQuote, sizeForShare, sizing, toRequest, totalOf, validate, type Draft } from "./formMath";

const m = { symbol: "BTCUSDT", priceTick: "0.1", qtyStep: "0.0001", minQty: "0.0001", maxQty: "100", minNotional: "5", quoteDecimals: 2 } as MarketSymbol;
const draft = (over: Partial<Draft>): Draft => ({ type: "LIMIT", side: "BUY", price: "", stopPrice: "", amount: "", total: "", trailingPct: "", ...over });

describe("sizing follows the order guide", () => {
  it("sizes a market-firing buy in quote and everything else in base", () => {
    expect(sizing("MARKET", "BUY")).toBe("quote"); expect(sizing("STOP_LOSS", "BUY")).toBe("quote"); expect(sizing("TAKE_PROFIT", "BUY")).toBe("quote");
    expect(sizing("MARKET", "SELL")).toBe("base"); expect(sizing("STOP_LOSS_LIMIT", "BUY")).toBe("base"); expect(sizing("LIMIT", "BUY")).toBe("base");
  });
  it("sends only the allowed field for the type and side", () => {
    expect(toRequest(draft({ type: "MARKET", side: "BUY", total: "100" }), m)).toMatchObject({ orderType: "MARKET", price: null, quantity: null, quoteOrderQty: "100.00", timeInForce: null });
    expect(toRequest(draft({ type: "MARKET", side: "SELL", amount: "0.5" }), m)).toMatchObject({ quantity: "0.5000", quoteOrderQty: null });
    expect(toRequest(draft({ type: "STOP_LOSS_LIMIT", side: "SELL", price: "60000", stopPrice: "61000", amount: "1" }), m))
      .toMatchObject({ price: "60000.0", stopPrice: "61000.0", quantity: "1.0000", quoteOrderQty: null, trailingDeltaBps: null });
    expect(toRequest(draft({ type: "TAKE_PROFIT", side: "SELL", amount: "1", trailingPct: "1.5" }), m)).toMatchObject({ stopPrice: null, trailingDeltaBps: 150 });
  });
});

describe("sizeForShare and estimates", () => {
  it("shares quote for a market buy and base otherwise, snapped to the step", () => {
    expect(sizeForShare(0.5, "1000.555", "quote", "", m)).toBe("500.27");
    expect(sizeForShare(0.25, "2.00005", "base", "", m)).toBe("0.5000");
  });
  it("estimates the base a budget buys, and the total a limit costs", () => {
    expect(baseForQuote("1000", "63250.5", m)).toBe("0.0158");
    expect(totalOf("63250.5", "0.0079")).toBe("499.67895");
  });
});

describe("validate", () => {
  it("wants a price only when the order has one, and a trigger unless trailing", () => {
    expect(validate(draft({ type: "LIMIT", amount: "1" }), m)).toEqual(["price"]);
    expect(validate(draft({ type: "MARKET", side: "SELL", amount: "1" }), m)).toEqual([]);
    expect(validate(draft({ type: "STOP_LOSS", side: "SELL", amount: "1" }), m)).toEqual(["stopPrice"]);
    expect(validate(draft({ type: "STOP_LOSS", side: "SELL", amount: "1", trailingPct: "2" }), m)).toEqual([]);
    expect(validate(draft({ type: "STOP_LOSS", side: "SELL", amount: "1", trailingPct: "50" }), m)).toEqual(["trailing"]);
  });
  it("checks the budget of a quote-sized order and the bounds of a base-sized one", () => {
    expect(validate(draft({ type: "MARKET", side: "BUY", total: "4" }), m)).toEqual(["minNotional"]);
    expect(validate(draft({ type: "MARKET", side: "BUY" }), m)).toEqual(["total"]);
    expect(validate(draft({ type: "LIMIT", price: "10", amount: "0.00001" }), m)).toEqual(["minQty"]);
    expect(validate(draft({ type: "LIMIT", price: "10", amount: "0.1" }), m)).toEqual(["minNotional"]);
    expect(validate(draft({ type: "LIMIT", price: "10", amount: "101" }), m)).toEqual(["maxQty"]);
  });
});
