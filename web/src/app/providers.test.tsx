import { describe, expect, it, vi } from "vitest";
import { makeQueryClient } from "./providers";
import { SESSION_KEY } from "@/features/auth/session";
import { BffError } from "@/lib/bff/errors";

/**
 * The cross-cutting rule: a 401 from any query ends the session. Tested against the real client
 * rather than a component, because the behaviour lives in the cache, not in React.
 */
describe("makeQueryClient", () => {
  const signedIn = { id: 1, email: "a@b.c", name: null, emailVerified: true, status: "ACTIVE" };

  async function runQueryThatFails(status: number, code = 2004) {
    const client = makeQueryClient();
    client.setQueryData(SESSION_KEY, signedIn);
    const spy = vi.spyOn(client, "invalidateQueries");
    await client
      .fetchQuery({
        queryKey: ["balances"],
        queryFn: () => Promise.reject(new BffError(code, status, "no")),
        retry: false,
      })
      .catch(() => undefined);
    return spy;
  }

  it("invalidates the session when an unrelated query answers 401", async () => {
    const spy = await runQueryThatFails(401);
    expect(spy).toHaveBeenCalledWith({ queryKey: SESSION_KEY });
  });

  it("leaves the session alone for any other failure", async () => {
    // A 404 or a 500 says nothing about who you are.
    expect(await runQueryThatFails(404, 5015)).not.toHaveBeenCalled();
    expect(await runQueryThatFails(500, 9000)).not.toHaveBeenCalled();
  });

  it("does not invalidate the session query from its own 401", async () => {
    // /bff/auth/me answering 401 IS the signed-out state. Invalidating it in response would
    // refetch it, which 401s again, which invalidates again -- a loop.
    const client = makeQueryClient();
    const spy = vi.spyOn(client, "invalidateQueries");
    await client
      .fetchQuery({
        queryKey: SESSION_KEY,
        queryFn: () => Promise.reject(new BffError(2004, 401, "no")),
        retry: false,
      })
      .catch(() => undefined);
    expect(spy).not.toHaveBeenCalled();
  });
});
