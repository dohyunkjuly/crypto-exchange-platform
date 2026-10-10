import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BffError } from "@/lib/bff/errors";

const nav = vi.hoisted(() => ({ replace: vi.fn() }));
const session = vi.hoisted(() => ({ data: undefined as unknown, isPending: false, error: null as unknown }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: nav.replace }) }));
vi.mock("./session", async (orig) => ({ ...(await orig<object>()), useSessionQuery: () => session }));
const { RedirectIfSignedIn } = await import("./RedirectIfSignedIn");

const user = (emailVerified: boolean) => ({ id: 1, email: "a@b.c", name: null, emailVerified, status: "ACTIVE" });
afterEach(() => {
  nav.replace.mockReset(); session.data = undefined; session.error = null; session.isPending = false;
  window.history.replaceState({}, "", "/");
});

describe("RedirectIfSignedIn", () => {
  it("shows the form to a signed-out visitor", () => {
    session.error = new BffError(2004, 401, "no");
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(screen.getByText("log in")).toBeInTheDocument();
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it("sends a signed-in user to the dashboard instead of the form", () => {
    session.data = user(true);
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(screen.queryByText("log in")).toBeNull();
    expect(nav.replace).toHaveBeenCalledWith("/en/my/dashboard");
  });

  it("honours where they were going", () => {
    window.history.replaceState({}, "", "/en/login?next=%2Fmy%2Forders");
    session.data = user(true);
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(nav.replace).toHaveBeenCalledWith("/en/my/orders");
  });

  it("sends a signed-in, unverified user to verification", () => {
    session.data = user(false);
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(nav.replace).toHaveBeenCalledWith("/en/verify-email");
  });

  it("shows nothing while the session is unknown", () => {
    session.isPending = true;
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(screen.queryByText("log in")).toBeNull();
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it("still shows the form when the backend cannot answer, which reports it itself", () => {
    session.error = new Error("network");
    render(<RedirectIfSignedIn><p>log in</p></RedirectIfSignedIn>);
    expect(screen.getByText("log in")).toBeInTheDocument();
  });
});
