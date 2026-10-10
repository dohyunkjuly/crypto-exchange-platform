import { describe, expect, it } from "vitest";
import type { PublicTrade } from "@/features/markets/api";
import { bidShare, cumulative, lastDirection, prependTrade } from "./bookMath";

const tr = (sequence: number, price: string): PublicTrade => ({ sequence, price, quantity: "1", quoteQuantity: price, takerSide: "BUY", time: "2026-09-13T00:00:00Z" });

describe("cumulative", () => {
  it("runs a total from the top and sizes each bar by its share", () => {
    const rows = cumulative([{ price: "10", quantity: "1" }, { price: "9", quantity: "3" }]);
    expect(rows.map((r) => r.total)).toEqual(["1", "4"]);
    expect(rows.map((r) => r.share)).toEqual([0.25, 1]);
  });
  it("copes with an empty side", () => { expect(cumulative([])).toEqual([]); });
});

describe("bidShare", () => {
  it("is the bids' part of both sides, and half when the book is empty", () => {
    expect(bidShare([{ price: "1", quantity: "3" }], [{ price: "2", quantity: "1" }])).toBe(0.75);
    expect(bidShare([], [])).toBe(0.5);
  });
});

describe("lastDirection", () => {
  it("compares the two newest trades", () => {
    expect(lastDirection([tr(2, "11"), tr(1, "10")])).toBe("up");
    expect(lastDirection([tr(2, "9"), tr(1, "10")])).toBe("down");
    expect(lastDirection([tr(1, "10")])).toBe("flat");
  });
});

describe("prependTrade", () => {
  it("adds newest first, caps, and drops a repeat of a trade already shown", () => {
    const list = prependTrade([tr(1, "10")], tr(2, "11"), 2);
    expect(list.map((t) => t.sequence)).toEqual([2, 1]);
    expect(prependTrade(list, tr(2, "11"), 2)).toBe(list);
    expect(prependTrade(list, tr(3, "12"), 2).map((t) => t.sequence)).toEqual([3, 2]);
  });
});
