import { afterEach, describe, expect, it, vi } from "vitest";
import type { Order } from "../shared/api";
import { applyBalanceChanged, applyOrderUpdate, isCancelable, toastForUpdate, triggerOf, withoutClosed } from "./orderMath";
import { coalesced } from "../shared/UserStream";

describe("isCancelable", () => {
  it("offers cancel only while the engine would still act on it", () => {
    for (const s of ["NEW", "PARTIALLY_FILLED", "PENDING_TRIGGER"]) expect(isCancelable(s), s).toBe(true);
    for (const s of ["FILLED", "CANCELED", "PENDING_CANCEL", "REJECTED", "EXPIRED"]) expect(isCancelable(s), s).toBe(false);
  });
});

describe("applyBalanceChanged", () => {
  const usdt = { asset: "USDT", available: "100", locked: "20", total: "120", version: 5 };
  const btc = { asset: "BTC", available: "1", locked: "0", total: "1", version: 2 };
  it("replaces the asset's row with the frame's whole state", () => {
    expect(applyBalanceChanged([usdt, btc], { asset: "USDT", free: "90.5", locked: "20", version: 6 }))
      .toEqual([{ asset: "USDT", available: "90.5", locked: "20", total: "110.5", version: 6 }, btc]);
  });
  it("adds an asset the account never held, so a first buy shows up", () => {
    expect(applyBalanceChanged([usdt], { asset: "ETH", free: "2", locked: "0", version: 1 }))
      .toEqual([usdt, { asset: "ETH", available: "2", locked: "0", total: "2", version: 1 }]);
  });
  it("ignores a frame older than the row -- the list was fetched after the frame was sent -- and an unloaded list", () => {
    expect(applyBalanceChanged([usdt], { asset: "USDT", free: "1", locked: "0", version: 5 })).toBeUndefined();
    expect(applyBalanceChanged([usdt], { asset: "USDT", free: "1", locked: "0", version: 4 })).toBeUndefined();
    expect(applyBalanceChanged(undefined, { asset: "USDT", free: "1", locked: "0", version: 9 })).toBeUndefined();
  });
});

describe("coalesced", () => {
  afterEach(() => vi.useRealTimers());

  it("a burst of frames is one refetch now and one when the gap ends, so a burst cannot trip the rate limit", () => {
    vi.useFakeTimers();
    const fired: unknown[] = [];
    const r = coalesced((key) => fired.push(key), 1_000);
    for (let i = 0; i < 100; i++) { r.invalidate(["orders", "open"]); r.invalidate(["balances"]); }
    expect(fired).toEqual([["orders", "open"], ["balances"]]);
    vi.advanceTimersByTime(999);
    expect(fired).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(fired).toEqual([["orders", "open"], ["balances"], ["orders", "open"], ["balances"]]);
    // The trailing refetch opened a gap of its own; quiet after it, nothing more fires.
    vi.advanceTimersByTime(5_000);
    expect(fired).toHaveLength(4);
  });

  it("a lone frame refetches at once and leaves nothing behind", () => {
    vi.useFakeTimers();
    const fired: unknown[] = [];
    const r = coalesced((key) => fired.push(key), 1_000);
    r.invalidate(["balances"]);
    vi.advanceTimersByTime(10_000);
    expect(fired).toEqual([["balances"]]);
    r.invalidate(["balances"]);
    r.invalidate(["balances"]);
    r.close();
    vi.advanceTimersByTime(10_000);
    expect(fired).toEqual([["balances"], ["balances"]]);
  });
});

