import { describe, expect, it } from "vitest";
import { add, compare, divide, isValidDecimal, multiply, roundToStep, scaleOf, subtract, toFixed } from "./index";

/**
 * The whole reason this module exists is that the obvious implementation is wrong, so the
 * tests are written against the cases a float would fail rather than against round trips.
 */
describe("money", () => {
  it("adds the numbers a float gets wrong", () => {
    // 0.1 + 0.2 === 0.30000000000000004 in IEEE 754. This is the entire argument.
    expect(add("0.1", "0.2")).toBe("0.3");
    expect(add("0.07", "0.01")).toBe("0.08");
  });

  it("keeps precision past what a double can hold", () => {
    // 2^53 is where a double starts skipping integers. Satoshi-scale balances live here:
    // 21 million BTC at 1e8 is 2.1e15, comfortably past it.
    expect(add("9007199254740993", "1")).toBe("9007199254740994");
    expect(multiply("21000000.00000000", "100000000")).toBe("2100000000000000.00000000");
  });

  it("widens scale rather than truncating it", () => {
    expect(add("1.5", "2.25")).toBe("3.75");
    expect(subtract("1", "0.00000001")).toBe("0.99999999");
  });

  it("multiplies exactly, adding scales", () => {
    expect(multiply("0.1", "0.2")).toBe("0.02");
    // A real notional: price x quantity has to agree with what the backend charged.
    expect(multiply("63250.01", "0.5")).toBe("31625.005");
  });

  it("compares without converting to a number", () => {
    expect(compare("10", "9")).toBe(1);
    // The trap: "9.9" > "10" as strings, and parseFloat would be right here but wrong
    // for the long values above.
    expect(compare("9.9", "10")).toBe(-1);
    expect(compare("1.10", "1.1")).toBe(0);
    expect(compare("-1", "1")).toBe(-1);
  });

  describe("roundToStep", () => {
    it("rounds down for bids and up for asks, both away from the spread", () => {
      // An order book groups bids down and asks up so a grouped level never advertises a
      // better price than actually exists.
      expect(roundToStep("63250.37", "1", "down")).toBe("63250");
      expect(roundToStep("63250.37", "1", "up")).toBe("63251");
      expect(roundToStep("63250.37", "0.1", "down")).toBe("63250.3");
      expect(roundToStep("63250.37", "50", "down")).toBe("63250");
      expect(roundToStep("63280.00", "50", "down")).toBe("63250");
    });

    it("leaves a value already on the step alone", () => {
      expect(roundToStep("63250", "50", "up")).toBe("63250");
      expect(roundToStep("63250", "50", "down")).toBe("63250");
    });

    it("floors negatives downward rather than toward zero", () => {
      // BigInt division truncates toward zero, so -0.5 rounded "down" by 1 would come out
      // as 0 if this were left to the default. Balances can go negative in a delta.
      expect(roundToStep("-0.5", "1", "down")).toBe("-1");
      expect(roundToStep("-0.5", "1", "up")).toBe("0");
    });

    it("refuses a step that cannot divide anything", () => {
      expect(() => roundToStep("1", "0", "down")).toThrow();
      expect(() => roundToStep("1", "-1", "down")).toThrow();
    });
  });

  describe("toFixed", () => {
    it("pads to the column width a price table needs", () => {
      expect(toFixed("63250", 2)).toBe("63250.00");
      expect(toFixed("0.5", 8)).toBe("0.50000000");
    });

    it("rounds half away from zero", () => {
      expect(toFixed("0.125", 2)).toBe("0.13");
      expect(toFixed("0.124", 2)).toBe("0.12");
      expect(toFixed("-0.125", 2)).toBe("-0.13");
    });
  });

  it("reads a symbol's display precision off its tick size", () => {
    expect(scaleOf("0.01")).toBe(2);
    expect(scaleOf("1")).toBe(0);
  });

  it("rejects anything that is not a plain decimal", () => {
    for (const bad of ["", "abc", "1e9", "1,000", "1.2.3", "0x10", " ", "--1", "1."]) {
      expect(isValidDecimal(bad), bad).toBe(false);
      expect(() => add(bad, "1"), bad).toThrow();
    }
    // Exponents deserve their own mention: accepting "1e9" as a price would be generous
    // in precisely the wrong direction.
    expect(isValidDecimal("1e9")).toBe(false);
  });

  it("accepts the shapes web-bff actually sends", () => {
    for (const good of ["0", "-0", "1", "0.00000001", "-12.5", "2100000000000000.00000000"]) {
      expect(isValidDecimal(good), good).toBe(true);
    }
  });
});

describe("divide", () => {
  it("divides to the asked precision, rounding down", () => {
    expect(divide("1000", "63250.5", 6)).toBe("0.015810");
    expect(divide("1", "3", 4)).toBe("0.3333");
    expect(divide("7.5", "2.5", 0)).toBe("3");
  });
  it("refuses a zero divisor", () => {
    expect(() => divide("1", "0", 2)).toThrow();
  });
});
