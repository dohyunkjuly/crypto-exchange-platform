import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * Each case re-imports the module, because the validation runs once at load. `resetModules`
 * is what makes that possible -- without it the first import wins and every later case
 * silently asserts against the first one's result.
 */
async function loadEnv(values: Record<string, string | undefined>) {
  vi.resetModules();
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined) {
      vi.stubEnv(key, undefined as unknown as string);
    } else {
      vi.stubEnv(key, value);
    }
  }
  return import("./env");
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("env", () => {
  it("throws when the BFF URL is missing rather than guessing localhost", async () => {
    // The point of the whole module: a default here means a typo in production points the
    // exchange at nothing and the page merely looks broken.
    await expect(loadEnv({ NEXT_PUBLIC_BFF_URL: undefined })).rejects.toThrow(
      /NEXT_PUBLIC_BFF_URL is not set/,
    );
  });

  it("throws on a blank value, which is how an unset .env entry actually arrives", async () => {
    await expect(loadEnv({ NEXT_PUBLIC_BFF_URL: "   " })).rejects.toThrow(/is not set/);
  });

  it("refuses a relative URL", async () => {
    await expect(loadEnv({ NEXT_PUBLIC_BFF_URL: "/bff" })).rejects.toThrow(/absolute URL/);
  });

  it("takes a lone slash as the page's own origin, which is the case behind the edge proxy", async () => {
    const env = await loadEnv({ NEXT_PUBLIC_BFF_URL: "/" });
    expect(env.BFF_ORIGIN).toBe("");
    // jsdom's page lives at http://localhost:3000.
    expect(env.bffWsOrigin()).toBe("ws://localhost:3000");
  });

  it("refuses a scheme that is not http(s)", async () => {
    await expect(loadEnv({ NEXT_PUBLIC_BFF_URL: "ws://localhost:8081" })).rejects.toThrow(
      /must be http or https/,
    );
  });

  it("normalises away a trailing slash and any path", async () => {
    // Otherwise every call site has to remember, and "/bff//auth/me" is the result of one
    // of them forgetting.
    const env = await loadEnv({ NEXT_PUBLIC_BFF_URL: "http://localhost:8081/" });
    expect(env.BFF_ORIGIN).toBe("http://localhost:8081");
  });

  it("derives the websocket origin rather than taking a second variable", async () => {
    // Two variables that must agree is one variable and a bug waiting to happen.
    expect((await loadEnv({ NEXT_PUBLIC_BFF_URL: "http://localhost:8081" })).bffWsOrigin())
      .toBe("ws://localhost:8081");
    expect((await loadEnv({ NEXT_PUBLIC_BFF_URL: "https://api.example.com" })).bffWsOrigin())
      .toBe("wss://api.example.com");
  });

  it("treats a missing Google client id as configuration, not an error", async () => {
    // An operator is free not to offer Google sign-in; web-bff refuses the flow anyway
    // unless its own client id is set. Absent means "hide the button".
    const env = await loadEnv({
      NEXT_PUBLIC_BFF_URL: "http://localhost:8081",
      NEXT_PUBLIC_GOOGLE_CLIENT_ID: "",
    });
    expect(env.GOOGLE_CLIENT_ID).toBeNull();
  });
});
