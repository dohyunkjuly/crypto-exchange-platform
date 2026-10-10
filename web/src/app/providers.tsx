"use client";

import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { BffError, BffUnreachableError } from "@/lib/bff/errors";
import { SESSION_KEY } from "@/features/auth/session";

/**
 * The one client-side provider tree, mounted by the root layout.
 *
 * A `useState` initialiser rather than a module-level `new QueryClient()`: a module-level
 * client is shared by every render of every request on the server, which on a multi-user
 * page means one person's cache can answer another person's query. This app does not render
 * BFF data on the server, but the shape is cheap to get right and expensive to retrofit.
 */
/**
 * Built by a factory so the cache's own error handler can reference the client it belongs to
 * without reaching for a variable that is still being initialised.
 */
export function makeQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    // A 401 from ANY query means the session is gone, not just the one that asked. Without this,
    // a session revoked elsewhere only surfaces when /bff/auth/me happens to refetch, and until
    // then a wallet whose balances just 401'd sits there looking signed in. Invalidating the
    // session entry is what trips RequireSession's redirect.
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (
          error instanceof BffError &&
          error.isUnauthenticated &&
          query.queryKey[0] !== SESSION_KEY[0]
        ) {
          client.invalidateQueries({ queryKey: SESSION_KEY });
        }
      },
    }),
    defaultOptions: {
      queries: {
        // Retry only when the network failed. A 4xx is an answer, not a hiccup: retrying a 401
        // hammers a dead session three times before the gate notices, and retrying a 404 cannot
        // make the thing exist.
        retry: (attempt, error) => {
          if (error instanceof BffUnreachableError) {
            return attempt < 2;
          }
          if (error instanceof BffError) {
            return error.status >= 500 && attempt < 2;
          }
          return false;
        },
        // Coming back to the tab should show current numbers. On a trading screen a stale
        // balance is worse than a brief loading state.
        refetchOnWindowFocus: true,
        // Nothing is fresh by default. Anything that can tolerate staleness says so at its own
        // call site, where the reason is visible.
        staleTime: 0,
      },
      mutations: {
        // Never automatic. A retried POST /bff/orders is a second order, and the endpoint
        // answers 202 without waiting for the match -- so a timeout tells us nothing about
        // whether the first one landed.
        retry: false,
      },
    },
  });
  return client;
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(makeQueryClient);
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
