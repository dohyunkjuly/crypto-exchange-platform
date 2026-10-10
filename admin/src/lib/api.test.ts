import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminApiError, api } from "./api";

function answering(status: number, body: unknown) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

afterEach(() => vi.unstubAllGlobals());

describe("api", () => {
  it("sends the cookie and the step-up code, and unwraps the envelope", async () => {
    const fetched = answering(200, { status: "OK", code: 0, data: { id: 1 }, message: null });
    vi.stubGlobal("fetch", fetched);

    await expect(api("/admin/x", { method: "PATCH", body: { a: 1 }, totp: "123456" })).resolves.toEqual({ id: 1 });
    const [url, init] = fetched.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://localhost:8083/admin/x");
    expect(init.credentials).toBe("include");
    expect((init.headers as Record<string, string>)["X-Admin-TOTP"]).toBe("123456");
  });

  it("turns admin-bff's error into one that says whether the session is gone", async () => {
    vi.stubGlobal("fetch", answering(401, { status: "ERROR", code: 2004, data: null, message: "No valid session" }));

    const error = await api("/admin/auth/me").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(AdminApiError);
    expect((error as AdminApiError).notAuthenticated).toBe(true);
    expect((error as AdminApiError).message).toBe("No valid session");
  });
});
