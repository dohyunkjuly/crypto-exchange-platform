// @vitest-environment node
import { describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import { config, proxy } from "./proxy";

const req = (path: string, headers: Record<string, string> = {}, cookie?: string) =>
  new NextRequest(`http://localhost:3000${path}`, {
    headers: { ...headers, ...(cookie ? { cookie } : {}) },
  });

describe("locale proxy", () => {
  it("sends a bare path to the browser's language", () => {
    const res = proxy(req("/login", { "accept-language": "ko-KR,ko;q=0.9" }));
    expect(res.headers.get("location")).toBe("http://localhost:3000/ko/login");
  });
  it("sends the root to /en by default", () => {
    expect(proxy(req("/")).headers.get("location")).toBe("http://localhost:3000/en");
  });
  it("prefers a saved choice over the browser's language", () => {
    const res = proxy(req("/wallet", { "accept-language": "ko" }, "NEXT_LOCALE=en"));
    expect(res.headers.get("location")).toBe("http://localhost:3000/en/wallet");
  });
  it("leaves a localized path alone and remembers it", () => {
    const res = proxy(req("/ko/login"));
    expect(res.headers.get("location")).toBeNull();
    expect(res.headers.get("set-cookie")).toContain("NEXT_LOCALE=ko");
  });
  it("never runs for assets, or every icon would 404", () => {
    const [pattern] = config.matcher;
    const re = new RegExp(`^${pattern.replace(/:path\*/g, ".*")}$`);
    for (const asset of ["/icon.svg", "/apple-icon.png", "/logo/x/y.svg", "/_next/static/a.js"]) {
      expect(re.test(asset), asset).toBe(false);
    }
    expect(re.test("/login")).toBe(true);
  });
});
