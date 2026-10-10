import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BffError } from "@/lib/bff/errors";

const nav = vi.hoisted(() => ({ pathname: "/en/my/assets", replace: vi.fn() }));
const session = vi.hoisted(() => ({ data: undefined as unknown, isPending: false, error: null as unknown }));
vi.mock("next/navigation", () => ({ usePathname: () => nav.pathname, useRouter: () => ({ replace: nav.replace }) }));
vi.mock("./session", async (orig) => ({ ...(await orig<object>()), useSessionQuery: () => session }));
const { RequireSession } = await import("./RequireSession");

const user = (emailVerified: boolean) => ({ id: 1, email: "a@b.c", name: null, emailVerified, status: "ACTIVE" });
afterEach(() => { nav.replace.mockReset(); nav.pathname = "/en/my/assets"; session.data = undefined; session.error = null; });

describe("RequireSession", () => {
  it("renders the page for a verified user", () => {
    session.data = user(true);
    render(<RequireSession><p>wallet</p></RequireSession>);
    expect(screen.getByText("wallet")).toBeInTheDocument();
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it("sends an unverified user to verification instead of the page", () => {
    session.data = user(false);
    render(<RequireSession><p>wallet</p></RequireSession>);
    expect(screen.queryByText("wallet")).toBeNull();
    expect(nav.replace).toHaveBeenCalledWith("/en/verify-email");
  });

  it("lets an unverified user see the verification page itself, or it would loop", () => {
    session.data = user(false); nav.pathname = "/en/verify-email";
    render(<RequireSession><p>code form</p></RequireSession>);
    expect(screen.getByText("code form")).toBeInTheDocument();
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it("sends a signed-out visitor to login with where they were going", () => {
    session.error = new BffError(2004, 401, "no");
    render(<RequireSession><p>wallet</p></RequireSession>);
    expect(nav.replace).toHaveBeenCalledWith("/en/login?next=%2Fen%2Fmy%2Fassets");
  });
});
