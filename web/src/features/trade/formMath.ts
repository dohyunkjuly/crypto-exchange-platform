import type { MarketSymbol } from "@/features/markets/api";
import { compare, divide, isValidDecimal, multiply, roundToStep, scaleOf } from "@/lib/money";

export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "LIMIT_MAKER" | "MARKET" | "STOP_LOSS" | "STOP_LOSS_LIMIT" | "TAKE_PROFIT" | "TAKE_PROFIT_LIMIT";
export const CONDITIONAL: OrderType[] = ["STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"];

/** Fires as a market order, at once or when triggered -- so it has no price (docs/reference/order-types.md). */
export const isMarketFiring = (t: OrderType) => t === "MARKET" || t === "STOP_LOSS" || t === "TAKE_PROFIT";
export const isConditional = (t: OrderType) => CONDITIONAL.includes(t);

/**
 * Which asset the order is sized in. The rule from the order guide: a market-firing BUY gives up
 * quote for an unknowable amount of base, so it is sized by a quote budget (`quoteOrderQty`);
 * everything else is sized by base `quantity`.
 */
export const sizing = (type: OrderType, side: Side): "quote" | "base" => (isMarketFiring(type) && side === "BUY" ? "quote" : "base");

export const positive = (v: string) => isValidDecimal(v) && compare(v, "0") > 0;

/** Price × amount, exact; "" when either is missing. */
export function totalOf(price: string, amount: string): string {
  return positive(price) && positive(amount) ? multiply(price, amount) : "";
}

/** A share of the available balance, snapped down to the step of the asset it is sized in. */
export function sizeForShare(share: number, available: string, kind: "quote" | "base", price: string, m: MarketSymbol): string {
  if (!isValidDecimal(available) || share <= 0) return "";
  const part = multiply(available, String(share));
  if (kind === "quote") return roundToStep(part, quoteStep(m), "down");
  return roundToStep(part, m.qtyStep, "down");
}

/** A quote budget turned into base at a price -- the estimate shown, never what is sent. */
export function baseForQuote(quote: string, price: string, m: MarketSymbol): string {
  return positive(quote) && positive(price) ? roundToStep(divide(quote, price, scaleOf(m.qtyStep) + 2), m.qtyStep, "down") : "";
}

const quoteStep = (m: MarketSymbol) => `0.${"0".repeat(Math.max(0, m.quoteDecimals - 1))}1`.replace(/^0\.1$/, "0.1");

export type Draft = { type: OrderType; side: Side; price: string; stopPrice: string; amount: string; total: string; trailingPct: string };

/** What the order is missing, in the order a trader would fix it. Empty means it can go. */
export function validate(f: Draft, m: MarketSymbol): string[] {
  const problems: string[] = [];
  const conditional = isConditional(f.type);
  const trailing = f.trailingPct.trim() !== "";
  if (!isMarketFiring(f.type) && !positive(f.price)) problems.push("price");
  if (conditional && !trailing && !positive(f.stopPrice)) problems.push("stopPrice");
  if (conditional && trailing) {
    const pct = Number(f.trailingPct);
    if (!(pct >= 0.1 && pct <= 20)) problems.push("trailing");
  }
  if (sizing(f.type, f.side) === "quote") {
    if (!positive(f.total)) problems.push("total");
    else if (compare(f.total, m.minNotional) < 0) problems.push("minNotional");
  } else {
    if (!positive(f.amount)) problems.push("amount");
    else if (compare(f.amount, m.minQty) < 0) problems.push("minQty");
    else if (compare(f.amount, m.maxQty) > 0) problems.push("maxQty");
    else if (!isMarketFiring(f.type) && positive(f.price) && compare(multiply(f.price, f.amount), m.minNotional) < 0) problems.push("minNotional");
  }
  return problems;
}

/** The request body for web-bff, with exactly the fields the order guide allows for this type and side. */
export function toRequest(f: Draft, m: MarketSymbol) {
  const quote = sizing(f.type, f.side) === "quote";
  const trailingBps = f.trailingPct.trim() === "" ? null : Math.round(Number(f.trailingPct) * 100);
  return {
    symbol: m.symbol, side: f.side, orderType: f.type,
    timeInForce: f.type === "MARKET" ? null : "GTC",
    price: isMarketFiring(f.type) ? null : roundToStep(f.price, m.priceTick, "down"),
    stopPrice: isConditional(f.type) && positive(f.stopPrice) ? roundToStep(f.stopPrice, m.priceTick, "down") : null,
    quantity: quote ? null : roundToStep(f.amount, m.qtyStep, "down"),
    quoteOrderQty: quote ? roundToStep(f.total, quoteStep(m), "down") : null,
    trailingDeltaBps: isConditional(f.type) ? trailingBps : null,
  };
}
