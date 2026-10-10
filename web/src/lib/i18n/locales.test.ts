import { describe, expect, it } from "vitest";
import { localeFromPath, localePath, matchLocale, swapLocale } from "./locales";

describe("matchLocale", () => {
  it("honours q-weights and regional tags", () => {
    expect(matchLocale("ko-KR,ko;q=0.9,en-US;q=0.8")).toBe("ko");
    expect(matchLocale("en-GB,en;q=0.9,ko;q=0.8")).toBe("en");
    // ko outranks en here despite coming later, because of its weight
    expect(matchLocale("en;q=0.5,ko;q=0.9")).toBe("ko");
  });
  it("falls back to English rather than guessing", () => {
    expect(matchLocale("fr-FR,fr;q=0.9")).toBe("en");
    expect(matchLocale(null)).toBe("en");
    expect(matchLocale("")).toBe("en");
  });
});

describe("paths", () => {
  it("reads and writes the locale prefix", () => {
    expect(localeFromPath("/ko/login")).toBe("ko");
    expect(localeFromPath("/login")).toBeNull();
    expect(localePath("ko", "/wallet")).toBe("/ko/wallet");
    expect(localePath("ko", "/")).toBe("/ko");
    // already prefixed: left alone, so a ?next= that carries a locale is not doubled
    expect(localePath("en", "/ko/wallet")).toBe("/ko/wallet");
    expect(swapLocale("/ko/trade/BTCUSDT", "en")).toBe("/en/trade/BTCUSDT");
    expect(swapLocale("/ko", "en")).toBe("/en");
  });
});
