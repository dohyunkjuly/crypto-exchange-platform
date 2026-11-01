import { describe, expect, it } from "vitest";
import { compact, grouped, percent, price, signed } from "./index";

describe("format", () => {
  it("renders a price at the market's precision with thousands grouping", () => {
    expect(price("63250.1", 2)).toBe("63,250.10");
    expect(price("0.00001234", 8)).toBe("0.00001234");
    expect(price("1234567", 0)).toBe("1,234,567");
  });

  it("keeps the sign on negatives and groups only the whole part", () => {
    expect(price("-1234.5", 2)).toBe("-1,234.50");
  });

  it("groups without touching the precision the backend chose", () => {
    expect(grouped("1234567.12345678")).toBe("1,234,567.12345678");
    expect(grouped("5")).toBe("5");
  });

  it("signs a profit, keeps a loss's sign, and leaves zero alone", () => {
    expect(signed("1234.5", 2)).toBe("+1,234.50");
    expect(signed("-0.004", 2)).toBe("0.00");
    expect(signed("-0.01", 2)).toBe("-0.01");
    expect(signed("0", 2)).toBe("0.00");
  });

  it("signs a percent both ways", () => {
    expect(percent("2.50")).toBe("+2.50%");
    expect(percent("-0.10")).toBe("-0.10%");
    expect(percent("0.00")).toBe("+0.00%");
  });

  it("compacts volume for display and passes garbage through untouched", () => {
    expect(compact("1234567")).toBe("1.23M");
    expect(compact("950")).toBe("950");
    expect(compact("not a number")).toBe("not a number");
  });
});
