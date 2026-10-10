import { api } from "@/lib/bff/client";

/** Minimum the backend accepts (`@Size(min = 12)` on register and reset). Checked here too so
 *  a too-short password is a field error rather than a round trip and a 400 envelope. */
export const MIN_PASSWORD_LENGTH = 12;

export type User = {
  id: number;
  email: string;
  name: string | null;
  emailVerified: boolean;
  status: string;
};

/**
 * Login answers one of two things, and the discriminator is `mfaRequired`.
 *
 * When it is true there is **no session yet** -- only a `challengeToken` to exchange for one.
 * Treating this as success is how 2FA gets bypassed, so the type makes `user` unavailable on
 * that branch rather than merely null.
 */
export type LoginOutcome =
  | { mfaRequired: false; user: User }
  | { mfaRequired: true; challengeToken: string };

type LoginResponse = { mfaRequired: boolean; challengeToken: string | null; user: User | null };

function toOutcome(response: LoginResponse): LoginOutcome {
  if (response.mfaRequired) {
    // The server promises a token whenever it says a challenge is required; if that ever
    // breaks, failing here is better than rendering a challenge form that cannot submit.
    if (!response.challengeToken) {
      throw new Error("server asked for MFA without issuing a challenge token");
    }
    return { mfaRequired: true, challengeToken: response.challengeToken };
  }
  if (!response.user) {
    throw new Error("server reported a session with no user");
  }
  return { mfaRequired: false, user: response.user };
}

export async function login(email: string, password: string): Promise<LoginOutcome> {
  return toOutcome(await api<LoginResponse>("/bff/auth/login", {
    method: "POST",
    body: { email, password },
  }));
}

/** Second half of a two-phase login. Accepts a TOTP code or a recovery code -- the server
 *  tells them apart by shape, so there is one field and one endpoint. */
export async function completeMfaLogin(challengeToken: string, code: string): Promise<User> {
  return api<User>("/bff/auth/login/mfa", {
    method: "POST",
    body: { challengeToken, code },
  });
}

export async function signInWithGoogle(idToken: string): Promise<LoginOutcome> {
  return toOutcome(await api<LoginResponse>("/bff/auth/google", {
    method: "POST",
    body: { idToken },
  }));
}

/** 201 with the new user. Registering does not sign you in -- the caller logs in next. */
export async function register(email: string, password: string, name: string): Promise<User> {
  return api<User>("/bff/auth/register", {
    method: "POST",
    // An empty name is absent, not "": the column is nullable and a blank string would render
    // as a user with a name that is nothing.
    body: { email, password, name: name.trim() || null },
  });
}

/** The session check. Throws `BffError` with `NOT_AUTHENTICATED` when there is no session,
 *  which is what the account gate reads. */
export async function fetchMe(signal?: AbortSignal): Promise<User> {
  return api<User>("/bff/auth/me", { signal });
}

export async function logout(): Promise<void> {
  return api<void>("/bff/auth/session", { method: "DELETE" });
}

/** Every device, not just this one -- the remedy for a stolen session. */
export async function logoutEverywhere(): Promise<void> {
  return api<void>("/bff/auth/sessions", { method: "DELETE" });
}

export async function verifyEmail(code: string): Promise<void> {
  return api<void>("/bff/auth/verify-email", { method: "POST", body: { code } });
}

export async function resendVerification(): Promise<void> {
  return api<void>("/bff/auth/verify-email/resend", { method: "POST", body: {} });
}

/** Always succeeds, whether or not the address exists -- the server will not confirm which,
 *  so the UI must not either. */
export async function requestPasswordReset(email: string): Promise<void> {
  return api<void>("/bff/auth/password-reset", { method: "POST", body: { email } });
}

export async function confirmPasswordReset(args: {
  email: string;
  code: string;
  newPassword: string;
  mfaCode?: string;
}): Promise<void> {
  return api<void>("/bff/auth/password-reset/confirm", {
    method: "POST",
    body: { ...args, mfaCode: args.mfaCode?.trim() || null },
  });
}
