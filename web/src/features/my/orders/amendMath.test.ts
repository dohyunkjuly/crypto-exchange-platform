import { describe, expect, it } from "vitest";
import type { MarketSymbol } from "@/features/markets/api";
import type { Order } from "../shared/api";
import { amendableFields, draftOf, toAmendRequest, validateAmend } from "./amendMath";

const m = { symbol: "BTCUSDT", baseAsset: "BTC", quoteAsset: "USDT", priceTick: "0.1", qtyStep: "0.0001", minQty: "0.0001", maxQty: "100", minNotional: "5", quoteDecimals: 2 } as MarketSymbol;
const order = (over: Partial<Order>): Order => ({
  orderId: "a", clientOrderId: null, symbol: "BTCUSDT", side: "BUY", orderType: "LIMIT", timeInForce: "GTC", status: "NEW",
  price: "100", stopPrice: null, trailingDeltaBps: null, triggerDirection: null, quantity: "2", quoteOrderQty: null,
  filledQuantity: "0", remainingQuantity: "2", cumulativeQuoteQuantity: "0", averageFillPrice: null, rejectReason: null,
  createdAt: "t0", updatedAt: "t0", ...over,
});

describe("amendableFields follows the order guide", () => {
  it("lets a resting limit change price and size, never a stop price", () => {
    expect(amendableFields(order({}))).toEqual(["price", "size"]);
  });
  it("lets a parked stop-limit move its trigger too, and a plain stop has no price to edit", () => {
    expect(amendableFields(order({ status: "PENDING_TRIGGER", stopPrice: "90" }))).toEqual(["stopPrice", "price", "size"]);
    expect(amendableFields(order({ status: "PENDING_TRIGGER", stopPrice: "90", price: null }))).toEqual(["stopPrice", "size"]);
  });
  it("lets a trailing order change its size only, parked or tracking", () => {
    expect(amendableFields(order({ status: "PENDING_TRIGGER", trailingDeltaBps: 100, stopPrice: "90" }))).toEqual(["size"]);
  });
});

describe("toAmendRequest sends only what changed", () => {
  it("sends nothing for an untouched draft, and that is a problem, not a request", () => {
    const o = order({});
    expect(toAmendRequest(o, draftOf(o), m)).toEqual({});
    expect(validateAmend(o, draftOf(o), m)).toEqual(["unchanged"]);
  });
  it("treats 100.0 as 100: the same number is no change", () => {
    expect(toAmendRequest(order({}), { price: "100.0", stopPrice: "", size: "2.0000" }, m)).toEqual({});
  });
  it("snaps to the tick and step and leaves the unchanged field out", () => {
    expect(toAmendRequest(order({}), { price: "101.27", stopPrice: "", size: "2" }, m)).toEqual({ price: "101.2" });
    expect(toAmendRequest(order({}), { price: "100", stopPrice: "", size: "3.00019" }, m)).toEqual({ quantity: "3.0001" });
  });
  it("sizes a quote-sized order by quoteOrderQty, never quantity", () => {
    const o = order({ orderType: "STOP_LOSS", status: "PENDING_TRIGGER", price: null, stopPrice: "120", quantity: null, quoteOrderQty: "50" });
    expect(toAmendRequest(o, { price: "", stopPrice: "125", size: "80" }, m)).toEqual({ stopPrice: "125.0", quoteOrderQty: "80" });
  });
  it("never sends a trailing order's trigger, whatever the draft says", () => {
    const o = order({ status: "PENDING_TRIGGER", trailingDeltaBps: 100, stopPrice: "90", price: null });
    expect(toAmendRequest(o, { price: "1", stopPrice: "80", size: "3" }, m)).toEqual({ quantity: "3.0000" });
  });
});

describe("validateAmend", () => {
  it("refuses a new total at or below what has filled", () => {
    const o = order({ filledQuantity: "1", status: "PARTIALLY_FILLED" });
    expect(validateAmend(o, { price: "100", stopPrice: "", size: "1" }, m)).toEqual(["belowFilled"]);
    expect(validateAmend(o, { price: "100", stopPrice: "", size: "1.5" }, m)).toEqual([]);
  });
  it("applies the placement bounds to the new terms", () => {
    expect(validateAmend(order({}), { price: "", stopPrice: "", size: "2" }, m)).toEqual(["price"]);
    expect(validateAmend(order({}), { price: "100", stopPrice: "", size: "101" }, m)).toEqual(["maxQty"]);
    expect(validateAmend(order({}), { price: "1", stopPrice: "", size: "2" }, m)).toEqual(["minNotional"]);
  });
});