describe("applyOrderUpdate", () => {
  it("an applied amend rewrites the row's terms and clears the pending mark", () => {
    const pending = { ...row, amendPending: true };
    const [next] = applyOrderUpdate([pending], { orderId: "a", status: "NEW", remainingQuantity: "3", time: "t2", amend: { rejectReason: null, price: "70", stopPrice: null, quantity: "3" } }) as Order[];
    expect(next).toMatchObject({ price: "70", quantity: "3", remainingQuantity: "3", amendPending: false, amendRejectReason: null, updatedAt: "t2" });
  });
  it("a refused amend keeps the row -- its null status must not read as closed -- and records why", () => {
    const pending = { ...row, amendPending: true };
    const next = applyOrderUpdate([pending, other], { orderId: "a", status: null, amend: { rejectReason: "AMEND_REJECT_REASON_WOULD_TAKE", price: null, stopPrice: null, quantity: null } }) as Order[];
    expect(next).toHaveLength(2);
    expect(next[0]).toMatchObject({ status: "NEW", remainingQuantity: "2", amendPending: false, amendRejectReason: "AMEND_REJECT_REASON_WOULD_TAKE" });
  });
  it("an amend frame for an order the list never had changes nothing", () => {
    const list = [other];
    expect(applyOrderUpdate(list, { orderId: "a", status: null, amend: { rejectReason: "x", price: null, stopPrice: null, quantity: null } })).toBe(list);
  });
  const row = { orderId: "a", status: "NEW", filledQuantity: "0", remainingQuantity: "2", updatedAt: "t0" } as Order;
  const other = { ...row, orderId: "b" };
  it("patches a partial fill in place, keeping everything the frame does not carry", () => {
    const next = applyOrderUpdate([row, other], { orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: "0.5", remainingQuantity: "1.5", time: "t1" });
    expect(next).toEqual([{ ...row, status: "PARTIALLY_FILLED", filledQuantity: "0.5", remainingQuantity: "1.5", updatedAt: "t1" }, other]);
  });
  it("a fill frame, which carries only this trade's quantity, moves the filled figure by quantity less remaining", () => {
    const sized = { ...row, quantity: "2" };
    const [next] = applyOrderUpdate([sized], { orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: null, remainingQuantity: "1.25", lastFillQuantity: "0.75", time: "t1" }) as Order[];
    expect(next).toMatchObject({ filledQuantity: "0.75", remainingQuantity: "1.25" });
    // Again, as a redelivered frame would: absolute, so it cannot count twice.
    expect((applyOrderUpdate([next], { orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: null, remainingQuantity: "1.25", lastFillQuantity: "0.75" }) as Order[])[0].filledQuantity).toBe("0.75");
  });
  it("a quote-sized order has no base quantity to subtract from, so the fill's own quantity is added", () => {
    const quoted = { ...row, quantity: null, quoteOrderQty: "100", remainingQuantity: null, filledQuantity: "0.1" };
    const [next] = applyOrderUpdate([quoted], { orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: null, remainingQuantity: null, lastFillQuantity: "0.05" }) as Order[];
    expect(next.filledQuantity).toBe("0.15");
  });
  it("drops a row the moment it can no longer fill", () => {
    for (const status of ["FILLED", "CANCELED", "REJECTED", "EXPIRED"]) {
      expect(applyOrderUpdate([row, other], { orderId: "a", status }), status).toEqual([other]);
    }
  });
  it("adds a new order from the frame's row, in time order, without a request", () => {
    const older = { ...row, orderId: "old", createdAt: "2026-09-12T10:00:00Z" };
    const newer = { ...row, orderId: "new", createdAt: "2026-09-12T12:00:00.5Z" };
    const placed = { ...row, orderId: "a", status: "PENDING_TRIGGER", createdAt: "2026-09-12T11:00:00.25Z" };
    expect(applyOrderUpdate([newer, older], { orderId: "a", status: "PENDING_TRIGGER", order: placed })).toEqual([newer, placed, older]);
  });
  it("refetches only for an acceptance that could not carry its row", () => {
    expect(applyOrderUpdate([other], { orderId: "a", status: "NEW" })).toBe("refetch");
    expect(applyOrderUpdate([other], { orderId: "a", status: "PENDING_TRIGGER", order: null })).toBe("refetch");
  });
  it("ignores a fill for an unseen order, since its acceptance follows if it rests", () => {
    const list = [other];
    expect(applyOrderUpdate(list, { orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: "1" })).toBe(list);
  });
  it("reports a closed order it never had, so a lagging refetch cannot resurrect it", () => {
    expect(applyOrderUpdate([other], { orderId: "a", status: "FILLED" })).toBe("closed");
    expect(applyOrderUpdate(undefined, { orderId: "a", status: "NEW" })).toBeUndefined();
  });
  it("strips remembered closed orders from a loaded list, and leaves an untouched list alone", () => {
    const list = [row, other];
    expect(withoutClosed(list, new Set(["a"]))).toEqual([other]);
    expect(withoutClosed(list, new Set(["zzz"]))).toBe(list);
  });
  it("keeps a quote-sized order's remainder null rather than inventing a base quantity", () => {
    const quote = { ...row, remainingQuantity: null };
    expect(applyOrderUpdate([quote], { orderId: "a", status: "PARTIALLY_FILLED", remainingQuantity: "-1" })).toEqual([{ ...quote, status: "PARTIALLY_FILLED" }]);
  });
});

