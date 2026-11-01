import { describe, expect, it } from "vitest";
import { below, compareFigures, exceeds, tone, withdrawable } from "./copyMath";

describe("copyMath", () => {
  it("takes the minimum off what is held, exactly", () => {
    // The float version showed 0.29999999999999716 here.
    expect(withdrawable("100.3", "100")).toBe("0.3");
    expect(withdrawable("100", "100")).toBe("0");
    expect(withdrawable("99.99999999", "100")).toBe("0");
    expect(withdrawable("0.00000001", "0")).toBe("0.00000001");
  });

  it("treats an untyped or malformed amount as neither too much nor too little", () => {
    expect(exceeds("", "10")).toBe(false);
    expect(exceeds("1.2.3", "10")).toBe(false);
    expect(below("", "10")).toBe(false);
    expect(exceeds("10.00000001", "10")).toBe(true);
    expect(exceeds("10", "10")).toBe(false);
    expect(below("9.99999999", "10")).toBe(true);
  });

  it("colours a profit by its sign and nothing at zero", () => {
    expect(tone("0")).toBe("");
    expect(tone("0.00")).toBe("");
    expect(tone("12.5")).toBe("text-up");
    expect(tone("-0.00000001")).toBe("text-down");
  });

  it("ranks larger figures first, smaller drawdown first, and missing figures last", () => {
    expect(compareFigures("2", "10", "other")).toBeGreaterThan(0);
    expect(compareFigures("10", "2", "maxDrawdown")).toBeGreaterThan(0);
    expect(compareFigures(null, "2", "other")).toBeGreaterThan(0);
    expect(compareFigures("2", null, "other")).toBeLessThan(0);
    expect(compareFigures(null, undefined, "other")).toBe(0);
    // A satoshi apart still sorts: a float would call these equal past 2^53.
    expect(compareFigures("90071992547409.92", "90071992547409.93", "other")).toBeGreaterThan(0);
  });
});
