import { describe, expect, it } from "vitest";
import { BffError } from "./errors";

describe("BffError.isUnauthenticated", () => {
  it("is only the missing-session code, so a wrong password or 2FA code still gets shown", () => {
    expect(new BffError(2004, 401, "no session").isUnauthenticated).toBe(true);
    expect(new BffError(2001, 401, "bad password").isUnauthenticated).toBe(false);
    expect(new BffError(2009, 401, "bad code").isUnauthenticated).toBe(false);
  });
});
