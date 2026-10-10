import { add, subtract } from "@/lib/money";
import type { Balance, Order } from "../shared/api";

/** Statuses the engine will still act on a cancel for. */
const CANCELABLE = new Set(["NEW", "PARTIALLY_FILLED", "PENDING_TRIGGER"]);
export const isCancelable = (status: string) => CANCELABLE.has(status);

export type Trigger =
  | { kind: "price"; op: "≥" | "≤"; price: string }
  | { kind: "trailing"; percent: string; activation: string | null };

/**
 * What has to happen for a conditional order to fire. A trailing order shows its distance and
 * activation price only: its live stop moves inside the engine and is never stored.
 */
export function triggerOf(o: Pick<Order, "stopPrice" | "trailingDeltaBps" | "triggerDirection">): Trigger | null {
  if (o.trailingDeltaBps != null) return { kind: "trailing", percent: `${o.trailingDeltaBps / 100}%`, activation: o.stopPrice };
  if (!o.stopPrice || !o.triggerDirection) return null;
  return { kind: "price", op: o.triggerDirection === "RISE" ? "≥" : "≤", price: o.stopPrice };
}

/** The fields of an `orderUpdate` frame this list uses; see web-bff's OrderUpdateData. */
export type OrderUpdate = {
  /** Null on a refused amend, which says nothing about the order's status. */
  orderId: string; status: string | null; symbol?: string; executedQuantity?: string | null; remainingQuantity?: string | null; time?: string;
  /** On a fill, which carries no cumulative figure: the engine's event has only this trade's. */
  lastFillQuantity?: string | null;
  /** The whole row, on an acceptance whose event describes all of it. */
  order?: Order | null;
  rejectReason?: string | null;
  cancelReason?: string | null;
  /** Set on the two frames an amend produces; `rejectReason` is null when it was applied. */
  amend?: { rejectReason: string | null; price: string | null; stopPrice: string | null; quantity: string | null } | null;
};
/** The statuses only an acceptance frame carries. */
const ACCEPTED = new Set(["NEW", "PENDING_TRIGGER"]);
const LIVE = new Set(["PENDING_TRIGGER", "NEW", "PARTIALLY_FILLED", "PENDING_CANCEL"]);

/**
 * The open-orders list after one `orderUpdate` frame: the row patched, or dropped once it can no
 * longer fill. An unseen order is added from the frame's `order`; an acceptance without one (it
 * filled on arrival, or fired from a trigger) is "refetch". A closing frame for an order the list
 * never had is "closed": the order filled before the list could show it, and a refetch already in
 * flight may still bring it back as NEW, so the caller must remember to drop it. Any other frame
 * for an unseen order is ignored: if it rests, its acceptance follows. Undefined before the first load.
 */
export function applyOrderUpdate(list: Order[] | undefined, u: OrderUpdate): Order[] | "refetch" | "closed" | undefined {
  if (!list) return undefined;
  const i = list.findIndex((o) => o.orderId === u.orderId);
  if (u.amend) return i < 0 ? list : list.map((x, j) => (j === i ? amended(x, u) : x));
  if (u.status === null || !LIVE.has(u.status)) return i < 0 ? "closed" : list.filter((_, j) => j !== i);
  if (i < 0) {
    if (u.order) return insertNewestFirst(list, u.order);
    return ACCEPTED.has(u.status) ? "refetch" : list;
  }
  const o = list[i];
  const next: Order = {
    ...o,
    status: u.status,
    filledQuantity: filledAfter(o, u),
    // Null for a quote-sized order, whose remainder is a budget, not a quantity (OrderView).
    remainingQuantity: o.remainingQuantity === null ? null : u.remainingQuantity ?? o.remainingQuantity,
    updatedAt: u.time ?? o.updatedAt,
  };
  return list.map((x, j) => (j === i ? next : x));
}

/**
 * The order's filled quantity after a frame. A fill frame has no cumulative figure, so it is
 * the order's quantity less what remains -- absolute, so a repeated frame cannot count twice --
 * and for a quote-sized order, whose remainder is a budget, the fill's own quantity added.
 */
function filledAfter(o: Order, u: OrderUpdate): string {
  if (u.executedQuantity) return u.executedQuantity;
  if (!u.lastFillQuantity) return o.filledQuantity;
  if (o.quantity && u.remainingQuantity) return subtract(o.quantity, u.remainingQuantity);
  return add(o.filledQuantity, u.lastFillQuantity);
}

