import { describe, expect, it } from "vitest";
import type { Order } from "./api";
import { clampRange, filterOpen, inKind, lastDays, toInstants } from "./filterMath";

const TODAY = "2026-09-12";

describe("date ranges", () => {
  it("counts today as one of the last n days", () => {
    expect(lastDays(1, TODAY)).toEqual({ from: TODAY, to: TODAY });
    expect(lastDays(90, TODAY)).toEqual({ from: "2026-06-15", to: TODAY });
  });
  it("pulls the untouched end in when a pick would exceed 90 days", () => {
    expect(clampRange({ from: "2026-01-01", to: TODAY }, "from", TODAY)).toEqual({ from: "2026-01-01", to: "2026-03-31" });
    expect(clampRange({ from: "2026-01-01", to: TODAY }, "to", TODAY)).toEqual({ from: "2026-06-15", to: TODAY });
  });
  it("never runs backwards or into the future", () => {
    expect(clampRange({ from: "2026-09-10", to: "2026-09-01" }, "from", TODAY)).toEqual({ from: "2026-09-10", to: "2026-09-10" });
    expect(clampRange({ from: "2026-09-01", to: "2026-12-01" }, "to", TODAY)).toEqual({ from: "2026-09-01", to: TODAY });
  });
  it("sends a window the server accepts, whole days and at most 90 of them", () => {
    const { from, to } = toInstants(lastDays(90, TODAY));
    expect(new Date(to).getTime() - new Date(from).getTime()).toBeLessThanOrEqual(90 * 86_400_000);
    const day = toInstants({ from: TODAY, to: TODAY });
    expect(new Date(day.to).getTime() - new Date(day.from).getTime()).toBe(86_400_000);
  });
});


describe("filterOpen", () => {
  const o = (symbol: string, side: "BUY" | "SELL", orderType: string) => ({ orderId: symbol + side + orderType, symbol, side, orderType }) as Order;
  const orders = [o("BTCUSDT", "BUY", "LIMIT"), o("BTCUSDT", "SELL", "STOP_LOSS_LIMIT"), o("ETHUSDT", "SELL", "LIMIT")];
  it("combines every filter, and an empty one means any", () => {
    expect(filterOpen(orders, { symbol: "", side: "", type: "" })).toHaveLength(3);
    expect(filterOpen(orders, { symbol: "BTCUSDT", side: "SELL", type: "" }).map((x) => x.orderType)).toEqual(["STOP_LOSS_LIMIT"]);
    expect(filterOpen(orders, { symbol: "", side: "", type: "LIMIT" })).toHaveLength(2);
  });
});

describe("inKind", () => {
  it("puts only parked orders in the trigger tab, so a fired stop moves to the book tab", () => {
    expect(inKind({ status: "PENDING_TRIGGER" }, "triggers")).toBe(true);
    expect(inKind({ status: "PENDING_TRIGGER" }, "book")).toBe(false);
    for (const status of ["NEW", "PARTIALLY_FILLED", "PENDING_CANCEL"]) {
      expect(inKind({ status }, "book"), status).toBe(true);
      expect(inKind({ status }, "triggers"), status).toBe(false);
    }
  });
});
