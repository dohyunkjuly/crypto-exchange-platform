import type { MarketSymbol } from "@/features/markets/api";
import { compare, isValidDecimal, multiply, roundToStep } from "@/lib/money";
import type { Order } from "../shared/api";

export type AmendField = "price" | "stopPrice" | "size";
export type AmendDraft = { price: string; stopPrice: string; size: string };
export type AmendRequest = { price?: string; stopPrice?: string; quantity?: string; quoteOrderQty?: string };

/**
 * What this order lets an amend change -- the table in docs/reference/order-types.md. A parked
 * order can move its trigger; a trailing one takes a new size only, as everywhere; an
 * order with no price (it fires at the market) has none to edit.
 */
export function amendableFields(o: Pick<Order, "status" | "price" | "trailingDeltaBps">): AmendField[] {
  if (o.trailingDeltaBps != null) return ["size"];
  const fields: AmendField[] = [];
  if (o.status === "PENDING_TRIGGER") fields.push("stopPrice");
  if (o.price) fields.push("price");
  return [...fields, "size"];
}

/** Sized by a quote budget rather than a base quantity: a market-firing BUY. */
export const isQuoteSized = (o: Pick<Order, "quoteOrderQty">) => o.quoteOrderQty != null;

export const draftOf = (o: Order): AmendDraft => ({
  price: o.price ?? "", stopPrice: o.stopPrice ?? "", size: o.quoteOrderQty ?? o.quantity ?? "",
});

const positive = (v: string) => isValidDecimal(v) && compare(v, "0") > 0;

/** What is wrong with the draft, in the order a trader would fix it. Empty means it can go. */
export function validateAmend(o: Order, d: AmendDraft, m: MarketSymbol): string[] {
  const fields = amendableFields(o);
  const problems: string[] = [];
  if (fields.includes("price") && !positive(d.price)) problems.push("price");
  if (fields.includes("stopPrice") && !positive(d.stopPrice)) problems.push("stopPrice");
  if (!positive(d.size)) problems.push("amount");
  else if (isQuoteSized(o)) {
    if (compare(d.size, m.minNotional) < 0) problems.push("minNotional");
    else if (compare(d.size, o.cumulativeQuoteQuantity) <= 0) problems.push("belowFilled");
  } else {
    if (compare(d.size, m.minQty) < 0) problems.push("minQty");
    else if (compare(d.size, m.maxQty) > 0) problems.push("maxQty");
    // The size is the new TOTAL, so it has to stay above what has already filled.
    else if (compare(d.size, o.filledQuantity) <= 0) problems.push("belowFilled");
    else if (fields.includes("price") && positive(d.price) && compare(multiply(d.price, d.size), m.minNotional) < 0) problems.push("minNotional");
  }
  if (!problems.length && !Object.keys(toAmendRequest(o, d, m)).length) problems.push("unchanged");
  return problems;
}

/** Only the fields that differ, snapped to the market's steps. Empty when nothing changed. */
export function toAmendRequest(o: Order, d: AmendDraft, m: MarketSymbol): AmendRequest {
  const fields = amendableFields(o);
  const request: AmendRequest = {};
  const differs = (next: string, current: string | null) => current == null || compare(next, current) !== 0;
  if (fields.includes("price") && positive(d.price)) {
    const price = roundToStep(d.price, m.priceTick, "down");
    if (differs(price, o.price)) request.price = price;
  }
  if (fields.includes("stopPrice") && positive(d.stopPrice)) {
    const stopPrice = roundToStep(d.stopPrice, m.priceTick, "down");
    if (differs(stopPrice, o.stopPrice)) request.stopPrice = stopPrice;
  }
  if (positive(d.size)) {
    if (isQuoteSized(o)) {
      if (differs(d.size, o.quoteOrderQty)) request.quoteOrderQty = d.size;
    } else {
      const quantity = roundToStep(d.size, m.qtyStep, "down");
      if (differs(quantity, o.quantity)) request.quantity = quantity;
    }
  }
  return request;
}