describe("triggerOf", () => {
  it("reads the direction the backend derived, never guessing from the type", () => {
    expect(triggerOf({ stopPrice: "60000", trailingDeltaBps: null, triggerDirection: "FALL" })).toEqual({ kind: "price", op: "≤", price: "60000" });
    expect(triggerOf({ stopPrice: "70000", trailingDeltaBps: null, triggerDirection: "RISE" })).toEqual({ kind: "price", op: "≥", price: "70000" });
  });
  it("shows a trailing order's distance and activation price, not a stop it cannot know", () => {
    expect(triggerOf({ stopPrice: "65000", trailingDeltaBps: 150, triggerDirection: "FALL" })).toEqual({ kind: "trailing", percent: "1.5%", activation: "65000" });
    expect(triggerOf({ stopPrice: null, trailingDeltaBps: 10, triggerDirection: "RISE" })).toEqual({ kind: "trailing", percent: "0.1%", activation: null });
  });
  it("has nothing to say about a plain order", () => {
    expect(triggerOf({ stopPrice: null, trailingDeltaBps: null, triggerDirection: null })).toBeNull();
  });
});

describe("toastForUpdate", () => {
  const row = { orderId: "a", symbol: "BTCUSDT", side: "BUY" } as Order;

  it("announces the outcomes a person is waiting on, and names the side from the row", () => {
    expect(toastForUpdate({ orderId: "a", symbol: "BTCUSDT", status: "FILLED", executedQuantity: "0.5" }, row))
      .toEqual({ tone: "success", kind: "filled", symbol: "BTCUSDT", side: "BUY", quantity: "0.5" });
    expect(toastForUpdate({ orderId: "a", symbol: "BTCUSDT", status: "REJECTED", rejectReason: "REJECT_REASON_WOULD_TAKE" }, row))
      .toMatchObject({ tone: "error", kind: "rejected", reason: "REJECT_REASON_WOULD_TAKE" });
    expect(toastForUpdate({ orderId: "a", symbol: "BTCUSDT", status: "EXPIRED" }, row))
      .toMatchObject({ tone: "info", kind: "expired" });
  });

  it("announces an order that rested, and a trigger that is armed", () => {
    // toEqual ignores undefined members, so this fails if a quantity or reason is attached.
    expect(toastForUpdate({ orderId: "a", symbol: "BTCUSDT", status: "NEW" }, row))
      .toEqual({ tone: "success", kind: "placed", symbol: "BTCUSDT", side: "BUY" });
    expect(toastForUpdate({ orderId: "a", symbol: "BTCUSDT", status: "PENDING_TRIGGER" }, row))
      .toEqual({ tone: "success", kind: "armed", symbol: "BTCUSDT", side: "BUY" });
  });

  /** The row is not in the list yet when it is accepted; the frame carries it instead. */
  it("names the side of an order the list has never seen, from the frame's own copy", () => {
    expect(toastForUpdate({ orderId: "a", symbol: "ETHUSDT", status: "NEW", order: { side: "SELL" } as Order }, undefined))
      .toEqual({ tone: "success", kind: "placed", symbol: "ETHUSDT", side: "SELL" });
  });

  it("says nothing on a partial fill -- one order can produce dozens", () => {
    expect(toastForUpdate({ orderId: "a", status: "PARTIALLY_FILLED", executedQuantity: "0.1" }, row)).toBeNull();
  });

  it("explains a cancel nobody asked for, and stays quiet about one they did", () => {
    // toEqual ignores undefined members, so this fails if a reason is ever attached.
    expect(toastForUpdate({ orderId: "a", status: "CANCELED", cancelReason: "CANCEL_REASON_USER_REQUESTED" }, row))
      .toEqual({ tone: "info", kind: "canceled", symbol: "BTCUSDT", side: "BUY" });
    expect(toastForUpdate({ orderId: "a", status: "CANCELED", cancelReason: "CANCEL_REASON_IOC_REMAINDER" }, row))
      .toMatchObject({ tone: "info", kind: "canceled", reason: "CANCEL_REASON_IOC_REMAINDER" });
  });

  it("reports both ends of an amend, whatever the status field says", () => {
    const amend = { rejectReason: null, price: "70", stopPrice: null, quantity: null };
    expect(toastForUpdate({ orderId: "a", status: "NEW", amend }, row)).toMatchObject({ tone: "success", kind: "amended" });
    expect(toastForUpdate({ orderId: "a", status: null, amend: { ...amend, rejectReason: "AMEND_REJECT_REASON_WOULD_TAKE" } }, row))
      .toMatchObject({ tone: "error", kind: "amendRejected", reason: "AMEND_REJECT_REASON_WOULD_TAKE" });
  });

  it("still names the market for an order that closed before the list ever held it", () => {
    expect(toastForUpdate({ orderId: "gone", symbol: "ETHUSDT", status: "FILLED" }, undefined))
      .toMatchObject({ kind: "filled", symbol: "ETHUSDT", side: undefined });
  });
});
