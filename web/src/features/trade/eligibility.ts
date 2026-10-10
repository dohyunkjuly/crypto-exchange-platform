import type { KycStatus, MfaStatus } from "@/features/my/shared/api";

const LEVELS = ["NONE", "BASIC", "INTERMEDIATE", "ADVANCED"];
export type TradeBlocker = "email" | "kyc" | "mfa";

/**
 * What still stands between this account and placing an order, in the server's order (EligibilityService):
 * a verified email, KYC BASIC that has not lapsed, then an active second factor. Only explains a disabled
 * button -- the server decides. API keys have their own, higher bar in features/my/apiKeyMath.
 */
export function tradeBlockers(emailVerified: boolean, kyc: KycStatus, mfa: MfaStatus, now: Date): TradeBlocker[] {
  const out: TradeBlocker[] = [];
  if (!emailVerified) out.push("email");
  const current = !kyc.expiresAt || Date.parse(kyc.expiresAt) > now.getTime();
  if (LEVELS.indexOf(kyc.level) < LEVELS.indexOf("BASIC") || !current) out.push("kyc");
  if (!mfa.enabled) out.push("mfa");
  return out;
}
