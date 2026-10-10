import { BFF_ORIGIN } from "@/lib/env";
import { BffError, BffUnreachableError } from "./errors";

/**
 * The only function in this app that performs an HTTP request.
 *
 * Features call `api(...)`; nothing else mentions a URL, a port, or the response envelope.
 * That is the BFF discipline and it is easy to break by accident -- the moment one
 * component does its own `fetch`, the rules below stop being guaranteed.
 */

/** `HttpApiResponse<T>` from libs/core: every response, success or failure, arrives in
 *  this shape because `HttpApiResponseBodyAdvice` wraps even a bare DTO return. */
type Envelope<T> = {
  status: string;
  code: number;
  data: T | null;
  message: string | null;
};

type Options = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** Aborts the request -- wire React Query's signal through so a navigation away does
   *  not leave a request running. */
  signal?: AbortSignal;
  /** Trade or read as the customer's lead account, not their own (docs/reference/copy-trading.md).
   *  Only orders, trades and balances listen; everything else is the customer's whichever is named. */
  account?: "lead";
};

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const { method = "GET", body, signal, account } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (account) headers["X-Trading-Account"] = account;

  let response: Response;
  try {
    response = await fetch(`${BFF_ORIGIN}${path}`, {
      method,
      signal,
      // NOT optional. The session is an httpOnly cookie on web-bff's origin, and a
      // cross-origin fetch sends no cookies unless asked -- without this every
      // authenticated call comes back NOT_AUTHENTICATED and the cause is invisible.
      credentials: "include",
      headers: Object.keys(headers).length ? headers : undefined,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    // An aborted request is not a failure -- let React Query see the AbortError it expects
    // rather than a "backend is down" message the user should never be shown.
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new BffUnreachableError(cause);
  }

  // 204 from a logout, a cancel, an unlink. There is no body to parse and no data to
  // return; parsing anyway throws on an empty stream.
  if (response.status === 204) {
    return undefined as T;
  }

  let envelope: Envelope<T>;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    // A non-JSON body means something upstream answered instead of the app -- a proxy
    // error page, a 502. Reporting it as a parse failure sends whoever debugs this to the
    // wrong place, so say what it actually was.
    throw new BffError(
      -1,
      response.status,
      `web-bff returned ${response.status} with a non-JSON body`,
    );
  }

  if (!response.ok) {
    throw new BffError(
      envelope.code,
      response.status,
      envelope.message ?? `Request failed (${response.status})`,
    );
  }

  return envelope.data as T;
}
