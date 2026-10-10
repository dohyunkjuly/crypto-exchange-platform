import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => vi.fn());
vi.mock("@/lib/bff/client", () => ({ api }));

const { login, register, signInWithGoogle } = await import("./api");

beforeEach(() => api.mockReset());

/**
 * The only logic in this module is turning login's two-shaped response into something a caller
 * cannot misread. Treating an MFA challenge as a signed-in session is how 2FA gets bypassed, so
 * these are the cases that matter rather than the request shapes.
 */
describe("login outcome", () => {
  it("reports a signed-in user when no factor is required", async () => {
    const user = { id: 1, email: "a@b.c", name: null, emailVerified: true, status: "ACTIVE" };
    api.mockResolvedValue({ mfaRequired: false, challengeToken: null, user });

    const outcome = await login("a@b.c", "correct-horse-battery");

    expect(outcome).toEqual({ mfaRequired: false, user });
  });

  it("reports a challenge, and no user, when a factor is required", async () => {
    api.mockResolvedValue({ mfaRequired: true, challengeToken: "tok", user: null });

    const outcome = await login("a@b.c", "correct-horse-battery");

    expect(outcome.mfaRequired).toBe(true);
    // The whole point: nothing on this branch can be mistaken for an authenticated session.
    expect(outcome).not.toHaveProperty("user");
    expect(outcome).toEqual({ mfaRequired: true, challengeToken: "tok" });
  });

  it("throws rather than rendering a challenge form with no token to submit", async () => {
    api.mockResolvedValue({ mfaRequired: true, challengeToken: null, user: null });

    await expect(login("a@b.c", "pw")).rejects.toThrow(/without issuing a challenge token/);
  });

  it("throws rather than claiming a session with no user", async () => {
    api.mockResolvedValue({ mfaRequired: false, challengeToken: null, user: null });

    await expect(login("a@b.c", "pw")).rejects.toThrow(/session with no user/);
  });

  it("applies the same reading to Google sign-in", async () => {
    // Google proving who someone is does not prove they hold the second factor, so this endpoint
    // can challenge too -- and must be read the same way.
    api.mockResolvedValue({ mfaRequired: true, challengeToken: "tok", user: null });

    expect(await signInWithGoogle("id-token")).toEqual({
      mfaRequired: true,
      challengeToken: "tok",
    });
  });
});

describe("register", () => {
  it("sends a blank name as absent, not as an empty string", async () => {
    api.mockResolvedValue({ id: 1 });

    await register("a@b.c", "correct-horse-battery", "   ");

    // The column is nullable; "" would render later as a user whose name is nothing.
    expect(api).toHaveBeenCalledWith("/bff/auth/register", {
      method: "POST",
      body: { email: "a@b.c", password: "correct-horse-battery", name: null },
    });
  });

  it("trims a real name", async () => {
    api.mockResolvedValue({ id: 1 });
    await register("a@b.c", "correct-horse-battery", "  Alex  ");
    expect(api.mock.calls[0][1].body.name).toBe("Alex");
  });
});
