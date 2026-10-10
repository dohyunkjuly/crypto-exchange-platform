import { ADMIN_BFF_ORIGIN } from "./env";

/** admin-bff's error, with its code. 2004 is "no session"; the caller sends the admin to /login. */
export class AdminApiError extends Error {
  constructor(
    readonly code: number,
    readonly status: number,
    message: string,
  ) {
    super(message);
  }

  get notAuthenticated(): boolean {
    return this.code === 2004;
  }
}

type Envelope<T> = { status: string; code: number; data: T | null; message: string | null };

type Options = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** A fresh authenticator code, for an endpoint that moves money or a price. */
  totp?: string;
};

/**
 * The only function here that performs an HTTP request. `credentials: "include"`: the session
 * is an httpOnly cookie on admin-bff's origin, and every call is cross-origin. A mutation is
 * never retried.
 */
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const { method = "GET", body, totp } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (totp) headers["X-Admin-TOTP"] = totp;

  let response: Response;
  try {
    response = await fetch(`${ADMIN_BFF_ORIGIN}${path}`, {
      method,
      credentials: "include",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    throw new AdminApiError(-1, 0, `admin-bff is unreachable (${String(cause)})`);
  }
  if (response.status === 204) return undefined as T;

  let envelope: Envelope<T>;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    throw new AdminApiError(-1, response.status, `admin-bff returned ${response.status} with no JSON`);
  }
  if (!response.ok) {
    throw new AdminApiError(envelope.code, response.status, envelope.message ?? `Request failed (${response.status})`);
  }
  return envelope.data as T;
}

/** A page of a list too large to count: whether another follows, not how many. */
export type Chunk<T> = { items: T[]; page: number; size: number; more: boolean };
