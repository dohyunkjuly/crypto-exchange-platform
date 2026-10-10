import { describe, expect, it } from "vitest";
import type { KycStatus } from "../shared/api";
import { apiKeyBlockers, expiresAtFor, keyStatus, maskKey, parseAllowlist } from "./apiKeyMath";

const NOW = new Date("2026-09-13T00:00:00Z");
const kyc = (level: string, expiresAt: string | null = null): KycStatus =>
  ({ status: "APPROVED", level, submittedAt: null, reviewedAt: null, expiresAt, rejectionReason: null });

describe("parseAllowlist", () => {
  it("takes lines, commas or spaces, and drops blanks and repeats", () => {
    expect(parseAllowlist("1.2.3.4\n10.0.0.0/8, 1.2.3.4  \n\n 5.6.7.8")).toEqual(["1.2.3.4", "10.0.0.0/8", "5.6.7.8"]);
    expect(parseAllowlist("  \n ")).toEqual([]);
  });
});

describe("expiresAtFor", () => {
  it("counts whole days from now, and never means none", () => {
    expect(expiresAtFor("30", NOW)).toBe("2026-10-13T00:00:00.000Z");
    expect(expiresAtFor("never", NOW)).toBeNull();
  });
});

describe("keyStatus", () => {
  it("puts a revocation before an expiry", () => {
    expect(keyStatus({ revokedAt: "2026-01-01T00:00:00Z", expiresAt: "2026-01-02T00:00:00Z" }, NOW)).toBe("revoked");
    expect(keyStatus({ revokedAt: null, expiresAt: "2026-09-12T23:59:59Z" }, NOW)).toBe("expired");
    expect(keyStatus({ revokedAt: null, expiresAt: null }, NOW)).toBe("active");
  });
});

describe("maskKey", () => {
  it("keeps the ends and hides the middle", () => {
    expect(maskKey("AbCdEf0123456789WxYz")).toBe("AbCdEf…WxYz");
    expect(maskKey("short")).toBe("short");
  });
});

describe("apiKeyBlockers", () => {
  it("wants INTERMEDIATE, not the BASIC that trading needs", () => {
    expect(apiKeyBlockers(kyc("BASIC"), { enabled: true, recoveryCodesRemaining: 8, enabledAt: null }, NOW)).toEqual(["kyc"]);
    expect(apiKeyBlockers(kyc("INTERMEDIATE"), { enabled: true, recoveryCodesRemaining: 8, enabledAt: null }, NOW)).toEqual([]);
    expect(apiKeyBlockers(kyc("ADVANCED"), { enabled: true, recoveryCodesRemaining: 8, enabledAt: null }, NOW)).toEqual([]);
  });
  it("does not count a lapsed verification, and lists 2FA after it", () => {
    expect(apiKeyBlockers(kyc("INTERMEDIATE", "2026-09-01T00:00:00Z"), { enabled: false, recoveryCodesRemaining: 0, enabledAt: null }, NOW)).toEqual(["kyc", "mfa"]);
  });
});
