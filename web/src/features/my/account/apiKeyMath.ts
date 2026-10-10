import type { ApiKey, KycStatus, MfaStatus } from "../shared/api";

/** One address or range per line, or comma- or space-separated; blanks and repeats dropped. The server validates each. */
export function parseAllowlist(text: string): string[] {
  return [...new Set(text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean))];
}

export type Expiry = "never" | "30" | "90" | "365";
/** The instant the server stores, `days` from `now`; "never" is null. */
export const expiresAtFor = (expiry: Expiry, now: Date): string | null =>
  expiry === "never" ? null : new Date(now.getTime() + Number(expiry) * 86_400_000).toISOString();

export type KeyStatus = "active" | "revoked" | "expired";
export function keyStatus(key: Pick<ApiKey, "revokedAt" | "expiresAt">, now: Date): KeyStatus {
  if (key.revokedAt) return "revoked";
  if (key.expiresAt && Date.parse(key.expiresAt) <= now.getTime()) return "expired";
  return "active";
}

/** Enough to recognise a key by, not enough to read it off someone's screen. */
export const maskKey = (key: string) => (key.length <= 12 ? key : `${key.slice(0, 6)}…${key.slice(-4)}`);

const LEVELS = ["NONE", "BASIC", "INTERMEDIATE", "ADVANCED"];
export type Blocker = "kyc" | "mfa";

/**
 * What still stands between this user and a key, in the order EligibilityService checks: a level
 * of INTERMEDIATE that has not lapsed, then an active second factor. Only explains a disabled
 * button -- the server decides. A verified email is already guaranteed inside /my.
 */
export function apiKeyBlockers(kyc: KycStatus, mfa: MfaStatus, now: Date): Blocker[] {
  const current = !kyc.expiresAt || Date.parse(kyc.expiresAt) > now.getTime();
  const blockers: Blocker[] = [];
  if (LEVELS.indexOf(kyc.level) < LEVELS.indexOf("INTERMEDIATE") || !current) blockers.push("kyc");
  if (!mfa.enabled) blockers.push("mfa");
  return blockers;
}
