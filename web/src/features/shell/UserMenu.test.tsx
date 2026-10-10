import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
const logout = vi.hoisted(() => ({ mutateAsync: vi.fn(() => Promise.resolve()) }));
vi.mock("next/navigation", () => ({ useRouter: () => nav, usePathname: () => "/en/my/assets" }));
vi.mock("@/features/auth/session", () => ({ useLogout: () => logout }));
const { UserMenu } = await import("./UserMenu");

const user = (emailVerified: boolean) => ({ id: 1, email: "alex@example.com", name: null, emailVerified, status: "ACTIVE" });
afterEach(() => { nav.push.mockReset(); nav.replace.mockReset(); logout.mutateAsync.mockClear(); });

describe("UserMenu", () => {
  it("is a link to the dashboard, and the menu opens on hover, listing the sections and no email", async () => {
    const u = userEvent.setup();
    render(<UserMenu user={user(true)} />);
    const icon = screen.getByRole("link", { name: "Account" });
    expect(icon).toHaveAttribute("href", "/en/my/dashboard");
    expect(screen.queryByText("alex@example.com")).toBeNull();
    await u.hover(icon);
    // The address is an identifier, not a greeting: nowhere in the bar and nowhere in the menu.
    expect(screen.queryByText("alex@example.com")).toBeNull();
    // The same sections as the rail, Dashboard included -- the icon is a shortcut, not the only way.
    expect(screen.getAllByRole("menuitem").map((i) => i.textContent))
      .toEqual(["Dashboard", "Assets", "Orders", "Copy trading", "Account", "Sub-accounts", "Log out"]);
    await u.unhover(icon);
    // Closes after a short grace period, so a pointer crossing the panel edge does not slam it.
    await waitFor(() => expect(screen.queryByRole("menu")).toBeNull());
  });

  it("opens a section where it stands, and going to one of its pages closes the menu", async () => {
    const u = userEvent.setup();
    render(<UserMenu user={user(true)} />);
    await u.hover(screen.getByRole("link", { name: "Account" }));

    const group = screen.getByRole("menuitem", { name: "Sub-accounts" });
    expect(group).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("menuitem", { name: "Order management" })).toBeNull();

    await u.click(group);
    expect(group).toHaveAttribute("aria-expanded", "true");
    await u.click(screen.getByRole("menuitem", { name: "Order management" }));
    expect(nav.push).toHaveBeenCalledWith("/en/my/sub-accounts/orders");
  });

  it("offers verification only while the email is unconfirmed", async () => {
    const u = userEvent.setup();
    const { unmount } = render(<UserMenu user={user(false)} />);
    await u.hover(screen.getByRole("link", { name: "Account" }));
    await u.click(screen.getByRole("menuitem", { name: "Verify email" }));
    expect(nav.push).toHaveBeenCalledWith("/en/verify-email");
    unmount();

    render(<UserMenu user={user(true)} />);
    await u.hover(screen.getByRole("link", { name: "Account" }));
    expect(screen.queryByRole("menuitem", { name: "Verify email" })).toBeNull();
  });

  it("logs out and lands on login", async () => {
    const u = userEvent.setup();
    render(<UserMenu user={user(true)} />);
    await u.hover(screen.getByRole("link", { name: "Account" }));
    await u.click(screen.getByRole("menuitem", { name: "Log out" }));
    expect(logout.mutateAsync).toHaveBeenCalledOnce();
    await vi.waitFor(() => expect(nav.replace).toHaveBeenCalledWith("/en/login"));
  });
});
