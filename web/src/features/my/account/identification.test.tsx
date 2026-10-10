import { describe, expect, it } from "vitest";
import { COUNTRY_CODES, countryOptions } from "@/lib/i18n/countries";
import { canSubmit } from "./Identification";

const kyc = (status: string) => ({ status, level: "NONE", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null });

describe("canSubmit", () => {
  it("offers the form only before a submission and after a rejection", () => {
    expect(canSubmit(kyc("NOT_STARTED"))).toBe(true);
    expect(canSubmit(kyc("REJECTED"))).toBe(true);
    // Resubmitting an approved profile would drop it to PENDING and revoke trading (2016).
    for (const s of ["PENDING", "IN_REVIEW", "APPROVED"]) expect(canSubmit(kyc(s)), s).toBe(false);
  });
});

describe("countries", () => {
  it("are all the backend's format, with no duplicates", () => {
    for (const c of COUNTRY_CODES) expect(c, c).toMatch(/^[A-Z]{2}$/);
    expect(new Set(COUNTRY_CODES).size).toBe(COUNTRY_CODES.length);
  });
  it("are named in the viewer's language and sorted by that name", () => {
    const ko = countryOptions("ko");
    expect(ko.find((c) => c.code === "KR")?.name).toBe("대한민국");
    const en = countryOptions("en");
    expect(en.find((c) => c.code === "KR")?.name).toBe("South Korea");
    const names = en.map((c) => c.name);
    expect(names).toEqual([...names].sort(new Intl.Collator("en").compare));
  });
});
