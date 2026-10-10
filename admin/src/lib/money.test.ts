import { describe, expect, it } from "vitest";
import { amount, isZero } from "./money";

describe("amount", () => {
  it("pads to the asset's own precision, so a column of numbers lines up", () => {
    expect(amount("600", 2)).toBe("600.00");
    expect(amount("0.5", 8)).toBe("0.50000000");
  });

  it("keeps every digit it was given: nothing is rounded in the browser", () => {
    expect(amount("0.00000031", 8)).toBe("0.00000031");
    expect(amount("123456789.87654321", 8)).toBe("123,456,789.87654321");
  });

  it("groups thousands and keeps a sign", () => {
    expect(amount("1234567", 0)).toBe("1,234,567");
    expect(amount("-2500.5", 2)).toBe("-2,500.50");
  });
});

describe("isZero", () => {
  it("is true however zero is written", () => {
    for (const z of ["0", "0.00", "-0", "0.00000000"]) expect(isZero(z), z).toBe(true);
    for (const n of ["0.00000001", "-3", "10"]) expect(isZero(n), n).toBe(false);
  });
});
