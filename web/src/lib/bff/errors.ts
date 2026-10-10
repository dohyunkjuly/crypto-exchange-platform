/**
 * The numeric codes web-bff attaches to a failed response.
 *
 * <p>A mirror of `BffHttpErrorCode` on the backend, and only of the codes this client
 * actually branches on -- copying all sixty would be sixty chances to drift. Features
 * compare against these, **never against message text**: the messages are user-facing copy
 * and will be reworded without anyone thinking to grep the frontend.
 *
 * The ranges come from the backend's own scheme: 2000s auth, 5000s orders, 6000s market
 * data.
 */
export const BffCode = {
  INVALID_CREDENTIALS: 2001,
  TOO_MANY_ATTEMPTS: 2002,
  EMAIL_ALREADY_REGISTERED: 2003,
  NOT_AUTHENTICATED: 2004,
  KYC_REQUIRED: 2007,
  MFA_REQUIRED: 2008,
  INVALID_MFA_CODE: 2009,
  MFA_ALREADY_ENABLED: 2010,
  MFA_NOT_ENABLED: 2011,
  /** The code may be right; the half-finished login it belonged to is gone. Start again
   *  from the password rather than prompting for another code. */
  MFA_CHALLENGE_EXPIRED: 2013,
  INVALID_VERIFICATION_CODE: 2014,
  EMAIL_NOT_VERIFIED: 2015,
  INVALID_RESET_CODE: 2020,
  CODE_REQUESTED_TOO_SOON: 2021,
  INVALID_GOOGLE_TOKEN: 2017,
  GOOGLE_SIGN_IN_UNAVAILABLE: 2018,
  /** The address belongs to an account this token may not claim; sign in by password and link
   *  Google from settings. The one Google failure the user can actually resolve. */
  ACCOUNT_EXISTS_USE_PASSWORD: 2019,
  GOOGLE_NOT_LINKED: 2022,
  PASSWORD_REQUIRED_BEFORE_UNLINK: 2023,
  RATE_LIMIT_EXCEEDED: 3001,
  INSUFFICIENT_BALANCE: 4001,
  SYMBOL_NOT_TRADING: 5002,
  ORDER_NOT_FOUND: 5015,
  ORDER_NOT_CANCELABLE: 5016,
} as const;

/**
 * A failed BFF call, carrying the backend's own code.
 *
 * `message` is the backend's text and is safe to show -- these are written as user-facing
 * copy, not internal detail. Prefer mapping the code to your own wording where the
 * surrounding UI can say something more specific.
 */
export class BffError extends Error {
  constructor(
    readonly code: number,
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "BffError";
  }

  /** The session is gone: revoked, expired, or never there. Callers do not render this --
   *  the account gate redirects instead. By code, never by status: a wrong password (2001) and a
   *  wrong 2FA code (2009) are 401s too, and those must be shown. */
  get isUnauthenticated(): boolean {
    return this.code === BffCode.NOT_AUTHENTICATED;
  }
}

/** The network never answered: offline, DNS, CORS, backend down. Distinct from a BffError,
 *  which means the backend answered and said no. */
export class BffUnreachableError extends Error {
  constructor(cause: unknown) {
    super("Could not reach the exchange. Check your connection.");
    this.name = "BffUnreachableError";
    this.cause = cause;
  }
}
