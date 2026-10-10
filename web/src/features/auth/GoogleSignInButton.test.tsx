import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const signInWithGoogle = vi.hoisted(() => vi.fn());
vi.mock("./api", () => ({ signInWithGoogle }));
// The real component defers to Google's script; here "loading" it just fires onLoad.
vi.mock("next/script", () => ({
  default: ({ onLoad }: { onLoad?: () => void }) => {
    onLoad?.();
    return null;
  },
}));

type Callback = (r: { credential: string }) => void;

/** A stand-in for Google Identity Services that captures the callback so the test can act as
 *  Google and hand back a credential. */
function stubGoogle() {
  let callback: Callback | undefined;
  const renderButton = vi.fn((parent: HTMLElement) => {
    parent.textContent = "Sign in with Google";
  });
  window.google = {
    accounts: { id: { initialize: ({ callback: cb }) => { callback = cb; }, renderButton } },
  };
  return { fire: (credential: string) => callback!({ credential }), renderButton };
}

async function load(clientId: string | undefined) {
  vi.resetModules();
  vi.stubEnv("NEXT_PUBLIC_BFF_URL", "http://localhost:8081");
  vi.stubEnv("NEXT_PUBLIC_GOOGLE_CLIENT_ID", clientId as string);
  return (await import("./GoogleSignInButton")).GoogleSignInButton;
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
  delete window.google;
  signInWithGoogle.mockReset();
});

describe("GoogleSignInButton", () => {
  it("renders nothing at all without a client id -- not even the divider", async () => {
    // An operator may simply not offer Google. A lone "or" above nothing looked broken.
    const Button = await load("");
    const { container } = render(<Button onOutcome={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("hands Google's credential to web-bff and the outcome to the caller", async () => {
    const google = stubGoogle();
    const Button = await load("client-id");
    const onOutcome = vi.fn();
    const outcome = { mfaRequired: true as const, challengeToken: "tok" };
    signInWithGoogle.mockResolvedValue(outcome);

    render(<Button onOutcome={onOutcome} />);
    await screen.findByText("Sign in with Google");
    expect(google.renderButton).toHaveBeenCalledOnce();

    google.fire("google-id-token");

    await waitFor(() => expect(onOutcome).toHaveBeenCalledWith(outcome));
    // The raw ID token goes to web-bff, which verifies signature, issuer and audience.
    expect(signInWithGoogle).toHaveBeenCalledWith("google-id-token");
  });

  it("shows web-bff's refusal instead of swallowing it", async () => {
    const google = stubGoogle();
    const Button = await load("client-id");
    // Imported AFTER load(): load() resets the module registry, and an error class from an
    // earlier registry fails the component's instanceof check and renders as a generic message.
    const { BffError } = await import("@/lib/bff/errors");
    signInWithGoogle.mockRejectedValue(new BffError(2019, 409, "Sign in with your password"));

    render(<Button onOutcome={() => {}} />);
    await screen.findByText("Sign in with Google");
    google.fire("token");

    expect(await screen.findByRole("alert")).toHaveTextContent("Sign in with your password");
  });
});