/** The row after an amend's outcome: its new terms, or unchanged with the reason it was refused. */
function amended(o: Order, u: OrderUpdate): Order {
  const a = u.amend!;
  if (a.rejectReason) return { ...o, amendPending: false, amendRejectReason: a.rejectReason };
  const quoteSized = o.quoteOrderQty != null;
  return {
    ...o,
    status: u.status ?? o.status,
    price: a.price ?? o.price,
    stopPrice: a.stopPrice ?? o.stopPrice,
    quantity: quoteSized ? o.quantity : a.quantity ?? o.quantity,
    quoteOrderQty: quoteSized ? a.quantity ?? o.quoteOrderQty : o.quoteOrderQty,
    filledQuantity: u.executedQuantity ?? o.filledQuantity,
    remainingQuantity: o.remainingQuantity === null ? null : u.remainingQuantity ?? o.remainingQuantity,
    updatedAt: u.time ?? o.updatedAt,
    amendPending: false,
    amendRejectReason: null,
  };
}

/** The list is newest first; a new order almost always goes on top, but is placed by time regardless. */
function insertNewestFirst(list: Order[], order: Order): Order[] {
  const at = Date.parse(order.createdAt);
  const i = list.findIndex((o) => Date.parse(o.createdAt) < at);
  return i < 0 ? [...list, order] : [...list.slice(0, i), order, ...list.slice(i)];
}

/** A balance, as web-bff pushes it (BalanceChangedData): whole state, not a delta. */
export type BalanceChanged = { asset: string; free: string; locked: string; version: number };

/**
 * The balances after a `balanceChanged` frame: that asset's row replaced, or added for an asset
 * the account never held. A frame older than the row -- the list was fetched after it was sent --
 * changes nothing, which is what `version` is for. Undefined before the first load.
 */
export function applyBalanceChanged(list: Balance[] | undefined, b: BalanceChanged): Balance[] | undefined {
  if (!list) return undefined;
  const row: Balance = { asset: b.asset, available: b.free, locked: b.locked, total: add(b.free, b.locked), version: b.version };
  const i = list.findIndex((x) => x.asset === b.asset);
  if (i < 0) return [...list, row];
  if (list[i].version >= b.version) return undefined;
  return list.map((x, j) => (j === i ? row : x));
}

/** The list without orders the socket has already reported closed -- a refetch can lag settlement. */
export function withoutClosed(list: Order[], closed: Set<string>): Order[] {
  return closed.size && list.some((o) => closed.has(o.orderId)) ? list.filter((o) => !closed.has(o.orderId)) : list;
}

export type OrderToastKind = "placed" | "armed" | "filled" | "canceled" | "rejected" | "expired" | "amended" | "amendRejected";
export type OrderToast = {
  tone: "success" | "error" | "info";
  kind: OrderToastKind;
  symbol: string;
  /** From the row the list still holds; absent for an order that closed before it was listed. */
  side?: "BUY" | "SELL";
  quantity?: string;
  /** The engine's own enum name. The caller translates it and drops what it cannot. */
  reason?: string;
};

/**
 * The toast an `orderUpdate` frame deserves, or null for one nobody needs announced.
 *
 * <p>Nothing announces a request, only what came of it: a 202 means the exchange has the
 * order, not that it worked, and "submitted" followed by "rejected" reads as two events.
 * The engine only accepts an order that rested, so an acceptance cannot double up with the
 * fill of an order that never did.
 *
 * <p>A partial fill is deliberately silent: one large order can produce dozens of them, and
 * the row already shows the progress.
 */
export function toastForUpdate(u: OrderUpdate, order?: Order): OrderToast | null {
  const at = { symbol: u.symbol ?? order?.symbol ?? "", side: order?.side ?? u.order?.side };
  if (u.amend) {
    return u.amend.rejectReason
      ? { tone: "error", kind: "amendRejected", ...at, reason: u.amend.rejectReason }
      : { tone: "success", kind: "amended", ...at };
  }
  switch (u.status) {
    case "NEW":
      return { tone: "success", kind: "placed", ...at };
    case "PENDING_TRIGGER":
      return { tone: "success", kind: "armed", ...at };
    case "FILLED":
      return { tone: "success", kind: "filled", ...at, quantity: u.executedQuantity ?? order?.filledQuantity };
    case "CANCELED":
      // USER_REQUESTED needs no explanation: the person pressed cancel a moment ago.
      return { tone: "info", kind: "canceled", ...at,
               reason: u.cancelReason && u.cancelReason !== "CANCEL_REASON_USER_REQUESTED" ? u.cancelReason : undefined };
    case "REJECTED":
      return { tone: "error", kind: "rejected", ...at, reason: u.rejectReason ?? undefined };
    case "EXPIRED":
      return { tone: "info", kind: "expired", ...at };
    default:
      return null;
  }
}
