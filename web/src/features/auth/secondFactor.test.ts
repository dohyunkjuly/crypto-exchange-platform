import { describe, expect, it } from "vitest";
import { BffError } from "@/lib/bff/errors";
import { needsSecondFactor } from "./secondFactor";

describe("needsSecondFactor", () => {
  it("asks for the code when none was sent and the server wants one", () => {
    expect(needsSecondFactor(new BffError(2009, 401, "x"), undefined)).toBe(true);
    expect(needsSecondFactor(new BffError(2009, 401, "x"), "")).toBe(true);
    expect(needsSecondFactor(new BffError(2008, 403, "x"), undefined)).toBe(true);
  });
  it("treats a refused code that was sent as a wrong code, to show as an error", () => {
    expect(needsSecondFactor(new BffError(2009, 401, "x"), "123456")).toBe(false);
  });
  it("ignores everything else, including a bad emailed code", () => {
    expect(needsSecondFactor(new BffError(2020, 400, "x"), undefined)).toBe(false);
    expect(needsSecondFactor(new Error("network"), undefined)).toBe(false);
    expect(needsSecondFactor(null, undefined)).toBe(false);
  });
});
