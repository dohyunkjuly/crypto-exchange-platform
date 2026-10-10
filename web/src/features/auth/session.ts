"use client";

import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchMe, logout, logoutEverywhere, type User } from "./api";
import { BffError } from "@/lib/bff/errors";

/** One cache entry for "who am I". Every screen reads the same answer, and a logout clears it
 *  everywhere at once. This entry IS the app's auth state; there is no context on top of it. */
export const SESSION_KEY = ["session"] as const;

/**
 * Declared once so `useSession` and `useSessionQuery` cannot drift apart.
 *
 * `retry: false` because a 401 here is the answer, not a failure -- retrying hammers a dead
 * session and delays the redirect. `staleTime` long enough that page changes do not re-ask,
 * short enough that a session revoked elsewhere surfaces quickly; refetch-on-focus covers the rest.
 */
export const sessionQueryOptions = queryOptions({
  queryKey: SESSION_KEY,
  queryFn: ({ signal }) => fetchMe(signal),
  retry: false,
  staleTime: 30_000,
});

export type Session =
  | { status: "loading" }
  | { status: "signedIn"; user: User }
  | { status: "signedOut" };

/** Who the viewer is, according to web-bff. Asking the server is the only way to know: the
 *  cookie is httpOnly and its presence proves nothing (AGENTS.md). */
export function useSession(): Session {
  const query = useQuery(sessionQueryOptions);
  if (query.isPending) {
    return { status: "loading" };
  }
  if (query.data) {
    return { status: "signedIn", user: query.data };
  }
  return { status: "signedOut" };
}

/** The raw query, for callers that must tell "signed out" from "backend unreachable". */
export function useSessionQuery() {
  return useQuery(sessionQueryOptions);
}

export function isUnauthenticated(error: unknown): boolean {
  return error instanceof BffError && error.isUnauthenticated;
}

/** Signs out and empties the WHOLE cache, not just the session: balances and orders are this
 *  user's data and must not survive for whoever signs in next on the same browser. `onSettled`
 *  so a failed logout still clears locally -- a signed-out user looking signed in is worse. */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: logout, onSettled: () => queryClient.clear() });
}

export function useLogoutEverywhere() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: logoutEverywhere, onSettled: () => queryClient.clear() });
}
