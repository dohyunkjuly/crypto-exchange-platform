import { describe, expect, it } from "vitest";
import { safeNextPath } from "./nextPath";

describe("safeNextPath", () => {
  it("keeps a path on this site", () => {
    expect(safeNextPath("?next=/orders")).toBe("/orders");
    expect(safeNextPath("?next=%2Ftrade%2FBTCUSDT%3Fgrouping%3D0.1"))
      .toBe("/trade/BTCUSDT?grouping=0.1");
  });

  it("falls back when nothing was asked for", () => {
    expect(safeNextPath("")).toBe("/my/dashboard");
    expect(safeNextPath("?next=")).toBe("/my/dashboard");
    expect(safeNextPath("?other=/orders")).toBe("/my/dashboard");
  });

  it("refuses an absolute URL to another origin", () => {
    // The attack: /login?next=https://evil.example/ would send someone who has just
    // authenticated to an attacker's page, with our domain as the referrer.
    expect(safeNextPath("?next=https://evil.example/")).toBe("/my/dashboard");
    expect(safeNextPath("?next=http://evil.example/")).toBe("/my/dashboard");
  });

  it("refuses a protocol-relative URL", () => {
    // What a naive startsWith("/") lets through -- the browser reads //host as absolute.
    expect(safeNextPath("?next=//evil.example/")).toBe("/my/dashboard");
    expect(safeNextPath("?next=//evil.example")).toBe("/my/dashboard");
  });

  it("refuses anything that is not a path", () => {
    for (const hostile of ["javascript:alert(1)", "data:text/html,x", "orders", "\\\\evil.example"]) {
      expect(safeNextPath(`?next=${encodeURIComponent(hostile)}`), hostile).toBe("/my/dashboard");
    }
  });
});
