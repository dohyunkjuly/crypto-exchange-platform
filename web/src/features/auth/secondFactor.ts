import { BffCode, BffError } from "@/lib/bff/errors";

/**
 * Whether a password reset was refused for want of the second factor, so the form should ask for it.
 * The server answers a missing code with INVALID_MFA_CODE (2009), the same as a wrong one, so 2009
 * counts only when no code was sent. A bad emailed code is INVALID_RESET_CODE (2020), never 2009.
 */
export function needsSecondFactor(error: unknown, sentMfaCode: string | undefined): boolean {
  if (!(error instanceof BffError)) return false;
  return error.code === BffCode.MFA_REQUIRED || (error.code === BffCode.INVALID_MFA_CODE && !sentMfaCode);
}
